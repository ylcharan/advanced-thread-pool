package com.threadpool;

import com.threadpool.core.QueueStrategy;
import com.threadpool.core.TaskFuture;
import org.junit.jupiter.api.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the FIFO scheduling strategy.
 */
@DisplayName("FIFO Pool Tests")
class FifoPoolTest {

    private ThreadPoolScheduler pool;

    @BeforeEach
    void setUp() {
        pool = new ThreadPoolScheduler.Builder()
                .corePoolSize(4)
                .maxPoolSize(4)
                .queueStrategy(QueueStrategy.FIFO)
                .build();
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("All submitted tasks should complete")
    void allTasksComplete() throws Exception {
        int count = 20;
        List<TaskFuture<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            final int n = i;
            futures.add(pool.submit(() -> n * 2));
        }

        int completed = 0;
        for (TaskFuture<Integer> f : futures) {
            Integer result = f.get(5, TimeUnit.SECONDS);
            assertNotNull(result, "Result should not be null");
            completed++;
        }
        assertEquals(count, completed, "All tasks should have completed");
    }

    @Test
    @DisplayName("Tasks should return correct computed values")
    void correctResults() throws Exception {
        TaskFuture<Long> f = pool.submit(() -> {
            long sum = 0;
            for (int i = 1; i <= 100; i++) sum += i;
            return sum;
        });
        assertEquals(5050L, f.get(5, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("Concurrent tasks should all complete without data races")
    void concurrentTasksSafe() throws Exception {
        int count = 100;
        AtomicInteger counter = new AtomicInteger(0);
        List<TaskFuture<Void>> futures = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            futures.add(pool.submit(() -> { counter.incrementAndGet(); return null; }));
        }

        for (TaskFuture<Void> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }

        assertEquals(count, counter.get(), "All increments must be visible");
    }

    @Test
    @DisplayName("Pool rejects tasks after shutdown")
    void rejectAfterShutdown() {
        pool.shutdown();
        assertThrows(java.util.concurrent.RejectedExecutionException.class,
                () -> pool.submit(() -> "should-fail"));
    }

    @Test
    @DisplayName("Monitor tracks completed count correctly")
    void monitorCounting() throws Exception {
        int count = 10;
        List<TaskFuture<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            futures.add(pool.submit(() -> 42));
        }
        for (TaskFuture<Integer> f : futures) {
            f.get(5, TimeUnit.SECONDS);
        }
        assertEquals(count, pool.getMonitor().getCompletedTasks());
    }
}
