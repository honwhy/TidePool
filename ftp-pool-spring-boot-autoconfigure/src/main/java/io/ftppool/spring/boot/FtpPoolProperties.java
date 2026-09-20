package io.ftppool.spring.boot;

import io.ftppool.api.FtpProtocol;
import io.ftppool.core.LifecycleType;
import io.ftppool.core.ObservabilityType;
import io.ftppool.core.PoolEngineType;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * {@code spring.tidepool.ftp.*} configuration properties (spec section 46).
 *
 * <p>Structural mirror of the reference YAML, mapped onto
 * {@link io.ftppool.core.FtpPoolBuilder} by {@link FtpPoolAutoConfiguration}.
 * Durations follow Spring Boot relaxed binding ({@code 3s}, {@code 30s},
 * {@code 5m}…). Passwords are read straight from properties or env
 * ({@code FTP_PASSWORD}) and are never logged, metrized or exposed via JMX.</p>
 *
 * <pre>{@code
 * spring:
 *   tidepool:
 *     ftp:
 *       enabled: true
 *       host: ftp.example.com
 *       port: 21
 *       username: user
 *       password: ${FTP_PASSWORD}
 *       protocol: ftp
 *       pool:
 *         mode: hybrid
 *         min-idle: 2
 *         max-size: 20
 *       observability:
 *         metrics: true
 *         jmx: true
 *         slow-operation-threshold: 3s
 * }</pre>
 */
@Data
@ConfigurationProperties(prefix = FtpPoolProperties.PREFIX)
public class FtpPoolProperties {

    /** Configuration namespace for every FtpPool property. */
    public static final String PREFIX = "spring.tidepool.ftp";

    /** Master switch; {@code spring.tidepool.ftp.enabled=false} skips the whole pool wiring. */
    private boolean enabled = true;

    private String host;

    /** 0 → protocol default (21 for FTP/FTPS-explicit, 990 for implicit). */
    private int port;

    private String username = "anonymous";

    private String password;

    private FtpProtocol protocol = FtpProtocol.FTP;

    private String encoding = "UTF-8";

    private String poolName = "default";

    private final Pool pool = new Pool();

    private final Observability observability = new Observability();

    private final Ssl ssl = new Ssl();

    // ------------------------- nested config groups -------------------------

    /** Pool tuning knobs (spec section 46, {@code spring.tidepool.ftp.pool.*}). */
    @Data
    public static class Pool {

        /** Convenience preset: {@code fast|commons|monitor|hybrid} (default hybrid). */
        private String mode = "hybrid";

        /** Per-axis overrides; when null the {@link #mode} preset wins. */
        private PoolEngineType engine;

        private LifecycleType lifecycle;

        private ObservabilityType observability;

        private int minIdle = 2;

        private int maxSize = 20;

        private Duration connectionTimeout = Duration.ofSeconds(3);

        private Duration socketTimeout = Duration.ofSeconds(30);

        private Duration dataTimeout = Duration.ofSeconds(60);

        private Duration idleTimeout = Duration.ofMinutes(5);

        private Duration maxLifetime = Duration.ofMinutes(30);

        private Duration validationInterval = Duration.ofSeconds(30);

        private int maxCreateConcurrency = 2;

        private Duration leakDetectionThreshold = Duration.ofSeconds(30);

        /** Grace period on shutdown to let in-flight operations finish. */
        private Duration shutdownTimeout = Duration.ofSeconds(10);
    }

    /** Observability switches (spec section 46, {@code spring.tidepool.ftp.observability.*}). */
    @Data
    public static class Observability {

        /** Master switch for the whole filter stack (metrics + audit + slow-op). */
        private boolean enabled = true;

        private boolean metrics = true;

        private boolean jmx = true;

        private Duration slowOperationThreshold = Duration.ofSeconds(3);
    }

    /** TLS policy for FTPS (spec section 64, {@code spring.tidepool.ftp.ssl.*}). */
    @Data
    public static class Ssl {

        /** UNSAFE, development/testing only: trust any certificate, skip hostname verification. */
        private boolean trustAll = false;

        private boolean hostnameVerification = true;
    }
}