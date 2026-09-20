package io.ftppool.tests;

import io.ftppool.api.FtpConnection;
import io.ftppool.api.FtpException;
import io.ftppool.api.FtpExceptionType;
import io.ftppool.api.FtpFile;
import io.ftppool.api.FtpPool;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end FTP operations over a real server (spec section 59): connect,
 * login, list, cwd, upload, download, delete — with connections pooled and
 * automatically reset/returned.
 */
@Tag("integration")
class RoundTripIntegrationTest extends AbstractFtpIntegrationTest {

    @Test
    void uploadDownloadListDeleteRoundTrip() throws Exception {
        FtpPool pool = newPool();
        String path = "/roundtrip.bin";
        byte[] payload = "tidepool".repeat(500).getBytes(StandardCharsets.UTF_8);
        try {
            pool.execute(connection -> connection.upload(path, new ByteArrayInputStream(payload)));

            byte[] downloaded = pool.execute(connection -> {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                connection.download(path, out);
                return out.toByteArray();
            });
            assertThat(downloaded).isEqualTo(payload);

            FtpFile[] listing = pool.execute(connection -> connection.listFiles("/"));
            assertThat(listing).extracting(FtpFile::name).contains("roundtrip.bin");

            boolean deleted = pool.execute(connection -> connection.delete(path));
            assertThat(deleted).isTrue();
        } finally {
            pool.close();
        }
    }

    @Test
    void renameAndMakeDirectoryRoundTrip() throws Exception {
        FtpPool pool = newPool();
        try {
            boolean created = pool.execute(connection -> connection.makeDirectory("/docs"));
            assertThat(created).isTrue();
            pool.execute(connection ->
                    connection.upload("/docs/a.txt", new ByteArrayInputStream("hi".getBytes(StandardCharsets.UTF_8))));

            boolean renamed = pool.execute(connection -> connection.rename("/docs/a.txt", "/docs/b.txt"));
            assertThat(renamed).isTrue();

            FtpFile[] listing = pool.execute(connection -> connection.listFiles("/docs"));
            assertThat(listing).extracting(FtpFile::name).contains("b.txt").doesNotContain("a.txt");
        } finally {
            pool.close();
        }
    }

    @Test
    void currentDirectoryDefaultsToServerHome() throws Exception {
        FtpPool pool = newPool();
        try {
            String cwd = pool.execute(FtpConnection::currentDirectory);
            assertThat(cwd).isEqualTo("/");
        } finally {
            pool.close();
        }
    }

    @Test
    void positiveReplyFailuresAreBusinessErrorsAndKeepConnection() throws Exception {
        FtpPool pool = newPool();
        try {
            assertThatThrownBy(() -> pool.execute(connection -> connection.delete("/no-such-file.bin")))
                    .isInstanceOf(FtpException.class)
                    .isInstanceOfSatisfying(FtpException.class,
                            e -> assertThat(e.getType()).isEqualTo(FtpExceptionType.BUSINESS));
            // business failure returned the connection to the pool, nothing destroyed
            assertThat(pool.stats().destroyed()).isZero();
            assertThat(pool.stats().created()).isGreaterThanOrEqualTo(1);
        } finally {
            pool.close();
        }
    }

    @Test
    void borrowedConnectionIsReusedAcrossExecutes() throws Exception {
        FtpPool pool = newPool();
        try {
            pool.execute(connection -> connection.currentDirectory());
            pool.execute(connection -> connection.currentDirectory());

            assertThat(pool.stats().created()).isEqualTo(1);
            assertThat(pool.stats().borrowed()).isEqualTo(2);
            assertThat(pool.stats().returned()).isEqualTo(2);
        } finally {
            pool.close();
        }
    }
}