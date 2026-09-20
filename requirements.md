# FtpPool Requirements

> **Brand:** TidePool
> **Project:** FtpPool
> **Description:** High-Performance, Production-Ready FTP Connection Pool for Java
> **Status:** Architecture & Requirements
> **Version:** 0.1.0-SNAPSHOT

---

## 1. 项目概述

### 1.1 项目定位

FtpPool 是一个面向 Java 应用的 **高性能 FTP 连接池**。

项目本身不是通用 Resource Pool，而是专门解决 FTP/FTPS 连接复用、连接生命周期管理、连接健康检查、故障恢复、并发访问、状态重置和监控等问题。

FtpPool 借鉴成熟 Java 连接池和对象池的设计思想：

* **HikariCP**

  * 高性能
  * Fast Path
  * 低锁竞争
  * CAS
  * Thread Local / Thread Affinity
  * 低对象分配
  * Connection Bag 思想
  * HouseKeeper

* **Apache Commons Pool**

  * Object Factory
  * Borrow / Return
  * Validation
  * Eviction
  * Idle Object Management
  * Max / Min Object 数量管理
  * 生命周期管理

* **Druid**

  * Metrics
  * Filter Chain
  * JMX
  * 慢操作统计
  * 连接状态统计
  * SQL/数据库领域中成熟的可观测性思想迁移到 FTP

同时针对 FTP 的特殊性建立自己的：

* FTP Connection State Management
* FTP Connection Validation
* FTP Connection Reset
* FTP Transfer Lifecycle
* Broken Connection Detection
* Control/Data Connection Management
* FTP/FTPS 支持
* FTP Operation Metrics

---

# 2. 核心设计原则

## 2.1 FTP 是第一公民

项目不设计成：

```text
GenericResourcePool
    ├── FTP
    ├── HTTP
    ├── Redis
    └── MySQL
```

而是：

```text
FtpPool
    ├── FTP Connection
    ├── FTPS Connection
    ├── FTP Lifecycle
    ├── FTP Validation
    ├── FTP State
    └── FTP Transfer
```

通用性只存在于内部 SPI 和工程抽象层，而不改变产品定位。

---

## 2.2 Pool Engine 与 FTP Resource 解耦

核心思想：

```text
                    FtpPool API
                         │
                         ▼
                    PoolEngine SPI
             ┌───────────┼───────────┐
             ▼           ▼           ▼
        Fast Engine   Commons      Monitor
        Hikari-like   Pool-like    Druid-like
             │           │           │
             └───────────┼───────────┘
                         ▼
                  FtpPoolEntry
                         │
                         ▼
                 FtpConnection
                         │
                         ▼
                    FTPClient
```

Pool Engine 负责：

> **怎么管理连接**

FTP Connection Layer 负责：

> **连接是什么以及如何正确使用 FTP**

Observability Layer 负责：

> **连接池发生了什么**

---

# 3. 设计目标

## 3.1 核心目标

FtpPool 必须实现：

1. FTP 连接复用
2. FTP 连接池化
3. 高并发 Borrow / Return
4. 连接生命周期管理
5. 空闲连接管理
6. 最小连接数
7. 最大连接数
8. 连接超时
9. 空闲超时
10. 最大生命周期
11. FTP 连接健康检查
12. Broken Connection 自动销毁
13. FTP 状态自动 Reset
14. FTP/FTPS 支持
15. Metrics
16. Filter SPI
17. JMX
18. Spring Boot Starter
19. 普通 Java JAR
20. SPI 可扩展 Pool Engine
21. JMH 性能 Benchmark

---

# 4. 非目标

以下内容不属于第一阶段核心范围：

* 不实现 FTP Server
* 不实现完整 FTP Server 协议栈
* 不替代 Apache Commons Net
* 不实现文件系统缓存
* 不实现分布式连接池
* 不实现跨 JVM 连接共享
* 不把 FTP Client API 泛化成任意资源池
* 不直接复制 HikariCP / Druid / Commons Pool 的源码

项目借鉴其经过验证的设计思想，而不是复制实现。

---

# 5. 支持的协议

## 5.1 FTP

第一阶段必须支持：

```text
FTP
```

基于 Apache Commons Net：

```java
org.apache.commons.net.ftp.FTPClient
```

---

## 5.2 FTPS

设计上必须支持：

```text
FTPS
```

包括：

* Explicit FTPS
* Implicit FTPS

通过 Connection Factory SPI 创建不同类型的底层客户端。

例如：

```java
FtpProtocol.FTP
FtpProtocol.FTPS_EXPLICIT
FtpProtocol.FTPS_IMPLICIT
```

---

# 6. 核心 API

## 6.1 FtpPool

```java
public interface FtpPool extends AutoCloseable {

    FtpConnection borrow() throws FtpPoolException;

    FtpConnection borrow(Duration timeout)
            throws FtpPoolException;

    void release(FtpConnection connection);

    FtpPoolStats stats();

    void close();

    boolean isClosed();
}
```

---

## 6.2 推荐的函数式 API

为了避免用户忘记 release：

```java
public interface FtpPool {

    <T> T execute(
        FtpCallback<T> callback
    ) throws FtpException;
}
```

使用：

