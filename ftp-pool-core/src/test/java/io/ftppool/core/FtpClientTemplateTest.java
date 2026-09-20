package io.ftppool.core;

import io.ftppool.api.FtpConnection;
import io.ftppool.api.FtpConnectionId;
import io.ftppool.api.FtpException;
import io.ftppool.api.FtpFile;
import io.ftppool.api.FtpPool;
import io.ftppool.api.PoolEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FtpClientTemplateTest {

    private StubFactory factory;
    private StubEngine engine;
    private FtpPoolStatsImpl stats;
    private FtpPool pool;
    private FtpClientTemplate template;

    @BeforeEach
    void setUp() {
        factory = new StubFactory();
        stats = new FtpPoolStatsImpl();
        engine = new StubEngine(new EntryFactory(factory), stats);
        stats.bind(engine::size, engine::active);
        pool = new FtpPoolImpl("test", PoolConfiguration.createDefault(), engine, factory, null, stats);
        template = new FtpClientTemplate(pool);
    }

    @Test
    void executeDelegatesAndReturnsResult() throws Exception {
        String result = template.execute(connection -> "ok:" + connection.id());

        assertThat(result).startsWith("ok:ftp-");
        assertThat(engine.returned()).isEqualTo(1);
    }

    @Test
    void convenienceOperationsRoundTripThroughPool() throws Exception {
        assertThat(template.upload("/data/x.bin", InputStream.nullInputStream())).isTrue();
        assertThat(template.download("/data/x.bin", OutputStream.nullOutputStream())).isTrue();
        assertThat(template.delete("/data/x.bin")).isTrue();

        assertThat(engine.returned()).isEqualTo(3);
        assertThat(factory.destroyed).hasValue(0);
    }

    @Test
    void borrowAndReleaseReturnConnectionToPool() throws Exception {
        FtpConnection connection = template.borrow();
        assertThat(connection).isNotNull();

        template.release(connection);

        assertThat(engine.active()).isZero();
        assertThat(engine.returned()).isEqualTo(1);
    }

    @Test
    void statsAndHealthReflectLivePool() throws Exception {
        assertThat(template.stats()).isSameAs(stats);
        // lazily-empty pool is UP (creates on demand), not DOWN
        assertThat(template.health().getStatus()).isEqualTo(FtpPoolHealth.Status.UP);

        FtpConnection connection = template.borrow();

        assertThat(engine.active()).isEqualTo(1);
        assertThat(template.health().getStatus()).isEqualTo(FtpPoolHealth.Status.UP);

        template.release(connection);
    }

    @Test
    void nullPoolRejected() {
        assertThatThrownBy(() -> new FtpClientTemplate(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void poolAccessorExposesWrappedPool() {
        assertThat(template.pool()).isSameAs(pool);
    }

    private static final class StubFactory implements FtpConnectionFactory {

        private final AtomicInteger destroyed = new AtomicInteger();

        @Override
        public FtpConnection create() {
            return new FakeConnection(new FtpConnectionId(1));
        }

        @Override
        public boolean validate(FtpConnection connection) {
            return !connection.isBroken();
        }

        @Override
        public boolean reset(FtpConnection connection) {
            return connection.isValid();
        }

        @Override
        public void destroy(FtpConnection connection) {
            destroyed.incrementAndGet();
        }
    }

    private static final class StubEngine implements PoolEngine<FtpPoolEntry> {

        private final ResourceFactory<FtpPoolEntry> factory;
        private final PoolStatsRecorder recorder;
        private final AtomicInteger returned = new AtomicInteger();
        private FtpPoolEntry single;
        private int active;

        StubEngine(ResourceFactory<FtpPoolEntry> factory, PoolStatsRecorder recorder) {
            this.factory = factory;
            this.recorder = recorder;
        }

        @Override
        public FtpPoolEntry borrow(Duration timeout) throws Exception {
            if (single == null) {
                single = factory.create();
                recorder.recordCreate();
            }
            active++;
            recorder.recordBorrow();
            return single;
        }

        @Override
        public void release(FtpPoolEntry resource) {
            active--;
            returned.incrementAndGet();
            recorder.recordReturn();
        }

        @Override
        public void invalidate(FtpPoolEntry resource) {
            if (single != null && single == resource) {
                active--;
                recorder.recordDestroy();
                factory.destroy(resource);
                single = null;
            }
        }

        @Override
        public int size() {
            return single == null ? 0 : 1;
        }

        @Override
        public int active() {
            return active;
        }

        @Override
        public int idle() {
            return 0;
        }

        @Override
        public void close() {
        }

        int returned() {
            return returned.get();
        }
    }

    private static final class FakeConnection implements FtpConnection {

        private final FtpConnectionId id;

        FakeConnection(FtpConnectionId id) {
            this.id = id;
        }

        @Override
        public FtpConnectionId id() {
            return id;
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
            return null;
        }

        @Override
        public OutputStream storeFileStream(String path) {
            return null;
        }

        @Override
        public boolean upload(String path, InputStream input) {
            return true;
        }

        @Override
        public boolean download(String path, OutputStream output) {
            return true;
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