package com.threadpool.core;

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Represents a unit of work submitted to the thread pool.
 *
 * <p>Each task wraps a {@link Callable}, carries a numeric priority (higher = runs first),
 * an optional delay for scheduled execution, and a unique monotonically-increasing ID
 * used as a FIFO tiebreaker when priorities are equal.
 *
 * @param <T> the return type of this task
 */
public class Task<T> implements Comparable<Task<T>> {

    private static final AtomicLong ID_GENERATOR = new AtomicLong(0);

    /** Higher number = higher priority (default 5 = normal). */
    public static final int PRIORITY_LOW    = 1;
    public static final int PRIORITY_NORMAL = 5;
    public static final int PRIORITY_HIGH   = 10;

    private final long       taskId;
    private final Callable<T> callable;
    private final int         priority;
    private final long        scheduledAtNanos; // System.nanoTime() when task should run
    private final String      name;

    /** Convenience constructor — normal priority, no delay. */
    public Task(Callable<T> callable) {
        this(callable, PRIORITY_NORMAL, 0, "task-" + ID_GENERATOR.get());
    }

    /** Priority task — runs before lower-priority tasks. */
    public Task(Callable<T> callable, int priority) {
        this(callable, priority, 0, "task-" + ID_GENERATOR.get());
    }

    /** Scheduled / delayed task. */
    public Task(Callable<T> callable, int priority, long delayNanos, String name) {
        if (priority < 1 || priority > 10) {
            throw new IllegalArgumentException("Priority must be 1–10, got: " + priority);
        }
        this.taskId           = ID_GENERATOR.incrementAndGet();
        this.callable         = callable;
        this.priority         = priority;
        this.scheduledAtNanos = System.nanoTime() + delayNanos;
        this.name             = name.isEmpty() ? "task-" + taskId : name;
    }

    // ── Accessors ────────────────────────────────────────────────────────────

    public long      getTaskId()           { return taskId; }
    public Callable<T> getCallable()       { return callable; }
    public int       getPriority()         { return priority; }
    public long      getScheduledAtNanos() { return scheduledAtNanos; }
    public String    getName()             { return name; }

    /** How many nanoseconds remain until this task is ready (negative = already ready). */
    public long remainingDelayNanos() {
        return scheduledAtNanos - System.nanoTime();
    }

    public boolean isReady() {
        return remainingDelayNanos() <= 0;
    }

    // ── Comparable — higher priority first; FIFO tiebreak on equal priority ──

    @Override
    public int compareTo(Task<T> other) {
        int cmp = Integer.compare(other.priority, this.priority); // reversed: higher first
        if (cmp != 0) return cmp;
        return Long.compare(this.taskId, other.taskId);           // earlier-submitted first
    }

    @Override
    public String toString() {
        return String.format("Task[id=%d, name=%s, priority=%d]", taskId, name, priority);
    }
}
