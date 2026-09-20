package io.ftppool.observability;

import io.ftppool.api.FtpContext;
import io.ftppool.api.FtpOperation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TracingFilterTest {

    private final RecordingTracer tracer = new RecordingTracer();
    private final TracingFilter filter = new TracingFilter(tracer);

    @Test
    void borrowLifecycleOpensAndClosesSpan() {
        FtpContext context = new FtpContext("pool", null, FtpOperation.BORROW);

        filter.beforeBorrow(context);
        filter.afterBorrow(context, null);

        assertThat(tracer.started).containsExactly("borrow");
        assertThat(tracer.ended).containsExactly("borrow");
        assertThat(tracer.errors).containsExactly((Throwable) null);
    }

    @Test
    void operationSpanCarriesOperationNameAndBytesOutcome() {
        FtpContext context = new FtpContext("pool", null, FtpOperation.UPLOAD);

        filter.beforeOperation(context, FtpOperation.UPLOAD);
        filter.afterOperation(context, FtpOperation.UPLOAD);

        assertThat(tracer.started).containsExactly("upload");
        assertThat(tracer.ended).containsExactly("upload");
    }

    @Test
    void errorEndsSpanWithThrowable() {
        FtpContext context = new FtpContext("pool", null, FtpOperation.DELETE);
        RuntimeException failure = new RuntimeException("boom");

        filter.beforeOperation(context, FtpOperation.DELETE);
        filter.onOperationError(context, FtpOperation.DELETE, failure);

        assertThat(tracer.errors).containsExactly(failure);
    }

    @Test
    void endIsIdempotentWhenNoSpanStarted() {
        FtpContext context = new FtpContext("pool", null, FtpOperation.RETURN);

        filter.afterExecute(context);

        assertThat(tracer.ended).isEmpty();
    }

    private static final class RecordingTracer implements FtpTracer {
        final List<String> started = new ArrayList<>();
        final List<String> ended = new ArrayList<>();
        final List<Throwable> errors = new ArrayList<>();

        @Override
        public Span start(String operation, String connectionId) {
            started.add(operation);
            return error -> {
                ended.add(operation);
                errors.add(error);
            };
        }
    }
}
