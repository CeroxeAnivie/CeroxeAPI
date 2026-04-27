package fun.ceroxe.api;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public final class ProcessContainer {
    private static final int JOB_OBJECT_EXTENDED_LIMIT_INFORMATION_CLASS = 9;
    private static final int JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE = 0x2000;
    private static final int PROCESS_TERMINATE = 0x0001;
    private static final int PROCESS_SET_QUOTA = 0x0100;

    private static final boolean IS_WINDOWS;
    private static final boolean IS_LINUX_OR_MAC;
    private static final MyKernel32 KERNEL32;
    private static final WinNT.HANDLE WINDOWS_JOB_HANDLE;
    private static final Set<ProcessHandle> TRACKED_HANDLES = ConcurrentHashMap.newKeySet();

    static {
        String os = System.getProperty("os.name").toLowerCase();
        IS_WINDOWS = os.contains("win");
        IS_LINUX_OR_MAC = os.contains("nux") || os.contains("mac") || os.contains("nix");

        if (IS_WINDOWS) {
            KERNEL32 = Native.load("kernel32", MyKernel32.class, W32APIOptions.DEFAULT_OPTIONS);
            WINDOWS_JOB_HANDLE = createWindowsJobHandle();
        } else {
            KERNEL32 = null;
            WINDOWS_JOB_HANDLE = null;
        }

        Runtime.getRuntime().addShutdownHook(new Thread(ProcessContainer::destroyTrackedProcesses, "ProcessContainer-Shutdown"));
    }

    private ProcessContainer() {
    }

    public static Process start(String... command) throws IOException {
        if (command == null || command.length == 0) {
            throw new IllegalArgumentException("Command cannot be empty");
        }
        return start(new ProcessBuilder(command));
    }

    public static Process start(ProcessBuilder builder) throws IOException {
        Objects.requireNonNull(builder, "builder");
        if (builder.command().isEmpty()) {
            throw new IllegalArgumentException("Command cannot be empty");
        }

        if (IS_LINUX_OR_MAC) {
            wrapCommandForLinux(builder);
        }

        Process process = builder.start();
        ProcessHandle handle = process.toHandle();
        TRACKED_HANDLES.add(handle);
        handle.onExit().thenRun(() -> TRACKED_HANDLES.remove(handle));

        if (IS_WINDOWS) {
            bindProcessToWindowsJob(process);
        }

        return process;
    }

    private static WinNT.HANDLE createWindowsJobHandle() {
        WinNT.HANDLE handle = KERNEL32.CreateJobObject(null, null);
        if (handle == null) {
            throw new IllegalStateException("Failed to create Windows Job Object, error: " + KERNEL32.GetLastError());
        }

        JOBOBJECT_EXTENDED_LIMIT_INFORMATION limits = new JOBOBJECT_EXTENDED_LIMIT_INFORMATION();
        limits.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
        limits.write();

        boolean configured = KERNEL32.SetInformationJobObject(
                handle,
                JOB_OBJECT_EXTENDED_LIMIT_INFORMATION_CLASS,
                limits,
                limits.size()
        );
        if (!configured) {
            int error = KERNEL32.GetLastError();
            KERNEL32.CloseHandle(handle);
            throw new IllegalStateException("Failed to configure Windows Job Object, error: " + error);
        }

        return handle;
    }

    private static void bindProcessToWindowsJob(Process process) {
        if (KERNEL32 == null || WINDOWS_JOB_HANDLE == null) {
            return;
        }

        int access = PROCESS_TERMINATE | PROCESS_SET_QUOTA;
        WinNT.HANDLE processHandle = KERNEL32.OpenProcess(access, false, (int) process.pid());
        if (processHandle == null) {
            return;
        }

        try {
            KERNEL32.AssignProcessToJobObject(WINDOWS_JOB_HANDLE, processHandle);
        } finally {
            KERNEL32.CloseHandle(processHandle);
        }
    }

    private static void destroyTrackedProcesses() {
        for (ProcessHandle handle : TRACKED_HANDLES) {
            try {
                handle.destroy();
            } catch (RuntimeException ignored) {
            }
        }

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        for (ProcessHandle handle : TRACKED_HANDLES) {
            while (handle.isAlive() && System.nanoTime() < deadline) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            if (handle.isAlive()) {
                try {
                    handle.destroyForcibly();
                } catch (RuntimeException ignored) {
                }
            }
        }

        if (KERNEL32 != null && WINDOWS_JOB_HANDLE != null) {
            KERNEL32.CloseHandle(WINDOWS_JOB_HANDLE);
        }
    }

    private static void wrapCommandForLinux(ProcessBuilder builder) {
        List<String> originalCmd = builder.command();
        if (originalCmd.isEmpty()) {
            return;
        }

        long javaPid = ProcessHandle.current().pid();
        String script =
                "java_pid=" + javaPid + ";\n" +
                        "\"$@\" &\n" +
                        "child_pid=$!;\n" +
                        "(\n" +
                        "  while kill -0 $java_pid 2>/dev/null; do\n" +
                        "    sleep 0.5;\n" +
                        "  done;\n" +
                        "  kill -9 $child_pid 2>/dev/null\n" +
                        ") &\n" +
                        "watcher_pid=$!;\n" +
                        "trap \"kill -TERM $child_pid\" TERM INT;\n" +
                        "wait $child_pid;\n" +
                        "exit_code=$?;\n" +
                        "kill $watcher_pid 2>/dev/null;\n" +
                        "exit $exit_code;";

        List<String> wrappedCmd = new ArrayList<>();
        wrappedCmd.add("/bin/sh");
        wrappedCmd.add("-c");
        wrappedCmd.add(script);
        wrappedCmd.add("_");
        wrappedCmd.addAll(originalCmd);
        builder.command(wrappedCmd);
    }

    public interface MyKernel32 extends StdCallLibrary {
        WinNT.HANDLE CreateJobObject(Pointer lpJobAttributes, String lpName);

        boolean SetInformationJobObject(WinNT.HANDLE hJob, int jobObjectInfoClass,
                                        Structure lpJobObjectInfo, int cbJobObjectInfoLength);

        boolean AssignProcessToJobObject(WinNT.HANDLE hJob, WinNT.HANDLE hProcess);

        WinNT.HANDLE OpenProcess(int dwDesiredAccess, boolean bInheritHandle, int dwProcessId);

        boolean CloseHandle(WinNT.HANDLE hObject);

        int GetLastError();
    }

    @Structure.FieldOrder({"PerProcessUserTimeLimit", "PerJobUserTimeLimit", "LimitFlags",
            "MinimumWorkingSetSize", "MaximumWorkingSetSize", "ActiveProcessLimit",
            "Affinity", "PriorityClass", "SchedulingClass"})
    public static class JOBOBJECT_BASIC_LIMIT_INFORMATION extends Structure {
        public long PerProcessUserTimeLimit;
        public long PerJobUserTimeLimit;
        public int LimitFlags;
        public long MinimumWorkingSetSize;
        public long MaximumWorkingSetSize;
        public int ActiveProcessLimit;
        public long Affinity;
        public int PriorityClass;
        public int SchedulingClass;
    }

    @Structure.FieldOrder({"ReadOperationCount", "WriteOperationCount", "OtherOperationCount",
            "ReadTransferCount", "WriteTransferCount", "OtherTransferCount"})
    public static class IO_COUNTERS extends Structure {
        public long ReadOperationCount;
        public long WriteOperationCount;
        public long OtherOperationCount;
        public long ReadTransferCount;
        public long WriteTransferCount;
        public long OtherTransferCount;
    }

    @Structure.FieldOrder({"BasicLimitInformation", "IoInfo", "ProcessMemoryLimit",
            "JobMemoryLimit", "PeakProcessMemoryUsed", "PeakJobMemoryUsed"})
    public static class JOBOBJECT_EXTENDED_LIMIT_INFORMATION extends Structure {
        public JOBOBJECT_BASIC_LIMIT_INFORMATION BasicLimitInformation = new JOBOBJECT_BASIC_LIMIT_INFORMATION();
        public IO_COUNTERS IoInfo = new IO_COUNTERS();
        public long ProcessMemoryLimit;
        public long JobMemoryLimit;
        public long PeakProcessMemoryUsed;
        public long PeakJobMemoryUsed;
    }
}
