# LOCK P1 交接文档：等待队列与租约硬化（HANDOFF）

> **本分支是任务交接分支**：只承载上下文文档，不含实现代码。接手的 agent 请先读完本文，再按第 7 节的流程开工。
>
> - 分支：`p1-lock-hardening`，基于 `develop`（`86fae39df`，Nacos 3.3.0）
> - 关联 issue：#15279（后续分期规划，开放中）、#16000（鉴权缺口修复，代码在 `feat-lock-grpc-auth` 分支，PR 待发起/合入）
> - 前置 PR：PR #15280（Phase 1，已合入，milestone 3.3.0）
> - 语言约定：正文中文，代码标识符/路径英文。提交信息遵循仓库惯例（`[ISSUE #xxxx] type(scope): ...`）

---

## 0. 接手 agent 操作清单

1. 读根目录 `AGENTS.md`（强制：spec-first、discuss-first、license 头、Spotless、小 PR）
2. 读 `specs/zh-cn/lock/lock-spec.md`（注意：P0-② 规范重写正在独立 PR 中，合入前以代码 + PR #15280 为准；本文第 1 节已摘录关键语义）
3. **P1-1 必须先讨论**：在 issue #15279 或 #16000 评论中提出 wait token 方案，获维护者确认后再动代码（AGENTS.md: Discuss before implementing）
4. 从 `develop`（建议在 #16000 合入后）新建实现分支，不要直接在本分支追加实现
5. 实现完成后：单测 + Spotless + 预提交检查（命令见第 6 节）→ 小 PR（`refs #15279`，不要写 `fixes`，除非维护者确认）

---

## 1. 当前实现地图（改代码前必读）

### 1.1 模块与文件

| 能力 | 路径 |
|---|---|
| gRPC 入口（含鉴权契约） | `lock/src/main/java/com/alibaba/nacos/lock/remote/rpc/handler/LockRequestHandler.java` |
| 业务编排（ACQUIRE/RELEASE/RENEW/EXPIRE/CANCEL_WAIT） | `lock/src/main/java/com/alibaba/nacos/lock/service/impl/LockOperationServiceImpl.java` |
| 服务接口 | `lock/src/main/java/com/alibaba/nacos/lock/service/LockOperationService.java` |
| 锁核心抽象 | `lock/src/main/java/com/alibaba/nacos/lock/core/reentrant/AbstractAtomicLock.java` |
| 三种内置实现 | `lock/src/main/java/com/alibaba/nacos/lock/core/reentrant/mutex/{MutexAtomicLock,ReentrantAtomicLock,NonReentrantAtomicLock}.java` |
| 过期扫描 | `lock/src/main/java/com/alibaba/nacos/lock/schedule/LockExpireScanner.java` |
| 断连清理（事件驱动） | `lock/src/main/java/com/alibaba/nacos/lock/remote/LockConnectionEventListener.java` |
| 指标 | `lock/src/main/java/com/alibaba/nacos/lock/monitor/LockMetricsMonitor.java` |
| 模型 | `lock/src/main/java/com/alibaba/nacos/lock/model/{LockInfo,LockKey}.java` |
| API 请求/响应 | `api/src/main/java/com/alibaba/nacos/api/lock/remote/request/LockOperationRequest.java`、`api/src/main/java/com/alibaba/nacos/api/lock/remote/response/LockOperationResponse.java` |
| API 枚举/常量 | `api/src/main/java/com/alibaba/nacos/api/lock/remote/LockOperationEnum.java`（ACQUIRE/RELEASE/EXPIRE/RENEW/CANCEL_WAIT；CLEANUP_CONNECTION 为内部路径）、`api/src/main/java/com/alibaba/nacos/api/lock/common/LockConstants.java` |
| API 锁模型 | `api/src/main/java/com/alibaba/nacos/api/lock/model/{LockInstance,LockResult}.java` |
| 客户端 gRPC | `client/src/main/java/com/alibaba/nacos/lock/remote/{LockGrpcClient,AbstractLockClient}.java` |
| 客户端签名注入 | `client-basic` 模块 `LockResourceInjector`（按 `SignType.LOCK`） |

### 1.2 不可破坏的关键语义（PR #15280 已合入行为）

