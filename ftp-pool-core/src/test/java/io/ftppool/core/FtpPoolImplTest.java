package io.ftppool.core;

import io.ftppool.api.FtpCallback;
import io.ftppool.api.FtpConnection;
import io.ftppool.api.FtpConnectionId;
import io.ftppool.api.FtpException;
import io.ftppool.api.FtpExceptionType;
import io.ftppool.api.FtpFile;
import io.ftppool.api.FtpPool;
import io.ftppool.api.FtpPoolException;
import io.ftppool.api.PoolEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FtpPoolImplTest {

    private StubFactory factory;
    private StubEngine engine;
    private FtpPoolStatsImpl stats;
    private FtpPool pool;

    @BeforeEach
    void setUp() {
        factory = new StubFactory();
        stats = new FtpPoolStatsImpl();
        engine = new StubEngine(new EntryFactory(factory), stats);
        stats.bind(engine::size, engine::active);
        pool = new FtpPoolImpl("test", PoolConfiguration.createDefault(), engine, factory, null, stats);
    }

    @Test
    void executeRunsCallbackAndReturnsConnection() {
        String result = pool.execute(connection -> "done:" + connection.id());

        assertThat(result).startsWith("done:ftp-");
        assertThat(engine.returned()).isEqualTo(1);
        assertThat(engine.invalidated()).isZero();
        assertThat(engine.active()).isZero();
    }

    @Test
    void businessFailureReturnsConnectionToPool() {
        assertThatThrownBy(() -> pool.execute(connection -> {
            throw new FtpException(FtpExceptionType.BUSINESS, "file not found");
        })).isInstanceOf(FtpException.class)
                .hasMessageContaining("file not found");

        assertThat(engine.returned()).isEqualTo(1);
        assertThat(engine.invalidated()).isZero();
        assertThat(factory.destroyed.get()).isZero();
    }

    @Test
    void connectionFailureDestroysConnection() {
        assertThatThrownBy(() -> pool.execute(connection -> {
            throw new FtpException(FtpExceptionType.CONNECTION, "socket reset");
        })).isInstanceOf(FtpException.class)
                .hasMessageContaining("socket reset");

        assertThat(engine.returned()).isZero();
        assertThat(engine.invalidated()).isEqualTo(1);
        assertThat(factory.destroyed.get()).isEqualTo(1);
    }

    @Test
    void ioExceptionIsTreatedAsConnectionFailure() {
        assertThatThrownBy(() -> pool.execute(connection -> {
            throw new RuntimeException(new java.io.IOException("EOF"));
        })).isInstanceOf(RuntimeException.class)
                .hasRootCauseInstanceOf(java.io.IOException.class);

        assertThat(engine.invalidated()).isEqualTo(1);
        assertThat(engine.returned()).isZero();
    }

    @Test
    void borrowTimeoutCountsInStats() {
        engine.failBorrow.set(true);

        assertThatThrownBy(() -> pool.borrow(Duration.ofMillis(50)))
                .isInstanceOf(FtpPoolException.class)
                .extracting(e -> ((FtpPoolException) e).getType())
                .isEqualTo(FtpExceptionType.POOL_TIMEOUT);

        assertThat(pool.stats().borrowTimeouts()).isEqualTo(1);
    }

    @Test
    void closedPoolRejectsBorrowAndDestroysOnRelease() {
        FtpConnection connection = pool.borrow();
        pool.close();

        assertThatThrownBy(() -> pool.borrow())
                .isInstanceOf(FtpPoolException.class)
                .extracting(e -> ((FtpPoolException) e).getType())
                .isEqualTo(FtpExceptionType.POOL_CLOSED);

        pool.release(connection);
        assertThat(factory.destroyed.get()).isEqualTo(1);
    }

    @Test
    void foreignConnectionIsDestroyedOnRelease() {
        AtomicReference<FtpConnection> foreign = new AtomicReference<>(
                new FakeConnection(new FtpConnectionId(9999)));
        pool.release(foreign.get());

        assertThat(foreign.get().isBroken()).isTrue();
    }

    @Test
    void executeRejectsNullCallback() {
        assertThatThrownBy(() -> pool.execute((FtpCallback<Object>) null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void leakDetectionWarnsAfterThreshold() throws Exception {
        PoolConfiguration config = PoolConfiguration.builder()
                .leakDetectionThreshold(Duration.ofMillis(50))
                .build();
        FtpPoolStatsImpl leakStats = new FtpPoolStatsImpl();
        pool = new FtpPoolImpl("leak-test", config, engine, factory, null, leakStats);

        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(FtpPoolImpl.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            FtpConnection connection = pool.borrow();
            await(() -> appender.list.stream()
                    .anyMatch(e -> e.getFormattedMessage().contains("Possible connection leak")), 3000);

            assertThat(appender.list)
                    .anyMatch(e -> e.getFormattedMessage().contains("Possible connection leak")
                            && e.getFormattedMessage().contains("connectionId=ftp-")
                            && e.getFormattedMessage().contains("borrowThread=")
                            && e.getFormattedMessage().contains("trace="));
            pool.release(connection);
        } finally {
            pool.close();
            logger.detachAppender(appender);
        }
    }

    private static void await(java.util.function.BooleanSupplier condition, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("condition not met within " + timeoutMillis + " ms");
    }

    @Test
    void statsReflectLiveEngine() {
        FtpConnection first = pool.borrow();
        FtpConnection second = pool.borrow();

        assertThat(pool.stats().active()).isEqualTo(2);
        assertThat(pool.stats().total()).isEqualTo(2);
        assertThat(pool.stats().created()).isEqualTo(2);

        pool.release(first);
        pool.release(second);
        assertThat(pool.stats().idle()).isEqualTo(2);
    }

    // ------------------------------------------------------------------
    // test doubles
    // ------------------------------------------------------------------

    private static final class StubFactory implements FtpConnectionFactory {

        final AtomicInteger created = new AtomicInteger();
        final AtomicInteger destroyed = new AtomicInteger();

        @Override
        public FtpConnection create() {
            created.incrementAndGet();
            return new FakeConnection(new FtpConnectionId(created.get()));
        }

        @Override
        public boolean validate(FtpConnection connection) {
            return !connection.isBroken();
        }

        @Override
        public boolean reset(FtpConnection connection) {
            return !connection.isBroken();
        }

        @Override
        public void destroy(FtpConnection connection) {
            destroyed.incrementAndGet();
        }
    }

    private static final class StubEngine implements PoolEngine<FtpPoolEntry> {

        private final ResourceFactory<FtpPoolEntry> factory;
        private final PoolStatsRecorder recorder;
        private final AtomicBoolean failBorrow = new AtomicBoolean();
        private final ConcurrentLinkedQueue<FtpPoolEntry> idle = new ConcurrentLinkedQueue<>();
        private final AtomicInteger returned = new AtomicInteger();
        private final AtomicInteger invalidated = new AtomicInteger();
        private final AtomicInteger active = new AtomicInteger();
        private final AtomicBoolean closed = new AtomicBoolean();

        StubEngine(ResourceFactory<FtpPoolEntry> factory, PoolStatsRecorder recorder) {
            this.factory = factory;
            this.recorder = recorder;
        }

        @Override
        public FtpPoolEntry borrow(Duration timeout) throws Exception {
            if (closed.get()) {
                throw new IllegalStateException("closed");
            }
            if (failBorrow.get()) {
                throw new TimeoutException("engine borrow timeout");
            }
            FtpPoolEntry entry = idle.poll();
            if (entry == null) {
                entry = factory.create();
                recorder.recordCreate();
            }
            active.incrementAndGet();
            recorder.recordBorrow();
            return entry;
        }

        @Override
        public void release(FtpPoolEntry resource) {
            active.decrementAndGet();
            returned.incrementAndGet();
            recorder.recordReturn();
            idle.offer(resource);
        }

        @Override
        public void invalidate(FtpPoolEntry resource) {
            active.decrementAndGet();
            invalidated.incrementAndGet();
            recorder.recordDestroy();
            factory.destroy(resource);
        }

        @Override
        public int size() {
            return idle.size() + active.get();
        }

        @Override
        public int active() {
            return active.get();
        }

        @Override
        public int idle() {
            return idle.size();
        }

        @Override
        public void close() {
            closed.set(true);
            idle.clear();
        }

        int returned() {
            return returned.get();
        }

        int invalidated() {
            return invalidated.get();
        }
    }

    private static final class FakeConnection implements FtpConnection {

        private final FtpConnectionId id;
        private volatile boolean broken;

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
            broken = true;
        }

        @Override
        public boolean isBroken() {
            return broken;
        }
    }
}