```java
String content = pool.execute(ftp -> {
    return ftp.download("/data/test.txt");
});
```

内部自动：

```text
borrow
   ↓
execute
   ↓
validate
   ↓
release
```

发生严重异常时：

```text
borrow
   ↓
execute
   ↓
Broken
   ↓
destroy
```

---

# 7. FtpConnection

不要直接把：

```java
FTPClient
```

暴露为 FtpPool 的核心 API。

定义：

```java
public interface FtpConnection {

    void changeDirectory(String path);

    String currentDirectory();

    InputStream retrieveFileStream(String path);

    OutputStream storeFileStream(String path);

    boolean upload(String path, InputStream input);

    boolean download(String path, OutputStream output);

    boolean delete(String path);

    FTPFile[] listFiles(String path);

    void completePendingCommand();

    boolean isValid();

    void markBroken();

    boolean isBroken();
}
```

底层可以使用：

```text
FtpConnection
      │
      ▼
CommonsNetFtpConnection
      │
      ▼
FTPClient
```

这样未来可以替换底层 FTP 实现，而不会破坏上层 API。

---

# 8. FTP Connection 生命周期

连接生命周期必须明确。

```text
NEW
 │
 ▼
CONNECTING
 │
 ▼
AUTHENTICATED
 │
 ▼
IDLE
 │
 ▼
IN_USE
 │
 ├──────────────┐
 │              │
 ▼              ▼
RETURNING      BROKEN
 │              │
 ▼              ▼
VALIDATING     DESTROY
 │
 ├── valid ──► IDLE
 │
 └── invalid ─► DESTROY
```

---

# 9. FTP 状态管理

FTP Client 与 JDBC Connection 最大的区别之一是：

> FTPClient 是高度有状态的资源。

因此每次 Return 都必须考虑状态污染。

需要管理的状态包括：

### 9.1 Working Directory

例如：

```text
/
```

Borrow 前：

```text
/home/user
```

业务执行：

```text
cd /data
```

Return 时必须恢复。

---

### 9.2 File Type

默认：

```text
BINARY
```

防止业务代码修改为：

```text
ASCII
```

导致下一次调用出现隐蔽问题。

---

### 9.3 Passive / Active Mode

统一管理：

```text
Passive
Active
```

默认建议：

```text
Passive
```

---

### 9.4 Character Encoding

例如：

```text
UTF-8
```

需要避免连接在业务过程中被修改后污染下一次 Borrow。

---

### 9.5 Transfer State

必须处理：

```text
retrieveFileStream()
storeFileStream()
completePendingCommand()
```

对于未完成的数据连接，Return 时不得直接把连接重新放入 Idle Pool。

---

# 10. Connection Reset

定义：

```java
public interface FtpStateManager {

    void reset(FtpConnection connection);

}
```

典型 Reset：

```text
Reset
 ├── working directory
 ├── file type
 ├── passive mode
 ├── encoding
 ├── transfer state
 └── protocol state
```

Reset 失败：

```text
reset failed
     ↓
mark BROKEN
     ↓
destroy
```

不得把状态异常的 FTP Connection 放回连接池。

---

# 11. Connection Validation

定义：

```java
public interface FtpConnectionValidator {

    boolean validate(FtpConnection connection);

}
```

默认验证方式：

```text
FTP NOOP
```

例如：

```java
ftpClient.sendNoOp();
```

---

## 11.1 Validation 不应每次 Borrow 都执行

FTP Validation 与 JDBC Validation 不同。

FTP：

```text
NOOP
 ↓
Network RTT
 ↓
Server Response
```

如果每次 Borrow 都执行：

```text
borrow
NOOP
borrow
NOOP
borrow
NOOP
```

会严重降低吞吐。

因此支持：

```yaml
validation-interval: 30s
```

只有连接超过 Validation Interval 时才主动验证。

---

# 12. Broken Connection

以下情况应标记：

```text
BROKEN
```

例如：

* SocketException
* Connection reset
* EOF
* Connection closed
* FTPClient disconnected
* Data connection failure
* IOException
* Validation failure
* Reset failure

Broken Connection：

```text
IN_USE
   ↓
BROKEN
   ↓
DESTROY
```

不能：

```text
BROKEN
 ↓
IDLE
```

---

# 13. Pool Engine SPI

这是项目最重要的 SPI。

```java
public interface PoolEngine<T> {

    T borrow(Duration timeout)
            throws Exception;

    void release(T resource);

    void invalidate(T resource);

    int size();

    int active();

    int idle();

    void close();
}
```

---

# 14. 三种核心模式

FtpPool 支持三种主要设计模式。

---

## 14.1 FAST 模式

来源思想：

> HikariCP

特点：

```text
Fast Path
CAS
低锁竞争
Thread Local
低分配
高吞吐
低延迟
```

实现：

```java
FastPoolEngine
```

核心目标：

```text
borrow latency ↓
return latency ↓
contention ↓
allocation ↓
throughput ↑
```

适合：

* 高并发
* 大量短 FTP 操作
* 微服务
* 高吞吐文件服务

---

# 15. COMMONS 模式

来源思想：

> Apache Commons Pool

实现：

```java
CommonsPoolEngine
```

特点：

```text
ObjectFactory
Borrow
Return
Validate
Evict
Idle Objects
Max Objects
Min Idle
```

