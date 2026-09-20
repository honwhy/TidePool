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
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * Contended borrow/return (spec §56): 1/4/8/16 workers share a single pool over
 * in-memory resources, exposing how contention on the idle queue and create
 * permits scales. Pool is sized to the benchmark's own need (64 idle slots).
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(2)
public class ConcurrentBorrowBenchmark {

    private FtpPool pool;

    @Setup(Level.Trial)
    public void setUp() {
        pool = BenchSupport.inMemoryPool(64, 64, false);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        pool.close();
    }

    @Benchmark
    @Threads(1)
    public void borrowAndReturn1(Blackhole blackhole) throws Exception {
        borrowAndReturn(blackhole);
    }

    @Benchmark
    @Threads(4)
    public void borrowAndReturn4(Blackhole blackhole) throws Exception {
        borrowAndReturn(blackhole);
    }

    @Benchmark
    @Threads(8)
    public void borrowAndReturn8(Blackhole blackhole) throws Exception {
        borrowAndReturn(blackhole);
    }

    @Benchmark
    @Threads(16)
    public void borrowAndReturn16(Blackhole blackhole) throws Exception {
        borrowAndReturn(blackhole);
    }

    private void borrowAndReturn(Blackhole blackhole) throws Exception {
        FtpConnection connection = pool.borrow();
        pool.release(connection);
        blackhole.consume(connection);
    }
}