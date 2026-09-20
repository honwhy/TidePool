package io.ftppool.tests;

import io.ftppool.api.FtpConnection;
import io.ftppool.api.FtpException;
import io.ftppool.api.FtpExceptionType;
import io.ftppool.api.FtpPool;
import io.ftppool.api.FtpPoolException;
import io.ftppool.core.FtpPoolBuilder;
import io.ftppool.core.ObservabilityType;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Failure handling (spec section 60): server shutdown, socket reset, pool
 * timeout. Broken connections must be destroyed, never returned to idle, and
 * exhausted pools must surface a classified pool timeout.
 */
@Tag("integration")
class FailureIntegrationTest {

    @Test
    void serverShutdownDestroysBrokenConnectionInsteadOfReturningIt() throws Exception {
        try (EmbeddedFtpServer server = EmbeddedFtpServer.start()) {
            FtpPool pool = FtpPoolBuilder.builder()
                    .poolName("shutdown")
                    .host("127.0.0.1")
                    .port(server.port())
                    .username(EmbeddedFtpServer.USERNAME)
                    .password(EmbeddedFtpServer.PASSWORD)
                    .minIdle(1)
                    .maxSize(4)
                    .socketTimeout(Duration.ofSeconds(2))
                    .dataTimeout(Duration.ofSeconds(2))
                    .observability(ObservabilityType.NONE)
                    .build();
            try {
                pool.execute(connection -> connection.currentDirectory());
                assertThat(pool.stats().active()).isZero();

                server.stop();

                assertThatThrownBy(() -> pool.execute(connection -> connection.listFiles("/")))
                        .isInstanceOf(FtpException.class);

                // the dead connection was destroyed, not returned to idle
                assertThat(pool.stats().active()).isZero();
                assertThat(pool.stats().destroyed()).isGreaterThanOrEqualTo(1);
            } finally {
                pool.close();
            }
        }
    }

    @Test
    void borrowTimesOutFastWhenServerIsDown() throws Exception {
        try (EmbeddedFtpServer server = EmbeddedFtpServer.start()) {
            FtpPool pool = FtpPoolBuilder.builder()
                    .poolName("down")
                    .host("127.0.0.1")
                    .port(server.port())
                    .username(EmbeddedFtpServer.USERNAME)
                    .password(EmbeddedFtpServer.PASSWORD)
                    .minIdle(0)
                    .maxSize(2)
                    .connectionTimeout(Duration.ofMillis(700))
                    .observability(ObservabilityType.NONE)
                    .build();
            try {
                server.stop();

                assertThatThrownBy(() -> pool.execute(connection -> connection.currentDirectory()))
                        .isInstanceOf(FtpPoolException.class)
                        .isInstanceOfSatisfying(FtpPoolException.class,
                                e -> assertThat(e.getType()).isEqualTo(FtpExceptionType.POOL_TIMEOUT));
                assertThat(pool.stats().borrowTimeouts()).isGreaterThanOrEqualTo(1);
            } finally {
                pool.close();
            }
        }
    }

    @Test
    void borrowTimesOutWhenPoolExhausted() throws Exception {
        try (EmbeddedFtpServer server = EmbeddedFtpServer.start()) {
            FtpPool pool = FtpPoolBuilder.builder()
                    .poolName("exhausted")
                    .host("127.0.0.1")
                    .port(server.port())
                    .username(EmbeddedFtpServer.USERNAME)
                    .password(EmbeddedFtpServer.PASSWORD)
                    .minIdle(0)
                    .maxSize(2)
                    .connectionTimeout(Duration.ofSeconds(3))
                    .observability(ObservabilityType.NONE)
                    .build();
            FtpConnection first = pool.borrow();
            FtpConnection second = pool.borrow();
            try {
                assertThatThrownBy(() -> pool.borrow(Duration.ofMillis(500)))
                        .isInstanceOf(FtpPoolException.class)
                        .isInstanceOfSatisfying(FtpPoolException.class,
                                e -> assertThat(e.getType()).isEqualTo(FtpExceptionType.POOL_TIMEOUT));
                assertThat(pool.stats().borrowTimeouts()).isGreaterThanOrEqualTo(1);
            } finally {
                pool.release(first);
                pool.release(second);
                pool.close();
            }
        }
    }

    @Test
    void manuallyMarkedBrokenConnectionIsDestroyedOnRelease() throws Exception {
        try (EmbeddedFtpServer server = EmbeddedFtpServer.start()) {
            FtpPool pool = FtpPoolBuilder.builder()
                    .poolName("broken")
                    .host("127.0.0.1")
                    .port(server.port())
                    .username(EmbeddedFtpServer.USERNAME)
                    .password(EmbeddedFtpServer.PASSWORD)
                    .minIdle(0)
                    .maxSize(2)
                    .observability(ObservabilityType.NONE)
                    .build();
            try {
                FtpConnection connection = pool.borrow();
                connection.markBroken();
                pool.release(connection);

                assertThat(pool.stats().active()).isZero();
                assertThat(pool.stats().destroyed()).isEqualTo(1);

                // pool recovers by creating a replacement
                assertThat(pool.<String>execute(c -> c.currentDirectory())).isEqualTo("/");
            } finally {
                pool.close();
            }
        }
    }
}