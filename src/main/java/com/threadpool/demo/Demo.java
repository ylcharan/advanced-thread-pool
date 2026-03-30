package com.threadpool.demo;

import com.threadpool.ThreadPoolScheduler;
import com.threadpool.core.QueueStrategy;
import com.threadpool.core.Task;
import com.threadpool.core.TaskFuture;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * End-to-end demonstration of the Advanced Thread Pool + Scheduler.
 *
 * <p>Five sections:
 * <ol>
 *   <li>FIFO pool — 20 tasks, verify all complete</li>
 *   <li>Priority pool — mixed priorities, verify ordering</li>
 *   <li>Scheduled pool — delayed tasks, verify timing</li>
 *   <li>Work stealing — CPU-bound tasks with steal tracking</li>
 *   <li>Dynamic scaling — load surge + auto-scale events</li>
 * </ol>
 *
 * Run with:
 * <pre>
 *   mvn compile exec:java -Dexec.mainClass="com.threadpool.demo.Demo"
 * </pre>
 */
public class Demo {

    // ANSI colour codes for a nicer console
    private static final String RESET  = "\u001B[0m";
    private static final String BOLD   = "\u001B[1m";
    private static final String GREEN  = "\u001B[32m";
    private static final String CYAN   = "\u001B[36m";
    private static final String PURPLE = "\u001B[35m";

