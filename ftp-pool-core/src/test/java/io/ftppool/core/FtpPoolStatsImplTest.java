package io.ftppool.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FtpPoolStatsImplTest {

    @Test
    void derivesIdleFromTotalActivePending() {
        FtpPoolStatsImpl stats = new FtpPoolStatsImpl(() -> 10, () -> 3);
        stats.recordWaitStart();

        assertThat(stats.total()).isEqualTo(10);
        assertThat(stats.active()).isEqualTo(3);
        assertThat(stats.pending()).isEqualTo(1);
        assertThat(stats.idle()).isEqualTo(6);
    }

    @Test
    void unboundStatsReportZero() {
        FtpPoolStatsImpl stats = new FtpPoolStatsImpl();

        assertThat(stats.total()).isZero();
        assertThat(stats.active()).isZero();
    }

    @Test
    void countersAccumulate() {
        FtpPoolStatsImpl stats = new FtpPoolStatsImpl(() -> 0, () -> 0);

        stats.recordCreate();
        stats.recordBorrow();
        stats.recordReturn();
        stats.recordBorrowTimeout();
        stats.recordValidation();
        stats.recordValidationFailure();
        stats.recordDestroy();

        assertThat(stats.created()).isEqualTo(1);
        assertThat(stats.borrowed()).isEqualTo(1);
        assertThat(stats.returned()).isEqualTo(1);
        assertThat(stats.borrowTimeouts()).isEqualTo(1);
        assertThat(stats.validations()).isEqualTo(1);
        assertThat(stats.validationFailures()).isEqualTo(1);
        assertThat(stats.destroyed()).isEqualTo(1);
    }

    @Test
    void bindAllowsLateEngineWiring() {
        FtpPoolStatsImpl stats = new FtpPoolStatsImpl();

        stats.bind(() -> 7, () -> 2);

        assertThat(stats.total()).isEqualTo(7);
        assertThat(stats.active()).isEqualTo(2);
        assertThat(stats.idle()).isEqualTo(5);
    }
}