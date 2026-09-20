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

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.TimeUnit;

/**
 * Physical connection establishment against an embedded MINA FTP server on
 * loopback (spec §56): TCP connect → login → BINARY → passive → PWD. Every
 * benchmark op pays the real server round-trips; an amortised close keeps the
 * open-socket count bounded.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class ConnectionCreationBenchmark {

    private BenchServer server;
    private FtpConnectionFactory factory;
    private Deque<FtpConnection> open;

    @Setup(Level.Trial)
    public void setUp() {
        server = BenchSupport.startServer();
        factory = BenchSupport.commonsFactory(server);
        open = new ArrayDeque<>();
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        while (!open.isEmpty()) {
            factory.destroy(open.removeFirst());
        }
        BenchSupport.stopServer(server);
    }

    @Benchmark
    public FtpConnection create(Blackhole blackhole) throws Exception {
        FtpConnection connection = factory.create();
        open.addLast(connection);
        if (open.size() > 8) {
            factory.destroy(open.removeFirst());
        }
        blackhole.consume(connection);
        return connection;
    }
}