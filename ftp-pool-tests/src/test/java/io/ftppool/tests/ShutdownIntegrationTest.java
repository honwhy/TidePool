package io.ftppool.tests;

import io.ftppool.api.FtpConnection;
import io.ftppool.api.FtpExceptionType;
import io.ftppool.api.FtpPool;
import io.ftppool.api.FtpPoolException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pool shutdown semantics (spec section 62): stop accepting borrows, release
 * connections, close everything, and make {@code close()} idempotent. After
 * close, borrows are rejected and no resources leak.
 */
@Tag("integration")
class ShutdownIntegrationTest extends AbstractFtpIntegrationTest {

    @Test
    void closeStopsBorrowingAndCleansUp() throws Exception {
        FtpPool pool = newPool();
        FtpConnection connection = pool.borrow();
        try {
            assertThat(pool.isClosed()).isFalse();

            pool.close();

            assertThat(pool.isClosed()).isTrue();
            assertThatThrownBy(pool::borrow)
                    .isInstanceOf(FtpPoolException.class)
                    .isInstanceOfSatisfying(FtpPoolException.class,
                            e -> assertThat(e.getType()).isEqualTo(FtpExceptionType.POOL_CLOSED));

            // an in-flight connection released after close is destroyed, not pooled
            pool.release(connection);
        } finally {
            pool.close();
        }
    }

    @Test
    void executeAfterCloseIsRejected() throws Exception {
        FtpPool pool = newPool();
        pool.close();

        assertThatThrownBy(() -> pool.execute(c -> c.currentDirectory()))
                .isInstanceOf(FtpPoolException.class)
                .isInstanceOfSatisfying(FtpPoolException.class,
                        e -> assertThat(e.getType()).isEqualTo(FtpExceptionType.POOL_CLOSED));
    }

    @Test
    void closeIsIdempotent() throws Exception {
        FtpPool pool = newPool();
        pool.execute(c -> c.currentDirectory());
        pool.execute(c -> c.currentDirectory());

        pool.close();
        pool.close();

        assertThat(pool.isClosed()).isTrue();
        assertThat(pool.stats().active()).isZero();
    }
}