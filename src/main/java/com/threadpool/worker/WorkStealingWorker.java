package com.threadpool.worker;

import com.threadpool.core.Task;
import com.threadpool.core.TaskFuture;
import com.threadpool.core.TaskQueue;
import com.threadpool.monitor.ThreadPoolMonitor;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Work-stealing worker thread.
 *
 * <p>Each worker owns a private double-ended queue ({@link Deque}). It pushes
 * tasks onto the <em>tail</em> and pops from the <em>tail</em> (LIFO for cache
 * locality). When idle, it <em>steals</em> from the <em>head</em> of a random
 * peer's deque — this is the classic ForkJoinPool pattern.
 *
 * <p>Flow:
 * <pre>
 *   1. Try own deque tail  (local work, LIFO)
 *   2. Fall back to shared global queue
 *   3. If still idle, steal from a random peer's deque head
 * </pre>
 *
 * <p>The global registry of all worker deques ({@code peerDeques}) is an
 * {@link AtomicReference} array managed by {@link com.threadpool.ThreadPoolScheduler}.
 */
public class WorkStealingWorker extends WorkerThread {

    /** Worker's own private deque — push/pop from tail; victims steal from head. */
    private final Deque<Task<?>> localDeque = new ArrayDeque<>();

    /**
     * Registry of all active worker deques, indexed by worker ID.
     * Workers use this to pick a victim when stealing.
     */
    private final AtomicReference<Deque<Task<?>>>[] peerDeques;

    private final int totalWorkers;

    @SuppressWarnings("unchecked")
    public WorkStealingWorker(int workerId,
                               TaskQueue sharedQueue,
                               Map<Long, TaskFuture<?>> futureRegistry,
                               ThreadPoolMonitor monitor,
                               AtomicReference<Deque<Task<?>>>[] peerDeques,
                               int totalWorkers) {
        super(workerId, sharedQueue, futureRegistry, monitor);
        this.peerDeques   = peerDeques;
        this.totalWorkers = totalWorkers;
        // Register our deque so peers can steal from us
        peerDeques[workerId].set(localDeque);
    }

    // ── Override to implement work-stealing cycle ────────────────────────────

    @Override
    protected void runOneCycle() {
        Task<?> task = null;

        // 1. Try local deque first (pop from tail = LIFO, cache-friendly)
        synchronized (localDeque) {
            task = localDeque.pollLast();
        }

        if (task == null) {
            // 2. Try the shared global queue (non-blocking peek)
            task = sharedQueue.poll();
        }

        if (task == null) {
            // 3. Work stealing: pick a random victim and steal from their head
            task = trySteal();
        }

        if (task == null) {
            // 4. Nothing available — yield briefly to avoid busy-spin
            Thread.yield();
            // Also try blocking poll on shared queue (with short timeout)
            try {
                task = sharedQueue.poll(10, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }

        if (task != null) {
            executeTask(task);
        }
    }

    /**
     * Attempt to steal one task from the head of a random peer's local deque.
     *
     * @return a stolen task, or {@code null} if no peers have work
     */
    private Task<?> trySteal() {
        if (totalWorkers <= 1) return null;

        // Pick a random starting peer to avoid always starving the same victim
        int startIdx = (int) (Math.random() * totalWorkers);
        for (int i = 0; i < totalWorkers; i++) {
            int victimIdx = (startIdx + i) % totalWorkers;
            if (victimIdx == workerId) continue; // don't steal from ourselves

            Deque<Task<?>> victimDeque = peerDeques[victimIdx].get();
            if (victimDeque == null || victimDeque.isEmpty()) continue;

            Task<?> stolen;
            synchronized (victimDeque) {
                stolen = victimDeque.pollFirst(); // steal from head
            }

            if (stolen != null) {
                monitor.recordStolen(workerId, victimIdx, stolen);
                return stolen;
            }
        }
        return null;
    }

    /**
     * Push a task onto this worker's local deque (called externally to
     * pre-populate work before the worker starts running).
     */
    public void pushLocal(Task<?> task) {
        synchronized (localDeque) {
            localDeque.addLast(task);
        }
    }

    public int localQueueSize() {
        synchronized (localDeque) {
            return localDeque.size();
        }
    }
}
