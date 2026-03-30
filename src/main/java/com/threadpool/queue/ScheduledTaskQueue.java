package com.threadpool.queue;

import com.threadpool.core.Task;
import com.threadpool.core.TaskQueue;

import java.util.concurrent.DelayQueue;
import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;

/**
 * Delay-based task queue backed by {@link DelayQueue}.
 *
 * <p>Tasks are not made available to workers until their requested delay
 * has elapsed since submission. This enables {@code schedule(task, 500, MILLISECONDS)}
 * semantics: the task sits in the queue but is invisible to {@code poll()} until
 * the delay expires.
 *
 * <p>Internally, each {@link Task} is wrapped in a {@link DelayedTask} adapter
 * that implements the {@link Delayed} contract.
 */
public class ScheduledTaskQueue implements TaskQueue {

    private final DelayQueue<DelayedTask<?>> queue = new DelayQueue<>();

    @Override
    public boolean offer(Task<?> task) {
        return queue.offer(new DelayedTask<>(task));
    }

    @Override
    public Task<?> poll(long timeout, TimeUnit unit) throws InterruptedException {
        DelayedTask<?> dt = queue.poll(timeout, unit);
        return dt == null ? null : dt.task;
    }

    @Override
    public Task<?> poll() {
        DelayedTask<?> dt = queue.poll();
        return dt == null ? null : dt.task;
    }

    @Override
    public int size() {
        return queue.size();
    }

    @Override
    public boolean isEmpty() {
        return queue.isEmpty();
    }

    @Override
    public String toString() {
        return "ScheduledTaskQueue[size=" + queue.size() + "]";
    }

    // ── DelayedTask adapter ──────────────────────────────────────────────────

    /**
     * Wraps a {@link Task} to implement {@link Delayed}, making it compatible
     * with {@link DelayQueue}.
     *
     * <p>The delay is derived from {@link Task#remainingDelayNanos()}, which in turn
     * is computed from the {@code delayNanos} passed when the task was constructed.
     */
    private static final class DelayedTask<T> implements Delayed {

        private final Task<T> task;

        DelayedTask(Task<T> task) {
            this.task = task;
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return unit.convert(task.remainingDelayNanos(), TimeUnit.NANOSECONDS);
        }

        @Override
        public int compareTo(Delayed other) {
            if (other instanceof DelayedTask<?> o) {
                return Long.compare(task.getScheduledAtNanos(), o.task.getScheduledAtNanos());
            }
            return Long.compare(
                    getDelay(TimeUnit.NANOSECONDS),
                    other.getDelay(TimeUnit.NANOSECONDS));
        }
    }
}
