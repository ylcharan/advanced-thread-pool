package com.threadpool;

import com.threadpool.core.*;
import com.threadpool.monitor.ThreadPoolMonitor;
import com.threadpool.queue.FifoTaskQueue;
import com.threadpool.queue.PriorityTaskQueue;
import com.threadpool.queue.ScheduledTaskQueue;
import com.threadpool.worker.WorkStealingWorker;
import com.threadpool.worker.WorkerThread;

import java.util.Deque;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Advanced Thread Pool + Scheduler — built from scratch.
 *
 * <p>Features:
 * <ul>
 *   <li>FIFO, Priority, and Scheduled (delay-based) task queues</li>
 *   <li>Custom {@link TaskFuture} with blocking {@code get()} and timeout</li>
 *   <li>Work-stealing — idle workers steal tasks from busy peers</li>
 *   <li>Dynamic scaling — auto-grows and shrinks based on queue depth</li>
 *   <li>Graceful shutdown + forced shutdownNow</li>
 *   <li>Live monitoring via {@link com.threadpool.monitor.ThreadPoolMonitor}</li>
 * </ul>
 *
 * <h2>Typical Usage</h2>
 * <pre>{@code
 *   ThreadPoolScheduler pool = new ThreadPoolScheduler.Builder()
 *       .corePoolSize(4)
 *       .maxPoolSize(16)
 *       .queueStrategy(QueueStrategy.PRIORITY)
 *       .keepAliveSeconds(30)
 *       .workStealing(true)
 *       .dynamicScaling(true)
 *       .build();
 *
 *   TaskFuture<String> f = pool.submit(() -> "hello");
 *   System.out.println(f.get()); // blocks until done
 *
 *   pool.schedule(() -> doWork(), 500, TimeUnit.MILLISECONDS);
 *   pool.shutdown();
 *   pool.awaitTermination(10, TimeUnit.SECONDS);
 * }</pre>
 */
public class ThreadPoolScheduler {

    // ── Configuration ────────────────────────────────────────────────────────

    private final int           corePoolSize;
    private final int           maxPoolSize;
    private final QueueStrategy queueStrategy;
    private final long          keepAliveMs;
    private final boolean       workStealingEnabled;
    private final boolean       dynamicScalingEnabled;

    // ── Internal state ───────────────────────────────────────────────────────

    private final TaskQueue              taskQueue;
    private final ThreadPoolMonitor      monitor;
    private final Map<Long, TaskFuture<?>> futureRegistry = new ConcurrentHashMap<>();
    private final java.util.concurrent.CopyOnWriteArrayList<WorkerThread> workers
            = new java.util.concurrent.CopyOnWriteArrayList<>();

    // Work-stealing deque array — indexed by worker ID
    @SuppressWarnings("unchecked")
    private final AtomicReference<Deque<Task<?>>>[] stealDeques;

    private final AtomicInteger workerIdCounter = new AtomicInteger(0);
    private final AtomicBoolean shutdown        = new AtomicBoolean(false);
    private final AtomicBoolean terminated      = new AtomicBoolean(false);

    private final Object terminationLock = new Object();

    private DynamicScaler dynamicScaler;
    private Thread        scalerThread;

    // ── Private constructor — use Builder ────────────────────────────────────

