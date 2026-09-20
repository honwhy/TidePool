package io.ftppool.tests;

import io.ftppool.api.FtpPool;
import io.ftppool.core.FtpPoolBuilder;
import io.ftppool.core.ObservabilityType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Temporary diagnostic for the concurrency integration failure. DELETE AFTER USE.
 */
@Tag("integration")
class DiagConcurrencyTest {

    private static EmbeddedFtpServer server;

    @BeforeAll
    static void startServer() throws Exception {
        server = EmbeddedFtpServer.start();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void diagRawFtpClient() throws Exception {
        int threadCount = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        CountDownLatch start = new CountDownLatch(1);
        for (int i = 0; i < threadCount; i++) {
            int index = i;
            futures.add(executor.submit(() -> {
                try {
                    start.await();
                    long t0 = System.nanoTime();
                    org.apache.commons.net.ftp.FTPClient c = new org.apache.commons.net.ftp.FTPClient();
                    c.setConnectTimeout(15000);
                    c.setDefaultTimeout(30000);
                    c.connect("127.0.0.1", server.port());
                    boolean ok = c.login(EmbeddedFtpServer.USERNAME, EmbeddedFtpServer.PASSWORD);
                    c.setFileType(org.apache.commons.net.ftp.FTPClient.BINARY_FILE_TYPE);
                    c.enterLocalPassiveMode();
                    String pwd = c.printWorkingDirectory();
                    c.quit();
                    c.disconnect();
                    if (ok && "/".equals(pwd)) {
                        successes.incrementAndGet();
                    } else {
                        failures.incrementAndGet();
                        System.out.println("[diagRaw] worker-" + index + " login=" + ok + " pwd=" + pwd
                                + " elapsed=" + (System.nanoTime() - t0) / 1_000_000 + "ms");
                    }
                } catch (Throwable e) {
                    failures.incrementAndGet();
                    System.out.println("[diagRaw] worker-" + index + " FAIL "
                            + e.getClass().getSimpleName() + ": " + e.getMessage()
                            + " cause=" + (e.getCause() == null ? "none" : e.getCause().getClass().getSimpleName()));
                }
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(60, TimeUnit.SECONDS);
        }
        executor.shutdownNow();
        System.out.println("[diagRaw] RESULT failures=" + failures + " successes=" + successes);
    }

    @Test
    void diagCommons() throws Exception {
        FtpPool pool = FtpPoolBuilder.builder()
                .poolName("diagCommons")
                .host("127.0.0.1")
                .port(server.port())
                .username(EmbeddedFtpServer.USERNAME)
                .password(EmbeddedFtpServer.PASSWORD)
                .minIdle(0)
                .maxSize(24)
                .engine(io.ftppool.core.PoolEngineType.COMMONS)
                .connectionTimeout(Duration.ofSeconds(15))
                .observability(ObservabilityType.NONE)
                .build();
        try {
            int threadCount = 100;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            AtomicInteger successes = new AtomicInteger();
            AtomicInteger failures = new AtomicInteger();
            List<Future<?>> futures = new ArrayList<>();
            CountDownLatch start = new CountDownLatch(1);
            for (int i = 0; i < threadCount; i++) {
                int index = i;
                futures.add(executor.submit(() -> {
                    byte[] expected = ("data-" + index).getBytes(StandardCharsets.UTF_8);
                    try {
                        start.await();
                        String path = "/diag-com-" + index + ".bin";
                        pool.execute(connection -> {
                            connection.upload(path, new ByteArrayInputStream(expected));
                            ByteArrayOutputStream out = new ByteArrayOutputStream();
                            connection.download(path, out);
                            connection.delete(path);
                            return null;
                        });
                        successes.incrementAndGet();
                    } catch (Throwable e) {
                        failures.incrementAndGet();
                    }
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(60, TimeUnit.SECONDS);
            }
            executor.shutdownNow();
            System.out.println("[diagCommons] RESULT failures=" + failures + " successes=" + successes);
            System.out.println("[diagCommons] stats created=" + pool.stats().created() + " destroyed=" + pool.stats().destroyed()
                    + " borrowed=" + pool.stats().borrowed() + " returned=" + pool.stats().returned()
                    + " timeouts=" + pool.stats().borrowTimeouts());
        } finally {
            pool.close();
        }
    }

    @Test
    void diagBorrowNoOp() throws Exception {
        FtpPool pool = FtpPoolBuilder.builder()
                .poolName("diagBorrowNoOp")
                .host("127.0.0.1")
                .port(server.port())
                .username(EmbeddedFtpServer.USERNAME)
                .password(EmbeddedFtpServer.PASSWORD)
                .minIdle(0)
                .maxSize(24)
                .maxCreateConcurrency(24)
                .connectionTimeout(Duration.ofSeconds(15))
                .observability(ObservabilityType.NONE)
                .build();
        try {
            int threadCount = 100;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            AtomicInteger successes = new AtomicInteger();
            AtomicInteger failures = new AtomicInteger();
            List<Future<?>> futures = new ArrayList<>();
            CountDownLatch start = new CountDownLatch(1);
            for (int i = 0; i < threadCount; i++) {
                futures.add(executor.submit(() -> {
                    try {
                        start.await();
                        pool.execute(connection -> connection.currentDirectory());
                        successes.incrementAndGet();
                    } catch (Throwable e) {
                        failures.incrementAndGet();
                    }
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(60, TimeUnit.SECONDS);
            }
            executor.shutdownNow();
            System.out.println("[diagBorrowNoOp] RESULT failures=" + failures + " successes=" + successes);
            System.out.println("[diagBorrowNoOp] stats created=" + pool.stats().created() + " destroyed=" + pool.stats().destroyed()
                    + " borrowed=" + pool.stats().borrowed() + " returned=" + pool.stats().returned()
                    + " timeouts=" + pool.stats().borrowTimeouts());
        } finally {
            pool.close();
        }
    }

    @Test
    void diag() throws Exception {
        FtpPool pool = FtpPoolBuilder.builder()
                .poolName("diag")
                .host("127.0.0.1")
                .port(server.port())
                .username(EmbeddedFtpServer.USERNAME)
                .password(EmbeddedFtpServer.PASSWORD)
                .minIdle(0)
                .maxSize(24)
                .connectionTimeout(Duration.ofSeconds(15))
                .observability(ObservabilityType.NONE)
                .build();
        try {
            int threadCount = 100;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            AtomicInteger successes = new AtomicInteger();
            AtomicInteger failures = new AtomicInteger();
            Map<String, Integer> exceptionCounts = new ConcurrentHashMap<>();
            long[] up = new long[threadCount];
            long[] down = new long[threadCount];
            long[] del = new long[threadCount];
            long[] total = new long[threadCount];
            List<Future<?>> futures = new ArrayList<>();
            CountDownLatch start = new CountDownLatch(1);
            for (int i = 0; i < threadCount; i++) {
                int index = i;
                futures.add(executor.submit(() -> {
                    byte[] expected = ("data-" + index).getBytes(StandardCharsets.UTF_8);
                    try {
                        start.await();
                        long t0 = System.nanoTime();
                        String path = "/diag-" + index + ".bin";
                        pool.execute(connection -> {
                            long a = System.nanoTime();
                            connection.upload(path, new ByteArrayInputStream(expected));
                            long b = System.nanoTime();
                            ByteArrayOutputStream out = new ByteArrayOutputStream();
                            connection.download(path, out);
                            long c = System.nanoTime();
                            connection.delete(path);
                            long d = System.nanoTime();
                            up[index] = (b - a) / 1_000_000;
                            down[index] = (c - b) / 1_000_000;
                            del[index] = (d - c) / 1_000_000;
                            total[index] = (System.nanoTime() - t0) / 1_000_000;
                            return null;
                        });
                        successes.incrementAndGet();
                    } catch (Throwable e) {
                        failures.incrementAndGet();
                        String name = e.getClass().getSimpleName();
                        if (e.getMessage() != null) {
                            name += ": " + e.getMessage();
                        }
                        exceptionCounts.merge(name, 1, Integer::sum);
                    }
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(60, TimeUnit.SECONDS);
            }
            executor.shutdownNow();
            long su = 0, sd = 0, sdel = 0, st = 0;
            int n = successes.get();
            for (int i = 0; i < threadCount; i++) {
                su += up[i];
                sd += down[i];
                sdel += del[i];
                st += total[i];
            }
            System.out.println("[diag] RESULT failures=" + failures + " successes=" + n
                    + " avgUpload=" + (n == 0 ? 0 : su / n) + "ms avgDownload=" + (n == 0 ? 0 : sd / n)
                    + "ms avgDelete=" + (n == 0 ? 0 : sdel / n) + "ms avgTotal=" + (n == 0 ? 0 : st / n) + "ms");
            System.out.println("[diag] counts=" + exceptionCounts);
            System.out.println("[diag] stats created=" + pool.stats().created() + " destroyed=" + pool.stats().destroyed()
                    + " active=" + pool.stats().active() + " borrowed=" + pool.stats().borrowed()
                    + " returned=" + pool.stats().returned() + " timeouts=" + pool.stats().borrowTimeouts());
        } finally {
            pool.close();
        }
    }
}