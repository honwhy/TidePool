package io.ftppool.core;

import lombok.Getter;

import java.time.Duration;

/**
 * Pool tuning knobs (spec section 23).
 *
 * <p>Only pool mechanics — connection/host/credentials live in
 * {@link FtpConnectionSettings}.</p>
 */
@Getter
public final class PoolConfiguration {

    public static final Duration DEFAULT_CONNECTION_TIMEOUT = Duration.ofSeconds(3);
    public static final Duration DEFAULT_SOCKET_TIMEOUT = Duration.ofSeconds(30);
    public static final Duration DEFAULT_DATA_TIMEOUT = Duration.ofSeconds(60);
    public static final Duration DEFAULT_IDLE_TIMEOUT = Duration.ofMinutes(5);
    public static final Duration DEFAULT_MAX_LIFETIME = Duration.ofMinutes(30);
    public static final Duration DEFAULT_VALIDATION_INTERVAL = Duration.ofSeconds(30);
    public static final Duration DEFAULT_LEAK_DETECTION_THRESHOLD = Duration.ofSeconds(30);
    public static final Duration DEFAULT_SLOW_OPERATION_THRESHOLD = Duration.ofSeconds(3);
    public static final Duration DEFAULT_SHUTDOWN_TIMEOUT = Duration.ofSeconds(10);
    public static final int DEFAULT_MIN_IDLE = 2;
    public static final int DEFAULT_MAX_SIZE = 20;
    public static final int DEFAULT_MAX_CREATE_CONCURRENCY = 2;

    private final String poolName;
    private final int minIdle;
    private final int maxSize;
    private final Duration connectionTimeout;
    private final Duration socketTimeout;
    private final Duration dataTimeout;
    private final Duration idleTimeout;
    private final Duration maxLifetime;
    private final Duration validationInterval;
    private final int maxCreateConcurrency;
    private final Duration leakDetectionThreshold;
    private final Duration slowOperationThreshold;
    private final Duration shutdownTimeout;
    private final LifecycleType lifecycle;

    private PoolConfiguration(Builder builder) {
        this.poolName = builder.poolName == null || builder.poolName.isBlank()
                ? "default"
                : builder.poolName;
        this.maxSize = builder.maxSize > 0 ? builder.maxSize : DEFAULT_MAX_SIZE;
        this.minIdle = builder.minIdle >= 0 ? builder.minIdle : DEFAULT_MIN_IDLE;
        validate(minIdle <= maxSize, "minIdle must be <= maxSize");
        this.connectionTimeout = positiveOrDefault(builder.connectionTimeout, DEFAULT_CONNECTION_TIMEOUT);
        this.socketTimeout = positiveOrDefault(builder.socketTimeout, DEFAULT_SOCKET_TIMEOUT);
        this.dataTimeout = positiveOrDefault(builder.dataTimeout, DEFAULT_DATA_TIMEOUT);
        this.idleTimeout = nonNegativeOrNull(builder.idleTimeout, DEFAULT_IDLE_TIMEOUT);
        this.maxLifetime = positiveOrDefault(builder.maxLifetime, DEFAULT_MAX_LIFETIME);
        this.validationInterval = nonNegativeOrNull(builder.validationInterval, DEFAULT_VALIDATION_INTERVAL);
        this.maxCreateConcurrency = builder.maxCreateConcurrency > 0 ? builder.maxCreateConcurrency : DEFAULT_MAX_CREATE_CONCURRENCY;
        this.leakDetectionThreshold = nonNegativeOrNull(builder.leakDetectionThreshold, DEFAULT_LEAK_DETECTION_THRESHOLD);
        this.slowOperationThreshold = positiveOrDefault(builder.slowOperationThreshold, DEFAULT_SLOW_OPERATION_THRESHOLD);
        this.shutdownTimeout = nonNegativeOrNull(builder.shutdownTimeout, DEFAULT_SHUTDOWN_TIMEOUT);
        this.lifecycle = builder.lifecycle == null ? LifecycleType.COMMONS : builder.lifecycle;
        validate(connectionTimeout.toMillis() > 0, "connectionTimeout must be positive");
        validate(maxLifetime.toMillis() > 0, "maxLifetime must be positive");
    }

    public static Builder builder() {
        return new Builder();
    }

    public static PoolConfiguration createDefault() {
        return builder().build();
    }

    private static void validate(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    private static Duration positiveOrDefault(Duration value, Duration fallback) {
        if (value == null || value.isZero() || value.isNegative()) {
            return fallback;
        }
        return value;
    }

    /** zero/negative means "disabled" but a null yields the default. */
    private static Duration nonNegativeOrNull(Duration value, Duration fallback) {
        if (value == null) {
            return fallback;
        }
        return value;
    }

    public static final class Builder {
        private String poolName;
        private int minIdle = DEFAULT_MIN_IDLE;
        private int maxSize = DEFAULT_MAX_SIZE;
        private Duration connectionTimeout;
        private Duration socketTimeout;
        private Duration dataTimeout;
        private Duration idleTimeout;
        private Duration maxLifetime;
        private Duration validationInterval;
        private int maxCreateConcurrency = DEFAULT_MAX_CREATE_CONCURRENCY;
        private Duration leakDetectionThreshold;
        private Duration slowOperationThreshold;
        private Duration shutdownTimeout;
        private LifecycleType lifecycle;

        private Builder() {
        }

        public Builder poolName(String poolName) {
            this.poolName = poolName;
            return this;
        }

        public Builder minIdle(int minIdle) {
            this.minIdle = minIdle;
            return this;
        }

        public Builder maxSize(int maxSize) {
            this.maxSize = maxSize;
            return this;
        }

        public Builder connectionTimeout(Duration connectionTimeout) {
            this.connectionTimeout = connectionTimeout;
            return this;
        }

        public Builder socketTimeout(Duration socketTimeout) {
            this.socketTimeout = socketTimeout;
            return this;
        }

        public Builder dataTimeout(Duration dataTimeout) {
            this.dataTimeout = dataTimeout;
            return this;
        }

        public Builder idleTimeout(Duration idleTimeout) {
            this.idleTimeout = idleTimeout;
            return this;
        }

        public Builder maxLifetime(Duration maxLifetime) {
            this.maxLifetime = maxLifetime;
            return this;
        }

        public Builder validationInterval(Duration validationInterval) {
            this.validationInterval = validationInterval;
            return this;
        }

        public Builder maxCreateConcurrency(int maxCreateConcurrency) {
            this.maxCreateConcurrency = maxCreateConcurrency;
            return this;
        }

        public Builder leakDetectionThreshold(Duration leakDetectionThreshold) {
            this.leakDetectionThreshold = leakDetectionThreshold;
            return this;
        }

        public Builder slowOperationThreshold(Duration slowOperationThreshold) {
            this.slowOperationThreshold = slowOperationThreshold;
            return this;
        }

        /** Grace period on {@code close()} to let in-flight operations finish. Zero disables the wait. */
        public Builder shutdownTimeout(Duration shutdownTimeout) {
            this.shutdownTimeout = shutdownTimeout;
            return this;
        }

        /**
         * Lifecycle axis (spec section 18): {@link LifecycleType#SIMPLE} disables
         * background maintenance (idle eviction, max-lifetime retirement,
         * periodic validation); {@link LifecycleType#COMMONS} enables it.
         */
        public Builder lifecycle(LifecycleType lifecycle) {
            this.lifecycle = lifecycle;
            return this;
        }

        public PoolConfiguration build() {
            return new PoolConfiguration(this);
        }
    }
}