适合：

* 传统企业应用
* 生命周期管理优先
* 希望行为更加明确
* 对性能没有极端要求

---

# 16. MONITOR 模式

来源思想：

> Druid

需要特别定义：

> Druid 的核心价值不是另一种高性能 Pool Algorithm，而是丰富的监控、统计和 Filter 能力。

因此 Monitor Mode 重点提供：

```text
Metrics
Filter
JMX
Slow Operation
Connection Statistics
Health
Audit
```

底层 Engine 可以继续使用：

```text
FastPoolEngine
```

或者：

```text
CommonsPoolEngine
```

---

# 17. HYBRID 模式

生产环境推荐提供：

```text
HYBRID
```

架构：

```text
Hikari-inspired
       │
       ▼
Fast Pool Engine
       │
       +
       │
       ▼
Commons-inspired
Lifecycle
       │
       +
       │
       ▼
Druid-inspired
Observability
```

即：

```text
Fast Engine
+
Robust Lifecycle
+
Full Observability
```

这是 FtpPool 最完整的模式。

---

# 18. 推荐 SPI 配置模型

不建议只设计一个：

```yaml
mode: hikari
```

因为：

> Pool Algorithm、Lifecycle、Observability 本质上是三个维度。

推荐：

```yaml
spring:
  tidepool:
    ftp:
      pool:
        engine: fast
        lifecycle: commons
        observability: full
```

定义：

```java
public enum PoolEngineType {
    FAST,
    COMMONS
}
```

```java
public enum LifecycleType {
    SIMPLE,
    COMMONS
}
```

```java
public enum ObservabilityType {
    NONE,
    BASIC,
    FULL
}
```

同时提供快捷 Profile：

```yaml
mode: fast
mode: commons
mode: monitor
mode: hybrid
```

映射为：

```text
fast
 └── Fast Engine

commons
 └── Commons Engine

monitor
 ├── Fast/Commons Engine
 └── Full Observability

hybrid
 ├── Fast Engine
 ├── Commons Lifecycle
 └── Full Observability
```

---

# 19. Pool Factory SPI

```java
public interface PoolEngineFactory {

    String name();

    PoolEngine<?> create(
        PoolConfiguration configuration,
        ResourceFactory<?> resourceFactory
    );
}
```

通过 Java SPI：

```text
META-INF/services/
```

实现：

```text
FastPoolEngineFactory
CommonsPoolEngineFactory
```

未来可以允许第三方：

```text
MyPoolEngineFactory
```

而不修改 FtpPool Core。

---

# 20. Resource Factory

FTP Connection 生命周期通过 Factory 管理：

```java
public interface FtpConnectionFactory {

    FtpConnection create()
        throws FtpException;

    boolean validate(FtpConnection connection);

    void reset(FtpConnection connection);

    void destroy(FtpConnection connection);
}
```

生命周期：

```text
create
   ↓
validate
   ↓
borrow
   ↓
business
   ↓
reset
   ↓
return
```

---

# 21. Connection Creation

连接创建过程：

```text
create
 ↓
connect
 ↓
TLS negotiation
 ↓
login
 ↓
configure encoding
 ↓
configure passive mode
 ↓
configure binary mode
 ↓
set initial directory
 ↓
validate
 ↓
IDLE
```

任何阶段失败：

```text
DESTROY
```

---

# 22. Connection Storm Protection

当连接池为空时：

```text
100 threads
    ↓
同时创建 100 FTP Connections
```

可能导致：

* FTP Server connection limit
* CPU 飙升
* Socket resource exhaustion
* Authentication storm
* Server overload

因此必须限制：

```yaml
max-create-concurrency: 2
```

支持 Connection Creation Throttling。

---

# 23. Pool Configuration

基础配置：

```yaml
spring:
  tidepool:
    ftp:
      host: ftp.example.com
      port: 21
      username: user
      password: password

      pool:
        mode: hybrid

        min-idle: 2
        max-size: 20

        connection-timeout: 3s
        socket-timeout: 30s
        data-timeout: 60s

        idle-timeout: 5m
        max-lifetime: 30m

        validation-interval: 30s

        max-create-concurrency: 2

        leak-detection-threshold: 30s
```

---

# 24. Pool Size

定义：

```text
min-idle
max-size
active
idle
total
```

例如：

```text
min-idle = 2
max-size = 20
```

状态：

```text
TOTAL = 10
IDLE = 7
ACTIVE = 3
```

---

# 25. Idle Eviction

HouseKeeper 定期检查：

```text
Idle Connection
       │
       ├── idle < timeout
       │       ↓
       │      keep
       │
       └── idle > timeout
               ↓
             evict
```

但必须保证：

```text
total >= minIdle
```

---

# 26. Max Lifetime

支持：

```yaml
max-lifetime: 30m
```

避免 FTP Server 长时间保持连接产生：

* Server timeout
* NAT timeout
* Firewall timeout
* Connection state corruption

生命周期达到阈值：

```text
IDLE
 ↓
evict
 ↓
destroy
```

对于：

```text
IN_USE
```

不应强制中断业务操作。

可以标记：

```text
RETIRE
```

业务完成后：

```text
RETURN
 ↓
DESTROY
```

