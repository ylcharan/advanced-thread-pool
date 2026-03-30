package com.threadpool;

import com.threadpool.core.QueueStrategy;
import com.threadpool.core.Task;
import com.threadpool.core.TaskFuture;
import org.junit.jupiter.api.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the Priority scheduling strategy.
 */
@DisplayName("Priority Pool Tests")
class PriorityPoolTest {

    private ThreadPoolScheduler pool;

    @AfterEach
    void tearDown() throws InterruptedException {
        if (pool != null) {
            pool.shutdown();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("High-priority tasks should complete before low-priority tasks (single-worker)")
    void highPriorityFirstSingleWorker() throws Exception {
        // Single worker ensures strict ordering
        pool = new ThreadPoolScheduler.Builder()
                .corePoolSize(1)
                .maxPoolSize(1)
                .queueStrategy(QueueStrategy.PRIORITY)
                .build();

        CopyOnWriteArrayList<String> order = new CopyOnWriteArrayList<>();

        // First saturate the worker so our test tasks actually queue
        TaskFuture<Void> blocker = pool.submit(() -> {
            Thread.sleep(200); // hold the worker
            return null;
        });

        // While blocker runs, queue 3 LOW and 3 HIGH tasks
        Thread.sleep(50); // ensure blocker is running

        pool.submit(() -> { order.add("LOW-1"); return null; }, Task.PRIORITY_LOW);
        pool.submit(() -> { order.add("LOW-2"); return null; }, Task.PRIORITY_LOW);
        pool.submit(() -> { order.add("LOW-3"); return null; }, Task.PRIORITY_LOW);

        pool.submit(() -> { order.add("HIGH-1"); return null; }, Task.PRIORITY_HIGH);
        pool.submit(() -> { order.add("HIGH-2"); return null; }, Task.PRIORITY_HIGH);
        pool.submit(() -> { order.add("HIGH-3"); return null; }, Task.PRIORITY_HIGH);

        blocker.get(5, TimeUnit.SECONDS);
        Thread.sleep(500); // let remaining tasks run

        System.out.println("Priority order: " + order);

        // The first three tasks after the blocker should all be HIGH
        List<String> afterBlocker = order.stream().toList();
        if (afterBlocker.size() >= 3) {
            assertTrue(afterBlocker.get(0).startsWith("HIGH"),
                    "First task after blocker should be HIGH, got: " + afterBlocker.get(0));
            assertTrue(afterBlocker.get(1).startsWith("HIGH"),
                    "Second task should be HIGH, got: " + afterBlocker.get(1));
        }
    }

    @Test
    @DisplayName("All tasks complete regardless of priority")
    void allTasksCompleteWithPriority() throws Exception {
        pool = new ThreadPoolScheduler.Builder()
                .corePoolSize(4)
                .maxPoolSize(4)
                .queueStrategy(QueueStrategy.PRIORITY)
                .build();

        List<TaskFuture<String>> futures = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            int priority = (i % 3 == 0) ? Task.PRIORITY_HIGH
                         : (i % 3 == 1) ? Task.PRIORITY_NORMAL
                         :                Task.PRIORITY_LOW;
            futures.add(pool.submit(() -> "done", priority));
        }

        for (TaskFuture<String> f : futures) {
            assertEquals("done", f.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    @DisplayName("FIFO ordering within same priority level")
    void fifoWithinSamePriority() throws Exception {
        pool = new ThreadPoolScheduler.Builder()
                .corePoolSize(1)
                .maxPoolSize(1)
                .queueStrategy(QueueStrategy.PRIORITY)
                .build();

        CopyOnWriteArrayList<Integer> order = new CopyOnWriteArrayList<>();

        // Blocker task
        TaskFuture<Void> blocker = pool.submit(() -> {
            Thread.sleep(200);
            return null;
        });
        Thread.sleep(50);

        // 5 tasks with identical priority — should run in submission order
        for (int i = 1; i <= 5; i++) {
            final int n = i;
            pool.submit(() -> { order.add(n); return null; }, Task.PRIORITY_NORMAL);
        }

        blocker.get(5, TimeUnit.SECONDS);
        Thread.sleep(500);

        System.out.println("Same-priority order: " + order);
        // Verify ascending order (FIFO tiebreak)
        for (int i = 0; i < order.size() - 1; i++) {
            assertTrue(order.get(i) < order.get(i + 1),
                    "Expected FIFO ordering within same priority");
        }
    }
}
