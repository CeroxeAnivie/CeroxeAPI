package top.ceroxe.api.utils;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SleeperTest {

    @Test
    void restoresInterruptedFlagWhenSleepIsInterrupted() throws Exception {
        AtomicBoolean interrupted = new AtomicBoolean(false);
        Thread thread = new Thread(() -> {
            Thread.currentThread().interrupt();
            Sleeper.sleep(1000);
            interrupted.set(Thread.currentThread().isInterrupted());
        });

        thread.start();
        thread.join(2_000);

        assertTrue(interrupted.get());
    }
}
