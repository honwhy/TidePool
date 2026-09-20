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

import java.util.concurrent.TimeUnit;

/**
 * Borrow throughput (spec §56): each operation borrows a connection from the
 * idle pool and releases it immediately, so the steady state is a deque-then-
 * re-enqueue cycle on the Fast engine — no sockets involved.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(2)
public class BorrowBenchmark {

    private FtpPool pool;

    @Setup(Level.Trial)
    public void setUp() {
        pool = BenchSupport.inMemoryPool(32, 32, false);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        pool.close();
    }

    @Benchmark
    public FtpConnection borrow(Blackhole blackhole) throws Exception {
        FtpConnection connection = pool.borrow();
        blackhole.consume(connection);
        pool.release(connection);
        return connection;
    }
}