package io.ftppool.observability;

import io.ftppool.api.FtpConnection;
import io.ftppool.api.FtpContext;
import io.ftppool.api.FtpFilter;
import io.ftppool.api.FtpOperation;

import java.util.Objects;

/**
 * Bridges the filter chain to a {@link FtpTracer} (spec section 67): one span
 * per borrow / execute / FTP operation. The span is stashed on the
 * {@link FtpContext#getAttachment()} slot between the {@code before*} and
 * {@code after*} callbacks.
 *
 * <p>This keeps OpenTelemetry / Micrometer Tracing optional: users provide a
 * {@link FtpTracer} adapter, the pool module stays dependency-free.</p>
 */
public final class TracingFilter implements FtpFilter {

    private final FtpTracer tracer;

    public TracingFilter(FtpTracer tracer) {
        this.tracer = Objects.requireNonNull(tracer, "tracer");
    }

    @Override
    public void beforeBorrow(FtpContext context) {
        start(context, "borrow");
    }

    @Override
    public void afterBorrow(FtpContext context, FtpConnection connection) {
        end(context, null);
    }

    @Override
    public void beforeExecute(FtpContext context) {
        start(context, "execute");
    }

    @Override
    public void afterExecute(FtpContext context) {
        end(context, null);
    }

    @Override
    public void beforeOperation(FtpContext context, FtpOperation operation) {
        start(context, operation.name().toLowerCase(java.util.Locale.ROOT));
    }

    @Override
    public void afterOperation(FtpContext context, FtpOperation operation) {
        end(context, null);
    }

    @Override
    public void onOperationError(FtpContext context, FtpOperation operation, Throwable error) {
        end(context, error);
    }

    @Override
    public void onError(FtpContext context, Throwable error) {
        end(context, error);
    }

    private void start(FtpContext context, String operation) {
        String connectionId = context.getConnectionId() == null ? null : context.getConnectionId().toString();
        context.setAttachment(tracer.start(operation, connectionId));
    }

    private static void end(FtpContext context, Throwable error) {
        if (context.getAttachment() instanceof FtpTracer.Span span) {
            span.end(error);
            context.setAttachment(null);
        }
    }
}
