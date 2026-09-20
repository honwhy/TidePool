package io.ftppool.benchmark;

import io.ftppool.api.FtpPool;
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

import java.io.ByteArrayInputStream;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Upload throughput over the embedded MINA FTP server (spec §56): one 4 KiB
 * payload per op through {@code FtpPool.execute(connection.upload(...))} —
 * borrow, STOR + data transfer, state reset, return.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class UploadBenchmark {

    private static final int SIZE = 4096;
    private static final byte[] PAYLOAD = new byte[SIZE];

    static {
        new Random(42L).nextBytes(PAYLOAD);
    }

    private BenchServer server;
    private FtpPool pool;

    @Setup(Level.Trial)
    public void setUp() {
        server = BenchSupport.startServer();
        pool = BenchSupport.serverPool(server, 4, 4);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        pool.close();
        BenchSupport.stopServer(server);
    }

    @Benchmark
    public boolean upload() throws Exception {
        return pool.execute(connection ->
                connection.upload("/bench-upload.bin", new ByteArrayInputStream(PAYLOAD)));
    }
}