---

# 27. HouseKeeper

参考 HikariCP 的 HouseKeeper 思想。

负责：

```text
Idle Eviction
Max Lifetime
Min Idle
Connection Validation
Pool Maintenance
Metrics Snapshot
```

例如：

```java
ScheduledExecutorService
```

默认：

```text
30s
```

---

# 28. Borrow Algorithm

Fast Mode 目标：

```text
Borrow
 ↓
Thread-local candidate
 ↓
Fast path
 ↓
CAS
 ↓
Shared queue
 ↓
Create connection
 ↓
Timeout
```

优先：

```text
Fast Path
```

其次：

```text
Shared Pool
```

最后：

```text
Create
```

---

# 29. Return Algorithm

```text
release(connection)
        │
        ▼
check broken
        │
   ┌────┴────┐
   ▼         ▼
broken      healthy
   │           │
   ▼           ▼
destroy      reset
               │
          ┌────┴────┐
          ▼         ▼
        success    failed
          │          │
          ▼          ▼
         IDLE      destroy
```

---

# 30. Thread Safety

核心原则：

> 一个 FTP Connection 在同一时间只能被一个业务线程独占。

禁止：

```text
Thread A ──┐
           ├── FTPClient
Thread B ──┘
```

允许：

```text
Thread A → Connection A
Thread B → Connection B
Thread C → Connection C
```

Pool 负责：

```text
borrow = ownership transfer
```

---

# 31. FTP Control Connection 与 Data Connection

这是 FTP Pool 与普通数据库连接池的重要区别。

FTP 包含：

```text
Control Connection
       │
       ├── USER
       ├── PASS
       ├── CWD
       ├── LIST
       ├── RETR
       └── STOR
              │
              ▼
        Data Connection
```

FtpPool 主要池化：

> **FTP Control Connection / FTPClient**

数据连接：

```text
LIST
RETR
STOR
```

由底层 FTP Client 按 FTP 协议生命周期创建和关闭。

不能简单把每个 Data Connection 当成池中的独立 Connection。

---

# 32. Transfer Lifecycle

文件传输必须保证：

```text
borrow
 ↓
open data connection
 ↓
transfer
 ↓
completePendingCommand
 ↓
close data stream
 ↓
reset state
 ↓
return
```

如果：

```text
transfer failed
```

需要根据异常类型判断：

```text
Business Error
```

还是：

```text
Connection Broken
```

Connection Broken：

```text
destroy
```

而不是：

```text
return
```

---

# 33. Exception Classification

定义：

```java
public enum FtpExceptionType {

    CONNECTION,
    AUTHENTICATION,
    TIMEOUT,
    PROTOCOL,
    TRANSFER,
    VALIDATION,
    POOL_TIMEOUT,
    POOL_CLOSED,
    BUSINESS
}
```

Connection 类异常：

```text
destroy connection
```

业务类 FTP Response：

```text
return connection
```

不能简单：

```java
catch (Exception e) {
    destroy();
}
```

否则会造成不必要的连接重建。

---

# 34. Retry

连接池本身只负责：

```text
Connection Recovery
```

不应该默认对：

```text
upload
download
delete
```

进行无限 Retry。

尤其是：

```text
STOR
```

失败后可能产生：

```text
partial file
```

重新上传可能造成业务副作用。

因此：

```text
Pool Retry
```

与：

```text
Business Operation Retry
```

必须分离。

---

# 35. Observability

提供：

```java
public interface FtpPoolMetrics {

    long borrowCount();

    long borrowTimeoutCount();

    long activeCount();

    long idleCount();

    long totalCount();

    long createCount();

    long destroyCount();

    long validationCount();

    long validationFailureCount();

    long brokenCount();

    long timeoutCount();

    long uploadCount();

    long downloadCount();

    long uploadBytes();

    long downloadBytes();
}
```

---

# 36. Operation Metrics

除了 Pool Metrics，还需要 FTP Operation Metrics：

```text
connect
login
borrow
return
validate
list
upload
download
delete
rename
mkdir
cwd
```

记录：

```text
count
success
failure
latency
bytes
```

---

# 37. Slow Operation

支持：

```yaml
observability:
  slow-operation-threshold: 3s
```

例如：

```text
UPLOAD
path=/data/test.zip
size=120MB
duration=8.2s
```

产生：

```text
Slow Operation Event
```

---

# 38. Filter SPI

借鉴 Druid Filter Chain。

```java
public interface FtpFilter {

    void beforeBorrow(FtpContext context);

    void afterBorrow(
        FtpContext context,
        FtpConnection connection
    );

    void beforeExecute(
        FtpContext context
    );

    void afterExecute(
        FtpContext context
    );

    void onError(
        FtpContext context,
        Throwable error
    );

    void beforeReturn(
        FtpContext context
    );

    void afterReturn(
        FtpContext context
    );
}
```

允许实现：

```text
MetricsFilter
LoggingFilter
SlowOperationFilter
AuditFilter
TracingFilter
```

---

# 39. Logging

默认不能输出：

```text
password
```

禁止日志：

```text
PASS xxx
```

必须脱敏：

```text
username=user
password=******
```

同时支持：

```yaml
logging:
  protocol-command: false
```