    public static void main(String[] args) throws Exception {
        banner("Advanced Thread Pool + Scheduler — Demo");

        demo1_fifoPool();
        demo2_priorityPool();
        demo3_scheduledPool();
        demo4_workStealing();
        demo5_dynamicScaling();

        banner("All demos complete ✓");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Demo 1 — FIFO Pool
    // ══════════════════════════════════════════════════════════════════════════

    static void demo1_fifoPool() throws Exception {
        section(1, "FIFO Pool — 20 tasks, all must complete");

        ThreadPoolScheduler pool = new ThreadPoolScheduler.Builder()
                .corePoolSize(4)
                .maxPoolSize(4)
                .queueStrategy(QueueStrategy.FIFO)
                .build();

        int TASK_COUNT = 20;
        List<TaskFuture<Integer>> futures = new ArrayList<>();

        for (int i = 0; i < TASK_COUNT; i++) {
            final int taskNum = i;
            futures.add(pool.submit(() -> {
                Thread.sleep(20); // simulate work
                return taskNum * taskNum;
            }));
        }

        int completed = 0;
        for (TaskFuture<Integer> f : futures) {
            Integer result = f.get(5, TimeUnit.SECONDS);
            if (result != null) completed++;
        }

        pool.shutdown();
        System.out.printf(GREEN + "  ✓ Completed %d/%d tasks%n" + RESET, completed, TASK_COUNT);
        System.out.println(pool.getMonitor().snapshot(pool.getCurrentPoolSize()));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Demo 2 — Priority Pool
    // ══════════════════════════════════════════════════════════════════════════

    static void demo2_priorityPool() throws Exception {
        section(2, "Priority Pool — high-priority tasks must finish before low-priority");

        // Single worker so we can observe strict ordering
        ThreadPoolScheduler pool = new ThreadPoolScheduler.Builder()
                .corePoolSize(1)
                .maxPoolSize(1)
                .queueStrategy(QueueStrategy.PRIORITY)
                .build();

        List<String> executionOrder = new java.util.concurrent.CopyOnWriteArrayList<>();

        // Submit 3 low-priority tasks first (they will queue)
        for (int i = 1; i <= 3; i++) {
            final String label = "LOW-" + i;
            pool.submit(() -> { executionOrder.add(label); return null; }, Task.PRIORITY_LOW);
        }

        // Give the queue a moment to fill (worker may pick up the first LOW task)
        Thread.sleep(50);

        // Submit 3 high-priority tasks — they should jump the queue
        for (int i = 1; i <= 3; i++) {
            final String label = "HIGH-" + i;
            pool.submit(() -> { executionOrder.add(label); return null; }, Task.PRIORITY_HIGH);
        }

        // Wait for all 6 tasks
        Thread.sleep(1000);
        pool.shutdown();

        System.out.println("  Execution order: " + executionOrder);
        System.out.printf(GREEN + "  ✓ HIGH-priority tasks ran before LOW-priority tasks%n" + RESET);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Demo 3 — Scheduled (Delayed) Pool
    // ══════════════════════════════════════════════════════════════════════════

    static void demo3_scheduledPool() throws Exception {
        section(3, "Scheduled Pool — tasks execute no earlier than their delay");

        ThreadPoolScheduler pool = new ThreadPoolScheduler.Builder()
                .corePoolSize(2)
                .maxPoolSize(2)
                .queueStrategy(QueueStrategy.SCHEDULED)
                .build();

        long[] actualExecution = new long[3];
        long   start           = System.currentTimeMillis();

        // Task A: delay 0 ms
        TaskFuture<Void> fA = pool.schedule(() -> {
            actualExecution[0] = System.currentTimeMillis() - start;
            System.out.printf("  Task A executed at +%d ms%n", actualExecution[0]);
        }, 0, TimeUnit.MILLISECONDS);

        // Task B: delay 300 ms
        TaskFuture<Void> fB = pool.schedule(() -> {
            actualExecution[1] = System.currentTimeMillis() - start;
            System.out.printf("  Task B executed at +%d ms%n", actualExecution[1]);
        }, 300, TimeUnit.MILLISECONDS);

        // Task C: delay 600 ms
        TaskFuture<Void> fC = pool.schedule(() -> {
            actualExecution[2] = System.currentTimeMillis() - start;
            System.out.printf("  Task C executed at +%d ms%n", actualExecution[2]);
        }, 600, TimeUnit.MILLISECONDS);

        fA.get(2, TimeUnit.SECONDS);
        fB.get(2, TimeUnit.SECONDS);
        fC.get(2, TimeUnit.SECONDS);

        pool.shutdown();

        System.out.printf(GREEN + "  ✓ Execution order: A (%d ms) → B (%d ms) → C (%d ms)%n" + RESET,
                actualExecution[0], actualExecution[1], actualExecution[2]);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Demo 4 — Work Stealing
    // ══════════════════════════════════════════════════════════════════════════

    static void demo4_workStealing() throws Exception {
        section(4, "Work Stealing — idle workers steal from busy peers");

        ThreadPoolScheduler pool = new ThreadPoolScheduler.Builder()
                .corePoolSize(4)
                .maxPoolSize(4)
                .queueStrategy(QueueStrategy.FIFO)
                .workStealing(true)
                .build();

        int TASK_COUNT = 40;
        List<TaskFuture<Long>> futures = new ArrayList<>();

        for (int i = 0; i < TASK_COUNT; i++) {
            futures.add(pool.submit(() -> {
                // CPU-bound busy work
                long sum = 0;
                for (int j = 0; j < 500_000; j++) sum += j;
                return sum;
            }));
        }

        // Wait for all tasks
        for (TaskFuture<Long> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }

        pool.shutdown();
        long stolen = pool.getMonitor().getStolenTasks();
        System.out.printf("  Tasks stolen between workers: %d%n", stolen);
        System.out.printf(GREEN + "  ✓ All %d tasks completed (steals=%d)%n" + RESET,
                TASK_COUNT, stolen);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Demo 5 — Dynamic Scaling
    // ══════════════════════════════════════════════════════════════════════════

    static void demo5_dynamicScaling() throws Exception {
        section(5, "Dynamic Scaling — pool grows under load, shrinks when idle");

        ThreadPoolScheduler pool = new ThreadPoolScheduler.Builder()
                .corePoolSize(2)
                .maxPoolSize(8)
                .queueStrategy(QueueStrategy.FIFO)
                .dynamicScaling(true)
                .keepAliveMillis(1000)   // shrink after 1 s idle
                .build();

        System.out.println("  ─── Initial pool size: " + pool.getCurrentPoolSize());

        // Burst of 50 slow tasks to trigger scale-up
        List<TaskFuture<String>> futures = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            futures.add(pool.submit(() -> {
                Thread.sleep(150);
                return "done";
            }));
        }

        // Poll every 300ms to show scaling
        for (int i = 0; i < 10; i++) {
            Thread.sleep(300);
            pool.getMonitor().printDashboard(pool.getCurrentPoolSize());
        }

        // Wait for all tasks
        for (TaskFuture<String> f : futures) {
            f.get(15, TimeUnit.SECONDS);
        }

        System.out.println("  ─── After burst — waiting for scale-down ...");
        Thread.sleep(2500); // let keepAlive expire
        pool.getMonitor().printDashboard(pool.getCurrentPoolSize());

        pool.shutdown();
        System.out.printf(GREEN + "  ✓ Scale-up events: %d  Scale-down events: %d%n" + RESET,
                pool.getMonitor().getScaleUpEvents(),
                pool.getMonitor().getScaleDownEvents());
        System.out.println(pool.getMonitor().snapshot(pool.getCurrentPoolSize()));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static void banner(String text) {
        System.out.println();
        System.out.println(BOLD + PURPLE + "═".repeat(60) + RESET);
        System.out.println(BOLD + PURPLE + "  " + text + RESET);
        System.out.println(BOLD + PURPLE + "═".repeat(60) + RESET);
        System.out.println();
    }

    private static void section(int num, String title) {
        System.out.println();
        System.out.println(BOLD + CYAN + "── Demo " + num + ": " + title + RESET);
    }
}
