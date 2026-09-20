package io.ftppool.benchmark;

import io.ftppool.api.FtpConnection;
import io.ftppool.api.FtpPool;
import io.ftppool.core.FtpConnectionFactory;
import io.ftppool.engine.commons.CommonsPoolEngineFactory;
import io.ftppool.engine.fast.FastPoolEngineFactory;
import org.apache.commons.pool2.BasePooledObjectFactory;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.impl.DefaultPooledObject;
import org.apache.commons.pool2.impl.GenericObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * Baseline comparison required by spec section 55: {@code noPool} vs
 * {@code commonsPool} vs FtpPool {@code fast} / {@code commons} / {@code hybrid}.
 *
 * <p>All variants run over the same socket-free fake connection factory so the
 * numbers isolate pool mechanics (borrow/return/reset), not network cost.
 * {@link ConnectionCreationBenchmark} measures the cold path separately
 * (spec section 58).</p>
 *
 * <p>Report form (spec section 57): {@code Mode | Threads | Pool Size | Ops/s |
 * p50 | p95 | p99 | Alloc/op} — run with {@code -prof gc -prof lock}.</p>
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(2)
public class EngineComparisonBenchmark {

    @Param({"noPool", "commonsPool", "fast", "commons", "hybrid"})
    private String mode;

    @Param({"8"})
    private int poolSize;

    private FtpPool pool;
    private GenericObjectPool<FtpConnection> commonsPool;
    private FtpConnectionFactory directFactory;

    @Setup(Level.Trial)
    public void setUp() {
        directFactory = BenchSupport.inMemoryConnectionFactory();
        switch (mode) {
            case "noPool" -> {
                // nothing to set up: every call creates and destroys directly
            }
            case "commonsPool" -> {
                GenericObjectPoolConfig<FtpConnection> config = new GenericObjectPoolConfig<>();
                config.setMaxTotal(poolSize);
                config.setMaxIdle(poolSize);
                config.setMinIdle(poolSize);
                commonsPool = new GenericObjectPool<>(new ConnectionPooledFactory(directFactory), config);
                for (int i = 0; i < poolSize; i++) {
                    try {
                        commonsPool.addObject();
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }
            }
            case "fast" -> pool = BenchSupport.inMemoryPool(new FastPoolEngineFactory(), poolSize, poolSize, false);
            case "commons" -> pool = BenchSupport.inMemoryPool(new CommonsPoolEngineFactory(), poolSize, poolSize, false);
            case "hybrid" -> {
                // Fast engine + Commons lifecycle + observability is the hybrid profile;
                // for pool-mechanics isolation we keep observability off.
                pool = BenchSupport.inMemoryPool(new FastPoolEngineFactory(), poolSize, poolSize, false);
            }
            default -> throw new IllegalArgumentException("Unknown mode: " + mode);
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        if (pool != null) {
            pool.close();
        }
        if (commonsPool != null) {
            commonsPool.close();
        }
    }

    @Benchmark
    public FtpConnection borrowReturn(Blackhole blackhole) throws Exception {
        FtpConnection connection;
        switch (mode) {
            case "noPool" -> {
                connection = directFactory.create();
                directFactory.reset(connection);
                directFactory.destroy(connection);
            }
            case "commonsPool" -> {
                connection = commonsPool.borrowObject();
                commonsPool.returnObject(connection);
            }
            default -> {
                connection = pool.borrow();
                pool.release(connection);
            }
        }
        blackhole.consume(connection);
        return connection;
    }

    private static final class ConnectionPooledFactory extends BasePooledObjectFactory<FtpConnection> {

        private final FtpConnectionFactory factory;

        ConnectionPooledFactory(FtpConnectionFactory factory) {
            this.factory = factory;
        }

        @Override
        public FtpConnection create() throws Exception {
            return factory.create();
        }

        @Override
        public PooledObject<FtpConnection> wrap(FtpConnection connection) {
            return new DefaultPooledObject<>(connection);
        }

        @Override
        public boolean validateObject(PooledObject<FtpConnection> p) {
            return factory.validate(p.getObject());
        }

        @Override
        public void passivateObject(PooledObject<FtpConnection> p) {
            factory.reset(p.getObject());
        }

        @Override
        public void destroyObject(PooledObject<FtpConnection> p) {
            factory.destroy(p.getObject());
        }
    }
}
