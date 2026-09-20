package io.ftppool.tests;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.ftppool.api.FtpConnection;
import io.ftppool.api.FtpPool;
import io.ftppool.core.FtpPoolBuilder;
import io.ftppool.core.FtpPoolImpl;
import io.ftppool.core.ObservabilityType;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lifecycle integration (spec sections 25/26/40/62) against a real FTP server:
 * idle eviction, max-lifetime retirement, leak detection and graceful shutdown
 * draining.
 */
@Tag("integration")
class LifecycleIntegrationTest extends AbstractFtpIntegrationTest {

    @Test
    void idleConnectionIsEvictedAfterIdleTimeout() throws Exception {
        FtpPool pool = builder("idle-evict")
                .minIdle(0)
                .maxSize(4)
                .idleTimeout(Duration.ofMillis(300))
                .validationInterval(Duration.ofSeconds(1))
                .build();
        try {
            FtpConnection connection = pool.borrow();
            pool.release(connection);

            await(() -> pool.stats().idle() == 0 && pool.stats().destroyed() >= 1, 5000);
            assertThat(pool.stats().destroyed()).isGreaterThanOrEqualTo(1);
        } finally {
            pool.close();
        }
    }

    @Test
    void maxLifetimeRetiresConnectionOnReturn() throws Exception {
        FtpPool pool = builder("max-lifetime")
                .minIdle(0)
                .maxSize(4)
                .maxLifetime(Duration.ofMillis(400))
                .validationInterval(Duration.ofSeconds(1))
                .build();
        try {
            FtpConnection first = pool.borrow();
            Thread.sleep(500);
            pool.release(first);

            assertThat(pool.stats().destroyed()).isGreaterThanOrEqualTo(1);

            FtpConnection second = pool.borrow();
            pool.release(second);
            assertThat(pool.stats().created()).isGreaterThanOrEqualTo(2);
        } finally {
            pool.close();
        }
    }

    @Test
    void leakDetectionWarnsAboutUnreleasedConnection() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(FtpPoolImpl.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        FtpPool pool = builder("leak-detect")
                .minIdle(0)
                .maxSize(2)
                .leakDetectionThreshold(Duration.ofMillis(200))
                .build();
        FtpConnection leaked = pool.borrow();
        try {
            await(() -> appender.list.stream()
                    .anyMatch(event -> event.getFormattedMessage().contains("Possible connection leak")), 5000);
        } finally {
            pool.release(leaked);
            pool.close();
            logger.detachAppender(appender);
        }
    }

    @Test
    void gracefulShutdownDrainsActiveBorrow() throws Exception {
        FtpPool pool = builder("drain")
                .minIdle(0)
                .maxSize(2)
                .shutdownTimeout(Duration.ofSeconds(5))
                .build();
        FtpConnection connection = pool.borrow();

        Thread closer = new Thread(pool::close, "drain-closer");
        closer.start();
        Thread.sleep(200);

        // close() must wait for the borrowed connection to come back
        assertThat(closer.isAlive()).isTrue();

        pool.release(connection);
        closer.join(3000);
        assertThat(closer.isAlive()).isFalse();
        assertThat(pool.isClosed()).isTrue();
    }

    private FtpPoolBuilder builder(String name) {
        return FtpPoolBuilder.builder()
                .poolName(name)
                .host("127.0.0.1")
                .port(port())
                .username(EmbeddedFtpServer.USERNAME)
                .password(EmbeddedFtpServer.PASSWORD)
                .observability(ObservabilityType.NONE);
    }

    private static void await(ThrowingCondition condition, long timeoutMillis) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.test()) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("condition not met within " + timeoutMillis + " ms");
    }

    @FunctionalInterface
    private interface ThrowingCondition {
        boolean test() throws Exception;
    }
}
