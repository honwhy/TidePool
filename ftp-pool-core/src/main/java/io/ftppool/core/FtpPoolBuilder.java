package io.ftppool.core;

import io.ftppool.api.FtpFilter;
import io.ftppool.api.FtpFilterConfig;
import io.ftppool.api.FtpFilterProvider;
import io.ftppool.api.FtpPool;
import io.ftppool.api.FtpProtocol;
import io.ftppool.api.PoolEngine;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.ServiceLoader;

/**
 * Programmatic builder for {@link FtpPool} (spec section 52).
 *
 * <p>Wires the three separable axes: a {@link PoolEngine} (discovered via the
 * {@link PoolEngineFactory} SPI from the engine modules), the
 * {@link FtpConnectionFactory} (discovered via {@link FtpConnectionFactoryProvider}
 * from the adapter module) and optional {@link FtpFilter}s. Core itself depends on
 * neither the adapter nor any engine module — everything is resolved via
 * {@code META-INF/services} at build time.</p>
 *
 * <pre>{@code
 * FtpPool pool = FtpPoolBuilder.builder()
 *     .host("ftp.example.com")
 *     .port(21)
 *     .username("user")
 *     .password(pass)
 *     .minIdle(2)
 *     .maxSize(20)
 *     .engine(PoolEngineType.FAST)
 *     .build();
 * }</pre>
 *
 * <p>Convenience profiles live in {@link FtpPoolProfiles}; the default profile is
 * {@code hybrid} (Fast engine + robust lifecycle + observability).</p>
 */
@Slf4j
public final class FtpPoolBuilder {

    private String host;
    private int port;
    private String username;
    private String password;
    private FtpProtocol protocol = FtpProtocol.FTP;
    private String encoding;
    private FtpSslConfig ssl;

    private String poolName;
    private int minIdle = PoolConfiguration.DEFAULT_MIN_IDLE;
    private int maxSize = PoolConfiguration.DEFAULT_MAX_SIZE;
    private Duration connectionTimeout;
    private Duration socketTimeout;
    private Duration dataTimeout;
    private Duration idleTimeout;
    private Duration maxLifetime;
    private Duration validationInterval;
    private int maxCreateConcurrency = PoolConfiguration.DEFAULT_MAX_CREATE_CONCURRENCY;
    private Duration leakDetectionThreshold;
    private Duration slowOperationThreshold;
    private Duration shutdownTimeout;

    private PoolEngineType engineType = PoolEngineType.FAST;
    private LifecycleType lifecycleType = LifecycleType.COMMONS;
    private ObservabilityType observabilityType = ObservabilityType.FULL;

    private final List<FtpFilter> filters = new ArrayList<>();
    private boolean autoRegisterMBeans = true;

    /** Test/embedding hooks to sidestep ServiceLoader. */
    private FtpConnectionFactoryProvider providerOverride;
    private PoolEngineFactory engineFactoryOverride;

    private FtpPoolBuilder() {
    }

    public static FtpPoolBuilder builder() {
        return new FtpPoolBuilder();
    }

    // ------------------------- connection -------------------------

    public FtpPoolBuilder host(String host) {
        this.host = host;
        return this;
    }

    public FtpPoolBuilder port(int port) {
        this.port = port;
        return this;
    }

    public FtpPoolBuilder username(String username) {
        this.username = username;
        return this;
    }

    public FtpPoolBuilder password(String password) {
        this.password = password;
        return this;
    }

    public FtpPoolBuilder protocol(FtpProtocol protocol) {
        this.protocol = protocol == null ? FtpProtocol.FTP : protocol;
        return this;
    }

    public FtpPoolBuilder encoding(String encoding) {
        this.encoding = encoding;
        return this;
    }

    /** TLS configuration for FTPS protocols (spec section 64). Defaults to secure settings. */
    public FtpPoolBuilder sslConnection(FtpSslConfig ssl) {
        this.ssl = ssl;
        return this;
    }

    // ------------------------- pool tuning -------------------------

    public FtpPoolBuilder poolName(String poolName) {
        this.poolName = poolName;
        return this;
    }

    public FtpPoolBuilder minIdle(int minIdle) {
        this.minIdle = minIdle;
        return this;
    }

    public FtpPoolBuilder maxSize(int maxSize) {
        this.maxSize = maxSize;
        return this;
    }

    public FtpPoolBuilder connectionTimeout(Duration connectionTimeout) {
        this.connectionTimeout = connectionTimeout;
        return this;
    }

