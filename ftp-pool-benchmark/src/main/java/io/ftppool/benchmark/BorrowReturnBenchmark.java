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
 * Combined borrow + return cycle (spec §56) against in-memory resources,
 * parameterised by pool size: shows how idle-queue depth affects the cycle.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(2)
public class BorrowReturnBenchmark {

    @Param({"1", "8", "32"})
    private int poolSize;

    private FtpPool pool;

    @Setup(Level.Trial)
    public void setUp() {
        pool = BenchSupport.inMemoryPool(poolSize, poolSize, false);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        pool.close();
    }

    @Benchmark
    public FtpConnection borrowAndReturn(Blackhole blackhole) throws Exception {
        FtpConnection connection = pool.borrow();
        pool.release(connection);
        blackhole.consume(connection);
        return connection;
    }
}