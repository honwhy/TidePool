package io.ftppool.benchmark;

import io.ftppool.api.FtpConnection;
import io.ftppool.core.FtpConnectionFactory;
import io.ftppool.benchmark.BenchSupport.BenchServer;
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
 * Connection validation (spec §56): NOOP round-trip over a live control
 * connection against the embedded server — the per-borrow validity check the
 * pool performs on aged idle entries.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class ValidationBenchmark {

    private BenchServer server;
    private FtpConnectionFactory factory;
    private FtpConnection connection;

    @Setup(Level.Trial)
    public void setUp() throws Exception {
        server = BenchSupport.startServer();
        factory = BenchSupport.commonsFactory(server);
        connection = factory.create();
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        factory.destroy(connection);
        BenchSupport.stopServer(server);
    }

    @Benchmark
    public boolean validate(Blackhole blackhole) {
        boolean valid = factory.validate(connection);
        blackhole.consume(valid);
        return valid;
    }
}