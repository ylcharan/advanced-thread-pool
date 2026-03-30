package com.threadpool.queue;

import com.threadpool.core.Task;
import com.threadpool.core.TaskQueue;

import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Priority-based task queue backed by {@link PriorityBlockingQueue}.
 *
 * <p>Tasks are ordered by {@link Task#compareTo}: higher-priority tasks
 * (priority 10 > 1) run first. When two tasks share the same priority,
 * the one submitted earlier (lower task ID) runs first — preserving FIFO
 * order within a priority level.
 *
 * <p>This queue is unbounded; it will grow dynamically. Worker threads never
 * block on {@code offer()}, only on {@code poll()}.
 */
public class PriorityTaskQueue implements TaskQueue {

    private static final int INITIAL_CAPACITY = 16;

    private final PriorityBlockingQueue<Task<?>> queue;

    public PriorityTaskQueue() {
        // Task.compareTo defines the ordering — see its Javadoc.
        this.queue = new PriorityBlockingQueue<>(INITIAL_CAPACITY);
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
        return "PriorityTaskQueue[size=" + queue.size() + "]";
    }
}
