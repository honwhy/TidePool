package io.ftppool.observability;

import io.ftppool.api.FtpFilter;
import io.ftppool.api.FtpFilterConfig;
import io.ftppool.api.FtpFilterProvider;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * {@link FtpFilterProvider} for the default observability stack (spec section 18):
 * metrics → audit logging → slow-operation detection.
 *
 * <p>Registered as {@code "full"} in {@code META-INF/services} so plain-Java users
 * get observability simply by putting {@code ftp-pool-observability} on the
 * classpath and leaving the default (hybrid) profile active.</p>
 *
 * <p>Retains the built {@link FtpFilters.ObservabilityStack} per pool name so
 * Micrometer/JMX/Spring can bind the shared {@link FtpMetrics} sink through
 * {@link #stackFor(String)} without coupling core to this module.</p>
 */
public final class ObservabilityFilterProvider implements FtpFilterProvider {

    public static final String NAME = "full";

    private static final ConcurrentMap<String, FtpFilters.ObservabilityStack> STACKS = new ConcurrentHashMap<>();

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<FtpFilter> create(FtpFilterConfig config) {
        Objects.requireNonNull(config, "config");
        Duration threshold = config.slowOperationThreshold() == null
                ? Duration.ofSeconds(3)
                : config.slowOperationThreshold();
        FtpFilters.ObservabilityStack stack = FtpFilters.standardStack(threshold);
        STACKS.put(config.poolName() == null ? "default" : config.poolName(), stack);
        return stack.filters();
    }

    /** Observability stack built by the last {@link #create} for the pool name. */
    public static FtpFilters.ObservabilityStack stackFor(String poolName) {
        return STACKS.get(poolName);
    }

    /** Test/embedding hook. */
    public static void reset() {
        STACKS.clear();
    }
}