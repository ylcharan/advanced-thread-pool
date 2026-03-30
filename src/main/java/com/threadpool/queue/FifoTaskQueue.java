package com.threadpool.queue;

import com.threadpool.core.Task;
import com.threadpool.core.TaskQueue;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * First-In-First-Out task queue backed by {@link LinkedBlockingQueue}.
 *
 * <p>This is the default scheduling strategy: tasks are executed in exactly
 * the order they were submitted, with no regard for priority.
 */
public class FifoTaskQueue implements TaskQueue {

    private final LinkedBlockingQueue<Task<?>> queue;

    /** Unbounded FIFO queue. */
    public FifoTaskQueue() {
        this.queue = new LinkedBlockingQueue<>();
    }

    /** Bounded FIFO queue — submissions block when capacity is reached. */
    public FifoTaskQueue(int capacity) {
        this.queue = new LinkedBlockingQueue<>(capacity);
    }

    @Override
    public boolean offer(Task<?> task) {
        return queue.offer(task);
    }

    @Override
    public Task<?> poll(long timeout, TimeUnit unit) throws InterruptedException {
        return queue.poll(timeout, unit);
    }

    @Override
    public Task<?> poll() {
        return queue.poll();
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
        return "FifoTaskQueue[size=" + queue.size() + "]";
    }
}
