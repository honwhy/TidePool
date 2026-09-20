package io.ftppool.observability;

import io.ftppool.api.FtpFilter;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Assemblies the standard observability filter stack (spec section 38):
 * metrics → audit logging → slow-operation detection.
 *
 * <pre>{@code
 * List<FtpFilter> filters = FtpFilters.standard(Duration.ofMillis(3000));
 * FtpPool pool = FtpPoolBuilder.builder().host(...).filters(filters).build();
 * }</pre>
 */
public final class FtpFilters {

    private FtpFilters() {
    }

    /** Metrics + logging + slow-operation (threshold) filters sharing one {@link FtpMetrics} sink. */
    public static List<FtpFilter> standard(Duration slowOperationThreshold) {
        Objects.requireNonNull(slowOperationThreshold, "slowOperationThreshold");
        FtpMetrics metrics = new FtpMetrics.Default();
        return List.of(
                new MetricsFilter(metrics),
                new LoggingFilter(),
                new SlowOperationFilter(slowOperationThreshold));
    }

    /** Same as {@link #standard(Duration)} but retains the metrics sink for programmatic reads. */
    public static ObservabilityStack standardStack(Duration slowOperationThreshold) {
        Objects.requireNonNull(slowOperationThreshold, "slowOperationThreshold");
        FtpMetrics.Default metrics = new FtpMetrics.Default();
        List<FtpFilter> filters = new ArrayList<>(3);
        filters.add(new MetricsFilter(metrics));
        filters.add(new LoggingFilter());
        filters.add(new SlowOperationFilter(slowOperationThreshold));
        return new ObservabilityStack(filters, metrics);
    }

    /** Bundles the assembled filters with the shared metrics sink. */
    public static final class ObservabilityStack {

        private final List<FtpFilter> filters;
        private final FtpMetrics.Default metrics;

        ObservabilityStack(List<FtpFilter> filters, FtpMetrics.Default metrics) {
            this.filters = List.copyOf(filters);
            this.metrics = metrics;
        }

        public List<FtpFilter> filters() {
            return filters;
        }

        public FtpMetrics.Default metrics() {
            return metrics;
        }
    }
}