- `AbstractAtomicLock.unLock`：owner 非空时校验持有者；owner 为 null **仅限系统级释放路径**（EXPIRE/内部清理）
- `AbstractAtomicLock.renew`：必须匹配 owner
- 队头获取锁 `tryLockAsQueueHead`：校验 owner + connectionId
- 锁类型工厂：`NACOS_LOCK`→MutexAtomicLock（0/1 互斥）、`REENTRANT`→ReentrantAtomicLock、`NON_REENTRANT`→NonReentrantAtomicLock
- 客户端看门狗：默认续约间隔 10000ms，锁超时 = 间隔 × 3（30000ms）
- 客户端 `NacosLock` 本地拒绝同线程 NON_REENTRANT 重入（抛 `IllegalMonitorStateException`）
- 鉴权契约（若 #16000 已合入）：`@Secured(action = WRITE, signType = SignType.LOCK)`，资源解析为 `namespace:*:lock/{key}` 模式

---

## 2. 任务 P1-1：等待令牌（wait token）服务端强化 ⭐ 维护者点名，优先级最高

### 2.1 问题

PR #15280 评审中维护者（KomachiSion）明确指出：**等待队列的信任边界有缺口**。当前客户端在 ACQUIRE 请求中携带 `waiterRetry` 布尔字段声明"这是既有等待者的重试"，服务端据此处理队列去重/位置——该声明完全由客户端提供，无服务端凭证支撑。CANCEL_WAIT 同理：服务端按请求内容移除等待者，未校验"等待者归属"。

可能的滥用/故障形态：

- 客户端伪造/重复声明 waiterRetry，破坏队列去重与 FIFO 顺序
- 连接 A 对不属于自己连接的等待者发起 CANCEL_WAIT（若服务端未绑定 connectionId 校验）
- 队列位置/重试计数被客户端操纵后，饥饿其他等待者

### 2.2 方案方向（供 issue 讨论，不要照抄实现）

1. 服务端在 ACQUIRE 成功入队时**签发唯一 wait token**（如 UUID 或 HMAC(connectionId, lockKey, seq)），随 `LockResult` 返回给客户端
2. CANCEL_WAIT 请求**必须携带 token**；服务端校验 token 与队列 entry 的绑定关系（含发起连接的 connectionId）后才执行移除
3. RENEW 是否纳入 token 域需讨论（当前 renew 已校验 owner，有基础防护）
4. **兼容性**：旧客户端不带 token 的降级策略（拒绝/告警放行）必须由维护者拍板；`LockResult`/`LockOperationRequest` 新增字段须向后兼容（api 模块 Java 8；序列化兼容以 common remote 实现为准，不要臆断是 proto 还是 JSON）

### 2.3 验收标准

- 无 token / 伪造 token 的 CANCEL_WAIT 被拒绝，且目标等待者不受影响（单测覆盖）
- 合法持有者取消自己的等待：成功且幂等（重复取消不报错不残留）
- 正常 ACQUIRE→等待→获得→释放 全链路回归通过
- 旧客户端兼容策略有明确文档与 spec 更新（zh + en）

---

## 3. 任务 P1-2：stale 队头等待者主动探测

### 3.1 问题

等待队列 FIFO 推进依赖两类机制：锁释放/过期时队头校验（`tryLockAsQueueHead`），以及连接断开时的事件驱动清理（`LockConnectionEventListener` → CLEANUP_CONNECTION）。若队头等待者的连接关闭事件丢失或存在窗口期，队头会**长期占位**阻塞整个队列。

### 3.2 方案方向

- 复用 `LockExpireScanner` 的周期任务（或新增独立 scanner）：周期性检查每个锁等待队列的**队头** entry，若其 connectionId 已不在活跃连接集合（core 的 ConnectionManager）中，则移除该 entry 并尝试推进队头
- 注意与 CLEANUP_CONNECTION 事件清理的**去重**（同一 entry 两条路径都会尝试移除，需幂等）
- 探测频率、移除后的通知推送（队头变更时向新队头发 LockNotificationRequest）与既有推送机制保持一致

### 3.3 验收标准

- 模拟"队头断连但无清理事件"场景：N 个周期内队头被移除、队列推进（单测）
- 活跃等待者不被误删（单测）
- 移除/推进路径有指标可观测（若顺带实现，可与 P2 指标扩展协调，避免重复 PR）

---

## 4. 任务 P1-3：Phase 1 新语义 java-sdk IT 补齐

单测已覆盖基本行为，但端到端 IT 缺口已在 `test/java-sdk-test/JAVA_SDK_IT_COVERAGE.md` 登记（该登记随 #16000 分支提交，合入后生效）。需要补的场景：

