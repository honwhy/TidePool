package io.ftppool.engine.commons;

import io.ftppool.api.PoolEngine;
import io.ftppool.core.PoolConfiguration;
import io.ftppool.core.PoolStatsRecorder;
import io.ftppool.core.ResourceFactory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CommonsPoolEngineFactoryTest {

    @Test
    void registersUnderCommonsName() {
        assertThat(new CommonsPoolEngineFactory().name()).isEqualTo("commons");
    }

    @Test
    void createsCommonsEngineForAnyResourceType() {
        PoolEngine<String> engine = new CommonsPoolEngineFactory()
                .create(PoolConfiguration.createDefault(), new StringFactory(), new NoopStats());

        assertThat(engine).isInstanceOf(CommonsPoolEngine.class);
        engine.close();
    }

    private static final class StringFactory implements ResourceFactory<String> {

        @Override
        public String create() {
            return "resource";
        }

        @Override
        public boolean validate(String resource) {
            return true;
        }

        @Override
        public boolean reset(String resource) {
            return true;
        }

        @Override
        public void destroy(String resource) {
        }
    }

    private static final class NoopStats implements PoolStatsRecorder {

        @Override
        public void recordCreate() {
        }

        @Override
        public void recordDestroy() {
        }

        @Override
        public void recordBorrow() {
        }

        @Override
        public void recordReturn() {
        }

        @Override
        public void recordBorrowTimeout() {
        }

        @Override
        public void recordValidation() {
        }

        @Override
        public void recordValidationFailure() {
        }

        @Override
        public void recordWaitStart() {
        }

        @Override
        public void recordWaitEnd() {
        }
    }
}