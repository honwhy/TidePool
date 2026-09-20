package io.ftppool.spring.boot;

import io.ftppool.api.FtpPool;
import io.ftppool.core.FtpPoolHealth;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Actuator health indicator for the FTP pool (spec section 69).
 *
 * <p>Spec-example payload, under the {@code ftpPool} contributor name:</p>
 *
 * <pre>{@code
 * {
 *   "status": "UP",
 *   "details": {
 *     "ftpPool": {
 *       "total": 10,
 *       "active": 3,
 *       "idle": 7
 *     }
 *   }
 * }
 * }</pre>
 *
 * <p>Only active when Spring Boot Actuator ({@link HealthIndicator}) is on the
 * classpath — the {@code spring-boot-actuator} dependency is optional, so the
 * bean simply never appears in a context without actuator.</p>
 *
 * @see FtpPoolHealth
 */
@AutoConfiguration
@AutoConfigureAfter(FtpPoolAutoConfiguration.class)
@ConditionalOnClass(HealthIndicator.class)
@ConditionalOnBean(FtpPool.class)
public class FtpPoolHealthIndicatorAutoConfiguration {

    /**
     * Maps {@link FtpPoolHealth.Status} to actuator {@link Health} and exposes
     * it under the {@code ftpPool} contributor name (bean name minus the
     * {@code HealthIndicator} suffix). No password or username is ever included.
     */
    @Bean
    @ConditionalOnMissingBean(name = "ftpPoolHealthIndicator")
    HealthIndicator ftpPoolHealthIndicator(FtpPool pool) {
        return () -> {
            FtpPoolHealth health = FtpPoolHealth.check(pool);
            Health.Builder builder = switch (health.getStatus()) {
                case UP -> Health.up();
                case DEGRADED -> Health.status(new Status("DEGRADED"));
                case DOWN -> Health.down();
            };
            return builder
                    .withDetail("total", health.getTotal())
                    .withDetail("active", health.getActive())
                    .withDetail("idle", health.getIdle())
                    .withDetail("pending", health.getPending())
                    .build();
        };
    }
}