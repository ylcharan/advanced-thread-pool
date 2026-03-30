# 🚀 Advanced Thread Pool + Scheduler

[![Java Version](https://img.shields.io/badge/Java-17+-blue.svg)](https://openjdk.java.net/projects/jdk/17/)
[![Maven](https://img.shields.io/badge/Maven-3.6+-orange.svg)](https://maven.apache.org/)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Tests](https://img.shields.io/badge/Tests-22%20✅-green.svg)]()

A production-grade, **from-scratch** thread pool built in Java 17 — no `ExecutorService`, no `ForkJoinPool`, no shortcuts. Every primitive is hand-rolled to demonstrate deep concurrency understanding.

## 🎯 Problem Solved

Modern applications need sophisticated task execution that goes beyond basic thread pools. This library provides:

- **Priority-based scheduling** for critical tasks
- **Work stealing** for optimal CPU utilization
- **Dynamic scaling** to handle load spikes gracefully
- **Scheduled execution** for delayed tasks
- **Zero-dependency implementation** for learning and customization

Perfect for high-throughput applications, real-time systems, and as a learning tool for advanced concurrency patterns.

## ✨ Key Features

| Feature                | Implementation               | Benefit                                                  |
| ---------------------- | ---------------------------- | -------------------------------------------------------- |
| **📋 FIFO Queue**      | `LinkedBlockingQueue`        | Tasks execute in submission order                        |
| **🎯 Priority Queue**  | `PriorityBlockingQueue`      | Higher-priority tasks preempt lower ones                 |
| **⏰ Scheduled Queue** | `DelayQueue`                 | Tasks execute after specified delays                     |
| **🔮 Custom Future**   | `TaskFuture<T>`              | Blocking/timed gets, cancellation, exception propagation |
| **🤝 Work Stealing**   | Private deques with stealing | Optimal load balancing across cores                      |
| **📈 Dynamic Scaling** | Daemon-based auto-scaling    | Grows under load, shrinks when idle                      |
| **📊 Live Monitoring** | `LongAdder` metrics          | Zero-contention performance tracking                     |

## 🏗️ Architecture

```
┌──────────────────────────────────────────────────────────┐
│                  ThreadPoolScheduler                      │
│                                                          │
│   submit(callable)  /  submit(callable, priority)        │
│   schedule(callable, delay, unit)                        │
│                         │                                │
│              ┌──────────▼──────────┐                    │
│              │      TaskQueue      │  ← Strategy pattern │
│              │  FIFO / PRIORITY /  │                     │
│              │     SCHEDULED       │                     │
│              └──────────┬──────────┘                    │
│                         │  poll()                        │
│     ┌──────────────────────────────────────┐            │
│     │          Worker Thread Pool          │            │
│     │  ┌──────────┐  ┌──────────┐         │            │
│     │  │ Worker 0 │  │ Worker 1 │  . . .  │            │
│     │  │ [deque]  │  │ [deque]  │         │            │
│     │  └────┬─────┘  └────┬─────┘         │            │
│     │       │   steal ◄───┘               │            │
│     └──────────────────────────────────────┘            │
│                                                          │
│   ┌─────────────────┐   ┌──────────────────┐            │
│   │  DynamicScaler  │   │ ThreadPoolMonitor │            │
│   │  (daemon thread)│   │  (LongAdder)      │            │
│   └─────────────────┘   └──────────────────┘            │
└──────────────────────────────────────────────────────────┘
```

## 🚀 Quick Start

### Prerequisites

- Java 17+
- Maven 3.6+

### Build & Run

```bash
# Clone and build
git clone https://github.com/ylcharan/advanced-thread-pool.git
cd advanced-thread-pool

# Compile
mvn compile

# Run tests (22 JUnit 5 tests)
mvn test

# Run the interactive demo
mvn compile exec:java -Dexec.mainClass="com.threadpool.demo.Demo"
```

## 📖 Usage Examples

### Basic FIFO Thread Pool

```java
ThreadPoolScheduler pool = new ThreadPoolScheduler.Builder()
    .corePoolSize(4)
    .maxPoolSize(4)
    .queueStrategy(QueueStrategy.FIFO)
    .build();

TaskFuture<String> future = pool.submit(() -> "Hello, World!");
String result = future.get();           // blocks until done
String result = future.get(1, SECONDS); // timed wait
```

### Priority-Based Scheduling

```java
ThreadPoolScheduler pool = new ThreadPoolScheduler.Builder()
    .corePoolSize(4)
    .maxPoolSize(4)
    .queueStrategy(QueueStrategy.PRIORITY)
    .build();

// Priority 1 (low) → 10 (high); default is 5
pool.submit(() -> doWork(), Task.PRIORITY_LOW);    // 1
pool.submit(() -> doWork(), Task.PRIORITY_NORMAL); // 5
pool.submit(() -> doWork(), Task.PRIORITY_HIGH);   // 10
pool.submit(() -> doWork(), 7);                    // custom
```

### Scheduled Tasks

```java
ThreadPoolScheduler pool = new ThreadPoolScheduler.Builder()
    .corePoolSize(2)
    .maxPoolSize(2)
    .queueStrategy(QueueStrategy.SCHEDULED)
    .build();

// Executes no earlier than 500ms from now
TaskFuture<Void> future = pool.schedule(() -> sendEmail(), 500, TimeUnit.MILLISECONDS);
future.get(2, TimeUnit.SECONDS);
```

### Work Stealing Pool

```java
ThreadPoolScheduler pool = new ThreadPoolScheduler.Builder()
    .corePoolSize(8)
    .maxPoolSize(8)
    .queueStrategy(QueueStrategy.FIFO)
    .workStealing(true)   // Enable work-stealing
    .build();

// Each idle worker steals tasks from busy peers' queues
for (int i = 0; i < 200; i++) {
    pool.submit(() -> heavyCpuWork());
}
```

### Dynamic Scaling Pool

```java
ThreadPoolScheduler pool = new ThreadPoolScheduler.Builder()
    .corePoolSize(2)          // Always-on workers
    .maxPoolSize(16)          // Ceiling during spikes
    .queueStrategy(QueueStrategy.FIFO)
    .dynamicScaling(true)     // Enable auto-scale
    .keepAliveSeconds(30)     // Idle time before shrink
    .build();
```

### Full-Featured Example

```java
ThreadPoolScheduler pool = new ThreadPoolScheduler.Builder()
    .corePoolSize(4)
    .maxPoolSize(16)
    .queueStrategy(QueueStrategy.PRIORITY)
    .keepAliveSeconds(60)
    .workStealing(true)
    .dynamicScaling(true)
    .build();

// Submit with priority
TaskFuture<Integer> future = pool.submit(() -> compute(), Task.PRIORITY_HIGH);

// Timed get — throws TimeoutException if not done in time
try {
    int result = future.get(2, TimeUnit.SECONDS);
} catch (TimeoutException e) {
    future.cancel(false);
}

// Live stats
pool.getMonitor().printDashboard(pool.getCurrentPoolSize());

// Graceful shutdown — drains queue then stops workers
pool.shutdown();
pool.awaitTermination(10, TimeUnit.SECONDS);
```

## 📁 Project Structure

```
src/
├── main/java/com/threadpool/
│   ├── core/
│   │   ├── Task.java               # Callable wrapper: priority, delay, ID
│   │   ├── TaskFuture.java         # Custom Future<T> (LockSupport park/unpark)
│   │   ├── TaskQueue.java          # Queue strategy interface
│   │   └── QueueStrategy.java      # FIFO | PRIORITY | SCHEDULED
│   ├── queue/
│   │   ├── FifoTaskQueue.java      # LinkedBlockingQueue
│   │   ├── PriorityTaskQueue.java  # PriorityBlockingQueue
│   │   └── ScheduledTaskQueue.java # DelayQueue + Delayed adapter
│   ├── worker/
│   │   ├── WorkerThread.java       # Core poll → execute → fulfill loop
│   │   └── WorkStealingWorker.java # Private Deque; LIFO own / FIFO steal
│   ├── monitor/
│   │   └── ThreadPoolMonitor.java  # LongAdder metrics; dashboard output
│   ├── DynamicScaler.java          # Daemon: scale up on load, down on idle
│   ├── ThreadPoolScheduler.java    # Main API — Builder pattern
│   └── demo/
│       └── Demo.java               # Colorized 5-part end-to-end demo
└── test/java/com/threadpool/
    ├── FifoPoolTest.java           # 5 tests
    ├── PriorityPoolTest.java       # 3 tests
    ├── FutureTest.java             # 7 tests
    ├── WorkStealingTest.java       # 3 tests
    └── DynamicScalingTest.java     # 4 tests
```

## 🧠 Design Decisions

### Custom Future Implementation

**Why not `FutureTask`?** `FutureTask` is a black box. `TaskFuture` uses `LockSupport.park`/`unpark` directly with a lock-free linked list of waiting threads — the same pattern used by `AbstractQueuedSynchronizer`. This demonstrates awareness of JVM parking primitives beyond basic `synchronized`/`wait`/`notify`.

### Work Stealing Algorithm

Each `WorkStealingWorker` owns an `ArrayDeque`:

- **Own work**: LIFO (cache-friendly, recently pushed tasks are still hot)
- **Stealing**: FIFO (oldest tasks, minimal contention with victim)

This matches `ForkJoinPool`'s algorithm. With a shared global queue fallback, even non-stealing paths benefit from locality.

### Dynamic Scaling Strategy

The `DynamicScaler` doesn't scale immediately on load. It waits for queue depth to stay above threshold for `SCALE_UP_PATIENCE_MS` (300ms) before adding workers. This prevents thrashing under short bursts — same hysteresis as production auto-scalers.

### Strategy Pattern for Queues

The `TaskQueue` interface allows swapping scheduling strategies without changing worker/pool code. New strategies (round-robin, deadline-aware) require implementing one interface — no pool modifications needed.

## 🧪 Testing

All 22 tests pass ✅

| Test Suite           | Tests | Coverage                                                          |
| -------------------- | ----- | ----------------------------------------------------------------- |
| `FifoPoolTest`       | 5     | Task completion, return values, thread-safety, shutdown rejection |
| `PriorityPoolTest`   | 3     | Priority execution order, FIFO tie-breaking                       |
| `FutureTest`         | 7     | Blocking/timed gets, cancellation, exceptions, concurrent waiters |
| `WorkStealingTest`   | 3     | Task completion, no data races under stealing                     |
| `DynamicScalingTest` | 4     | Scale-up/down behavior, pool size limits                          |

```bash
mvn test
```

## 🎯 Interview Preparation

This project demonstrates answers to common concurrency interview questions:

| Question                                               | Implementation                                        |
| ------------------------------------------------------ | ----------------------------------------------------- |
| "How does `Future.get()` block without busy-spinning?" | `TaskFuture` — `LockSupport.park`, unparked by worker |
| "Implement thread pool from scratch?"                  | `ThreadPoolScheduler` + `WorkerThread`                |
| "What is work stealing?"                               | `WorkStealingWorker` — LIFO own, FIFO steal           |
| "Dynamic pool scaling?"                                | `DynamicScaler` — queue-depth + patience timer        |
| "`shutdown()` vs `shutdownNow()`?"                     | `ThreadPoolScheduler` methods                         |
| "Priority scheduling?"                                 | `PriorityTaskQueue` + `Task.compareTo()`              |

## 📄 License

MIT License — see [LICENSE](LICENSE) for details.

---

**Built with ❤️ for learning advanced concurrency patterns**