生产环境默认关闭 FTP Command Trace。

---

# 40. Leak Detection

支持：

```yaml
leak-detection-threshold: 30s
```

如果：

```text
borrow
 ↓
30s
 ↓
connection still IN_USE
```

记录：

```text
Possible connection leak
```

可以记录：

```text
borrow thread
borrow timestamp
stack trace
connection id
```

但不应该默认强制销毁连接。

---

# 41. Connection Identity

每一个 Pool Entry 都应该拥有唯一 ID：

```text
ftp-000001
ftp-000002
ftp-000003
```

例如：

```java
public record FtpConnectionId(long id) {}
```

方便：

* Metrics
* Debug
* JMX
* Leak Detection
* Logging

---

# 42. Pool Statistics

```java
public interface FtpPoolStats {

    int total();

    int active();

    int idle();

    int pending();

    long created();

    long destroyed();

    long borrowed();

    long returned();

    long borrowTimeouts();

    long validationFailures();
}
```

---

# 43. JMX

提供：

```text
com.ftppool:type=FtpPool,name=default
```

支持：

```text
Total
Active
Idle
Pending
Borrow Count
Timeout Count
Create Count
Destroy Count
Validation Failure
```

以及操作：

```text
clearIdle()
validateAll()
shutdown()
```

危险操作需要谨慎设计。

---

# 44. Micrometer

提供可选模块：

```text
ftp-pool-micrometer
```

指标：

```text
ftp.pool.size
ftp.pool.active
ftp.pool.idle
ftp.pool.pending
ftp.pool.borrow
ftp.pool.borrow.timeout
ftp.pool.create
ftp.pool.destroy
ftp.pool.validation.failure
ftp.operation.upload
ftp.operation.download
ftp.operation.latency
```

Tags：

```text
pool
host
port
protocol
operation
```

不要默认将：

```text
username
file path
password
```

作为高基数 Tag。

---

# 45. Spring Boot Starter

提供：

```text
ftp-pool-spring-boot-starter
```

用户只需要：

```xml
<dependency>
    <groupId>io.ftppool</groupId>
    <artifactId>ftp-pool-spring-boot-starter</artifactId>
</dependency>
```

即可自动配置。

---

# 46. Spring Boot Configuration

```yaml
spring:
  tidepool:
    ftp:
      enabled: true

      host: ftp.example.com
      port: 21

      username: user
      password: ${FTP_PASSWORD}

      protocol: ftp

      pool:
        mode: hybrid

        min-idle: 2
        max-size: 20

        connection-timeout: 3s
        socket-timeout: 30s
        data-timeout: 60s

        idle-timeout: 5m
        max-lifetime: 30m

        validation-interval: 30s

        leak-detection-threshold: 30s

      observability:
        metrics: true
        jmx: true
        slow-operation-threshold: 3s
```

---

# 47. Spring Bean

自动提供：

```java
@Bean
FtpPool ftpPool()
```

以及：

```java
@Bean
FtpClientTemplate ftpClientTemplate()
```

使用：

```java
@Service
public class FileService {

    private final FtpClientTemplate ftp;

    public void upload(InputStream input) {
        ftp.execute(connection -> {
            connection.upload(
                "/data/test.txt",
                input
            );
            return null;
        });
    }
}
```

---

# 48. Maven Multi-Module

项目采用 Maven Multi-Module。

```text
ftp-pool/
│
├── ftp-pool-api
│
├── ftp-pool-core
│
├── ftp-pool-engine-fast
│
├── ftp-pool-engine-commons
│
├── ftp-pool-adapter
│
├── ftp-pool-observability
│
├── ftp-pool-micrometer
│
├── ftp-pool-jmx
│
├── ftp-pool-spring-boot-autoconfigure
│
├── ftp-pool-spring-boot-starter
│
├── ftp-pool-benchmark
│
├── ftp-pool-examples
│
└── ftp-pool-tests
```

---

# 49. Module Responsibilities

## ftp-pool-api

只放公共 API：

```text
FtpPool
FtpConnection
FtpCallback
FtpPoolStats
FtpException
PoolEngine SPI
FtpFilter SPI
```

禁止：

```text
Spring
Micrometer
JMX
Apache Commons Net
```

依赖。

---

## ftp-pool-core

负责：

```text
FtpPoolImpl
Pool lifecycle
Connection lifecycle
Borrow / Return
Validation
Reset
Eviction
HouseKeeper
Configuration
```

---

## ftp-pool-engine-fast

实现：

```text
FastPoolEngine
```

重点：

```text
CAS
Fast Path
Low Lock
Thread Local
Concurrent Queue
```

---

## ftp-pool-engine-commons

基于：

```text
Apache Commons Pool
```

实现：

```text
CommonsPoolEngine
```

---

## ftp-pool-adapter

负责：

```text
FTPClient
FTPSClient
FtpConnection
FtpConnectionFactory
FtpStateManager
FtpConnectionValidator
```

---

## ftp-pool-observability

负责：

```text
Metrics
Filter
Slow Operation
Audit
Health
```

---

## ftp-pool-micrometer

负责：

```text
Micrometer integration
```

---

## ftp-pool-jmx

负责：

```text
JMX integration
```

---

## ftp-pool-spring-boot-autoconfigure

