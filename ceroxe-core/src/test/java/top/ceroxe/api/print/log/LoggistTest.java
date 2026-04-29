package top.ceroxe.api.print.log;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class LoggistTest {

    @TempDir
    Path tempDir;

    @Test
    void supportsFileNameWithoutParentAndLargeSingleWrite() throws Exception {
        Path logFile = tempDir.resolve("large.log");
        String largeMessage = "x".repeat(80 * 1024);

        try (Loggist loggist = new Loggist(logFile.toString())) {
            loggist.write(largeMessage, true);
        }

        String content = Files.readString(logFile);
        assertTrue(content.contains(largeMessage));
        assertTrue(content.endsWith(System.lineSeparator()));
    }

    @Test
    void asynchronousSayFlushesOnClose() throws Exception {
        Path logFile = tempDir.resolve("async.log");

        try (Loggist loggist = new Loggist(logFile.toString())) {
            loggist.say(new State(LogType.INFO, "test", "async-message"));
        }

        assertTrue(Files.readString(logFile).contains("async-message"));
    }
}
