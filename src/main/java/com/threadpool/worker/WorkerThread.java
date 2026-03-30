package com.threadpool.worker;

import com.threadpool.core.Task;
import com.threadpool.core.TaskFuture;
import com.threadpool.core.TaskQueue;
import com.threadpool.monitor.ThreadPoolMonitor;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * A pooled worker thread.
 *
 * <p>Each {@code WorkerThread} loops indefinitely:
 * <ol>
 *   <li>Poll the shared {@link TaskQueue} for a task (blocks up to {@code POLL_TIMEOUT_MS}).</li>
 *   <li>If a task is found, execute it and record the result in its {@link TaskFuture}.</li>
 *   <li>If no task arrives within the timeout AND the pool has been asked to scale down or
 *       shut down, exit the loop.</li>
 * </ol>
 *
 * <p>Shutdown is cooperative: the pool sets {@code shutdown = true}, and the worker exits
 * naturally after its current poll timeout expires. For immediate stop, the thread is
 * interrupted via {@link Thread#interrupt()}.
 */
public class WorkerThread implements Runnable {

    private static final Logger LOG              = Logger.getLogger(WorkerThread.class.getName());
    private static final long   POLL_TIMEOUT_MS  = 100;

    // Shared state
    protected final TaskQueue                  sharedQueue;
    protected final Map<Long, TaskFuture<?>>   futureRegistry; // taskId → future
    protected final ThreadPoolMonitor          monitor;

    // Worker identity
    protected final int  workerId;
    protected final String name;

    // Lifecycle flags — set by the pool
    private final AtomicBoolean shutdown     = new AtomicBoolean(false);
    private final AtomicBoolean shouldExpire = new AtomicBoolean(false); // dynamic scaling: shrink

    // The OS thread this worker runs on (set once thread starts)
    protected volatile Thread thread;

    public WorkerThread(int workerId,
                        TaskQueue sharedQueue,
                        Map<Long, TaskFuture<?>> futureRegistry,
                        ThreadPoolMonitor monitor) {
        this.workerId       = workerId;
        this.sharedQueue    = sharedQueue;
        this.futureRegistry = futureRegistry;
        this.monitor        = monitor;
        this.name           = "pool-worker-" + workerId;
    }

    // ── Runnable ─────────────────────────────────────────────────────────────

    @Override
    public void run() {
        thread = Thread.currentThread();
        thread.setName(name);
        LOG.fine("[" + name + "] started");

        try {
            while (!shutdown.get() && !shouldExpire.get()) {
                runOneCycle();
            }
        } finally {
            LOG.fine("[" + name + "] exiting");
            monitor.workerExited(workerId);
        }
    }

    /**
     * One iteration: try to pick a task and run it.
     */
    protected void runOneCycle() {
        Task<?> task = null;
        try {
            task = sharedQueue.poll(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }

        if (task != null) {
            executeTask(task);
        }
    }

    /**
     * Execute a task and fulfill its future.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    protected void executeTask(Task<?> task) {
        TaskFuture future = futureRegistry.get(task.getTaskId());
        if (future == null) {
            // Future was discarded (e.g., pool shutdownNow) — skip
            return;
        }

        if (!future.markRunning()) {
            // Task was cancelled before we picked it up
            monitor.recordSkipped();
            return;
        }

        monitor.workerStarted(workerId, task);
        long start = System.nanoTime();

        try {
            Object result = task.getCallable().call();
            future.complete(result);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            monitor.recordCompleted(workerId, task, elapsedMs);
        } catch (Exception e) {
            future.completeExceptionally(e);
            monitor.recordFailed(workerId, task, e);
        }
    }

    // ── Lifecycle control ────────────────────────────────────────────────────

    /** Signal this worker to stop after its current task. Does not interrupt. */
    public void shutdown() {
        shutdown.set(true);
    }

    /** Signal this worker to stop (for dynamic scale-down). Does not interrupt. */
    public void expire() {
        shouldExpire.set(true);
    }

    /** Hard stop — interrupt the thread mid-task. */
    public void interruptNow() {
        shutdown.set(true);
        Thread t = thread;
        if (t != null) t.interrupt();
    }

    public boolean isShutdown()    { return shutdown.get(); }
    public boolean isExpired()     { return shouldExpire.get(); }
    public int     getWorkerId()   { return workerId; }
    public String  getWorkerName() { return name; }

    @Override
    public String toString() {
        return "WorkerThread[id=" + workerId + ", shutdown=" + shutdown + "]";
    }
}