负责：

```text
@Configuration
@ConfigurationProperties
Conditional Bean
AutoConfiguration
```

---

## ftp-pool-spring-boot-starter

只负责聚合依赖。

---

## ftp-pool-benchmark

使用：

```text
JMH
```

---

# 50. Dependency Graph

```text
ftp-pool-api
      ▲
      │
      │
ftp-pool-core
   ▲      ▲
   │      │
   │      ├── ftp-pool-engine-fast
   │      │
   │      └── ftp-pool-engine-commons
   │
   └── ftp-pool-adapter
          │
          ▼
   Apache Commons Net


Observability
      │
      ▼
ftp-pool-observability
      │
      ├── micrometer
      └── jmx


Spring
      │
      ▼
spring-boot-autoconfigure
      │
      ▼
spring-boot-starter
```

---

# 51. API 与实现隔离

禁止 API 层出现：

```java
FTPClient
```

例如不允许：

```java
FtpPool<FTPClient>
```

推荐：

```java
FtpPool
```

以及：

```java
FtpConnection
```

这样用户面对的是 FtpPool，而不是 Apache Commons Net。

---

# 52. Configuration Builder

普通 Java 用户可以：

```java
FtpPool pool = FtpPoolBuilder
    .builder()
    .host("ftp.example.com")
    .port(21)
    .username("user")
    .password("password")
    .minIdle(2)
    .maxSize(20)
    .engine(PoolEngineType.FAST)
    .observability(ObservabilityType.FULL)
    .build();
```

---

# 53. 快捷 Profile

提供：

```java
FtpPoolProfiles.fast()
FtpPoolProfiles.commons()
FtpPoolProfiles.monitor()
FtpPoolProfiles.hybrid()
```

例如：

```java
FtpPool pool = FtpPoolProfiles
    .hybrid()
    .host("ftp.example.com")
    .username("user")
    .password(password)
    .build();
```

---

# 54. 默认 Profile

默认：

```text
HYBRID
```

即：

```text
Fast Engine
+
Robust Lifecycle
+
Basic/Full Observability
```

默认目标：

> 不要求用户理解 HikariCP、Commons Pool、Druid 的内部设计，也能直接获得合理的生产级 FTP Pool。

---

# 55. 性能目标

性能目标必须通过 JMH 实际验证，而不是文档声称。

Benchmark 至少包括：

### Borrow / Return

```text
1 thread
2 threads
4 threads
8 threads
16 threads
32 threads
64 threads
128 threads
```

---

### Pool Size

```text
1
2
4
8
16
32
64
```

---

### 对比

至少比较：

```text
No Pool
Apache Commons Pool
FtpPool Fast
FtpPool Commons
FtpPool Hybrid
```

---

# 56. JMH Benchmark

测试：

```text
BorrowBenchmark
ReturnBenchmark
BorrowReturnBenchmark
ConcurrentBorrowBenchmark
ConnectionCreationBenchmark
ValidationBenchmark
UploadBenchmark
DownloadBenchmark
BrokenConnectionBenchmark
EvictionBenchmark
```

---

# 57. Benchmark 指标

不能只看：

```text
Throughput
```

必须同时关注：

```text
Average Time
p50
p90
p95
p99
Allocation Rate
GC
CPU
Lock Contention
```

最终报告：

```text
Mode
Threads
Pool Size
Ops/s
p50
p95
p99
Alloc/op
```

---

# 58. Cold vs Warm Connection

必须区分：

```text
Cold Connection
```

与：

```text
Warm Connection
```

Cold：

```text
TCP
TLS
USER/PASS
Server negotiation
```

Warm：

```text
borrow
 ↓
FTP operation
 ↓
return
```

连接池的主要价值是降低：

```text
Cold Connection Cost
```

因此 Benchmark 必须分别测试。

---

# 59. Integration Test

测试环境可以使用：

```text
Apache MINA FTP Server
```

或者 Docker FTP Server。

测试：

```text
connect
login
list
cwd
upload
download
delete
rename
mkdir
```

---

# 60. Failure Test

必须覆盖：

### Server shutdown

```text
FTP Server
   ↓
shutdown
   ↓
borrow
   ↓
validation failed
   ↓
destroy
```

---

### Connection reset

```text
Socket
 ↓
reset
 ↓
IOException
 ↓
BROKEN
 ↓
destroy
 ↓
create replacement
```

---

### Server timeout

测试：

```text
idle timeout
```

---

### Pool timeout

例如：

```yaml
max-size: 2
```

3 个线程同时 Borrow：

```text
T1 → Connection 1
T2 → Connection 2
T3 → WAIT
```

超过：

```text
connection-timeout
```

返回：

```text
PoolTimeoutException
```

---

# 61. Concurrency Test

必须验证：

```text
100 threads
1000 threads
```

场景：

```text
borrow
upload
download
return
```

确保：

* 无连接泄漏
* 无重复分配
* 无 Connection Concurrent Use
* 无死锁
* 无数据竞争
* Pool close 后无资源泄漏

---

# 62. Pool Shutdown

调用：

```java
pool.close();
```

流程：

