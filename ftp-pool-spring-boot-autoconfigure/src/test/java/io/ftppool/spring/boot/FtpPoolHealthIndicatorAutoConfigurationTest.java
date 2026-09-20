package io.ftppool.spring.boot;

import io.ftppool.api.FtpCallback;
import io.ftppool.api.FtpConnection;
import io.ftppool.api.FtpException;
import io.ftppool.api.FtpPool;
import io.ftppool.api.FtpPoolException;
import io.ftppool.api.FtpPoolStats;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link FtpPoolHealthIndicatorAutoConfiguration} wiring and status mapping
 * (spec section 69).
 */
class FtpPoolHealthIndicatorAutoConfigurationTest {

    private AnnotationConfigApplicationContext context;

    @AfterEach
    void tearDown() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void exposesFtpPoolHealthIndicatorWhenActuatorAndPoolPresent() {
        StubPool pool = new StubPool(8, 3, 0, false);
        context = context(pool);

        assertThat(context.getBean("ftpPoolHealthIndicator")).isInstanceOf(HealthIndicator.class);

        Health health = context.getBean(HealthIndicator.class).health();
        assertThat(health.getStatus().getCode()).isEqualTo("UP");
        assertThat(health.getDetails())
                .containsEntry("total", 8)
                .containsEntry("active", 3)
                .containsEntry("idle", 5)
                .containsEntry("pending", 0);
    }

    @Test
    void mapsDegradedWhenBorrowersWaiting() {
        StubPool pool = new StubPool(2, 2, 1, false);
        context = context(pool);

        Health health = context.getBean(HealthIndicator.class).health();
        assertThat(health.getStatus().getCode()).isEqualTo("DEGRADED");
    }

    @Test
    void mapsDownWhenPoolClosed() {
        StubPool pool = new StubPool(0, 0, 0, true);
        context = context(pool);

        Health health = context.getBean(HealthIndicator.class).health();
        assertThat(health.getStatus().getCode()).isEqualTo("DOWN");
    }

    // ------------------------- helpers -------------------------

    private static AnnotationConfigApplicationContext context(FtpPool pool) {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                "test", Map.of("spring.tidepool.ftp.host", "ftp.example.com",
                        "spring.tidepool.ftp.pool.min-idle", "0")));
        ctx.register(FtpPoolAutoConfiguration.class);
        ctx.register(FtpPoolHealthIndicatorAutoConfiguration.class);
        ctx.register(ConfigurationPropertiesAutoConfiguration.class);
        ctx.registerBean("ftpPool", FtpPool.class, () -> pool);
        ctx.refresh();
        return ctx;
    }

    /** Minimal {@link FtpPool} whose stats + closed flag drive {@link io.ftppool.core.FtpPoolHealth}. */
    private static final class StubPool implements FtpPool {

        private final int total;
        private final int active;
        private final int pending;
        private final boolean closed;

        StubPool(int total, int active, int pending, boolean closed) {
            this.total = total;
            this.active = active;
            this.pending = pending;
            this.closed = closed;
        }

        @Override
        public FtpConnection borrow() throws FtpPoolException {
            throw new UnsupportedOperationException();
        }

        @Override
        public FtpConnection borrow(Duration timeout) throws FtpPoolException {
            throw new UnsupportedOperationException();
        }

        @Override
        public void release(FtpConnection connection) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> T execute(FtpCallback<T> callback) throws FtpException {
            throw new UnsupportedOperationException();
        }

        @Override
        public FtpPoolStats stats() {
            return new FtpPoolStats() {
                @Override
                public int total() {
                    return total;
                }

                @Override
                public int active() {
                    return active;
                }

                @Override
                public int idle() {
                    return Math.max(total - active - pending, 0);
                }

                @Override
                public int pending() {
                    return pending;
                }

                @Override
                public long created() {
                    return 0;
                }

                @Override
                public long destroyed() {
                    return 0;
                }

                @Override
                public long borrowed() {
                    return 0;
                }

                @Override
                public long returned() {
                    return 0;
                }

                @Override
                public long borrowTimeouts() {
                    return 0;
                }

                @Override
                public long validationFailures() {
                    return 0;
                }
            };
        }

        @Override
        public void close() {
        }

        @Override
        public boolean isClosed() {
            return closed;
        }
    }
}