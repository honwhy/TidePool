package io.ftppool.tests;

import io.ftppool.api.FtpPool;
import io.ftppool.api.FtpProtocol;
import io.ftppool.core.FtpPoolBuilder;
import io.ftppool.core.FtpSslConfig;
import io.ftppool.core.ObservabilityType;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end FTPS verification (spec sections 5.2 / 64): a real TLS handshake,
 * login and data transfer over both explicit and implicit FTPS against an
 * embedded server with a self-signed certificate.
 *
 * <p>The client uses the explicit {@code insecureTrustAll()} opt-in because the
 * server certificate is self-signed; this is a test-only configuration and
 * exercises the full FTPS data path (PBSZ/PROT) rather than only TLS settings.</p>
 */
@Tag("integration")
class FtpsIntegrationTest {

    @Test
    void explicitFtpsUploadDownloadRoundTrip() throws Exception {
        try (EmbeddedFtpsServer server = EmbeddedFtpsServer.start(false)) {
            assertRoundTrip(FtpProtocol.FTPS_EXPLICIT, server, "explicit");
        }
    }

    @Test
    void implicitFtpsUploadDownloadRoundTrip() throws Exception {
        try (EmbeddedFtpsServer server = EmbeddedFtpsServer.start(true)) {
            assertRoundTrip(FtpProtocol.FTPS_IMPLICIT, server, "implicit");
        }
    }

    private void assertRoundTrip(FtpProtocol protocol, EmbeddedFtpsServer server, String name) throws Exception {
        FtpPool pool = FtpPoolBuilder.builder()
                .poolName("ftps-" + name)
                .host("127.0.0.1")
                .port(server.port())
                .username(EmbeddedFtpsServer.USERNAME)
                .password(EmbeddedFtpsServer.PASSWORD)
                .protocol(protocol)
                .sslConnection(FtpSslConfig.insecureTrustAll())
                .minIdle(0)
                .maxSize(4)
                .observability(ObservabilityType.NONE)
                .build();
        try {
            byte[] payload = ("tidepool-" + name).repeat(200).getBytes(StandardCharsets.UTF_8);
            String path = "/ftps-" + name + ".bin";

            pool.execute(connection -> connection.upload(path, new ByteArrayInputStream(payload)));
            byte[] downloaded = pool.execute(connection -> {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                connection.download(path, out);
                return out.toByteArray();
            });
            assertThat(downloaded).isEqualTo(payload);

            assertThat(pool.stats().active()).isZero();
            assertThat(pool.stats().created()).isGreaterThanOrEqualTo(1);
        } finally {
            pool.close();
        }
    }
}