    public FtpPoolBuilder socketTimeout(Duration socketTimeout) {
        this.socketTimeout = socketTimeout;
        return this;
    }

    public FtpPoolBuilder dataTimeout(Duration dataTimeout) {
        this.dataTimeout = dataTimeout;
        return this;
    }

    public FtpPoolBuilder idleTimeout(Duration idleTimeout) {
        this.idleTimeout = idleTimeout;
        return this;
    }

    public FtpPoolBuilder maxLifetime(Duration maxLifetime) {
        this.maxLifetime = maxLifetime;
        return this;
    }

    public FtpPoolBuilder validationInterval(Duration validationInterval) {
        this.validationInterval = validationInterval;
        return this;
    }

    public FtpPoolBuilder maxCreateConcurrency(int maxCreateConcurrency) {
        this.maxCreateConcurrency = maxCreateConcurrency;
        return this;
    }

    public FtpPoolBuilder leakDetectionThreshold(Duration leakDetectionThreshold) {
        this.leakDetectionThreshold = leakDetectionThreshold;
        return this;
    }

    public FtpPoolBuilder slowOperationThreshold(Duration slowOperationThreshold) {
        this.slowOperationThreshold = slowOperationThreshold;
        return this;
    }

    /** Grace period on {@code close()} to let in-flight operations finish. */
    public FtpPoolBuilder shutdownTimeout(Duration shutdownTimeout) {
        this.shutdownTimeout = shutdownTimeout;
        return this;
    }

    // ------------------------- strategy axes -------------------------

    public FtpPoolBuilder engine(PoolEngineType engineType) {
        this.engineType = engineType == null ? PoolEngineType.FAST : engineType;
        return this;
    }

    public FtpPoolBuilder lifecycle(LifecycleType lifecycleType) {
        this.lifecycleType = lifecycleType == null ? LifecycleType.COMMONS : lifecycleType;
        return this;
    }

    public FtpPoolBuilder observability(ObservabilityType observabilityType) {
        this.observabilityType = observabilityType == null ? ObservabilityType.FULL : observabilityType;
        return this;
    }

    public FtpPoolBuilder addFilter(FtpFilter filter) {
        if (filter != null) {
            filters.add(filter);
        }
        return this;
    }

    public FtpPoolBuilder filters(List<? extends FtpFilter> filters) {
        if (filters != null) {
            this.filters.clear();
            filters.forEach(this::addFilter);
        }
        return this;
    }

    // ------------------------- service provider hooks -------------------------

    public FtpPoolBuilder connectionFactoryProvider(FtpConnectionFactoryProvider provider) {
        this.providerOverride = provider;
        return this;
    }

    public FtpPoolBuilder engineFactory(PoolEngineFactory engineFactory) {
        this.engineFactoryOverride = engineFactory;
        return this;
    }

    /**
     * Auto-register management beans discovered via {@link FtpPoolMBeanRegistrar}
     * (e.g. JMX) on {@link #build()}. On by default; frameworks that own the
     * registration themselves (Spring Boot) turn it off to avoid duplicates.
     */
    public FtpPoolBuilder autoRegisterMBeans(boolean autoRegisterMBeans) {
        this.autoRegisterMBeans = autoRegisterMBeans;
        return this;
    }

    // ------------------------- build -------------------------

    public FtpPool build() {
        Objects.requireNonNull(host, "host is required");
        Objects.requireNonNull(protocol, "protocol is required");

        FtpConnectionSettings settings = new FtpConnectionSettings(
                host, port, username, password, protocol, encoding, ssl);
        PoolConfiguration poolConfig = PoolConfiguration.builder()
                .poolName(poolName)
                .minIdle(minIdle)
                .maxSize(maxSize)
                .connectionTimeout(connectionTimeout)
                .socketTimeout(socketTimeout)
                .dataTimeout(dataTimeout)
                .idleTimeout(idleTimeout)
                .maxLifetime(maxLifetime)
                .validationInterval(validationInterval)
                .maxCreateConcurrency(maxCreateConcurrency)
                .leakDetectionThreshold(leakDetectionThreshold)
                .slowOperationThreshold(slowOperationThreshold)
                .shutdownTimeout(shutdownTimeout)
                .lifecycle(lifecycleType)
                .build();

        FtpConnectionFactory connectionFactory = resolveConnectionFactory(settings, poolConfig);
        FtpPoolStatsImpl stats = new FtpPoolStatsImpl();
        PoolEngine<FtpPoolEntry> engine = resolveEngineFactory()
                .create(poolConfig, new EntryFactory(connectionFactory), stats);
        stats.bind(engine::size, engine::active);

        List<FtpFilter> effectiveFilters = new ArrayList<>(filters);
        effectiveFilters.addAll(resolveObservabilityFilters(poolConfig));

        log.info("Building pool '{}' with engine={}, lifecycle={}, observability={}, minIdle={}, maxSize={}",
                poolConfig.getPoolName(), engineType, lifecycleType, observabilityType,
                poolConfig.getMinIdle(), poolConfig.getMaxSize());

        FtpPoolImpl pool = new FtpPoolImpl(poolConfig.getPoolName(), poolConfig, engine,
                connectionFactory, effectiveFilters, stats);
        if (autoRegisterMBeans) {
            registerMBeans(pool);
        }
        return pool;
    }

