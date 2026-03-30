package com.threadpool.monitor;

import com.threadpool.core.Task;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Collects live metrics about the thread pool.
 *
 * <p>Thread-safe via atomics ({@link LongAdder} for hot-path counters,
 * {@link AtomicLong} for gauges). Designed to be read periodically for
 * dashboard display without blocking workers.
 */
public class ThreadPoolMonitor {

    // ── Counters (monotonically increasing) ──────────────────────────────────

    private final LongAdder completedTasks  = new LongAdder();
    private final LongAdder failedTasks     = new LongAdder();
    private final LongAdder skippedTasks    = new LongAdder(); // cancelled before running
    private final LongAdder stolenTasks     = new LongAdder();
    private final LongAdder rejectedTasks   = new LongAdder();
    private final LongAdder totalSubmitted  = new LongAdder();

    // ── Gauges (point-in-time values) ────────────────────────────────────────

    private final AtomicLong activeWorkers  = new AtomicLong(0);
    private final AtomicLong queueDepth     = new AtomicLong(0);

    // ── Scale events log ─────────────────────────────────────────────────────

    private final LongAdder scaleUpEvents   = new LongAdder();
    private final LongAdder scaleDownEvents = new LongAdder();

    // ── Worker event callbacks ────────────────────────────────────────────────

    public void workerStarted(int workerId, Task<?> task) {
        activeWorkers.incrementAndGet();
    }

    public void workerExited(int workerId) {
        activeWorkers.updateAndGet(v -> Math.max(0, v - 1));
    }

    public void recordCompleted(int workerId, Task<?> task, long elapsedMs) {
        activeWorkers.updateAndGet(v -> Math.max(0, v - 1));
        completedTasks.increment();
    }

    public void recordFailed(int workerId, Task<?> task, Throwable t) {
        activeWorkers.updateAndGet(v -> Math.max(0, v - 1));
        failedTasks.increment();
    }

    public void recordSkipped() {
        skippedTasks.increment();
    }

    public void recordStolen(int thiefId, int victimId, Task<?> task) {
        stolenTasks.increment();
    }

    public void recordRejected() {
        rejectedTasks.increment();
    }

    public void recordSubmitted() {
        totalSubmitted.increment();
    }

    public void recordScaleUp(int newSize) {
        scaleUpEvents.increment();
    }

    public void recordScaleDown(int newSize) {
        scaleDownEvents.increment();
    }

    public void updateQueueDepth(long depth) {
        queueDepth.set(depth);
    }

    // ── Snapshot accessors ────────────────────────────────────────────────────

    public long getCompletedTasks()  { return completedTasks.sum(); }
    public long getFailedTasks()     { return failedTasks.sum(); }
    public long getSkippedTasks()    { return skippedTasks.sum(); }
    public long getStolenTasks()     { return stolenTasks.sum(); }
    public long getRejectedTasks()   { return rejectedTasks.sum(); }
    public long getTotalSubmitted()  { return totalSubmitted.sum(); }
    public long getActiveWorkers()   { return activeWorkers.get(); }
    public long getQueueDepth()      { return queueDepth.get(); }
    public long getScaleUpEvents()   { return scaleUpEvents.sum(); }
    public long getScaleDownEvents() { return scaleDownEvents.sum(); }

    /** Print a one-line dashboard summary. */
    public void printDashboard(int poolSize) {
        System.out.printf(
                "[MONITOR] workers=%d/%d  queued=%d  completed=%d  failed=%d  stolen=%d  " +
                "scaleUp=%d  scaleDown=%d%n",
                activeWorkers.get(), poolSize,
                queueDepth.get(),
                completedTasks.sum(),
                failedTasks.sum(),
                stolenTasks.sum(),
                scaleUpEvents.sum(),
                scaleDownEvents.sum()
        );
    }

    /** Full multi-line snapshot for end-of-run reporting. */
    public String snapshot(int poolSize) {
        return String.format("""
                ╔══════════════════════════════════════╗
                ║       Thread Pool Monitor Snapshot    ║
                ╠══════════════════════════════════════╣
                ║  Active workers   : %6d / %d
                ║  Queue depth      : %6d
                ║  Submitted        : %6d
                ║  Completed        : %6d
                ║  Failed           : %6d
                ║  Cancelled/Skip   : %6d
                ║  Work-stolen      : %6d
                ║  Rejected         : %6d
                ║  Scale-up events  : %6d
                ║  Scale-down events: %6d
                ╚══════════════════════════════════════╝
                """,
                activeWorkers.get(), poolSize,
                queueDepth.get(),
                totalSubmitted.sum(),
                completedTasks.sum(),
                failedTasks.sum(),
                skippedTasks.sum(),
                stolenTasks.sum(),
                rejectedTasks.sum(),
                scaleUpEvents.sum(),
                scaleDownEvents.sum()
        );
    }
}
