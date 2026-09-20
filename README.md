<p align="center">
  <img src="public/logo.svg" alt="TidePool — High-Performance, Production-Ready FTP Connection Pool for Java" width="480">
</p>

# TidePool

> **TidePool** — High-Performance, Production-Ready FTP Connection Pool for Java.
>
> 面向 Java 的高性能、可观测、生产级 FTP 连接池。
> 潮起潮落，连接永续。

Project name: **FtpPool** · Package: `io.ftppool` · Artifacts: `ftp-pool-*` · GroupId: `io.ftppool`

---

## 目录

- [环境要求](#环境要求)
- [安装](#安装)
- [发布到企业内部私有 Maven 仓库](#发布到企业内部私有-maven-仓库)
- [快速使用](#快速使用)
- [JVM 参数（JDK 17 / 21）](#jvm-参数jdk-17--21)
- [License 说明](#license-说明)
- [更多文档](#更多文档)
- [赞助支持](#赞助支持)

## 环境要求

| 项 | 要求 |
| --- | --- |
| JDK | **17 或更高**（编译目标 `maven.compiler.release=17`，适配 17 / 21 运行时） |
| Maven | 3.8+（仓库自带 Wrapper，`mvn` 不在 PATH 时用 `./mvnw.cmd` / `./mvnw`） |
| Gradle | 7+（仅消费依赖时可用） |
| Spring Boot（可选） | **3.x**（使用 `ftp-pool-spring-boot-starter` 时；本项目锁定 `spring-boot.version=3.4.5`） |

> 本项目按 Java 17 字节码发布，**可运行在 JDK 17 及更高版本**；Spring Boot 侧锁定 3.x，请勿升到需要 Java 21+ 的 4.x。

## 安装

FtpPool 采用「核心 + SPI 实现」分层：`ftp-pool-core` 不直接依赖任何引擎或 FTP 客户端，运行时通过
`META-INF/services` 发现实现。因此**必须把引擎模块和适配器模块一并放到 classpath**，否则构建 Pool 时会抛异常。

### 从企业内部私有仓库引入（推荐）

Maven：

```xml
<properties>
    <ftp-pool.version>0.1.0-SNAPSHOT</ftp-pool.version>
</properties>

<dependencies>
    <!-- 核心：FtpPool / FtpPoolBuilder / FtpClientTemplate（会传递引入 ftp-pool-api） -->
    <dependency>
        <groupId>io.ftppool</groupId>
        <artifactId>ftp-pool-core</artifactId>
        <version>${ftp-pool.version}</version>
    </dependency>

    <!-- 高性能引擎（默认 FAST） -->
    <dependency>
        <groupId>io.ftppool</groupId>
        <artifactId>ftp-pool-engine-fast</artifactId>
        <version>${ftp-pool.version}</version>
    </dependency>

    <!-- Apache Commons Net 适配器：FTP / FTPS -->
    <dependency>
        <groupId>io.ftppool</groupId>
        <artifactId>ftp-pool-adapter</artifactId>
        <version>${ftp-pool.version}</version>
    </dependency>
</dependencies>
```

Gradle：

```groovy
def ftpPoolVersion = "0.1.0-SNAPSHOT"

dependencies {
    implementation "io.ftppool:ftp-pool-core:$ftpPoolVersion"
    implementation "io.ftppool:ftp-pool-engine-fast:$ftpPoolVersion"
    implementation "io.ftppool:ftp-pool-adapter:$ftpPoolVersion"
}
```

按需追加的模块：

| artifact | 何时需要 |
| --- | --- |
| `ftp-pool-engine-commons` | 使用 `commons()` profile 或 `engine=COMMONS` |
| `ftp-pool-observability` | 需要指标 / 慢操作 / 审计日志过滤链（`hybrid()` / `monitor()` 会用到） |
| `ftp-pool-micrometer` | 对接 Micrometer（Prometheus 等） |
| `ftp-pool-jmx` | 注册 JMX MBean |
| `ftp-pool-spring-boot-starter` | Spring Boot 一键接入（传递引入 adapter + engine-fast + observability + micrometer + jmx） |

> 生产推荐 `hybrid()` 默认档，最小依赖集之外再加 `ftp-pool-observability`（按需再加 `ftp-pool-micrometer` / `ftp-pool-jmx`）。
> 缺少 `ftp-pool-observability` 不会报错，但会打印 `No FtpFilterProvider ... on the classpath` 警告且没有任何指标。

### 从源码构建并安装到本地仓库

```bash
# 全量构建（13 个模块）
./mvnw.cmd clean install          # Windows
./mvnw clean install              # Unix

# 快速校验
./mvnw.cmd -q validate

# 仅构建某个模块（依赖需已安装到本地仓库）
./mvnw.cmd -pl ftp-pool-core install
```

## 发布到企业内部私有 Maven 仓库

发布前先确保版本号已从 `-SNAPSHOT` 改为正式版本（`0.1.0`、`1.0.0` …）。
正式版本进 `maven-releases`，`-SNAPSHOT` 进 `maven-snapshots`，Nexus/Artifactory 的 repository 地址不同。

### 1. 配置仓库凭据（`~/.m2/settings.xml`）

```xml
<settings>
  <servers>
    <server>
      <id>internal-releases</id>
      <username>${env.NEXUS_USER}</username>
      <password>${env.NEXUS_PASS}</password>
    </server>
    <server>
      <id>internal-snapshots</id>
      <username>${env.NEXUS_USER}</username>
      <password>${env.NEXUS_PASS}</password>
    </server>
  </servers>
</settings>
```

> 用户名/密码用环境变量注入，**不要写死在 `settings.xml` 或仓库里**。

### 2. 发布（二选一）

**方式 A：命令行指定仓库（不改 pom，适合一次性发布）**

```bash
# Windows（^ 为 cmd 续行符）
./mvnw.cmd -Prelease clean deploy ^
  -pl .,ftp-pool-api,ftp-pool-core,ftp-pool-adapter,ftp-pool-engine-fast,ftp-pool-engine-commons,ftp-pool-observability,ftp-pool-micrometer,ftp-pool-jmx,ftp-pool-spring-boot-autoconfigure,ftp-pool-spring-boot-starter ^
  -DaltDeploymentRepository=internal-releases::default::https://nexus.example.com/repository/maven-releases/ ^
  -Dgpg.skip=true
```

```bash
# Unix
./mvnw -Prelease clean deploy \
  -pl .,ftp-pool-api,ftp-pool-core,ftp-pool-adapter,ftp-pool-engine-fast,ftp-pool-engine-commons,ftp-pool-observability,ftp-pool-micrometer,ftp-pool-jmx,ftp-pool-spring-boot-autoconfigure,ftp-pool-spring-boot-starter \
  -DaltDeploymentRepository=internal-releases::default::https://nexus.example.com/repository/maven-releases/ \
  -Dgpg.skip=true
```

- `-pl` 只发布对外可用的 10 个模块 + 根 POM（`.`），**不发布** `ftp-pool-benchmark` / `ftp-pool-examples` / `ftp-pool-tests`。
- 根 POM 必须一起发布，否则下游解析子模块时找不到 parent。
- 内网 Nexus 通常不需要 GPG 签名，用 `-Dgpg.skip=true` 跳过；需要签名时去掉该参数并配置好 GPG key。

**方式 B：在根 `pom.xml` 固化 `distributionManagement`（推荐长期使用）**

```xml
<distributionManagement>
  <repository>
    <id>internal-releases</id>
    <url>https://nexus.example.com/repository/maven-releases/</url>
  </repository>
  <snapshotRepository>
    <id>internal-snapshots</id>
    <url>https://nexus.example.com/repository/maven-snapshots/</url>
  </snapshotRepository>
</distributionManagement>
```

之后直接：

```bash
./mvnw.cmd -Prelease clean deploy -Dgpg.skip=true
```

> 若不想让 benchmark/examples/tests 也 deploy，可给这些模块的 `pom.xml` 加
> `<properties><maven.deploy.skip>true</maven.deploy.skip></properties>`，或在 CI 里使用上面的 `-pl` 列表。

### 3. Gradle 发布（可选）

若企业仓库要求用 Gradle 发布，在根项目启用 `maven-publish`：

```groovy
plugins { id 'maven-publish' }

publishing {
    repositories {
        maven {
            url = "https://nexus.example.com/repository/maven-releases/"
            credentials {
                username = System.getenv("NEXUS_USER")
                password = System.getenv("NEXUS_PASS")
            }
        }
    }
}
```

### 4. 发布检查清单

- [ ] 版本号已去掉 `-SNAPSHOT`
- [ ] `LICENSE` 随发布物分发（`-Prelease` 会附加 sources/javadoc）
- [ ] `-Dgpg.skip=true` 仅用于内网；对外发布必须签名
- [ ] `settings.xml` 凭据来自环境变量
- [ ] 下游用 `io.ftppool:ftp-pool-spring-boot-starter` 验证一次依赖解析

## 快速使用

### 纯 Java

```java
import io.ftppool.api.FtpPool;
import io.ftppool.core.FtpPoolProfiles;

import java.io.InputStream;

public class Demo {
    public static void main(String[] args) throws Exception {
        FtpPool pool = FtpPoolProfiles.hybrid()          // 默认推荐：Fast 引擎 + Commons 生命周期 + 可观测性
                .host("ftp.example.com")
                .port(21)
                .username("user")
                .password(System.getenv("FTP_PASSWORD")) // 从环境变量读取，勿硬编码
                .poolName("main")
                .build();

        try (pool) {                                     // FtpPool 实现 AutoCloseable
            try (InputStream in = Demo.class.getResourceAsStream("/report.csv")) {
                boolean ok = pool.execute(ftp -> ftp.upload("/upload/report.csv", in));
                System.out.println("uploaded=" + ok);
            }
        }
    }
}
```

需要精细控制生命周期时手动借还：

```java
FtpConnection conn = pool.borrow();                 // 或 pool.borrow(Duration.ofSeconds(2))
try {
    FtpFile[] files = conn.listFiles("/data");
    // ... 业务逻辑 ...
} finally {
    pool.release(conn);                             // 必须归还，否则连接泄漏
}
```

### Spring Boot 3.x

```xml
<dependency>
    <groupId>io.ftppool</groupId>
    <artifactId>ftp-pool-spring-boot-starter</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

```yaml
spring:
  tidepool:
    ftp:
      host: ftp.example.com
      port: 21
      username: user
      password: ${FTP_PASSWORD}
      pool:
        mode: hybrid          # fast | commons | monitor | hybrid（默认 hybrid）
        min-idle: 2
        max-size: 20
      observability:
        metrics: true
        jmx: true
        slow-operation-threshold: 3s
```

自动配置会注册 `FtpPool` 与 `FtpClientTemplate` Bean（容器关闭时自动 `close()`），存在 Actuator 时还会提供
`ftpPool` 健康检查，`spring.tidepool.ftp.observability.jmx=true` 时注册 MBean。
`spring.tidepool.ftp.enabled=true`（默认）但未配置 `spring.tidepool.ftp.host` 会导致启动失败，禁用请显式设置 `spring.tidepool.ftp.enabled=false`。

```java
@Service
public class ReportService {
    private final FtpClientTemplate ftp;

    public ReportService(FtpClientTemplate ftp) {   // 也可直接注入 FtpPool
        this.ftp = ftp;
    }

    public void upload(InputStream in) throws FtpException {
        ftp.upload("/data/report.csv", in);
    }
}
```

### Profile 一览

| profile | engine | lifecycle | observability |
| --- | --- | --- | --- |
| `hybrid`（默认） | FAST | COMMONS | FULL |
| `fast` | FAST | SIMPLE | NONE |
| `commons` | COMMONS | COMMONS | NONE |
| `monitor` | FAST | SIMPLE | FULL |

### 示例

`ftp-pool-examples` 含 `QuickStartExample` / `TemplateExample` / `ProfilesExample`，可直接用 exec 插件运行：

```bash
./mvnw.cmd -pl ftp-pool-examples -am exec:java -Dexec.mainClass=io.ftppool.examples.QuickStartExample -Dexec.args="ftp.example.com 21 user ******"
```

## JVM 参数（JDK 17 / 21）

**结论：不需要任何特殊 JVM 参数。** 本项目以 `--release 17` 编译、没有 `module-info`，是标准 classpath 应用，
在 JDK 17 与 JDK 21 上直接 `java -cp ... Main` 即可，无需 `--add-opens` / `--add-exports` / `--enable-preview`。

以下是可选的按需参数：

| 场景 | 参数 | 说明 |
| --- | --- | --- |
| 统一本地字符集（JDK 17 尤其 Windows） | `-Dfile.encoding=UTF-8` | 池内部 FTP 控制编码固定 UTF-8；JDK 18+ 默认已是 UTF-8（JEP 400），17 建议显式加 |
| FTPS 自定义信任库 | `-Djavax.net.ssl.trustStore=/path/truststore.jks -Djavax.net.ssl.trustStorePassword=...` | 或直接提供一个 `SSLContext` Bean，二选一 |
| 远程 JMX 监控 | `-Dcom.sun.management.jmxremote -Dcom.sun.management.jmxremote.port=9010 -Dcom.sun.management.jmxremote.authenticate=true -Dcom.sun.management.jmxremote.ssl=true` | 仅远程采集 JMX 时需要；本地 MBean 不需要 |
| 使用 JDK 21 虚拟线程（业务侧可选） | Spring Boot 3.2+ 加 `spring.threads.virtual.enabled=true` | TidePool 本身线程安全、未使用虚拟线程，非必需 |

> 若构建机是 JDK 21 而运行机是 JDK 17：保持 `maven.compiler.release=17` 即可（已配置），不要使用 Java 18+ 的 API。
> 根 `pom.xml` 的 enforcer 会强制 JDK ≥ 17。

## License 说明

本项目采用 **Apache License 2.0**（见 [LICENSE](LICENSE)，并在根 `pom.xml` 的 `<licenses>` 中声明）。


## 更多文档

- 详细中文文档（快速开始 / 配置 / FTPS / Spring Boot / 可观测性 / 排错）：[`docs/index.html`](docs/index.html)
- 需求与架构（唯一权威规格）：[`requirements.md`](requirements.md)
- 变更记录：[`CHANGELOG.md`](CHANGELOG.md)
- 模块地图与开发约定：[`AGENTS.md`](AGENTS.md)

## 赞助支持

如果 TidePool 对你有帮助，欢迎请作者喝杯咖啡 ☕ 你的支持是项目持续维护的动力。

<table>
  <tr>
    <th align="center">微信支付</th>
    <th align="center">支付宝</th>
  </tr>
  <tr>
    <td align="center" valign="top">
      <img src="public/wechat-pay.webp" alt="微信赞赏码" width="220" height="220">
    </td>
    <td align="center" valign="top">
      <img src="public/alipay.webp" alt="支付宝赞赏码" width="220" height="220">
    </td>
  </tr>
</table>


