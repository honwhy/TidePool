package io.ftppool.core;

import io.ftppool.api.FtpConnection;
import io.ftppool.api.FtpConnectionId;
import io.ftppool.api.FtpContext;
import io.ftppool.api.FtpException;
import io.ftppool.api.FtpExceptionType;
import io.ftppool.api.FtpFile;
import io.ftppool.api.FtpFilter;
import io.ftppool.api.FtpOperation;

import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link FtpConnection} decorator that reports per-operation events
 * (upload/download/delete/list/…) into the {@link FtpFilter} chain.
 *
 * <p>The filter SPI only sees borrow/execute/return at the pool facade; this
 * seam is what makes the operation-level metrics required by spec sections 35/36
 * possible — operation kind, latency and transferred bytes — without leaking
 * the FTP client into the API. Only installed when a filter chain is present,
 * so the no-observability fast path keeps zero extra allocation.</p>
 *
 * <p>Never reports credentials or file paths; bytes and operation kind are the
 * only payload.</p>
 */
final class InstrumentedFtpConnection implements FtpConnection {

    private final String pool;
    private final FtpConnection delegate;
    private final List<FtpFilter> filters;

    /** In-flight streaming transfer (retrieve/store), finished on stream close. */
    private volatile Transfer pending;

    InstrumentedFtpConnection(String pool, FtpConnection delegate, List<FtpFilter> filters) {
        this.pool = Objects.requireNonNull(pool, "pool");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.filters = Objects.requireNonNull(filters, "filters");
    }

    @Override
    public FtpConnectionId id() {
        return delegate.id();
    }

    @Override
    public void changeDirectory(String path) throws FtpException {
        run(FtpOperation.CHANGE_DIRECTORY, () -> delegate.changeDirectory(path));
    }

    @Override
    public String currentDirectory() throws FtpException {
        return call(FtpOperation.CURRENT_DIRECTORY, delegate::currentDirectory);
    }

    @Override
    public InputStream retrieveFileStream(String path) throws FtpException {
        Transfer transfer = new Transfer(FtpOperation.DOWNLOAD);
        before(transfer);
        try {
            InputStream in = delegate.retrieveFileStream(path);
            pending = transfer;
            return new CountingInputStream(in, transfer, () -> finish(transfer));
        } catch (RuntimeException e) {
            fail(transfer, e);
            throw e;
        }
    }

    @Override
    public OutputStream storeFileStream(String path) throws FtpException {
        Transfer transfer = new Transfer(FtpOperation.UPLOAD);
        before(transfer);
        try {
            OutputStream out = delegate.storeFileStream(path);
            pending = transfer;
            return new CountingOutputStream(out, transfer, () -> finish(transfer));
        } catch (RuntimeException e) {
            fail(transfer, e);
            throw e;
        }
    }

    @Override
    public boolean upload(String path, InputStream input) throws FtpException {
        Objects.requireNonNull(input, "input");
        Transfer transfer = new Transfer(FtpOperation.UPLOAD);
        before(transfer);
        try {
            boolean result = delegate.upload(path, new CountingInputStream(input, transfer, null));
            finish(transfer);
            return result;
        } catch (RuntimeException e) {
            fail(transfer, e);
            throw e;
        }
    }

    @Override
    public boolean download(String path, OutputStream output) throws FtpException {
        Objects.requireNonNull(output, "output");
        Transfer transfer = new Transfer(FtpOperation.DOWNLOAD);
        before(transfer);
        try {
            boolean result = delegate.download(path, new CountingOutputStream(output, transfer, null));
            finish(transfer);
            return result;
        } catch (RuntimeException e) {
            fail(transfer, e);
            throw e;
        }
    }

    @Override
    public boolean delete(String path) throws FtpException {
        return call(FtpOperation.DELETE, () -> delegate.delete(path));
    }

    @Override
    public boolean rename(String from, String to) throws FtpException {
        return call(FtpOperation.RENAME, () -> delegate.rename(from, to));
    }

    @Override
    public boolean makeDirectory(String path) throws FtpException {
        return call(FtpOperation.MAKE_DIRECTORY, () -> delegate.makeDirectory(path));
    }

    @Override
    public FtpFile[] listFiles(String path) throws FtpException {
        return call(FtpOperation.LIST, () -> delegate.listFiles(path));
    }

