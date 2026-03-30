package com.threadpool.core;

/**
 * Selects which scheduling strategy the thread pool uses for its task queue.
 */
public enum QueueStrategy {

    /**
     * Tasks are executed in the order they were submitted.
     * Backed by {@link java.util.concurrent.LinkedBlockingQueue}.
     */
    FIFO,

    /**
     * Tasks with higher priority (1–10) run before lower-priority tasks.
     * Equal-priority tasks are FIFO-ordered by submission sequence.
     * Backed by {@link java.util.concurrent.PriorityBlockingQueue}.
     */
    PRIORITY,

    /**
     * Tasks execute no earlier than their specified delay after submission.
     * Backed by {@link java.util.concurrent.DelayQueue}.
     */
    SCHEDULED
}
