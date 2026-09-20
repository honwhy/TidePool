package io.ftppool.observability;

/**
 * Minimal, dependency-free tracing seam (spec section 67). Implementations wrap
 * OpenTelemetry, Micrometer Tracing or any other backend and are plugged into
 * the pool through {@link TracingFilter}.
 *
 * <pre>{@code
 * FtpPool pool = FtpPoolBuilder.builder()
 *     .host(...)
 *     .addFilter(new TracingFilter(myOtelTracer))
 *     .build();
 * }</pre>
 */
public interface FtpTracer {

    /**
     * Start a span for a pool lifecycle event or FTP operation.
     *
     * @param operation   e.g. {@code borrow}, {@code execute}, {@code UPLOAD}
     * @param connectionId connection identity, or {@code null} for borrow
     */
    Span start(String operation, String connectionId);

    /** A started span; {@link #end(Throwable)} must be idempotent. */
    interface Span {

        /** Finish the span; {@code error == null} means success. */
        void end(Throwable error);
    }
}
