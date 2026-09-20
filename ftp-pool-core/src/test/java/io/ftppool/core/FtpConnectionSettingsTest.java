package io.ftppool.core;

import io.ftppool.api.FtpProtocol;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FtpConnectionSettingsTest {

    @Test
    void appliesImplicitFtpsDefaultPort() {
        FtpConnectionSettings settings = new FtpConnectionSettings(
                "ftp.example.com", 0, "user", "pw", FtpProtocol.FTPS_IMPLICIT, null);

        assertThat(settings.port()).isEqualTo(990);
        assertThat(settings.encoding()).isEqualTo("UTF-8");
    }

    @Test
    void masksPasswordInToString() {
        FtpConnectionSettings settings = new FtpConnectionSettings(
                "ftp.example.com", 21, "user", "s3cret", FtpProtocol.FTP, null);

        assertThat(settings.toString())
                .contains("password=******")
                .doesNotContain("s3cret");
    }

    @Test
    void anonymousWhenUsernameMissing() {
        FtpConnectionSettings settings = new FtpConnectionSettings(
                "ftp.example.com", 0, null, null, FtpProtocol.FTP, null);

        assertThat(settings.username()).isEqualTo("anonymous");
    }

    @Test
    void customEncodingKept() {
        FtpConnectionSettings settings = new FtpConnectionSettings(
                "ftp.example.com", 21, "u", "p", FtpProtocol.FTP, "ISO-8859-1");

        assertThat(settings.encoding()).isEqualTo("ISO-8859-1");
    }
}