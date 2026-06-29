package top.ceroxe.api.thread;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Java 17-compatible task manager backed by daemon platform threads.
 *
 * <p>This class intentionally lives in {@code ceroxe-core-shared}: it provides the same task
 * orchestration surface as the Java 21 {@code ThreadManager}, but avoids any virtual-thread API
 * so Java 17 runtimes can link and execute it safely. Use {@code ThreadManager} from
 * {@code ceroxe-core} when the application is Java 21+ and specifically wants virtual threads.</p>
 */
public final class TaskManager implements AutoCloseable {
    private static final int CPU_COUNT = Math.max(1, Runtime.getRuntime().availableProcessors());
    private static final int WORKER_COUNT = Math.max(4, CPU_COUNT);

    private static final ExecutorService SHARED_EXECUTOR = Executors.newCachedThreadPool(
            namedDaemonFactory("ceroxe-task-shared-")
    );
    private static final ScheduledExecutorService SCHEDULER = Executors.newScheduledThreadPool(
            Math.max(2, Math.min(4, CPU_COUNT)),
            namedDaemonFactory("ceroxe-task-scheduler-")
    );

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            SHARED_EXECUTOR.shutdownNow();
            SCHEDULER.shutdownNow();
        }, "ceroxe-task-shutdown"));
    }

    private final List<Runnable> tasks;
    private final ExecutorService executor;

    public TaskManager(Runnable... tasks) {
        this(createExecutor(), tasks);
    }

    public TaskManager(List<Runnable> tasks) {
        this(createExecutor(), tasks);
    }

    private TaskManager(ExecutorService executor, Runnable... tasks) {
        this(executor, tasks == null ? null : List.of(tasks));
    }

    private TaskManager(ExecutorService executor, List<Runnable> tasks) {
        this.executor = Objects.requireNonNull(executor, "executor");
        if (tasks == null || tasks.isEmpty()) {
            throw new IllegalArgumentException("Tasks cannot be null or empty");
        }
        if (tasks.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Tasks cannot contain null elements");
        }
        this.tasks = List.copyOf(tasks);
    }

    /**
     * Returns the shared platform-thread scheduler for periodic or delayed tasks.
     */
    public static ScheduledExecutorService getScheduledExecutor() {
        return SCHEDULER;
    }

    /**
     * Runs a single task on the shared daemon platform-thread executor.
     */
    public static void runAsync(Runnable task) {
        SHARED_EXECUTOR.execute(Objects.requireNonNull(task, "task"));
    }

    public List<Throwable> start() {
        return startWithTimeout(null);
    }

    public List<Throwable> startWithTimeout(Duration timeout) {
        validateTimeout(timeout);

        CountDownLatch latch = new CountDownLatch(tasks.size());
        List<Throwable> exceptions = Collections.synchronizedList(new ArrayList<>());

        for (Runnable task : tasks) {
            executor.execute(() -> {
                try {
                    task.run();
                } catch (Throwable throwable) {
                    exceptions.add(throwable);
                } finally {
                    latch.countDown();
                }
            });
        }

        awaitCompletion(latch, timeout, exceptions);
        return List.copyOf(exceptions);
    }

    public void startAsync() {
        tasks.forEach(executor::execute);
    }

    public void startAsyncWithCallback(Consumer<TaskResult> callback) {
        Objects.requireNonNull(callback, "callback");

        CompletableFuture<?>[] futures = new CompletableFuture<?>[tasks.size()];
        for (int i = 0; i < tasks.size(); i++) {
            futures[i] = CompletableFuture.runAsync(tasks.get(i), executor);
        }

        CompletableFuture.allOf(futures).whenComplete((unused, failure) -> {
            List<Throwable> exceptions = new ArrayList<>();
            for (CompletableFuture<?> future : futures) {
                collectFutureFailure(future, exceptions);
            }
            callback.accept(new TaskResult(Collections.unmodifiableList(exceptions)));
        });
    }

    @Override
    public void close() {
        if (executor != SHARED_EXECUTOR) {
            executor.shutdownNow();
        }
    }

    private static ExecutorService createExecutor() {
        return Executors.newFixedThreadPool(WORKER_COUNT, namedDaemonFactory("ceroxe-task-worker-"));
    }

    private static ThreadFactory namedDaemonFactory(String prefix) {
        AtomicInteger threadId = new AtomicInteger(1);
        return task -> {
            Thread thread = new Thread(task, prefix + threadId.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
    }

    private static void validateTimeout(Duration timeout) {
        if (timeout != null && timeout.isNegative()) {
            throw new IllegalArgumentException("Timeout cannot be negative");
        }
    }

    private static void awaitCompletion(
            CountDownLatch latch,
            Duration timeout,
            List<Throwable> exceptions
    ) {
        try {
            if (timeout == null) {
                latch.await();
            } else if (!latch.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                exceptions.add(new TimeoutException("Tasks did not complete within " + timeout));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Thread interrupted while waiting for tasks", e);
        }
    }

    private static void collectFutureFailure(CompletableFuture<?> future, List<Throwable> exceptions) {
        if (!future.isCompletedExceptionally()) {
            return;
        }
        try {
            future.join();
        } catch (CompletionException e) {
            exceptions.add(e.getCause());
        } catch (CancellationException e) {
            exceptions.add(e);
        }
    }

    public record TaskResult(List<Throwable> exceptions) {
        public TaskResult {
            exceptions = List.copyOf(Objects.requireNonNull(exceptions, "exceptions"));
        }

        public boolean hasErrors() {
            return !exceptions.isEmpty();
        }
    }
}
