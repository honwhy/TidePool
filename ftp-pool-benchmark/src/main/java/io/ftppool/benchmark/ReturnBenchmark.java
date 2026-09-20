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
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.TimeUnit;

/**
 * Return throughput (spec §56): a small stack of pre-borrowed connections is
 * kept; each operation releases one and immediately borrows a replacement, so
 * ops/sec reflects the {@link FtpPool#release} path (state-reset, re-queue,
 * validation bookkeeping) rather than the full acquire.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(2)
public class ReturnBenchmark {

    private FtpPool pool;
    private Deque<FtpConnection> held;

    @Setup(Level.Trial)
    public void setUp() throws Exception {
        pool = BenchSupport.inMemoryPool(16, 16, false);
        held = new ArrayDeque<>();
        for (int i = 0; i < 16; i++) {
            held.addLast(pool.borrow());
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        while (!held.isEmpty()) {
            pool.release(held.removeFirst());
        }
        pool.close();
    }

    @Benchmark
    public void returnConnection(Blackhole blackhole) throws Exception {
        FtpConnection connection = held.removeFirst();
        pool.release(connection);
        held.addLast(pool.borrow());
        blackhole.consume(connection);
    }
}