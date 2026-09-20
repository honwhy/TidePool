# AGENTS.md

## Project status

Active implementation, not greenfield. The only authoritative spec is `requirements.md` at repo root — treat it as the source of truth for all design/API decisions. The full 13-module reactor builds (`./mvnw.cmd clean install`) and every module has real code plus tests.

Current progress against the spec roadmap:

- **Phase 0-1 done**: `ftp-pool-api` (dependency-free, 16 types incl. `PoolEngine`/`FtpFilter` SPI), `ftp-pool-core` (`FtpPoolImpl`, borrow/return, validation, reset, broken handling, idle/max-lifetime, leak detector, `FtpPoolProfiles`, builders, stats), `ftp-pool-adapter` (Apache Commons Net `FtpConnection`, FTP/FTPS explicit+implicit, trust-all gated behind explicit unsafe config).
- **Phase 2 done**: `FastPoolEngine` (CAS fast path, thread-local candidates, throttled creation, housekeeper) and `CommonsPoolEngine` (`GenericObjectPool` + reset-on-return, testWhileIdle, max-lifetime at borrow).
- **Phase 3 done**: `ftp-pool-observability` (metrics/slow-op/logging filters), `ftp-pool-micrometer`, `ftp-pool-jmx`, leak detection.
- **Phase 4 done**: Hybrid profile is the default; `LifecycleType` is a real axis (`SIMPLE` disables background maintenance, `COMMONS` enables it) so Fast engine + Commons lifecycle is genuine.
- **Phase 5 done**: Spring Boot autoconfigure (properties, `FtpPool`/`FtpClientTemplate` beans, HealthIndicator, JMX conditional) + starter; FTPS adapter **and end-to-end FTPS verification** (explicit + implicit against embedded MINA FTPS); `FtpTracer`/`TracingFilter` SPI for OTel/Micrometer Tracing; JMX auto-registration via `FtpPoolMBeanRegistrar`.
- **Phase 6 done**: JMH benchmarks incl. `EngineComparisonBenchmark` (noPool/commonsPool/fast/commons/hybrid), `ftp-pool-examples`, integration/failure/concurrency/lifecycle/FTPS suites, GitHub Actions CI.

Also done: per-operation + byte metrics (`ftp.operation.*`), graceful-shutdown drain (`shutdown-timeout`), live-state health semantics, `rename`/`mkdir`, Apache-2.0 LICENSE + `release` Maven profile + CHANGELOG. Remaining for Release 1.0: capture JMH numbers from a release run and finish the `requirements.md` §79 docs polish.

## Naming (easy to get wrong)

- Brand name: **TidePool** (docs, marketing, badge). Do NOT use for package names.
- Project name: **FtpPool** → package `io.ftppool.*`, artifacts `ftp-pool-*`, groupId `io.ftppool`.
- Never use `io.tidepool` — a different project owns that namespace (explicit constraint).
- Packages in use: `io.ftppool.api`, `.core`, `.engine.fast`, `.engine.commons`, `.adapter`, `.observability`.

## Module layout (from spec)

Maven multi-module: `ftp-pool-api`, `ftp-pool-core`, `ftp-pool-engine-fast`, `ftp-pool-engine-commons`, `ftp-pool-adapter` (Apache Commons Net adapter), `ftp-pool-observability`, `ftp-pool-micrometer`, `ftp-pool-jmx`, `ftp-pool-spring-boot-autoconfigure`, `ftp-pool-spring-boot-starter`, `ftp-pool-benchmark` (JMH), `ftp-pool-examples`, `ftp-pool-tests`.

## Architecture constraints (non-negotiable, from spec)

