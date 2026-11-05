# LOCK P2 交接文档：Phase 2/3/4 路线图与生态项（HANDOFF）

> **本分支是任务交接分支**：只承载上下文文档，不含实现代码。接手的 agent 请先读完本文，再按第 8 节流程开工。
>
> - 分支：`p2-lock-roadmap`，基于 `develop`（`86fae39df`，Nacos 3.3.0）
> - 关联 issue：#15279（分期规划主体，开放中）、#16000（鉴权缺口，`feat-lock-grpc-auth` 分支）
> - 前置 PR：PR #15280（Phase 1，已合入，milestone 3.3.0）
> - **P2 全部任务均需先设计评审**：每个 Phase 都触及公共 API/规范语义，AGENTS.md 要求 spec/design PR 先行

---

## 0. 接手 agent 操作清单

1. 读 `AGENTS.md`、`specs/zh-cn/lock/lock-spec.md`（P0-② 重写版独立 PR 中；其"待处理问题"节与本文同源）
2. 选中本文的一个任务（一次只做一个 Phase 的一个能力）
3. **先发 spec/design PR 或 issue 讨论**（zh + en 双语规范），获维护者确认后再实现
4. 从 `develop` 新建实现分支；实现、测试、Spotless、小 PR（`refs #15279`）

---

## 1. 当前基础（Phase 1 已合入，实现新能力前的地图）

| 能力 | 路径 |
|---|---|
| gRPC 入口 | `lock/src/main/java/com/alibaba/nacos/lock/remote/rpc/handler/LockRequestHandler.java` |
| 业务编排 | `lock/src/main/java/com/alibaba/nacos/lock/service/impl/LockOperationServiceImpl.java` |
| 锁核心抽象 | `lock/src/main/java/com/alibaba/nacos/lock/core/reentrant/AbstractAtomicLock.java` |
| 内置实现（3 种） | `lock/src/main/java/com/alibaba/nacos/lock/core/reentrant/mutex/{MutexAtomicLock,ReentrantAtomicLock,NonReentrantAtomicLock}.java` |
| 过期扫描 | `lock/src/main/java/com/alibaba/nacos/lock/schedule/LockExpireScanner.java` |
| 断连清理 | `lock/src/main/java/com/alibaba/nacos/lock/remote/LockConnectionEventListener.java` |
| 指标 | `lock/src/main/java/com/alibaba/nacos/lock/monitor/LockMetricsMonitor.java` |
| API 模型 | `api/src/main/java/com/alibaba/nacos/api/lock/...`（`LockOperationEnum`：ACQUIRE/RELEASE/EXPIRE/RENEW/CANCEL_WAIT；**无 QUERY**） |
| 客户端 | `client/src/main/java/com/alibaba/nacos/lock/remote/{LockGrpcClient,AbstractLockClient}.java`；高层门面 `getReentrantLock`/`getNonReentrantLock` → `NacosLock`（实现 `java.util.concurrent.locks.Lock`） |

关键语义（不可破坏）：owner 校验释放、renew 匹配 owner、队头 owner+connectionId 校验、看门狗 10000ms 间隔（超时=×3）、类型工厂 NACOS_LOCK/REENTRANT/NON_REENTRANT。

**重要**：Phase 1 获取路径**未承诺公平性**（规范明确将公平锁列为 Phase 2）。等待者由 gRPC 推送触发重试，与新到来的 ACQUIRE 存在竞争窗口——这是 Phase 2 公平锁要解决的核心点。

---

## 2. Phase 2：公平锁 / 非可重入公平锁 / 读写锁

### 2.1 公平锁（FAIR_REENTRANT / FAIR_NON_REENTRANT）

- 语义目标：新 ACQUIRE 在"锁空闲且队列为空"时才直接获取，否则必须入队尾——消除抢占窗口
- 实现思路：扩展 `AbstractAtomicLock` 的获取策略（新增公平判定），新增 LockType 并注册进类型工厂
- 需要讨论：公平性边界（connection 断连后队列压缩是否保序）、等待队列满时的拒绝策略
- 客户端：`LockService` 门面需暴露新锁类型的工厂方法（api 模块 Java 8 兼容）；SDK IT 需覆盖"新请求不插队"断言

### 2.2 读写锁

- 语义目标：多读者共享持有 + 写者排他；需要 owner 模型支持多读者（当前 AbstractAtomicLock 是单 owner 互斥模型）
- 需要讨论：写者优先 vs 读者优先 vs 公平轮转；与等待队列的交互（写者等待时后续读者是否排队）；RENEW/EXPIRE 在多读者下的租约语义（按 reader 各自租约还是整体租约）
- 难度评估：等待队列推送与批量唤醒逻辑需重新设计，建议独立 design doc

### 2.3 交付物

