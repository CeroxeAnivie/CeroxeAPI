package fun.ceroxe.api.management.objectMgr;

import java.io.File;
import java.io.IOException;
import java.io.ObjectInputFilter;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.SerializablePermission;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

public final class ObjectMgr {
    private static final long MAX_DESERIALIZATION_BYTES = 100L * 1024L * 1024L;
    private static final long MAX_DESERIALIZATION_REFS = 100_000L;
    private static final long MAX_DESERIALIZATION_DEPTH = 64L;

    private ObjectMgr() {
    }

    /**
     * Legacy trusted-file loader kept for source compatibility.
     *
     * <p>Java object deserialization is unsafe for untrusted input by design. This
     * method therefore only adds resource limits and preserves the old "null on
     * failure" contract. New code that reads user-controlled files should call
     * {@link #loadObj(File, Class, ObjectInputFilter)} with an application allowlist.
     */
    @Deprecated(since = "1.0.0")
    public static Object loadObj(File file) {
        try {
            return readObject(file, Object.class, ObjectMgr::resourceLimitFilter);
        } catch (IOException | ClassNotFoundException | RuntimeException e) {
            return null;
        }
    }

    public static <T> T loadObj(File file, Class<T> expectedType) throws IOException, ClassNotFoundException {
        return loadObj(file, expectedType, allowTypes(expectedType));
    }

    public static <T> T loadObj(File file, Class<T> expectedType, ObjectInputFilter filter)
            throws IOException, ClassNotFoundException {
        return readObject(file, expectedType, filter);
    }

    public static boolean writeObject(File file, Object obj) throws IOException {
        Objects.requireNonNull(file, "file");

        Path target = file.toPath();
        Path parent = target.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        Path tempFile = Files.createTempFile(parent, "." + target.getFileName(), ".tmp");
        boolean moved = false;
        try (ObjectOutputStream outputStream = new ObjectOutputStream(Files.newOutputStream(tempFile))) {
            outputStream.writeObject(obj);
            try {
                Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicMoveFailed) {
                Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
            return true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(tempFile);
            }
        }
    }

    public static ObjectInputFilter allowTypes(Class<?> rootType, Class<?>... extraAllowedTypes) {
        Objects.requireNonNull(rootType, "rootType");
        Set<String> extraAllowedNames = new HashSet<>();
        if (extraAllowedTypes != null) {
            for (Class<?> type : extraAllowedTypes) {
                if (type != null) {
                    extraAllowedNames.add(type.getName());
                }
            }
        }

        return info -> {
            ObjectInputFilter.Status resourceStatus = resourceLimitFilter(info);
            if (resourceStatus != ObjectInputFilter.Status.UNDECIDED) {
                return resourceStatus;
            }

            Class<?> serialClass = info.serialClass();
            if (serialClass == null) {
                return ObjectInputFilter.Status.UNDECIDED;
            }

            while (serialClass.isArray()) {
                serialClass = serialClass.getComponentType();
            }

            if (serialClass.isPrimitive()
                    || serialClass.isEnum()
                    || serialClass == String.class
                    || Number.class.isAssignableFrom(serialClass)
                    || Boolean.class == serialClass
                    || Character.class == serialClass
                    || Class.class == serialClass
                    || SerializablePermission.class == serialClass
                    || serialClass.getName().startsWith("java.time.")
                    || serialClass.getName().startsWith("java.util.")
                    || rootType.isAssignableFrom(serialClass)
                    || extraAllowedNames.contains(serialClass.getName())) {
                return ObjectInputFilter.Status.ALLOWED;
            }

            return ObjectInputFilter.Status.REJECTED;
        };
    }

    private static <T> T readObject(File file, Class<T> expectedType, ObjectInputFilter filter)
            throws IOException, ClassNotFoundException {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(expectedType, "expectedType");
        if (!file.exists()) {
            return null;
        }

        try (ObjectInputStream inputStream = new ObjectInputStream(Files.newInputStream(file.toPath()))) {
            if (filter != null) {
                inputStream.setObjectInputFilter(filter);
            }
            Object obj = inputStream.readObject();
            if (obj == null) {
                return null;
            }
            if (!expectedType.isInstance(obj)) {
                throw new IOException("Serialized object type " + obj.getClass().getName()
                        + " is not assignable to " + expectedType.getName());
            }
            return expectedType.cast(obj);
        }
    }

    private static ObjectInputFilter.Status resourceLimitFilter(ObjectInputFilter.FilterInfo info) {
        if (info.depth() > MAX_DESERIALIZATION_DEPTH
                || info.references() > MAX_DESERIALIZATION_REFS
                || info.streamBytes() > MAX_DESERIALIZATION_BYTES
                || info.arrayLength() > MAX_DESERIALIZATION_REFS) {
            return ObjectInputFilter.Status.REJECTED;
        }
        return ObjectInputFilter.Status.UNDECIDED;
    }
}
