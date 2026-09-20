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
import java.io.ByteArrayOutputStream;
import java.util.Random;

/**
 * Download throughput over the embedded MINA FTP server (spec §56): a 4 KiB
 * file is seeded once, then every op downloads it through
 * {@code FtpPool.execute(connection.download(...))}.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@org.openjdk.jmh.annotations.OutputTimeUnit(java.util.concurrent.TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class DownloadBenchmark {

    private static final int SIZE = 4096;

    private final byte[] expected = new byte[SIZE];

    {
        new Random(42L).nextBytes(expected);
    }

    private BenchServer server;
    private FtpPool pool;

    @Setup(Level.Trial)
    public void setUp() throws Exception {
        server = BenchSupport.startServer();
        pool = BenchSupport.serverPool(server, 4, 4);
        pool.execute(connection ->
                connection.upload("/bench-download.bin", new ByteArrayInputStream(expected)));
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        pool.close();
        BenchSupport.stopServer(server);
    }

    @Benchmark
    public boolean download() throws Exception {
        return pool.execute(connection -> {
            ByteArrayOutputStream output = new ByteArrayOutputStream(SIZE);
            return connection.download("/bench-download.bin", output);
        });
    }
}