| 场景 | 断言要点 |
|---|---|
| 重入计数 | REENTRANT：同线程 `lock()` 两次立即成功，需 `unlock()` 两次才释放 |
| NON_REENTRANT 本地拒绝 | 同线程重入抛 `IllegalMonitorStateException`，服务端无副作用 |
| 看门狗续约 | 持锁超过 30000ms（3 × 10000ms）仍有效（续约成功）；主动释放后续约停止 |
| FIFO 竞争 | A 持有；B、C 依次等待；A 释放 → B 先获得、C 后获得（顺序断言） |
| 取消等待 | B 取消等待（cancelWait/中断）后无幽灵等待者；A 随后释放 → 新请求立即可获得 |
| 断连清理 | B 直接断连（不显式取消）→ CLEANUP_CONNECTION 清理；A 释放后新客户端可获取 |
| 鉴权联动（可选） | readOnly 身份对 lock 操作得到 NO_RIGHT（依赖 #16000 合入；CI 身份脚本 `test/scripts/auth-it-identity.sh` 已授 `'*:*:lock/*'`） |

运行方式：`mvn -pl test/java-sdk-test -Pjava-sdk-integration-test -DskipTests=false verify`（standalone server 模式，不要在 IT 内起 Spring/Nacos）。既有参考：`LockServiceJavaSdkITCase`、基类 `JavaSdkBaseITCase`（含 `AuthIdentity` 枚举、`createLockService`、`assertNoRight`、`randomServiceName` 辅助）。

---

## 5. 工程约束（AGENTS.md 摘要，违反会被 CI 拦）

- 新文件必须带 Apache License 头（`Copyright 1999-2026 Alibaba Group Holding Ltd.`）
- 4 空格缩进、100 字符行宽、禁 star import、禁未使用 import
- 提交前必须 `mvn spotless:apply` + `mvn spotless:check`（不要用 checkstyle 代替）
- Java 版本：`api`/`client`/`client-basic`/`plugin` 兼容 Java 8；服务端模块 Java 17+
- API/SDK 公共契约变更必须同变更集更新 `test/java-sdk-test` 覆盖与 `JAVA_SDK_IT_COVERAGE.md`，并同步 spec（zh + en）
- 行为/spec 影响先在 issue 讨论；小 PR 拆分；AI 生成的 commit 加 trailer `Assisted-by: Claude Code`
- 安全类问题走 ASRC，不走公开 issue

## 6. 测试与验证（含已踩坑记录）

⚠️ **surefire 坑**：`-Dtest='com.alibaba.nacos.lock.*'` 包名通配在 surefire 3.2.5 下不匹配，会**静默跳过全部测试**。必须用显式类名列表：

```bash
mvn -pl lock -am test -Dtest='AbstractAtomicLockTest,MutexAtomicLockTest,NonReentrantAtomicLockTest,ReentrantAtomicLockTest,WaitEntryTest,NacosLockSnapshotOperationTest,LockManagerTest,LockInfoTest,LockKeyTest,LockMetricsMonitorTest,LockConnectionEventListenerTest,LockRequestAuthFilterTest,LockRequestHandlerTest,LockExpireScannerTest,LockOperationServiceImplTest' -Dsurefire.failIfNoSpecifiedTests=false
```

基线参考（#16000 分支）：lock 15 类 / 188 测试全过；auth 18 类 / 170 测试全过。

预提交检查：

```bash
mvn -q -B -pl lock,api,client,client-basic -am clean compile apache-rat:check checkstyle:check spotbugs:check spotless:check -DskipTests
```

注意：全 reactor `mvn test` 会撞上 common 模块 `RpcClientFactoryTest` 的预存失败（已用 git stash 对照确认与 lock 改动无关），用上面的显式类名方式绕过。

## 7. 完成定义（DoD）

- [ ] 方案已在 issue 中获维护者确认（P1-1 硬性要求）
- [ ] spec 相应章节同步更新（`specs/zh-cn/lock/lock-spec.md` + `specs/en/lock/lock-spec.md`）
- [ ] 单测 + IT 通过；Spotless / Checkstyle / RAT 通过
- [ ] PR 拆分合理（建议 P1-1 / P1-2 / P1-3 各一个 PR），`refs #15279`
- [ ] 若改动公共 API/序列化：兼容性说明写进 PR 描述
