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
 * Android-compatible task manager.
 *
 * <p>Android does not provide Java virtual threads, so this artifact deliberately uses bounded
 * platform-thread pools. The API mirrors the desktop ThreadManager where practical while keeping
 * the implementation free of Java 21-only runtime calls.</p>
 */
public final class ThreadManager implements AutoCloseable {
    private static final int CPU_COUNT = Math.max(1, Runtime.getRuntime().availableProcessors());
    private static final ExecutorService SHARED_EXECUTOR = Executors.newFixedThreadPool(
            Math.max(4, CPU_COUNT),
            namedDaemonFactory("ceroxe-core-android-worker-")
    );
    private static final ScheduledExecutorService SCHEDULER = Executors.newScheduledThreadPool(
            Math.max(2, Math.min(4, CPU_COUNT)),
            namedDaemonFactory("ceroxe-core-android-scheduler-")
    );

    private final List<Runnable> tasks;
    private final ExecutorService executor;

    public ThreadManager(Runnable... tasks) {
        this(createExecutor(), tasks);
    }

    public ThreadManager(List<Runnable> tasks) {
        this(createExecutor(), tasks);
    }

    private ThreadManager(ExecutorService executor, Runnable... tasks) {
        this(executor, List.of(tasks));
    }

    private ThreadManager(ExecutorService executor, List<Runnable> tasks) {
        this.executor = Objects.requireNonNull(executor, "executor");
        if (tasks == null || tasks.isEmpty()) {
            throw new IllegalArgumentException("Tasks cannot be null or empty");
        }
        this.tasks = List.copyOf(tasks);
    }

    public static ScheduledExecutorService getScheduledExecutor() {
        return SCHEDULER;
    }

    public static void runAsync(Runnable task) {
        SHARED_EXECUTOR.execute(Objects.requireNonNull(task, "task"));
    }

    public List<Throwable> start() {
        return startWithTimeout(null);
    }

    public List<Throwable> startWithTimeout(Duration timeout) {
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
        return Executors.newFixedThreadPool(Math.max(4, CPU_COUNT), namedDaemonFactory("ceroxe-core-android-task-"));
    }

    private static ThreadFactory namedDaemonFactory(String prefix) {
        AtomicInteger threadId = new AtomicInteger(1);
        return task -> {
            Thread thread = new Thread(task, prefix + threadId.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
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
        public boolean hasErrors() {
            return !exceptions.isEmpty();
        }
    }
}
