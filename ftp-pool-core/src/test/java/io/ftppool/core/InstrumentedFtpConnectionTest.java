package io.ftppool.core;

import io.ftppool.api.FtpConnection;
import io.ftppool.api.FtpConnectionId;
import io.ftppool.api.FtpContext;
import io.ftppool.api.FtpException;
import io.ftppool.api.FtpExceptionType;
import io.ftppool.api.FtpFile;
import io.ftppool.api.FtpFilter;
import io.ftppool.api.FtpOperation;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the operation-level instrumentation required by spec sections 35/36:
 * operation kind, latency and transferred bytes reach the filter chain.
 */
class InstrumentedFtpConnectionTest {

    private final RecordingFilter filter = new RecordingFilter();
    private final FtpConnection delegate = new FakeConnection();
    private final InstrumentedFtpConnection connection =
            new InstrumentedFtpConnection("test-pool", delegate, List.of(filter));

    @Test
    void uploadReportsOperationAndBytes() throws Exception {
        byte[] payload = "tidepool".getBytes(StandardCharsets.UTF_8);

        connection.upload("/data/x.bin", new ByteArrayInputStream(payload));

        assertThat(filter.operations).containsExactly(FtpOperation.UPLOAD);
        assertThat(filter.bytes).containsExactly((long) payload.length);
        assertThat(filter.pool).isEqualTo("test-pool");
    }

    @Test
    void downloadReportsOperationAndBytes() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        connection.download("/data/y.bin", out);

        assertThat(filter.operations).containsExactly(FtpOperation.DOWNLOAD);
        assertThat(filter.bytes).containsExactly(5L);
    }

    @Test
    void deleteReportsOperationWithoutBytes() throws Exception {
        connection.delete("/data/z.bin");

        assertThat(filter.operations).containsExactly(FtpOperation.DELETE);
        assertThat(filter.bytes).containsExactly(0L);
    }

    @Test
    void streamingRetrieveReportsBytesWhenStreamCloses() throws Exception {
        try (InputStream in = connection.retrieveFileStream("/data/s.bin")) {
            in.readAllBytes();
        }

        assertThat(filter.operations).containsExactly(FtpOperation.DOWNLOAD);
        assertThat(filter.bytes).containsExactly(5L);
    }

    @Test
    void errorEventCarriesExceptionType() {
        InstrumentedFtpConnection failing =
                new InstrumentedFtpConnection("test-pool", new FailingConnection(), List.of(filter));

        assertThatThrownBy(() -> failing.delete("/nope"))
                .isInstanceOf(FtpException.class);

        assertThat(filter.operations).containsExactly(FtpOperation.DELETE);
        assertThat(filter.errorTypes).containsExactly(FtpExceptionType.BUSINESS);
    }

    private static final class RecordingFilter implements FtpFilter {
        final List<FtpOperation> operations = new ArrayList<>();
        final List<Long> bytes = new ArrayList<>();
        final List<FtpExceptionType> errorTypes = new ArrayList<>();
        String pool;

        @Override
        public void afterOperation(FtpContext context, FtpOperation operation) {
            operations.add(operation);
            bytes.add(context.getBytes());
            pool = context.getPool();
        }

        @Override
        public void onOperationError(FtpContext context, FtpOperation operation, Throwable error) {
            operations.add(operation);
            errorTypes.add(context.getExceptionType());
        }
    }

    private static final class FakeConnection implements FtpConnection {
        private boolean broken;

        @Override
        public FtpConnectionId id() {
            return new FtpConnectionId(1);
        }

        @Override
        public void changeDirectory(String path) {
        }

        @Override
        public String currentDirectory() {
            return "/";
        }

        @Override
        public InputStream retrieveFileStream(String path) {
            return new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public OutputStream storeFileStream(String path) {
            return new ByteArrayOutputStream();
        }

        @Override
        public boolean upload(String path, InputStream input) throws FtpException {
            try {
                input.transferTo(OutputStream.nullOutputStream());
                return true;
            } catch (java.io.IOException e) {
                throw new FtpException(FtpExceptionType.CONNECTION, "upload", e);
            }
        }

        @Override
        public boolean download(String path, OutputStream output) throws FtpException {
            try {
                output.write("hello".getBytes(StandardCharsets.UTF_8));
                return true;
            } catch (java.io.IOException e) {
                throw new FtpException(FtpExceptionType.CONNECTION, "download", e);
            }
        }

        @Override
        public boolean delete(String path) {
            return true;
        }

        @Override
        public FtpFile[] listFiles(String path) {
            return new FtpFile[0];
        }

        @Override
        public void completePendingCommand() {
        }

        @Override
        public boolean isValid() {
            return !broken;
        }

        @Override
        public void markBroken() {
            broken = true;
        }

        @Override
        public boolean isBroken() {
            return broken;
        }
    }

    private static final class FailingConnection implements FtpConnection {
        @Override
        public FtpConnectionId id() {
            return new FtpConnectionId(2);
        }

        @Override
        public void changeDirectory(String path) throws FtpException {
            throw new FtpException(FtpExceptionType.BUSINESS, "CWD rejected");
        }

        @Override
        public String currentDirectory() {
            return "/";
        }

        @Override
        public InputStream retrieveFileStream(String path) {
            return null;
        }

        @Override
        public OutputStream storeFileStream(String path) {
            return null;
        }

        @Override
        public boolean upload(String path, InputStream input) throws FtpException {
            throw new FtpException(FtpExceptionType.BUSINESS, "STOR rejected");
        }

        @Override
        public boolean download(String path, OutputStream output) {
            return true;
        }

        @Override
        public boolean delete(String path) throws FtpException {
            throw new FtpException(FtpExceptionType.BUSINESS, "DELE rejected");
        }

        @Override
        public FtpFile[] listFiles(String path) {
            return new FtpFile[0];
        }

        @Override
        public void completePendingCommand() {
        }

        @Override
        public boolean isValid() {
            return true;
        }

        @Override
        public void markBroken() {
        }

        @Override
        public boolean isBroken() {
            return false;
        }
    }
}