    /** Discover and register optional management backends (JMX) without a compile dependency. */
    private static void registerMBeans(FtpPoolImpl pool) {
        for (FtpPoolMBeanRegistrar registrar : ServiceLoader.load(FtpPoolMBeanRegistrar.class)) {
            try {
                pool.addMBeanHandle(registrar.register(pool));
            } catch (RuntimeException e) {
                log.warn("MBean registrar {} failed for pool '{}'",
                        registrar.getClass().getName(), pool.poolName(), e);
            }
        }
    }

    /**
     * Resolves observability filters via the {@link FtpFilterProvider} SPI
     * (spec section 18). Core never depends on an observability implementation —
     * the adapter-first behaviour is: {@code NONE} ⇒ no filters,
     * {@code BASIC}/{@code FULL} ⇒ look up a provider named {@code basic}/{@code full}
     * (with a {@code basic → full} fallback).
     */
    private List<FtpFilter> resolveObservabilityFilters(PoolConfiguration poolConfig) {
        if (observabilityType == ObservabilityType.NONE) {
            return List.of();
        }
        FtpFilterConfig filterConfig = new FtpFilterConfig(
                poolConfig.getPoolName(), poolConfig.getSlowOperationThreshold());
        String primary = observabilityType.name().toLowerCase(Locale.ROOT);
        List<FtpFilter> discovered = new ArrayList<>();
        collectFilterProvider(primary, filterConfig, discovered);
        if (discovered.isEmpty() && ObservabilityType.BASIC == observabilityType) {
            collectFilterProvider("full", filterConfig, discovered);
        }
        if (discovered.isEmpty()) {
            log.warn("No FtpFilterProvider named '{}' on the classpath; pool '{}' gets no observability filters. "
                    + "Add ftp-pool-observability to your classpath.", primary, poolConfig.getPoolName());
        }
        return discovered;
    }

    private static void collectFilterProvider(String name, FtpFilterConfig config,
                                              List<FtpFilter> sink) {
        for (FtpFilterProvider provider : ServiceLoader.load(FtpFilterProvider.class)) {
            if (name.equals(provider.name())) {
                List<FtpFilter> provided = provider.create(config);
                if (provided != null) {
                    sink.addAll(provided);
                }
            }
        }
    }

    private FtpConnectionFactory resolveConnectionFactory(FtpConnectionSettings settings,
                                                          PoolConfiguration poolConfig) {
        if (providerOverride != null) {
            return providerOverride.create(settings, poolConfig);
        }
        for (FtpConnectionFactoryProvider provider : ServiceLoader.load(FtpConnectionFactoryProvider.class)) {
            if (provider.supports(settings.protocol())) {
                return provider.create(settings, poolConfig);
            }
        }
        throw new IllegalStateException(
                "No FtpConnectionFactoryProvider on the classpath supports " + settings.protocol()
                        + ". Add ftp-pool-adapter to your classpath.");
    }

    private PoolEngineFactory resolveEngineFactory() {
        if (engineFactoryOverride != null) {
            return engineFactoryOverride;
        }
        String engineName = engineType.name().toLowerCase(Locale.ROOT);
        for (PoolEngineFactory factory : ServiceLoader.load(PoolEngineFactory.class)) {
            if (engineName.equals(factory.name())) {
                return factory;
            }
        }
        throw new IllegalStateException(
                "No PoolEngineFactory named '" + engineName + "' on the classpath."
                        + " Add ftp-pool-engine-fast / ftp-pool-engine-commons.");
    }
}