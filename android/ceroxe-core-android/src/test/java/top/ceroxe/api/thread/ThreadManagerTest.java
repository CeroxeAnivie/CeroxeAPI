package top.ceroxe.api.thread;

import org.junit.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ThreadManagerTest {

    @Test
    public void startCollectsTaskFailuresWithoutStoppingOtherTasks() {
        AtomicBoolean completed = new AtomicBoolean(false);
        ThreadManager manager = new ThreadManager(
                () -> {
                    throw new IllegalStateException("boom");
                },
                () -> completed.set(true)
        );

        List<Throwable> errors = manager.start();

        assertEquals(1, errors.size());
        assertTrue(completed.get());
        manager.close();
    }

    @Test
    public void asyncCallbackReportsCompletion() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean callbackCalled = new AtomicBoolean(false);
        ThreadManager manager = new ThreadManager(() -> {
        });

        manager.startAsyncWithCallback(result -> {
            callbackCalled.set(!result.hasErrors());
            latch.countDown();
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertTrue(callbackCalled.get());
        manager.close();
    }

    @Test
    public void timeoutIsReportedAsError() {
        ThreadManager manager = new ThreadManager(() -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        List<Throwable> errors = manager.startWithTimeout(Duration.ofMillis(10));

        assertEquals(1, errors.size());
        manager.close();
    }
}
