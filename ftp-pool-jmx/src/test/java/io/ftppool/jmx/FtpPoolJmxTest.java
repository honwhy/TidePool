package io.ftppool.jmx;

import io.ftppool.api.FtpCallback;
import io.ftppool.api.FtpConnection;
import io.ftppool.api.FtpException;
import io.ftppool.api.FtpPool;
import io.ftppool.api.FtpPoolStats;
import io.ftppool.api.PoolEngine;
import org.junit.jupiter.api.Test;

import javax.management.AttributeNotFoundException;
import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;
import javax.management.ObjectName;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class FtpPoolJmxTest {

    @Test
    void objectNameUsesCanonicalDomainTypeAndQuotedName() {
        assertThat(FtpPoolJmx.objectName("default").getCanonicalName())
                .isEqualTo("com.ftppool:name=\"default\",type=FtpPool");
        assertThat(FtpPoolJmx.objectName("order-sync").getCanonicalName())
                .isEqualTo("com.ftppool:name=\"order-sync\",type=FtpPool");
    }

    @Test
    void objectNameQuotesSpecialCharacters() {
        String quoted = FtpPoolJmx.objectName("pool:datacenter").getCanonicalName();
        assertThat(quoted).isEqualTo("com.ftppool:name=\"pool:datacenter\",type=FtpPool");
    }

    @Test
    void objectNameCarriesFixedDomainAndType() {
        ObjectName name = FtpPoolJmx.objectName("anything");

        assertThat(name.getDomain()).isEqualTo("com.ftppool");
        assertThat(name.getKeyProperty("type")).isEqualTo("FtpPool");
        assertThat(name.getKeyProperty("name")).isEqualTo("\"anything\"");
    }

    @Test
    void attributesMirrorStatsSnapshot() {
        StubStats stats = new StubStats(9, 4, 3, 2, 10L, 6L, 7L, 5L, 1L, 8L);
        MBeanServer server = MBeanServerFactory.newMBeanServer(null);
        ObjectName name = FtpPoolJmx.register("attrs", stats, new StubEngine(), new StubPool(), server);
        try {
            assertThat(attribute(server, name, "PoolName")).isEqualTo("attrs");
            assertThat(attribute(server, name, "Total")).isEqualTo(9);
            assertThat(attribute(server, name, "Active")).isEqualTo(4);
            assertThat(attribute(server, name, "Idle")).isEqualTo(3);
            assertThat(attribute(server, name, "Pending")).isEqualTo(2);
            assertThat(attribute(server, name, "BorrowCount")).isEqualTo(7L);
            assertThat(attribute(server, name, "BorrowTimeoutCount")).isEqualTo(1L);
            assertThat(attribute(server, name, "CreateCount")).isEqualTo(10L);
            assertThat(attribute(server, name, "DestroyCount")).isEqualTo(6L);
            assertThat(attribute(server, name, "ReturnCount")).isEqualTo(5L);
            assertThat(attribute(server, name, "ValidationFailureCount")).isEqualTo(8L);
        } finally {
            FtpPoolJmx.unregister(name, server);
        }
    }

    @Test
    void clearIdleAndValidateAllDelegateToEngine() {
        StubEngine engine = new StubEngine();
        MBeanServer server = MBeanServerFactory.newMBeanServer(null);
        ObjectName name = FtpPoolJmx.register("ops", new StubStats(), engine, new StubPool(), server);
        try {
            invoke(server, name, "clearIdle");
            invoke(server, name, "validateAll");

            assertThat(engine.clearIdleCalls).isEqualTo(1);
            assertThat(engine.validateAllCalls).isEqualTo(1);
        } finally {
            FtpPoolJmx.unregister(name, server);
        }
    }

    @Test
    void shutdownClosesPool() {
        StubPool pool = new StubPool();
        MBeanServer server = MBeanServerFactory.newMBeanServer(null);
        ObjectName name = FtpPoolJmx.register("shutdown", new StubStats(), new StubEngine(), pool, server);
        try {
            invoke(server, name, "shutdown");

            assertThat(pool.closed.get()).isTrue();
        } finally {
            FtpPoolJmx.unregister(name, server);
        }
    }

    @Test
    void registerWithoutPoolNameDefaultsToDefault() {
        MBeanServer server = MBeanServerFactory.newMBeanServer(null);
        ObjectName name = FtpPoolJmx.register("default", new StubStats(), new StubEngine(), new StubPool(), server);
        try {
            assertThat(name.getCanonicalName()).isEqualTo("com.ftppool:name=\"default\",type=FtpPool");
        } finally {
            FtpPoolJmx.unregister(name, server);
        }
    }

    @Test
    void reRegisterReplacesExistingBean() {
        MBeanServer server = MBeanServerFactory.newMBeanServer(null);
        ObjectName name = FtpPoolJmx.register("replace-me", new StubStats(), new StubEngine(), new StubPool(), server);
        try {
            FtpPoolJmx.register("replace-me", new StubStats(), new StubEngine(), new StubPool(), server);

            assertThat(ftppoolBeanCount(server, "replace-me")).isEqualTo(1);
            assertThat(server.isRegistered(name)).isTrue();
        } finally {
            FtpPoolJmx.unregister(name, server);
        }
    }

    @Test
    void unregisterIsIdempotent() {
        MBeanServer server = MBeanServerFactory.newMBeanServer(null);
        ObjectName name = FtpPoolJmx.register("gone", new StubStats(), new StubEngine(), new StubPool(), server);

        FtpPoolJmx.unregister(name, server);
        FtpPoolJmx.unregister(name, server);

        assertThat(server.isRegistered(name)).isFalse();
    }

    private static int ftppoolBeanCount(MBeanServer server, String poolName) {
        try {
            return server.queryNames(new ObjectName("com.ftppool:*"), null).size();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void invoke(MBeanServer server, ObjectName name, String operation) {
        try {
            server.invoke(name, operation, new Object[0], new String[0]);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static Object attribute(MBeanServer server, ObjectName name, String attr) {
        try {
            return server.getAttribute(name, attr);
        } catch (Exception e) {
            if (e instanceof AttributeNotFoundException) {
                throw new AssertionError(e);
            }
            throw new RuntimeException(e);
        }
    }

    private static final class StubStats implements FtpPoolStats {

        private final int total;
        private final int active;
        private final int idle;
        private final int pending;
        private final long created;
        private final long destroyed;
        private final long borrowed;
        private final long returned;
        private final long borrowTimeouts;
        private final long validationFailures;

        StubStats() {
            this(0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }

        StubStats(int total, int active, int idle, int pending, long created, long destroyed,
                  long borrowed, long returned, long borrowTimeouts, long validationFailures) {
            this.total = total;
            this.active = active;
            this.idle = idle;
            this.pending = pending;
            this.created = created;
            this.destroyed = destroyed;
            this.borrowed = borrowed;
            this.returned = returned;
            this.borrowTimeouts = borrowTimeouts;
            this.validationFailures = validationFailures;
        }

        @Override
        public int total() {
            return total;
        }

        @Override
        public int active() {
            return active;
        }

        @Override
        public int idle() {
            return idle;
        }

        @Override
        public int pending() {
            return pending;
        }

        @Override
        public long created() {
            return created;
        }

        @Override
        public long destroyed() {
            return destroyed;
        }

        @Override
        public long borrowed() {
            return borrowed;
        }

        @Override
        public long returned() {
            return returned;
        }

        @Override
        public long borrowTimeouts() {
            return borrowTimeouts;
        }

        @Override
        public long validationFailures() {
            return validationFailures;
        }
    }

    private static final class StubEngine implements PoolEngine<Object> {

        int clearIdleCalls;
        int validateAllCalls;

        @Override
        public Object borrow(Duration timeout) {
            return new Object();
        }

        @Override
        public void release(Object resource) {
        }

        @Override
        public void invalidate(Object resource) {
        }

        @Override
        public int size() {
            return 0;
        }

        @Override
        public int active() {
            return 0;
        }

        @Override
        public int idle() {
            return 0;
        }

        @Override
        public void close() {
        }

        @Override
        public void clearIdle() {
            clearIdleCalls++;
        }

        @Override
        public void validateAll() {
            validateAllCalls++;
        }
    }

    private static final class StubPool implements FtpPool {

        private final AtomicBoolean closed = new AtomicBoolean();

        @Override
        public FtpConnection borrow() {
            return null;
        }

        @Override
        public FtpConnection borrow(Duration timeout) {
            return null;
        }

        @Override
        public void release(FtpConnection connection) {
        }

        @Override
        public <T> T execute(FtpCallback<T> callback) throws FtpException {
            return null;
        }

        @Override
        public FtpPoolStats stats() {
            return null;
        }

        @Override
        public void close() {
            closed.set(true);
        }

        @Override
        public boolean isClosed() {
            return closed.get();
        }
    }
}