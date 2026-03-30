package com.threadpool.core;

import java.util.concurrent.TimeUnit;

/**
 * Abstraction over different task scheduling strategies.
 *
 * <p>Implementations include:
 * <ul>
 *   <li>{@code FifoTaskQueue}    — first-in, first-out</li>
 *   <li>{@code PriorityTaskQueue} — highest-priority first</li>
 *   <li>{@code ScheduledTaskQueue} — tasks available after a delay</li>
 * </ul>
 */
public interface TaskQueue {

    /**
     * Offer a task to the queue. Non-blocking; returns false if the queue is full.
     */
    boolean offer(Task<?> task);

    /**
     * Retrieve and remove the next ready task, waiting up to {@code timeout} for one
     * to become available (or ready in the case of delayed queues).
     *
     * @return the next task, or {@code null} if the timeout expires
     */
    Task<?> poll(long timeout, TimeUnit unit) throws InterruptedException;

    /**
     * Retrieve and remove the next ready task immediately, or return {@code null}.
     */
    Task<?> poll();

    /** Current number of tasks in the queue. */
    int size();

    /** True if the queue contains no tasks. */
    boolean isEmpty();
}