- **ftp-pool-api must stay dependency-free**: no Spring, Micrometer, JMX, or Apache Commons Net in the API module. It exposes only `FtpPool`, `FtpConnection`, `FtpCallback`, `FtpPoolStats`, `FtpException`, `PoolEngine` SPI, `FtpFilter` SPI.
- Public API must never leak `FTPClient`/`FTPSClient`/`FtpPool<T>` — users face `FtpPool`/`FtpConnection`, not Apache Commons Net.
- FTP-first, not a generic resource pool. Pool engine, lifecycle, and observability are three separable axes (engine / lifecycle / observability), not one `mode` enum. Default profile is `hybrid` (Fast Engine + Commons-style lifecycle + observability).
- FTPClient is highly stateful: returning a connection requires state reset (working directory, file type→BINARY, passive mode, encoding, transfer state). A failed reset ⇒ mark broken ⇒ destroy, never back to idle. Never return a connection with an open/uncompleted data stream.
- Broken connections must be destroyed, never returned to idle.
- Do not copy HikariCP/Druid/Commons Pool source code — borrow the proven ideas (CAS, fast path, housekeeper, object factory, metrics/filter) and recombine for FTP.
- Non-goals: FTP server, generic resource pool, distributed/JVM-shared pool, infinite retry of upload/download/delete (pool recovery != business retry).

## Build & conventions

- **Java 17 toolchain** (the project baseline; `maven.compiler.release=17` in root `pom.xml`), Maven via the checked-in Maven Wrapper. **`mvn` is NOT on PATH** — always use `./mvnw.cmd` (or `./mvnw` on Unix) from the repo root.
- Full reactor build: `./mvnw.cmd clean install` (13 modules). Quick validate: `./mvnw.cmd -q validate`. Single module: `./mvnw.cmd -pl ftp-pool-core`.
- Local repo at `d:\r` (from `~/.m2/settings.xml`), mirror is Aliyun — use `-o` (offline) after first resolve when artifacts are already cached.
- **Lombok is mandatory** (declared once in root `pom.xml`, scope `provided`, v1.18.46): use `@Getter`/`@Setter`/`@Builder`/`@Data` for beans, and **`@Slf4j`** for SLF4J logging in every module — never hand-write `LoggerFactory.getLogger`. Lombok is compile-time only (not transitive), so `ftp-pool-api` stays dependency-free at runtime. Any module that overrides `annotationProcessorPaths` (only `ftp-pool-benchmark`, for JMH) must re-declare `lombok` in its processor paths.
- **Java 17 baseline, Spring Boot 3.x**: every module is built with `maven.compiler.release=17` (root `pom.xml`) and CI runs on JDK 17 — keep all source Java 17 compatible (no Java 18+ APIs/language features). Spring Boot is pinned at 3.x (`spring-boot.version` in root `pom.xml`) — do not bump to 4.x / a framework that requires Java 21+. Only the Spring Boot autoconfigure/starter modules touch Spring; `ftp-pool-api` stays dependency-free.
- `ftp-pool-tests` splits unit vs integration via Surefire groups: unit tests run by default (`groups=unit`, `excludedGroups=integration`); integration tests (MINA FTP Server, failure/concurrency suites) run explicitly. JMH: `./mvnw.cmd -pl ftp-pool-benchmark package` then `java -jar ftp-pool-benchmark/target/benchmarks.jar '.*'`.
- Tests: JUnit unit tests; integration tests against Apache MINA FTP Server (`org.apache.ftpserver:ftpserver-core:1.2.1`). Expected coverage includes failure tests (server shutdown, socket reset, pool timeout), concurrency tests (100/1000 threads), and JMH benchmarks.

## Security & observability gotchas (from spec)

- Passwords must never appear in logs, metrics, JMX, or exception messages. Default-off FTP command trace. Nothing like `PASS xxx` in logs.
- No high-cardinality metric tags: never default `filePath`, `username`, or full FTP command as tags.
- Any `trust-all` FTPS option must be explicitly marked unsafe/development-only; TLS verification must not be silently disabled.
- Logging context should carry `pool`, `connectionId`, `operation`, `duration`, `result`, `exceptionType`.

## Roadmap status

- **Done**: `FtpPool` API → `FtpConnection` → factory → Fast engine → Commons engine → observability/JMX/Micrometer → leak detection; hybrid lifecycle axis, FTPS e2e verification, Tracing SPI, JMX auto-registration, operation/byte metrics, graceful shutdown, CI.
- **Remaining for Release 1.0**: capture JMH numbers from a release run (`EngineComparisonBenchmark`) and the `requirements.md` §79 docs polish.