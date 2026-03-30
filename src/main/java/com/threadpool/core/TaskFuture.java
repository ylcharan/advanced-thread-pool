package com.threadpool.core;

import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/**
 * A fully custom {@link Future} implementation — no AbstractFuture, no FutureTask.
 *
 * <p>Uses {@link LockSupport#park}/{@link LockSupport#unpark} for blocking {@code get()}
 * callers, demonstrating low-level thread synchronisation primitives.
 *
 * <p>Internal state machine:
 * <pre>
 *   NEW → RUNNING → COMPLETED
 *             ↘ CANCELLED
 *             ↘ FAILED
 * </pre>
 *
 * @param <T> result type
 */
public class TaskFuture<T> implements Future<T> {

    // ── State constants ──────────────────────────────────────────────────────

    private static final int NEW       = 0;
    private static final int RUNNING   = 1;
    private static final int COMPLETED = 2;
    private static final int CANCELLED = 3;
    private static final int FAILED    = 4;

    private final AtomicInteger state = new AtomicInteger(NEW);

    // Result storage (written by worker, read by callers after unpark)
    private volatile T         result;
    private volatile Throwable exception;

    // Linked list of waiting threads (lock-free stack using CAS)
    private volatile WaitNode waiters;

    // The task this future is associated with (used for cancellation)
    private final Task<T> task;

    public TaskFuture(Task<T> task) {
        this.task = task;
    }

    // ── Worker-facing API (called by WorkerThread) ───────────────────────────

    /** Mark this future as running. Returns false if already cancelled. */
    public boolean markRunning() {
        return state.compareAndSet(NEW, RUNNING);
    }

    /** Called by the worker thread upon successful completion. */
    public void complete(T value) {
        result = value;
        state.set(COMPLETED);
        releaseWaiters();
    }

    /** Called by the worker thread when the callable throws. */
    public void completeExceptionally(Throwable t) {
        exception = t;
        state.set(FAILED);
        releaseWaiters();
    }

    // ── Future interface ─────────────────────────────────────────────────────

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        int s = state.get();
        if (s >= COMPLETED) return false;
        if (!state.compareAndSet(s, CANCELLED)) return false;
        releaseWaiters();
        return true;
    }

    @Override
    public boolean isCancelled() {
        return state.get() == CANCELLED;
    }

    @Override
    public boolean isDone() {
        return state.get() >= COMPLETED;
    }

    /** Blocking get — waits indefinitely until the result is available. */
    @Override
    public T get() throws InterruptedException, ExecutionException {
        int s = state.get();
        if (s < COMPLETED) {
            s = awaitDone(false, 0L);
        }
        return reportResult(s);
    }

    /** Timed get — blocks at most {@code timeout} units. */
    @Override
    public T get(long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        if (unit == null) throw new NullPointerException("unit");
        int s = state.get();
        if (s < COMPLETED) {
            s = awaitDone(true, unit.toNanos(timeout));
        }
        if (s < COMPLETED) throw new TimeoutException();
        return reportResult(s);
    }

    // ── Internal waiting machinery ───────────────────────────────────────────

    /**
     * Spins briefly, then parks the calling thread until done or timed out.
     * Returns the final state.
     */
    private int awaitDone(boolean timed, long nanos) throws InterruptedException {
        final long deadline = timed ? System.nanoTime() + nanos : 0L;
        WaitNode q = null;
        boolean queued = false;

        for (;;) {
            if (Thread.interrupted()) {
                removeWaiter(q);
                throw new InterruptedException();
            }

            int s = state.get();
            if (s >= COMPLETED) {
                if (q != null) q.thread = null;
                return s;
            }

            // Create and enqueue a wait node for the current thread
            if (q == null) {
                q = new WaitNode();
            } else if (!queued) {
                queued = casWaiters(q, q.next = waiters);
            } else if (timed) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0L) {
                    removeWaiter(q);
                    return state.get();
                }
                LockSupport.parkNanos(this, remaining);
            } else {
                LockSupport.park(this);
            }
        }
    }

    private void releaseWaiters() {
        WaitNode q;
        // Drain the wait list and unpark every waiting thread
        do {
            q = waiters;
        } while (!casWaiters(null, q));

        while (q != null) {
            Thread t = q.thread;
            if (t != null) {
                q.thread = null;
                LockSupport.unpark(t);
            }
            q = q.next;
        }
    }

    private void removeWaiter(WaitNode node) {
        if (node == null) return;
        node.thread = null;
        // Retry to remove stale nodes
        retry:
        for (;;) {
            for (WaitNode pred = null, q = waiters, s; q != null; q = s) {
                s = q.next;
                if (q.thread != null) {
                    pred = q;
                } else if (pred != null) {
                    pred.next = s;
                    if (pred.thread == null) continue retry;
                } else if (!casWaiters(s, q)) {
                    continue retry;
                }
            }
            break;
        }
    }

    @SuppressWarnings("unchecked")
    private T reportResult(int s) throws ExecutionException {
        if (s == COMPLETED) return result;
        if (s == CANCELLED) throw new CancellationException();
        throw new ExecutionException(exception);
    }

    private boolean casWaiters(WaitNode update, WaitNode expected) {
        // Simple volatile CAS via synchronized (avoiding VarHandle for Java 17 compat clarity)
        synchronized (this) {
            if (waiters == expected) {
                waiters = update;
                return true;
            }
            return false;
        }
    }

    // ── Wait node for the linked list of blocked callers ────────────────────

    private static final class WaitNode {
        volatile Thread   thread = Thread.currentThread();
        volatile WaitNode next;
    }

    // ── Accessors ────────────────────────────────────────────────────────────

    public Task<T> getTask() { return task; }

    public String stateString() {
        return switch (state.get()) {
            case NEW       -> "NEW";
            case RUNNING   -> "RUNNING";
            case COMPLETED -> "COMPLETED";
            case CANCELLED -> "CANCELLED";
            case FAILED    -> "FAILED";
            default        -> "UNKNOWN";
        };
    }

    @Override
    public String toString() {
        return "TaskFuture[task=" + task + ", state=" + stateString() + "]";
    }
}
