package top.ceroxe.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class WindowsOperationTest {

    @TempDir
    Path tempDir;

    @Test
    void isOccupiedDoesNotTruncateFile() throws Exception {
        Path file = tempDir.resolve("data.txt");
        byte[] content = "do-not-destroy".getBytes();
        Files.write(file, content);

        WindowsOperation.isOccupied(file.toFile());

        assertArrayEquals(content, Files.readAllBytes(file));
    }

    @Test
    void nullAndBlankInputsAreSafe() {
        assertFalse(WindowsOperation.isRunning(null));
        assertFalse(WindowsOperation.isRunning(" "));
        assertFalse(WindowsOperation.isOccupied(null));
        WindowsOperation.taskKill((String) null);
        WindowsOperation.killUnknownProcess((String[]) null);
    }
}
