package com.threadpool;

import com.threadpool.core.QueueStrategy;
import com.threadpool.core.TaskFuture;
import org.junit.jupiter.api.*;

import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the custom {@link com.threadpool.core.TaskFuture} implementation.
 *
 * Covers: blocking get(), timed get(), cancel(), exception propagation,
 * and state transitions.
 */
@DisplayName("TaskFuture Tests")
class FutureTest {

    private ThreadPoolScheduler pool;

    @BeforeEach
    void setUp() {
        pool = new ThreadPoolScheduler.Builder()
                .corePoolSize(2)
                .maxPoolSize(2)
                .queueStrategy(QueueStrategy.FIFO)
                .build();
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("get() blocks until result is available")
    void getBlocksUntilDone() throws Exception {
        TaskFuture<String> f = pool.submit(() -> {
            Thread.sleep(200);
            return "hello";
        });

        assertFalse(f.isDone(), "Should not be done yet");
        String result = f.get();
        assertEquals("hello", result);
        assertTrue(f.isDone(), "Should be done after get()");
    }

    @Test
    @DisplayName("get(timeout) returns result within time limit")
    void timedGetReturns() throws Exception {
        TaskFuture<Integer> f = pool.submit(() -> {
            Thread.sleep(100);
            return 42;
        });
        assertEquals(42, f.get(3, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("get(timeout) throws TimeoutException when too short")
    void timedGetTimesOut() {
        TaskFuture<Void> f = pool.submit(() -> {
            Thread.sleep(2000);
            return null;
        });
        assertThrows(TimeoutException.class, () -> f.get(100, TimeUnit.MILLISECONDS));
    }

    @Test
    @DisplayName("cancel() returns true for a pending task")
    void cancelPendingTask() throws Exception {
        // Submit a long blocker to saturate workers
        pool.submit(() -> { Thread.sleep(5000); return null; });
        pool.submit(() -> { Thread.sleep(5000); return null; });

        // This task should sit in the queue
        TaskFuture<String> toCancel = pool.submit(() -> "should-not-run");
        Thread.sleep(50); // ensure the first tasks are running

        boolean cancelled = toCancel.cancel(false);
        assertTrue(cancelled || toCancel.isDone(), "cancel() should return true or task already done");
        if (cancelled) {
            assertTrue(toCancel.isCancelled());
            assertThrows(CancellationException.class, toCancel::get);
        }
    }

    @Test
    @DisplayName("Exception in callable is wrapped as ExecutionException")
    void exceptionPropagation() {
        TaskFuture<Void> f = pool.submit(() -> {
            throw new IllegalStateException("deliberate failure");
        });

        ExecutionException ex = assertThrows(ExecutionException.class, () -> f.get(5, TimeUnit.SECONDS));
        assertInstanceOf(IllegalStateException.class, ex.getCause());
        assertEquals("deliberate failure", ex.getCause().getMessage());
    }

    @Test
    @DisplayName("Multiple threads can call get() concurrently — all receive the same result")
    void multipleWaiters() throws Exception {
        TaskFuture<Long> f = pool.submit(() -> {
            Thread.sleep(200);
            return 12345L;
        });

        // Spawn 5 threads all waiting on the same future
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(5);
        long[] results = new long[5];

        for (int i = 0; i < 5; i++) {
            final int idx = i;
            Thread t = new Thread(() -> {
                try {
                    results[idx] = f.get(5, TimeUnit.SECONDS);
                    latch.countDown();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            t.start();
        }

        latch.await(6, TimeUnit.SECONDS);
        for (long r : results) {
            assertEquals(12345L, r, "All waiters should see the same result");
        }
    }

    @Test
    @DisplayName("isDone() transitions correctly: NEW → RUNNING → COMPLETED")
    void stateTransitions() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);

        TaskFuture<String> f = pool.submit(() -> {
            started.countDown();
            proceed.await();
            return "done";
        });

        started.await(3, TimeUnit.SECONDS);
        assertFalse(f.isDone(), "Should not be done while still running");

        proceed.countDown();
        f.get(3, TimeUnit.SECONDS);
        assertTrue(f.isDone());
        assertFalse(f.isCancelled());
    }
}
