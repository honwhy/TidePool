package io.ftppool.tests;

import io.ftppool.api.FtpPool;
import io.ftppool.core.FtpPoolBuilder;
import io.ftppool.core.ObservabilityType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

import java.time.Duration;

/**
 * Owns one {@link EmbeddedFtpServer} shared by every integration test in the
 * class. Concrete subclasses are tagged {@code integration} so the default
 * unit-only Surefire run skips them (see {@code ftp-pool-tests/pom.xml}).
 */
abstract class AbstractFtpIntegrationTest {

    private static EmbeddedFtpServer ftp;

    @BeforeAll
    static void startServer() throws Exception {
        stopServer();
        ftp = EmbeddedFtpServer.start();
    }

    @AfterAll
    static void stopServer() {
        if (ftp != null) {
            try {
                ftp.stop();
            } finally {
                ftp = null;
            }
        }
    }

    int port() {
        return ftp.port();
    }

    /** Fast-engine pool, no observability filters, no pre-warmed connections. */
    FtpPool newPool() {
        return newPool(20, Duration.ofSeconds(3));
    }

    FtpPool newPool(int maxSize, Duration connectionTimeout) {
        return FtpPoolBuilder.builder()
                .poolName(getClass().getSimpleName())
                .host("127.0.0.1")
                .port(port())
                .username(EmbeddedFtpServer.USERNAME)
                .password(EmbeddedFtpServer.PASSWORD)
                .minIdle(0)
                .maxSize(maxSize)
                .connectionTimeout(connectionTimeout)
                .observability(ObservabilityType.NONE)
                .build();
    }
}