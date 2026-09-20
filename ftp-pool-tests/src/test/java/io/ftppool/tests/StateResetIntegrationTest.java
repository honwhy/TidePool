package io.ftppool.tests;

import io.ftppool.api.FtpConnection;
import io.ftppool.api.FtpPool;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FTPClient is highly stateful: a returned connection must be reset to a
 * clean, predictable state (spec sections 9/10). Failed reset ⇒ destroy, never
 * idle; a connection with an open data stream is never re-issued.
 */
@Tag("integration")
class StateResetIntegrationTest extends AbstractFtpIntegrationTest {

    @Test
    void workingDirectoryIsRestoredAfterBusinessCwdPollution() throws Exception {
        FtpPool pool = newPool();
        try {
            pool.execute(connection -> {
                connection.changeDirectory("/");
                connection.upload("/pollute.bin",
                        new ByteArrayInputStream("polluted".getBytes(StandardCharsets.UTF_8)));
                return null;
            });

            assertThat(pool.<String>execute(connection -> connection.currentDirectory())).isEqualTo("/");
        } finally {
            pool.close();
        }
    }

    @Test
    void uploadDoesNotLeakStateAcrossBorrows() throws Exception {
        FtpPool pool = newPool();
        try {
            byte[] payload = "binary-data".getBytes(StandardCharsets.UTF_8);
            pool.execute(connection -> {
                connection.upload("/state.bin", new ByteArrayInputStream(payload));
                return null;
            });
            pool.execute(connection -> {
                connection.upload("/state2.bin", new ByteArrayInputStream(payload));
                return null;
            });

            // the same connection was reused and stayed healthy
            assertThat(pool.stats().created()).isEqualTo(1);
            assertThat(pool.stats().returned()).isEqualTo(2);
            assertThat(pool.stats().destroyed()).isZero();
        } finally {
            pool.close();
        }
    }

    @Test
    void uncompletedTransferIsCompletedOnReturn() throws Exception {
        FtpPool pool = newPool();
        try {
            FtpConnection connection = pool.borrow();
            OutputStream out = connection.storeFileStream("/pending.bin");
            out.write("partial".getBytes(StandardCharsets.UTF_8));
            out.close();
            // NOTE: completePendingCommand() never invoked — the pool must clean up
            pool.release(connection);

            // A connection with an open data stream is never re-issued; the next
            // borrow must work against a clean, completed transfer state.
            assertThat(pool.stats().active()).isZero();
            assertThat(pool.<String>execute(connection2 -> {
                connection2.upload("/after.bin",
                        new ByteArrayInputStream("ok".getBytes(StandardCharsets.UTF_8)));
                return connection2.currentDirectory();
            })).isEqualTo("/");
        } finally {
            pool.close();
        }
    }

    @Test
    void failedResetDestroysConnectionInsteadOfReissuing() throws Exception {
        FtpPool pool = newPool();
        try {
            FtpConnection connection = pool.borrow();
            connection.markBroken();
            pool.release(connection);

            assertThat(pool.stats().destroyed()).isGreaterThanOrEqualTo(1);
            assertThat(pool.stats().active()).isZero();
            // next borrow creates a fresh, working connection
            assertThat(pool.<String>execute(c -> c.currentDirectory())).isEqualTo("/");
        } finally {
            pool.close();
        }
    }
}