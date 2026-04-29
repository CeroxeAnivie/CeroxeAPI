package top.ceroxe.api.management.objectMgr;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.Serial;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObjectMgrTest {

    @TempDir
    Path tempDir;

    @Test
    void writeObjectCreatesParentAndTypedLoadRoundTrips() throws Exception {
        File file = tempDir.resolve("nested/value.bin").toFile();
        TestPayload payload = new TestPayload("ceroxe", 42);

        assertTrue(ObjectMgr.writeObject(file, payload));
        assertEquals(payload, ObjectMgr.loadObj(file, TestPayload.class));
    }

    @Test
    void legacyLoadKeepsNullOnMissingFileContract() {
        assertNull(ObjectMgr.loadObj(tempDir.resolve("missing.bin").toFile()));
    }

    @Test
    void typedLoadRejectsUnexpectedRootType() throws Exception {
        File file = tempDir.resolve("value.bin").toFile();
        ObjectMgr.writeObject(file, new TestPayload("ceroxe", 7));

        assertThrows(IOException.class, () -> ObjectMgr.loadObj(file, String.class));
    }

    @Test
    void failedWriteDoesNotLeaveTemporaryFilesInTargetDirectory() throws Exception {
        File file = tempDir.resolve("value.bin").toFile();
        assertThrows(IOException.class, () -> ObjectMgr.writeObject(file, new NonSerializablePayload()));

        try (var files = Files.list(tempDir)) {
            assertEquals(0L, files.count());
        }
    }

    private record TestPayload(String name, int value) implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
    }

    private static final class NonSerializablePayload {
    }
}