```text
STOP ACCEPT BORROW
       ↓
WAIT ACTIVE
       ↓
CLOSE IDLE
       ↓
CLOSE ACTIVE
       ↓
STOP HOUSEKEEPER
       ↓
STOP METRICS
       ↓
CLOSED
```

对于 Spring Boot：

```text
ApplicationContext shutdown
        ↓
FtpPool.close()
```

---

# 63. Security

必须支持：

```text
Password
Environment Variable
Secret Provider
```

密码不得：

```text
log
metrics
JMX
exception message
```

支持：

```yaml
password: ${FTP_PASSWORD}
```

---

# 64. FTPS Security

支持：

```text
TLS
SSLContext
TrustManager
KeyManager
Hostname Verification
```

不允许默认关闭 TLS 验证作为正常配置。

如提供：

```text
trust-all
```

必须明确标记为：

```text
unsafe / development only
```

---

# 65. Metrics Cardinality

禁止默认使用：

```text
filePath
username
full FTP command
```

作为 Metrics Tag。

否则可能造成：

```text
Metric Cardinality Explosion
```

---

# 66. Logging Context

日志推荐：

```text
pool
connectionId
operation
duration
result
exceptionType
```

例如：

```text
pool=default
connectionId=ftp-000012
operation=DOWNLOAD
duration=152ms
result=SUCCESS
```

---

# 67. Tracing

预留：

```java
FtpFilter
```

接入：

```text
OpenTelemetry
Micrometer Tracing
```

但第一阶段不强制依赖。

---

# 68. Health Check

提供：

```java
FtpPoolHealth
```

状态：

```text
UP
DEGRADED
DOWN
```

例如：

```text
UP
active=3
idle=5
total=8
```

---

# 69. Spring Boot Actuator

可选支持：

```text
/actuator/health
```

返回：

```json
{
  "status": "UP",
  "details": {
    "ftpPool": {
      "total": 10,
      "active": 3,
      "idle": 7
    }
  }
}
```

---

# 70. Project Naming

品牌名（对外）：

# TidePool

> 潮汐池 —— 天然自带 "Pool" 意象，潮起潮落对应连接 Borrow / Return 复用循环，
> 潮水换新对应连接重置与重生，与 HikariCP（水之意象）一脉相承。

工程名（对内）：

# FtpPool

品牌名与工程名分离：

```text
品牌名：TidePool      → 文档、标语、徽标、对外宣传
工程名：FtpPool       → 包名、Maven artifact、代码
```

> 注意：`tidepool.org`（糖尿病开源平台）已存在，故不占用 `io.tidepool` 包名，
> 工程内部统一使用 `io.ftppool / ftp-pool-*`，规避冲突风险。

Maven：

```xml
<groupId>io.ftppool</groupId>
<artifactId>ftp-pool</artifactId>
```

推荐 package：

```java
io.ftppool
```

例如：

```text
io.ftppool.api
io.ftppool.core
io.ftppool.engine.fast
io.ftppool.engine.commons
io.ftppool.adapter
io.ftppool.observability
```

---

# 71. 项目宣传语

品牌标语（英文）：

> **TidePool — High-Performance, Production-Ready FTP Connection Pool for Java.**

中文：

> **TidePool —— 面向 Java 的高性能、可观测、生产级 FTP 连接池。**
>
> 潮起潮落，连接永续。

更完整的技术定位：

> Inspired by HikariCP, Apache Commons Pool and Druid — designed specifically for FTP.

工程名表述：

> **FtpPool（TidePool）—— 面向 Java 的高性能、可观测、生产级 FTP 连接池。**

---

# 72. 第一阶段 MVP

第一阶段不要一次性实现所有功能。

必须先实现：

```text
FtpPool API
       ↓
FtpConnection
       ↓
FtpConnectionFactory
       ↓
FastPoolEngine
       ↓
Apache Commons Net
```

完成：

* FTP
* Borrow
* Return
* Min Idle
* Max Size
* Connection Timeout
* Validation
* Reset
* Broken Connection
* Idle Timeout
* Max Lifetime
* HouseKeeper
* Basic Metrics
* JUnit Test

---

# 73. 第二阶段

加入：

```text
CommonsPoolEngine
```

实现：

```text
engine=commons
```

验证：

```text
Fast vs Commons
```

---

# 74. 第三阶段

加入：

```text
Observability
```

包括：

```text
Metrics
Filter
Slow Operation
Leak Detection
JMX
```

形成：

```text
mode=monitor
```

---

# 75. 第四阶段

实现：

```text
HYBRID
```

即：

```text
Fast Engine
+
Commons Lifecycle
+
Druid-inspired Observability
```

形成正式生产模式。

---

# 76. 第五阶段

加入：

```text
FTPS
Micrometer
Spring Boot Starter
Actuator
OpenTelemetry SPI
```

---

# 77. 第六阶段

性能优化：

```text
CAS
Fast Path
Thread Local
Queue Optimization
Allocation Reduction
Contention Reduction
HouseKeeper Optimization
```

以 JMH 数据驱动优化，而不是为了“像 HikariCP”而机械复制其实现。

---

# 78. Roadmap

