package io.ftppool.tests;

import io.ftppool.api.FtpConnection;
import io.ftppool.api.FtpExceptionType;
import io.ftppool.api.FtpPool;
import io.ftppool.api.FtpPoolException;
import io.ftppool.core.FtpPoolBuilder;
import io.ftppool.core.ObservabilityType;
import io.ftppool.core.PoolEngineType;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Commons-Pool engine must behave correctly against a real FTP server too
 * (spec section 73): explicit lifecycle, borrow/return, failed reset ⇒ destroy,
 * pool exhaustion timeout.
 */
@Tag("integration")
class CommonsEngineIntegrationTest extends AbstractFtpIntegrationTest {

    private FtpPool commonsPool(int maxSize) {
        return FtpPoolBuilder.builder()
                .poolName("commons-it")
                .host("127.0.0.1")
                .port(port())
                .username(EmbeddedFtpServer.USERNAME)
                .password(EmbeddedFtpServer.PASSWORD)
                .minIdle(0)
                .maxSize(maxSize)
                .connectionTimeout(Duration.ofSeconds(3))
                .engine(PoolEngineType.COMMONS)
                .observability(ObservabilityType.NONE)
                .build();
    }

    @Test
    void roundTripThroughCommonsEngine() throws Exception {
        FtpPool pool = commonsPool(4);
        String path = "/commons.bin";
        byte[] payload = "commons-engine".getBytes(StandardCharsets.UTF_8);
        try {
            pool.execute(connection -> connection.upload(path, new ByteArrayInputStream(payload)));

            byte[] downloaded = pool.execute(connection -> {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                connection.download(path, out);
                return out.toByteArray();
            });
            assertThat(downloaded).isEqualTo(payload);
            assertThat(pool.<Boolean>execute(connection -> connection.delete(path))).isTrue();

            assertThat(pool.stats().borrowed()).isEqualTo(3);
            assertThat(pool.stats().returned()).isEqualTo(3);
        } finally {
            pool.close();
        }
    }

    @Test
    void commonsEngineBorrowTimesOutWhenExhausted() throws Exception {
        FtpPool pool = commonsPool(2);
        FtpConnection first = pool.borrow();
        FtpConnection second = pool.borrow();
        try {
            assertThatThrownBy(() -> pool.borrow(Duration.ofMillis(500)))
                    .isInstanceOf(FtpPoolException.class)
                    .isInstanceOfSatisfying(FtpPoolException.class,
                            e -> assertThat(e.getType()).isEqualTo(FtpExceptionType.POOL_TIMEOUT));
        } finally {
            pool.release(first);
            pool.release(second);
            pool.close();
        }
    }
}