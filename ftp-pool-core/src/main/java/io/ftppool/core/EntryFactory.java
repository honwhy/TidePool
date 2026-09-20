package io.ftppool.core;

import io.ftppool.api.FtpConnection;

import java.util.Objects;

/**
 * Adapts an {@link FtpConnectionFactory} (FTP-specific lifecycle) to the generic
 * {@link ResourceFactory} over {@link FtpPoolEntry} that pool engines manage.
 *
 * <p>This is the seam between the FTP resource layer and the pool engine layer
 * (spec section 80 — FtpPoolEntry). Both {@link FtpPoolImpl} and
 * {@link FtpPoolBuilder} build engines against this factory.</p>
 */
public final class EntryFactory implements ResourceFactory<FtpPoolEntry> {

    private final FtpConnectionFactory delegate;

    public EntryFactory(FtpConnectionFactory delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public FtpPoolEntry create() throws Exception {
        FtpConnection connection = delegate.create();
        return new FtpPoolEntry(connection.id(), connection);
    }

    @Override
    public boolean validate(FtpPoolEntry resource) {
        return delegate.validate(resource.connection());
    }

    @Override
    public boolean reset(FtpPoolEntry resource) {
        return delegate.reset(resource.connection());
    }

    @Override
    public void destroy(FtpPoolEntry resource) {
        delegate.destroy(resource.connection());
    }
}