package io.ftppool.tests;

import io.ftppool.api.FtpPool;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrency (spec section 61): 100 and 1000 threads borrowing, uploading,
 * downloading and deleting over a small pool must leak nothing, double-allocate
 * nothing and finish with zero active connections and zero failures.
 */
@Tag("integration")
class ConcurrencyIntegrationTest extends AbstractFtpIntegrationTest {

    @Test
    void hundredThreadsShareThePoolSafely() throws Exception {
        runConcurrentWorkload(100);
    }

    @Test
    void thousandThreadsShareThePoolSafely() throws Exception {
        runConcurrentWorkload(1000);
    }

    private void runConcurrentWorkload(int threadCount) throws Exception {
        FtpPool pool = newPool(24, Duration.ofSeconds(10));
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        AtomicInteger failures = new AtomicInteger();
        AtomicInteger mismatches = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>(threadCount);
        try {
            for (int i = 0; i < threadCount; i++) {
                int index = i;
                futures.add(executor.submit(() -> {
                    byte[] expected = ("data-" + index).getBytes(StandardCharsets.UTF_8);
                    try {
                        start.await();
                        String path = "/concurrent-" + index + ".bin";
                        String roundtrip = pool.execute(connection -> {
                            connection.upload(path, new ByteArrayInputStream(expected));
                            ByteArrayOutputStream out = new ByteArrayOutputStream();
                            connection.download(path, out);
                            connection.delete(path);
                            return new String(out.toByteArray(), StandardCharsets.UTF_8);
                        });
                        if (!("data-" + index).equals(roundtrip)) {
                            mismatches.incrementAndGet();
                        }
                    } catch (Exception e) {
                        failures.incrementAndGet();
                    }
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(120, TimeUnit.SECONDS);
            }

            assertThat(failures).hasValue(0);
            assertThat(mismatches).hasValue(0);
            assertThat(pool.stats().active()).isZero();
            assertThat(pool.stats().borrowed()).isEqualTo(threadCount);
            assertThat(pool.stats().returned()).isEqualTo(threadCount);
        } finally {
            executor.shutdownNow();
            pool.close();
        }
    }
}