    private ThreadPoolScheduler(Builder builder) {
        this.corePoolSize          = builder.corePoolSize;
        this.maxPoolSize           = builder.maxPoolSize;
        this.queueStrategy         = builder.queueStrategy;
        this.keepAliveMs           = builder.keepAliveMs;
        this.workStealingEnabled   = builder.workStealing;
        this.dynamicScalingEnabled = builder.dynamicScaling;

        this.monitor = new ThreadPoolMonitor();

        // Allocate per-worker deque slots (only used when work-stealing is on)
        this.stealDeques = new AtomicReference[maxPoolSize];
        for (int i = 0; i < maxPoolSize; i++) {
            stealDeques[i] = new AtomicReference<>(null);
        }

        // Create the task queue according to strategy
        this.taskQueue = switch (queueStrategy) {
            case FIFO      -> new FifoTaskQueue();
            case PRIORITY  -> new PriorityTaskQueue();
            case SCHEDULED -> new ScheduledTaskQueue();
        };

        // Start core workers
        for (int i = 0; i < corePoolSize; i++) {
            startWorker();
        }

        // Start the dynamic scaler if requested
        if (dynamicScalingEnabled) {
            dynamicScaler = new DynamicScaler(
                    taskQueue, monitor, workers,
                    this::startWorker,
                    corePoolSize, maxPoolSize,
                    /* queueDepthThreshold= */ corePoolSize * 2,
                    keepAliveMs
            );
            scalerThread = new Thread(dynamicScaler, "pool-dynamic-scaler");
            scalerThread.setDaemon(true);
            scalerThread.start();
        }
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Submit a task with normal priority (5) and no delay.
     */
    public <T> TaskFuture<T> submit(Callable<T> callable) {
        return submit(callable, Task.PRIORITY_NORMAL);
    }

    /**
     * Submit a Runnable (result will be {@code null}).
     */
    public TaskFuture<Void> submit(Runnable runnable) {
        return submit(() -> { runnable.run(); return null; }, Task.PRIORITY_NORMAL);
    }

    /**
     * Submit a task with explicit priority (1–10).
     */
    public <T> TaskFuture<T> submit(Callable<T> callable, int priority) {
        Task<T> task = new Task<>(callable, priority);
        return enqueue(task);
    }

    /**
     * Submit a named task (useful for monitoring/debugging).
     */
    public <T> TaskFuture<T> submit(Callable<T> callable, int priority, String name) {
        Task<T> task = new Task<>(callable, priority, 0L, name);
        return enqueue(task);
    }

    /**
     * Schedule a task to run after the given delay.
     * The pool must be using {@link QueueStrategy#SCHEDULED} for delays to be honoured;
     * with FIFO/PRIORITY the delay is stored but not enforced.
     */
    public <T> TaskFuture<T> schedule(Callable<T> callable, long delay, TimeUnit unit) {
        long delayNanos = unit.toNanos(delay);
        Task<T> task = new Task<>(callable, Task.PRIORITY_NORMAL, delayNanos, "scheduled-task");
        return enqueue(task);
    }

    /**
     * Schedule a Runnable with a delay.
     */
    public TaskFuture<Void> schedule(Runnable runnable, long delay, TimeUnit unit) {
        return schedule(() -> { runnable.run(); return null; }, delay, unit);
    }

    // ── Shutdown ─────────────────────────────────────────────────────────────

    /**
     * Initiates graceful shutdown: no new tasks accepted; queued tasks finish.
     */
    public void shutdown() {
        shutdown.set(true);
        if (dynamicScaler != null) dynamicScaler.stop();
        workers.forEach(WorkerThread::shutdown);
    }

    /**
     * Immediate shutdown: workers are interrupted, pending tasks are abandoned.
     */
    public void shutdownNow() {
        shutdown.set(true);
        if (dynamicScaler != null) dynamicScaler.stop();
        futureRegistry.clear();
        workers.forEach(WorkerThread::interruptNow);
    }

    /**
     * Blocks until all workers have exited or the timeout elapses.
     *
     * @return true if terminated cleanly, false if timed out
     */
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        if (System.nanoTime() >= deadline) return false;
        // Give time for workers to drain
        while (System.nanoTime() < deadline) {
            if (isTerminated()) {
                terminated.set(true);
                synchronized (terminationLock) { terminationLock.notifyAll(); }
                return true;
            }
            TimeUnit.MILLISECONDS.sleep(50);
        }
        return isTerminated();
    }

    public boolean isShutdown()    { return shutdown.get(); }
    public boolean isTerminated()  {
        if (!shutdown.get()) return false;
        return taskQueue.isEmpty() && workers.stream().allMatch(WorkerThread::isShutdown);
    }

