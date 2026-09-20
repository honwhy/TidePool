package io.ftppool.adapter;

import io.ftppool.api.FtpException;
import io.ftppool.api.FtpExceptionType;
import org.apache.commons.net.ftp.FTPConnectionClosedException;
import org.junit.jupiter.api.Test;

import java.net.SocketTimeoutException;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class FtpExceptionTranslatorTest {

    @Test
    void closedConnectionIsConnectionClass() {
        FtpException e = FtpExceptionTranslator.connection("x", new FTPConnectionClosedException("bye"));
        assertThat(e.getType()).isEqualTo(FtpExceptionType.CONNECTION);
    }

    @Test
    void socketTimeoutIsTimeoutClass() {
        FtpException e = FtpExceptionTranslator.connection("x", new SocketTimeoutException("slow"));
        assertThat(e.getType()).isEqualTo(FtpExceptionType.TIMEOUT);
    }

    @Test
    void genericIoIsConnectionClass() {
        FtpException e = FtpExceptionTranslator.connection("x", new IOException("reset"));
        assertThat(e.getType()).isEqualTo(FtpExceptionType.CONNECTION);
    }

    @Test
    void businessRejectionsStayBusinessClass() {
        FtpException e = FtpExceptionTranslator.business("550 file not found");
        assertThat(e.getType()).isEqualTo(FtpExceptionType.BUSINESS);
    }
}