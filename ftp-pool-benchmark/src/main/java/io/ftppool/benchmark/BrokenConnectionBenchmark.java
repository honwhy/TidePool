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
 * Broken-connection turnover (spec §56): every created resource is already
 * marked broken, so each release destroys and the next borrow recreates. The
 * measured cycle is the pool's full destroy → recreate path — the worst case a
 * real drop/socket-reset forces.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(2)
public class BrokenConnectionBenchmark {

    private FtpPool pool;

    @Setup(Level.Trial)
    public void setUp() {
        pool = BenchSupport.inMemoryPool(0, 8, true);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        pool.close();
    }

    @Benchmark
    public FtpConnection brokenTurnover(Blackhole blackhole) throws Exception {
        FtpConnection connection = pool.borrow();
        pool.release(connection);
        blackhole.consume(connection);
        return connection;
    }
}