```text
Phase 0
├── Project skeleton
├── Maven modules
└── API definition

Phase 1
├── FTP connection
├── Pool
├── Borrow / Return
├── Validation
├── Reset
├── Broken connection
└── Lifecycle

Phase 2
├── Fast Engine
└── Commons Engine

Phase 3
├── Metrics
├── Filter
├── Slow Operation
├── Leak Detection
└── JMX

Phase 4
├── Hybrid Mode
└── Production hardening

Phase 5
├── FTPS
├── Micrometer
├── Spring Boot
└── Actuator

Phase 6
├── JMH
├── Performance tuning
├── Stress testing
└── Documentation

Phase 7
├── Release 0.1
├── Release 0.5
└── Release 1.0
```

---

# 79. Definition of Done

FtpPool 1.0 必须满足：

### API

* [ ] FtpPool API 稳定
* [ ] FtpConnection API 稳定
* [ ] SPI 稳定
* [ ] Builder API
* [ ] execute API

### Pool

* [ ] Borrow
* [ ] Return
* [ ] Timeout
* [ ] Min Idle
* [ ] Max Size
* [ ] Idle Timeout
* [ ] Max Lifetime
* [ ] Validation
* [ ] Reset
* [ ] Broken Connection
* [ ] Eviction
* [ ] HouseKeeper

### Engine

* [ ] Fast Engine
* [ ] Commons Engine
* [ ] Hybrid Profile

### FTP

* [ ] FTP
* [ ] FTPS
* [ ] Passive Mode
* [ ] Binary Mode
* [ ] Working Directory
* [ ] Transfer State

### Observability

* [ ] Metrics
* [ ] Filter
* [ ] Slow Operation
* [ ] Leak Detection
* [ ] JMX
* [ ] Micrometer

### Spring

* [ ] Spring Boot Auto Configuration
* [ ] Starter
* [ ] Configuration Properties
* [ ] Actuator Health

### Quality

* [ ] Unit Tests
* [ ] Integration Tests
* [ ] Failure Tests
* [ ] Concurrency Tests
* [ ] Stress Tests
* [ ] JMH Benchmarks

### Documentation

* [ ] README
* [ ] Architecture
* [ ] Quick Start
* [ ] Configuration
* [ ] SPI Guide
* [ ] Performance Guide
* [ ] Spring Boot Guide
* [ ] FTPS Guide

---

# 80. 最终架构

最终 FtpPool 应形成如下结构：

```text
                         ┌─────────────────────┐
                         │       FtpPool       │
                         │     Public API      │
                         └──────────┬──────────┘
                                    │
                                    ▼
                         ┌─────────────────────┐
                         │    Pool Manager     │
                         └──────────┬──────────┘
                                    │
                     ┌──────────────┼──────────────┐
                     │              │              │
                     ▼              ▼              ▼
              ┌────────────┐ ┌────────────┐ ┌────────────┐
              │    FAST    │ │   COMMONS  │ │   CUSTOM   │
              │   Engine   │ │   Engine   │ │   Engine   │
              └─────┬──────┘ └─────┬──────┘ └─────┬──────┘
                    │              │              │
                    └──────────────┼──────────────┘
                                   ▼
                         ┌─────────────────────┐
                         │   FtpPoolEntry      │
                         │ Connection Lifecycle│
                         └──────────┬──────────┘
                                    │
                  ┌─────────────────┼─────────────────┐
                  │                 │                 │
                  ▼                 ▼                 ▼
          ┌──────────────┐ ┌──────────────┐ ┌──────────────┐
          │   Validator  │ │ StateManager │ │   Factory    │
          └──────────────┘ └──────────────┘ └──────────────┘
                  │                 │                 │
                  └─────────────────┼─────────────────┘
                                    ▼
                         ┌─────────────────────┐
                         │   FtpConnection     │
                         └──────────┬──────────┘
                                    │
                                    ▼
                         ┌─────────────────────┐
                         │ FTPClient / FTPS    │
                         │ Apache Commons Net  │
                         └─────────────────────┘


              ┌──────────────────────────────────────┐
              │          Observability SPI            │
              │                                      │
              │ Metrics │ Filters │ JMX │ Micrometer │
              │ SlowOps │ Leak Detection │ Health    │
              └──────────────────────────────────────┘
```

---

# 81. 核心设计结论

FtpPool 的核心不是：

> “把 HikariCP 改成 FTP。”

而应该是：

> **把 HikariCP 的高性能连接池思想、Commons Pool 的生命周期管理思想、Druid 的可观测性思想，重新组合成一个专门解决 FTP 有状态连接问题的连接池。**

最终形成：

```text
                FtpPool
                   │
        ┌──────────┼──────────┐
        │          │          │
        ▼          ▼          ▼
      Fast      Commons    Monitor
    Hikari-like Pool-like  Druid-like
        │          │          │
        └──────────┼──────────┘
                   ▼
                Hybrid
                   │
        ┌──────────┼──────────┐
        ▼          ▼          ▼
     Fast      Lifecycle  Observability
    Engine     Manager       Layer
        │          │          │
        └──────────┼──────────┘
                   ▼
             FTP-specific
             State Machine
                   │
                   ▼
             FTP / FTPS
```

**最终产品不是三个独立的 FTP 连接池，而是一个 FtpPool，通过 SPI 把“性能算法、生命周期管理、可观测性”拆成可以替换和组合的能力。**

这也是这个项目最值得长期维护的架构核心。
