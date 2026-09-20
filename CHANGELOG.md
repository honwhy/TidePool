# Changelog

All notable changes to TidePool / FtpPool are documented here.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- Per-operation metrics and transferred-byte counters (`ftp.operation.<op>`,
  `ftp.operation.latency`, `ftp.operation.bytes`), instrumented at the
  `FtpConnection` seam via `FtpFilter#beforeOperation/afterOperation/onOperationError`.
- Graceful shutdown: `close()` now stops accepting borrows, drains in-flight
  operations bounded by `shutdown-timeout`, then destroys everything.
- `shutdown-timeout` configuration knob (builder + `spring.tidepool.ftp.pool.shutdown-timeout`).
- Real `LifecycleType` axis: `SIMPLE` disables background maintenance
  (eviction/max-lifetime/validation), `COMMONS` enables it — making the
  `hybrid` profile meaningful.
- JMX auto-registration for plain-Java pools via the `FtpPoolMBeanRegistrar`
  SPI (discovered from `ftp-pool-jmx`).
- End-to-end FTPS verification suite (explicit and implicit) against an
  embedded MINA FTPS server.
- Integration tests for idle eviction, max-lifetime retirement, leak detection
  and graceful-shutdown draining.
- `EngineComparisonBenchmark` (noPool / commonsPool / fast / commons / hybrid).
- GitHub Actions CI running unit and integration tests.
- Apache-2.0 `LICENSE` and Maven `release` profile (sources, javadoc, GPG).

### Changed
- Health is now live-state based: `DOWN` only when closed, `DEGRADED` only when
  borrowers are currently waiting (historical counters no longer pin the pool).
- `FtpPoolStats#idle()` is clamped to zero.

## [0.1.0] - 2026-09-21

### Added
- Initial 13-module reactor: API, core, Fast/Commons engines, Commons Net
  adapter (FTP/FTPS), observability, Micrometer, JMX, Spring Boot
  autoconfigure/starter, benchmarks, examples and integration tests.