spec（zh+en）→ api 模型 + 服务端实现 + 客户端门面 + 单测 + java-sdk IT + `JAVA_SDK_IT_COVERAGE.md` 更新。PR 拆分建议：每个锁类型一个 PR。

---

## 3. Phase 3：信号量 / CountDownLatch

### 3.1 信号量（Semaphore）

- 核心差异：**多许可计数 + 多持有者**，现有 AbstractAtomicLock 的 0/1 单持有模型不适用；需要新的核心抽象（如 permit 计数器 + 持有者集合，每个持有者带独立租约）
- RENEW/EXPIRE 需按持有者粒度处理；释放必须校验持有者（沿用 P1-1 wait token / owner 思路防伪造）
- 需要讨论：acquire(p)/release(p) 批量语义、公平/非公平策略是否与 Phase 2 复用

### 3.2 CountDownLatch

- 语义：count 原子递减 + 到 0 时批量唤醒等待者；等待队列可复用现有 wait queue + 推送机制
- 需要讨论：count 归属与清理（谁可以归零/重置——建议仅创建连接）、lease 语义（latch 是否需要租约防止永久残留）

### 3.3 交付物

同 2.3；信号量建议先出独立 design doc（核心抽象变更影响面大）。

---

## 4. Phase 4：Fenced Lock / QUERY / Console 展示

| 能力 | 要点 | 前置讨论 |
|---|---|---|
| Fenced lock | 单调递增 fencing token 随 LockResult 返回；客户端在下游存储校验 token 防脑裂 | token 生成机制（CP 写入？全局序列？）、存储端校验语义归属 |
| QUERY 操作 | 查询锁状态/持有者/队列长度：新增 `LockOperationEnum.QUERY` + 响应模型 | **鉴权动作为 READ**（区别于现有 WRITE）；限流/暴露面控制 |
| Console 展示 | admin API + console-ui 展示锁持有者与等待队列 | 走 admin API 规范（`/v3/admin/...`、`@Secured apiType = ADMIN_API`）、权限模型 |

---

## 5. 指标扩展

现有：`grpcLockTotal/Success`、`grpcUnLockTotal/Success`、`grpcRenewTotal/Success`、`aliveLockCount` gauge、handler Timer（`LockMetricsMonitor`）。

待扩展（可与 P1-2 协同，避免重复实现）：

- 等待队列长度 gauge（全局 + per-lockType 维度）
- 等待时长分布 Timer（入队 → 获得锁）
- `CANCEL_WAIT`、`EXPIRE`、（P1-2 的）stale 移除 计数器
- 指标命名与 spec 的可观测节同步（zh + en）

---

## 6. 文档与生态

- **用户文档**：`nacos-group/nacos-group.github.io` 的 `docs/next`——锁概念、Java SDK 用法（含看门狗/等待队列行为说明）、配置、运维指南；中英双语
- **多语言 SDK**：以 `specs/en/grpc-api/api-spec.md` §7.5（lock 请求/响应契约）为协议事实来源；GO/C++/Python 等与社区协调排期；注意鉴权头构造参照客户端 `AbstractLockClient.getSecurityHeaders()` 与 `LockResourceInjector`
- **小项**：模型字段命名统一（`waitTimeMs`→`waitTime` 已有本地 fix 分支 `feature/distributed-lock`；`expiredTime` 命名待讨论）、lock 资源 namespace 设计确认（当前解析器 namespace/group 留空）

---

## 7. 工程约束与测试（同 P1，摘要）

- AGENTS.md 全量约束适用：spec-first、discuss-first、license 头（`Copyright 1999-2026`）、Spotless 强制、Java 8（api/client/plugin）/ Java 17（服务端）、小 PR、AI commit trailer、安全问题走 ASRC
- ⚠️ surefire 坑：`-Dtest='com.alibaba.nacos.lock.*'` 包名通配静默跳过；用显式类名列表（完整命令见 `p1-lock-hardening` 分支的 `LOCK_P1_HANDOFF.md` 第 6 节）
- 基线：lock 15 类 / 188 测试（#16000 分支）；预提交 `mvn -q -B -pl lock,api,client,client-basic -am clean compile apache-rat:check checkstyle:check spotbugs:check spotless:check -DskipTests`
- java-sdk IT：`mvn -pl test/java-sdk-test -Pjava-sdk-integration-test -DskipTests=false verify`（standalone server，不在 IT 内起 Spring/Nacos）

---

## 8. 完成定义（DoD）

- [ ] 选定任务已出 spec/design（zh+en）并在 issue 获维护者确认
- [ ] api 模型变更向后兼容（Java 8 + 序列化兼容说明写进 PR）
- [ ] 服务端实现 + 客户端门面 + 单测 + java-sdk IT 全覆盖
- [ ] `JAVA_SDK_IT_COVERAGE.md` 与 spec 同步更新
- [ ] PR 小步拆分（一个能力一个 PR），`refs #15279`