    // ── Stats ─────────────────────────────────────────────────────────────────

    public ThreadPoolMonitor getMonitor()  { return monitor; }
    public int  getCurrentPoolSize()       { return workers.size(); }
    public int  getQueueSize()             { return taskQueue.size(); }
    public QueueStrategy getQueueStrategy(){ return queueStrategy; }

    // ── Internal ──────────────────────────────────────────────────────────────

    private <T> TaskFuture<T> enqueue(Task<T> task) {
        if (shutdown.get()) {
            monitor.recordRejected();
            throw new RejectedExecutionException("Thread pool is shut down — cannot accept: " + task);
        }

        TaskFuture<T> future = new TaskFuture<>(task);
        futureRegistry.put(task.getTaskId(), future);
        monitor.recordSubmitted();

        if (!taskQueue.offer(task)) {
            futureRegistry.remove(task.getTaskId());
            monitor.recordRejected();
            throw new RejectedExecutionException("Task queue is full — rejected: " + task);
        }

        monitor.updateQueueDepth(taskQueue.size());
        return future;
    }

    /**
     * Spawn a new worker and add it to the pool. Used by core init and by {@link DynamicScaler}.
     */
    private WorkerThread startWorker() {
        int id = workerIdCounter.getAndIncrement();
        WorkerThread worker;

        if (workStealingEnabled) {
            worker = new WorkStealingWorker(
                    id, taskQueue, futureRegistry, monitor,
                    stealDeques, Math.min(maxPoolSize, workerIdCounter.get()));
        } else {
            worker = new WorkerThread(id, taskQueue, futureRegistry, monitor);
        }

        workers.add(worker);
        Thread t = new Thread(worker, worker.getWorkerName());
        t.setDaemon(true);
        t.start();
        return worker;
    }

    // ── Builder ───────────────────────────────────────────────────────────────

    /**
     * Fluent builder for {@link ThreadPoolScheduler}.
     *
     * <pre>{@code
     *   ThreadPoolScheduler pool = new ThreadPoolScheduler.Builder()
     *       .corePoolSize(4)
     *       .maxPoolSize(16)
     *       .queueStrategy(QueueStrategy.PRIORITY)
     *       .keepAliveSeconds(30)
     *       .workStealing(true)
     *       .dynamicScaling(true)
     *       .build();
     * }</pre>
     */
    public static final class Builder {

        private int           corePoolSize   = Runtime.getRuntime().availableProcessors();
        private int           maxPoolSize    = corePoolSize * 4;
        private QueueStrategy queueStrategy  = QueueStrategy.FIFO;
        private long          keepAliveMs    = 30_000;
        private boolean       workStealing   = false;
        private boolean       dynamicScaling = false;

        public Builder corePoolSize(int n) {
            if (n <= 0) throw new IllegalArgumentException("corePoolSize must be > 0");
            this.corePoolSize = n;
            return this;
        }

        public Builder maxPoolSize(int n) {
            this.maxPoolSize = n;
            return this;
        }

        public Builder queueStrategy(QueueStrategy strategy) {
            this.queueStrategy = strategy;
            return this;
        }

        public Builder keepAliveSeconds(long seconds) {
            this.keepAliveMs = seconds * 1000;
            return this;
        }

        public Builder keepAliveMillis(long ms) {
            this.keepAliveMs = ms;
            return this;
        }

        public Builder workStealing(boolean enabled) {
            this.workStealing = enabled;
            return this;
        }

        public Builder dynamicScaling(boolean enabled) {
            this.dynamicScaling = enabled;
            return this;
        }

        public ThreadPoolScheduler build() {
            if (maxPoolSize < corePoolSize) {
                throw new IllegalArgumentException(
                        "maxPoolSize (" + maxPoolSize + ") < corePoolSize (" + corePoolSize + ")");
            }
            return new ThreadPoolScheduler(this);
        }
    }
}
