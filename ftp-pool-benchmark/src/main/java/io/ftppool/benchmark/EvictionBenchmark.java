package io.ftppool.benchmark;

import io.ftppool.api.FtpConnection;
import io.ftppool.api.FtpPool;
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
 * Eviction/turnover pressure (spec §56): the pool is held at a deliberately
 * small {@code maxSize} so cache misses force constant create-permit contention
 * and idle replacement — the cost profile a scheduler incurs when the
 * housekeeper is cycling connections under load.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(2)
public class EvictionBenchmark {

    @Param({"1", "4"})
    private int maxSize;

    private FtpPool pool;

    @Setup(Level.Trial)
    public void setUp() {
        pool = BenchSupport.inMemoryPool(0, maxSize, false);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        pool.close();
    }

    @Benchmark
    public FtpConnection cycleUnderPressure(Blackhole blackhole) throws Exception {
        FtpConnection connection = pool.borrow();
        pool.release(connection);
        blackhole.consume(connection);
        return connection;
    }
}