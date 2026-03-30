package com.threadpool;

import com.threadpool.core.TaskQueue;
import com.threadpool.monitor.ThreadPoolMonitor;
import com.threadpool.worker.WorkerThread;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Background daemon thread responsible for dynamic pool scaling.
 *
 * <p><strong>Scale-up policy:</strong> If the queue depth exceeds
 * {@code queueDepthThreshold} for {@code SCALE_UP_PATIENCE_MS} milliseconds
 * and the pool hasn't reached {@code maxPoolSize}, add one worker.
 *
 * <p><strong>Scale-down policy:</strong> If the queue has been empty for
 * {@code keepAliveMs} milliseconds and the pool is above {@code corePoolSize},
 * expire one idle worker.
 *
 * <p>This scaler runs as a daemon thread so it never prevents JVM shutdown.
 */
public class DynamicScaler implements Runnable {

    private static final Logger LOG                  = Logger.getLogger(DynamicScaler.class.getName());
    private static final long   CHECK_INTERVAL_MS    = 200;
    private static final long   SCALE_UP_PATIENCE_MS = 300;  // must be sustained for this long

    private final TaskQueue             taskQueue;
    private final ThreadPoolMonitor     monitor;
    private final CopyOnWriteArrayList<WorkerThread> workers;
    private final Supplier<WorkerThread> workerFactory; // creates new workers on demand
    private final int    corePoolSize;
    private final int    maxPoolSize;
    private final int    queueDepthThreshold; // queue size that triggers scale-up
    private final long   keepAliveMs;

    private final AtomicBoolean stopped = new AtomicBoolean(false);

    // Timing state
    private long queueBusySince  = -1; // nanoTime when queue first became "busy"
    private long queueEmptySince = -1; // nanoTime when queue first became "empty"

    public DynamicScaler(TaskQueue taskQueue,
                          ThreadPoolMonitor monitor,
                          CopyOnWriteArrayList<WorkerThread> workers,
                          Supplier<WorkerThread> workerFactory,
                          int corePoolSize,
                          int maxPoolSize,
                          int queueDepthThreshold,
                          long keepAliveMs) {
        this.taskQueue           = taskQueue;
        this.monitor             = monitor;
        this.workers             = workers;
        this.workerFactory       = workerFactory;
        this.corePoolSize        = corePoolSize;
        this.maxPoolSize         = maxPoolSize;
        this.queueDepthThreshold = queueDepthThreshold;
        this.keepAliveMs         = keepAliveMs;
    }

    @Override
    public void run() {
        // Note: thread name and daemon flag are set by the caller (ThreadPoolScheduler)
        // before start() — calling setDaemon() here would throw IllegalThreadStateException.
        LOG.fine("[DynamicScaler] started");

        while (!stopped.get()) {
            try {
                TimeUnit.MILLISECONDS.sleep(CHECK_INTERVAL_MS);
                checkAndScale();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        LOG.fine("[DynamicScaler] stopped");
    }

    private void checkAndScale() {
        int  currentSize = countAliveWorkers();
        int  queueSize   = taskQueue.size();
        long now         = System.nanoTime();

        monitor.updateQueueDepth(queueSize);

        // ── Scale-up check ────────────────────────────────────────────────────
        if (queueSize > queueDepthThreshold) {
            if (queueBusySince < 0) {
                queueBusySince = now; // start the patience timer
            } else {
                long busyMs = (now - queueBusySince) / 1_000_000;
                if (busyMs >= SCALE_UP_PATIENCE_MS && currentSize < maxPoolSize) {
                    scaleUp();
                    queueBusySince = -1; // reset after acting
                }
            }
            queueEmptySince = -1;
        } else {
            queueBusySince = -1;

            // ── Scale-down check ──────────────────────────────────────────────
            if (queueSize == 0 && currentSize > corePoolSize) {
                if (queueEmptySince < 0) {
                    queueEmptySince = now;
                } else {
                    long emptyMs = (now - queueEmptySince) / 1_000_000;
                    if (emptyMs >= keepAliveMs) {
                        scaleDown();
                        queueEmptySince = -1;
                    }
                }
            } else {
                queueEmptySince = -1;
            }
        }
    }

    private void scaleUp() {
        WorkerThread newWorker = workerFactory.get();
        workers.add(newWorker);
        Thread t = new Thread(newWorker, newWorker.getWorkerName());
        t.setDaemon(true);
        t.start();
        int newSize = countAliveWorkers();
        monitor.recordScaleUp(newSize);
        System.out.printf("[DynamicScaler] ↑ Scaled UP  → %d workers (queue depth: %d)%n",
                newSize, taskQueue.size());
    }

    private void scaleDown() {
        // Expire the most recently added worker (LIFO removal)
        for (int i = workers.size() - 1; i >= corePoolSize; i--) {
            WorkerThread w = workers.get(i);
            if (!w.isShutdown() && !w.isExpired()) {
                w.expire();
                workers.remove(i);
                int newSize = countAliveWorkers();
                monitor.recordScaleDown(newSize);
                System.out.printf("[DynamicScaler] ↓ Scaled DOWN → %d workers%n", newSize);
                return;
            }
        }
    }

    private int countAliveWorkers() {
        return (int) workers.stream().filter(w -> !w.isShutdown() && !w.isExpired()).count();
    }

    public void stop() {
        stopped.set(true);
    }
}
