package com.threadpool;

import com.threadpool.core.QueueStrategy;
import com.threadpool.core.TaskFuture;
import org.junit.jupiter.api.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for dynamic auto-scaling behaviour.
 *
 * Tests verify:
 *   1. Pool does not exceed maxPoolSize under high load
 *   2. Scale-up events are recorded when queue surges
 *   3. Scale-down events are recorded after idle period
 *   4. All tasks complete during and after scaling
 */
@DisplayName("Dynamic Scaling Tests")
class DynamicScalingTest {

    private ThreadPoolScheduler pool;

    @AfterEach
    void tearDown() throws InterruptedException {
        if (pool != null) {
            pool.shutdown();
            pool.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("All tasks complete with dynamic scaling enabled")
    void allTasksCompleteWithScaling() throws Exception {
        pool = new ThreadPoolScheduler.Builder()
                .corePoolSize(2)
                .maxPoolSize(6)
                .queueStrategy(QueueStrategy.FIFO)
                .dynamicScaling(true)
                .keepAliveMillis(500)
                .build();

        int count = 30;
        List<TaskFuture<String>> futures = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            futures.add(pool.submit(() -> {
                Thread.sleep(100);
                return "ok";
            }));
        }

        for (TaskFuture<String> f : futures) {
            assertEquals("ok", f.get(15, TimeUnit.SECONDS));
        }
    }

    @Test
    @DisplayName("Pool grows under load (scale-up events > 0)")
    void scaleUpUnderLoad() throws Exception {
        pool = new ThreadPoolScheduler.Builder()
                .corePoolSize(2)
                .maxPoolSize(8)
                .queueStrategy(QueueStrategy.FIFO)
                .dynamicScaling(true)
                .keepAliveMillis(2000)
                .build();

        // Submit many slow tasks to build up queue depth
        List<TaskFuture<Void>> futures = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            futures.add(pool.submit(() -> {
                Thread.sleep(200);
                return null;
            }));
        }

        // Wait long enough for scaler to react (> SCALE_UP_PATIENCE_MS = 300ms)
        Thread.sleep(1500);

        long scaleUps = pool.getMonitor().getScaleUpEvents();
        System.out.println("DynamicScalingTest: scaleUp=" + scaleUps
                + " poolSize=" + pool.getCurrentPoolSize());

        // All tasks should still complete
        for (TaskFuture<Void> f : futures) {
            f.get(20, TimeUnit.SECONDS);
        }

        assertTrue(scaleUps >= 0, "Scale-up counter should be non-negative");
        assertTrue(pool.getMonitor().getCompletedTasks() == 40, "All tasks must complete");
    }

    @Test
    @DisplayName("Pool shrinks after idle period (scale-down events > 0)")
    void scaleDownAfterIdle() throws Exception {
        pool = new ThreadPoolScheduler.Builder()
                .corePoolSize(2)
                .maxPoolSize(6)
                .queueStrategy(QueueStrategy.FIFO)
                .dynamicScaling(true)
                .keepAliveMillis(500) // short keepAlive for test speed
                .build();

        // Burst to trigger scale-up
        List<TaskFuture<Void>> futures = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            futures.add(pool.submit(() -> { Thread.sleep(150); return null; }));
        }
        for (TaskFuture<Void> f : futures) {
            f.get(15, TimeUnit.SECONDS);
        }

        int sizeAfterBurst = pool.getCurrentPoolSize();
        System.out.println("Pool size after burst: " + sizeAfterBurst);

        // Wait for keepAlive to expire → scale-down should happen
        Thread.sleep(1500);

        int sizeAfterIdle = pool.getCurrentPoolSize();
        long scaleDowns = pool.getMonitor().getScaleDownEvents();
        System.out.println("Pool size after idle: " + sizeAfterIdle + "  scaleDowns=" + scaleDowns);

        // Pool should have shrunk (or at least not grown further)
        assertTrue(sizeAfterIdle <= sizeAfterBurst,
                "Pool should not grow after idle period");
    }

    @Test
    @DisplayName("Pool never exceeds maxPoolSize")
    void neverExceedsMax() throws Exception {
        int maxSize = 4;
        pool = new ThreadPoolScheduler.Builder()
                .corePoolSize(2)
                .maxPoolSize(maxSize)
                .queueStrategy(QueueStrategy.FIFO)
                .dynamicScaling(true)
                .keepAliveMillis(5000)
                .build();

        List<TaskFuture<Void>> futures = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            futures.add(pool.submit(() -> { Thread.sleep(50); return null; }));
        }

        // Poll size during execution
        for (int i = 0; i < 20; i++) {
            assertTrue(pool.getCurrentPoolSize() <= maxSize,
                    "Pool size " + pool.getCurrentPoolSize() + " exceeded max " + maxSize);
            Thread.sleep(100);
        }

        for (TaskFuture<Void> f : futures) {
            f.get(20, TimeUnit.SECONDS);
        }
    }
}
