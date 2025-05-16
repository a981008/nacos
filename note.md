`com.alibaba.nacos.bootstrap.NacosBootstrap`
   1. `com.alibaba.nacos.NacosServerBasicApplication`
   2. `com.alibaba.nacos.NacosServerWebApplication`
   3. `com.alibaba.nacos.console.NacosConsole`

```text
com.alibaba.nacos.bootstrap.NacosBootstrap ->
   com.alibaba.nacos.NacosServerBasicApplication ->
      nacos-naming -> 
        nacos-core：spring.factories -> 
          com.alibaba.nacos.core.code.StandaloneProfileApplicationListener
          com.alibaba.nacos.core.code.SpringApplicationRunListener
          nacos-persistence
      nacos-config
      nacos-istio
      nacos-prometheus
      nacos-default-plugin-all ->
        default-auth-plugin
        nacos-default-auth-plugin
   com.alibaba.nacos.NacosServerWebApplication
   com.alibaba.nacos.console.NacosConsole
```

auth-plugin 只有 merged 和 server 才会装配。主要用于，主要用于对 nacos 服务进行访问控制、身份验证和权限管理。它确保只有经过授权的用户或系统能够访问 nacos 提供的服务。 

nacos 分为 server 和 cosole，server 为核心服务，负责配置管理、服务注册与发现，console 为控制台 UI。
server 和 console 可以独立运行，也可一起运行（merged），通过 `nacos.deployment.type` 控制。
server 包含 config 和 naming，也可分开运行，通过 `nacos.functionMode` 控制。

persistence 数据库分内置和外置
* 内置采用 derby 实现 
  * standalone 模式：`StandaloneDatabaseOperateImpl`，一个 derby
  * cluster 模式：`DistributedDatabaseOperateImpl`，raft + derby
* 外置 MySQL、Postgres 等...

nacos-common、nacos-auth、nacos-trace-plugin、nacos-consistency 都没有 bean、没有 SPI。