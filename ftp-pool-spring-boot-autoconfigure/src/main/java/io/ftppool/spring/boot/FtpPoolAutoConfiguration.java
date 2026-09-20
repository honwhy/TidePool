package io.ftppool.spring.boot;

import io.ftppool.api.FtpFilter;
import io.ftppool.api.FtpPool;
import io.ftppool.api.FtpProtocol;
import io.ftppool.core.FtpClientTemplate;
import io.ftppool.core.FtpPoolBuilder;
import io.ftppool.core.FtpPoolImpl;
import io.ftppool.core.FtpPoolProfiles;
import io.ftppool.core.FtpSslConfig;
import io.ftppool.core.ObservabilityType;
import io.ftppool.jmx.FtpPoolJmx;
import io.ftppool.micrometer.FtpMicrometer;
import io.ftppool.observability.FtpMetrics;
import io.ftppool.observability.LoggingFilter;
import io.ftppool.observability.MetricsFilter;
import io.ftppool.observability.SlowOperationFilter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import javax.management.ObjectName;
import javax.net.ssl.SSLContext;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Spring Boot auto-configuration for FtpPool (spec sections 45–47).
 *
 * <p>Exposes two beans:
 * <ul>
 *   <li>{@link FtpPool} — built from {@code spring.tidepool.ftp.*} properties via
 *       {@link FtpPoolBuilder} (adapter and engine are discovered through their
 *       {@code META-INF/services} registrations).</li>
 *   <li>{@link FtpClientTemplate} — inject-safe facade over the pool.</li>
 * </ul>
 *
 * <p>Observability is composed directly (Spring owns the filter chain so a
 * Micrometer sink can be wired without double-recording): a
 * {@link MetricsFilter} feeding Micrometer (when a {@link MeterRegistry} bean
 * exists), an audit {@link LoggingFilter} and a {@link SlowOperationFilter}.
 * Pool gauges (see {@link FtpMicrometer#bindPool}) are bound to every available
 * registry. JMX is registered through {@link FtpPoolJmx} and unregistered on
 * context shutdown.</p>
 *
 * <p>Ground rules: no passwords in logs/metrics/JMX; no high-cardinality tags;
 * {@code spring.tidepool.ftp.ssl.trust-all=true} is only honoured as an
 * explicit, loudly warned, development-only opt-in.</p>
 *
 * <p>Disabled with {@code spring.tidepool.ftp.enabled=false} or by simply not
 * configuring {@code spring.tidepool.ftp.host}. The very same
 * auto-configuration backs the {@code ftp-pool-spring-boot-starter} artifact.</p>
 */
@Slf4j
@AutoConfiguration
@EnableConfigurationProperties(FtpPoolProperties.class)
@AutoConfigureAfter(name = "org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration")
@ConditionalOnClass(FtpPoolBuilder.class)
@ConditionalOnProperty(prefix = FtpPoolProperties.PREFIX, name = "enabled", havingValue = "true", matchIfMissing = true)
public class FtpPoolAutoConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(FtpPool.class)
    FtpPool ftpPool(FtpPoolProperties properties,
                    ObjectProvider<SSLContext> sslContexts,
                    ObjectProvider<MeterRegistry> registries) {
        if (properties.getHost() == null || properties.getHost().isBlank()) {
            throw new IllegalStateException(
                    FtpPoolProperties.PREFIX + ".enabled=true but " + FtpPoolProperties.PREFIX
                            + ".host is not configured. Set " + FtpPoolProperties.PREFIX
                            + ".host (or disable the pool with " + FtpPoolProperties.PREFIX
                            + ".enabled=false / provide your own FtpPool bean).");
        }
        MeterRegistry registry = registries.getIfAvailable();

        FtpPoolProperties.Pool poolProps = properties.getPool();
        FtpPoolBuilder builder = profileBuilder(properties)
                // Spring owns JMX registration (conditional on spring.tidepool.ftp.observability.jmx).
                .autoRegisterMBeans(false)
                .host(properties.getHost())
                .port(properties.getPort())
                .username(properties.getUsername())
                .password(properties.getPassword())
                .protocol(properties.getProtocol())
                .encoding(properties.getEncoding())
                .poolName(properties.getPoolName())
                .minIdle(poolProps.getMinIdle())
                .maxSize(poolProps.getMaxSize())
                .connectionTimeout(poolProps.getConnectionTimeout())
                .socketTimeout(poolProps.getSocketTimeout())
                .dataTimeout(poolProps.getDataTimeout())
                .idleTimeout(poolProps.getIdleTimeout())
                .maxLifetime(poolProps.getMaxLifetime())
                .validationInterval(poolProps.getValidationInterval())
                .maxCreateConcurrency(poolProps.getMaxCreateConcurrency())
                .leakDetectionThreshold(poolProps.getLeakDetectionThreshold())
                .shutdownTimeout(poolProps.getShutdownTimeout())
                .slowOperationThreshold(slowOperationThreshold(properties))
                .sslConnection(sslConfig(properties, sslContexts.getIfAvailable()));

        // Spring composes the filter chain below; suppress the ServiceLoader
        // stack so a single metrics sink feeds Micrometer (no double counting).
        builder.observability(ObservabilityType.NONE);
        builder.filters(assembleFilters(properties, registry));

        FtpPool pool = builder.build();

        if (registry != null && properties.getObservability().isMetrics()) {
            FtpMicrometer.bindPool(registry, pool, poolTags(properties));
        }
        log.info("FtpPool bean '{}' created: host={}, protocol={}, engine={}, minIdle={}, maxSize={}, "
                        + "metrics={}, jmx={}",
                properties.getPoolName(), properties.getHost(), properties.getProtocol(),
                properties.getPool().getMode(), poolProps.getMinIdle(), poolProps.getMaxSize(),
                properties.getObservability().isMetrics(), properties.getObservability().isJmx());
        return pool;
    }

    @Bean
    @ConditionalOnMissingBean(FtpClientTemplate.class)
    FtpClientTemplate ftpClientTemplate(FtpPool pool) {
        return new FtpClientTemplate(pool);
    }

    @Bean
    @ConditionalOnBean(FtpPool.class)
    @ConditionalOnProperty(prefix = FtpPoolProperties.PREFIX + ".observability", name = "jmx",
            havingValue = "true", matchIfMissing = true)
    FtpPoolJmxRegistration ftpPoolJmxRegistration(FtpPool pool, FtpPoolProperties properties) {
        return new FtpPoolJmxRegistration(pool, properties.getPoolName());
    }

    // ------------------------- internals -------------------------

    /** Applies {@code spring.tidepool.ftp.pool.mode} (fast/commons/monitor/hybrid) plus per-axis overrides. */
    static FtpPoolBuilder profileBuilder(FtpPoolProperties properties) {
        String mode = properties.getPool().getMode();
        FtpPoolBuilder builder;
        if (mode == null || mode.isBlank() || "hybrid".equalsIgnoreCase(mode)) {
            builder = FtpPoolProfiles.hybrid();
        } else {
            switch (mode.toLowerCase(Locale.ROOT)) {
                case "fast" -> builder = FtpPoolProfiles.fast();
                case "commons" -> builder = FtpPoolProfiles.commons();
                case "monitor" -> builder = FtpPoolProfiles.monitor();
                default -> throw new IllegalArgumentException(
                        "Unsupported " + FtpPoolProperties.PREFIX + ".pool.mode '" + mode
                                + "' (expected fast|commons|monitor|hybrid)");
            }
        }
        FtpPoolProperties.Pool pool = properties.getPool();
        if (pool.getEngine() != null) {
            builder.engine(pool.getEngine());
        }
        if (pool.getLifecycle() != null) {
            builder.lifecycle(pool.getLifecycle());
        }
        if (pool.getObservability() != null) {
            builder.observability(pool.getObservability());
        }
        return builder;
    }

    /** Effective observability depth: per-axis override, else implied by the profile. */
    static ObservabilityType effectiveObservability(FtpPoolProperties properties) {
        ObservabilityType explicit = properties.getPool().getObservability();
        if (explicit != null) {
            return explicit;
        }
        String mode = properties.getPool().getMode();
        if (mode == null || mode.isBlank()) {
            return ObservabilityType.FULL;
        }
        return switch (mode.toLowerCase(Locale.ROOT)) {
            case "fast", "commons" -> ObservabilityType.NONE;
            default -> ObservabilityType.FULL;
        };
    }

    static List<FtpFilter> assembleFilters(FtpPoolProperties properties, MeterRegistry registry) {
        if (!properties.getObservability().isEnabled()
                || effectiveObservability(properties) == ObservabilityType.NONE) {
            return List.of();
        }
        List<FtpFilter> filters = new ArrayList<>(3);
        if (properties.getObservability().isMetrics()) {
            if (registry != null) {
                Tag poolTag = Tag.of("pool", properties.getPoolName());
                filters.add(new MetricsFilter(FtpMicrometer.metricsSink(registry, poolTag)));
            } else {
                filters.add(new MetricsFilter(new FtpMetrics.Default()));
            }
        }
        filters.add(new LoggingFilter());
        filters.add(new SlowOperationFilter(slowOperationThreshold(properties)));
        return filters;
    }

    static Duration slowOperationThreshold(FtpPoolProperties properties) {
        Duration threshold = properties.getObservability().getSlowOperationThreshold();
        return threshold == null ? Duration.ofSeconds(3) : threshold;
    }

    /** Secure FTPS policy by default; {@code trust-all} only as an explicit, warned opt-in. */
    static FtpSslConfig sslConfig(FtpPoolProperties properties, SSLContext customContext) {
        FtpPoolProperties.Ssl ssl = properties.getSsl();
        if (ssl.isTrustAll()) {
            log.warn("{}.ssl.trust-all=true — FTPS server certificate AND hostname verification disabled. "
                    + "UNSAFE, development/testing only; never use against production servers.",
                    FtpPoolProperties.PREFIX);
            return FtpSslConfig.insecureTrustAll();
        }
        return new FtpSslConfig(customContext, null, null, false, ssl.isHostnameVerification());
    }

    /** Low-cardinality gauge tags: host, port, protocol — never username or paths. */
    static Tag[] poolTags(FtpPoolProperties properties) {
        return new Tag[]{
                Tag.of("host", properties.getHost()),
                Tag.of("port", String.valueOf(properties.getPort())),
                Tag.of("protocol", properties.getProtocol() == null
                        ? FtpProtocol.FTP.name().toLowerCase(Locale.ROOT)
                        : properties.getProtocol().name().toLowerCase(Locale.ROOT))
        };
    }

    /**
     * Registers the pool on the platform MBean server on startup and unregisters
     * it when the enclosing Spring context closes (spec section 43).
     */
    static final class FtpPoolJmxRegistration implements InitializingBean, DisposableBean {

        private final FtpPool pool;
        private final String poolName;
        private ObjectName objectName;

        FtpPoolJmxRegistration(FtpPool pool, String poolName) {
            this.pool = Objects.requireNonNull(pool, "pool");
            this.poolName = Objects.requireNonNull(poolName, "poolName");
        }

        @Override
        public void afterPropertiesSet() {
            if (pool instanceof FtpPoolImpl impl) {
                objectName = FtpPoolJmx.register(poolName, pool.stats(), impl.engine(), pool);
            } else {
                log.warn("FtpPool bean is not a core FtpPoolImpl; skipping JMX registration");
            }
        }

        @Override
        public void destroy() {
            FtpPoolJmx.unregister(objectName);
        }
    }
}