    @Override
    public void completePendingCommand() throws FtpException {
        Transfer transfer = pending;
        if (transfer != null) {
            // A streaming transfer whose stream was never closed: finalize it so
            // the operation event is not lost.
            finish(transfer);
        }
        run(FtpOperation.COMPLETE_PENDING, delegate::completePendingCommand);
    }

    @Override
    public boolean isValid() {
        return delegate.isValid();
    }

    @Override
    public void markBroken() {
        delegate.markBroken();
    }

    @Override
    public boolean isBroken() {
        return delegate.isBroken();
    }

    // ------------------------- reporting -------------------------

    private void run(FtpOperation op, Action action) throws FtpException {
        Transfer transfer = new Transfer(op);
        before(transfer);
        try {
            action.run();
            succeed(transfer, 0L);
        } catch (RuntimeException e) {
            fail(transfer, e);
            throw e;
        }
    }

    private <T> T call(FtpOperation op, Supplier<T> action) throws FtpException {
        Transfer transfer = new Transfer(op);
        before(transfer);
        try {
            T result = action.get();
            succeed(transfer, 0L);
            return result;
        } catch (RuntimeException e) {
            fail(transfer, e);
            throw e;
        }
    }

    private void before(Transfer transfer) {
        for (FtpFilter filter : filters) {
            filter.beforeOperation(transfer.context, transfer.operation);
        }
    }

    private void succeed(Transfer transfer, long bytes) {
        transfer.context.setBytes(bytes);
        transfer.context.completed(true, Duration.between(transfer.context.getStartTime(), Instant.now()));
        for (FtpFilter filter : filters) {
            filter.afterOperation(transfer.context, transfer.operation);
        }
    }

    private void fail(Transfer transfer, RuntimeException error) {
        transfer.context.failed(error, typeOf(error));
        for (FtpFilter filter : filters) {
            filter.onOperationError(transfer.context, transfer.operation, error);
        }
    }

    /** Finalize a transfer exactly once, reporting its accumulated bytes. */
    private void finish(Transfer transfer) {
        if (pending == transfer) {
            pending = null;
        }
        if (transfer.finished) {
            return;
        }
        transfer.finished = true;
        succeed(transfer, transfer.bytes.get());
    }

    private static FtpExceptionType typeOf(Throwable error) {
        Throwable cause = error;
        while (cause != null) {
            if (cause instanceof FtpException ftpException) {
                return ftpException.getType();
            }
            cause = cause.getCause();
        }
        return FtpExceptionType.CONNECTION;
    }

    private final class Transfer {
        final FtpContext context;
        final FtpOperation operation;
        final AtomicLong bytes = new AtomicLong();
        volatile boolean finished;

        Transfer(FtpOperation operation) {
            this.operation = operation;
            this.context = new FtpContext(pool, delegate.id(), operation);
        }
    }

    @FunctionalInterface
    private interface Action {
        void run() throws FtpException;
    }

    @FunctionalInterface
    private interface Supplier<T> {
        T get() throws FtpException;
    }

    // ------------------------- byte counting -------------------------

    static final class CountingInputStream extends FilterInputStream {
        private final Transfer transfer;
        private final Runnable onClose;
        private long count;

        CountingInputStream(InputStream in, Transfer transfer, Runnable onClose) {
            super(in);
            this.transfer = transfer;
            this.onClose = onClose;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) {
                count++;
                transfer.bytes.set(count);
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int read = super.read(b, off, len);
            if (read > 0) {
                count += read;
                transfer.bytes.set(count);
            }
            return read;
        }

        @Override
        public void close() throws IOException {
            try {
                super.close();
            } finally {
                transfer.bytes.set(count);
                if (onClose != null) {
                    onClose.run();
                }
            }
        }
    }

    static final class CountingOutputStream extends FilterOutputStream {
        private final Transfer transfer;
        private final Runnable onClose;
        private long count;

        CountingOutputStream(OutputStream out, Transfer transfer, Runnable onClose) {
            super(out);
            this.transfer = transfer;
            this.onClose = onClose;
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
            count++;
            transfer.bytes.set(count);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            count += len;
            transfer.bytes.set(count);
        }

        @Override
        public void close() throws IOException {
            try {
                super.close();
            } finally {
                transfer.bytes.set(count);
                if (onClose != null) {
                    onClose.run();
                }
            }
        }
    }
}
