package top.ceroxe.api.thread;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskManagerTest {

    @Test
    void startCollectsTaskFailuresWithoutStoppingOtherTasks() {
        AtomicBoolean completed = new AtomicBoolean(false);

        try (TaskManager manager = new TaskManager(
                () -> {
                    throw new IllegalStateException("boom");
                },
                () -> completed.set(true)
        )) {
            List<Throwable> errors = manager.start();

            assertEquals(1, errors.size());
            assertTrue(completed.get());
        }
    }

    @Test
    void asyncCallbackReportsCompletion() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean callbackCalled = new AtomicBoolean(false);

        try (TaskManager manager = new TaskManager(() -> {
        })) {
            manager.startAsyncWithCallback(result -> {
                callbackCalled.set(!result.hasErrors());
                latch.countDown();
            });

            assertTrue(latch.await(5, TimeUnit.SECONDS));
            assertTrue(callbackCalled.get());
        }
    }

    @Test
    void timeoutIsReportedAsError() {
        try (TaskManager manager = new TaskManager(() -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        })) {
            List<Throwable> errors = manager.startWithTimeout(Duration.ofMillis(10));

            assertEquals(1, errors.size());
        }
    }

    @Test
    void rejectsInvalidTaskInputs() {
        assertThrows(IllegalArgumentException.class, TaskManager::new);
        assertThrows(IllegalArgumentException.class, () -> new TaskManager((Runnable[]) null));
        assertThrows(NullPointerException.class, () -> new TaskManager((Runnable) null));
    }

    @Test
    void rejectsNegativeTimeout() {
        try (TaskManager manager = new TaskManager(() -> {
        })) {
            assertThrows(IllegalArgumentException.class, () -> manager.startWithTimeout(Duration.ofMillis(-1)));
        }
    }

    @Test
    void runAsyncUsesDaemonPlatformThread() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Thread> taskThread = new AtomicReference<>();

        TaskManager.runAsync(() -> {
            taskThread.set(Thread.currentThread());
            latch.countDown();
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertTrue(taskThread.get().isDaemon());
        assertFalse(isVirtual(taskThread.get()));
    }

    @Test
    void scheduledExecutorUsesPlatformThread() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Thread> taskThread = new AtomicReference<>();

        ScheduledFuture<?> future = TaskManager.getScheduledExecutor().schedule(() -> {
            taskThread.set(Thread.currentThread());
            latch.countDown();
        }, 1, TimeUnit.MILLISECONDS);

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        future.get(5, TimeUnit.SECONDS);
        assertTrue(taskThread.get().isDaemon());
        assertFalse(isVirtual(taskThread.get()));
    }

    private static boolean isVirtual(Thread thread) {
        try {
            return (boolean) Thread.class.getMethod("isVirtual").invoke(thread);
        } catch (ReflectiveOperationException ignored) {
            return false;
        }
    }
}
