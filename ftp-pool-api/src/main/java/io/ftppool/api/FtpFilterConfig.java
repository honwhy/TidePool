package io.ftppool.api;

import java.time.Duration;

/**
 * Dependency-free snapshot of pool settings an {@link FtpFilter} needs at build
 * time. {@code FtpPoolBuilder} derives it from {@code PoolConfiguration};
 * keeping the type here preserves the api-module isolation rule.
 */
public record FtpFilterConfig(String poolName, Duration slowOperationThreshold) {
}