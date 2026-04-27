package fun.ceroxe.api;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessContainerTest {

    @Test
    void startsProcessWithArgumentArray() throws Exception {
        Process process;
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            process = ProcessContainer.start("cmd", "/c", "exit", "0");
        } else {
            process = ProcessContainer.start("sh", "-c", "exit 0");
        }

        assertTrue(process.waitFor(5, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue());
    }
}
