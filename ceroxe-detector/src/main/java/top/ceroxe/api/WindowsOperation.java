package top.ceroxe.api;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

public final class WindowsOperation {
    private static final Charset COMMAND_OUTPUT_CHARSET = StandardCharsets.UTF_8;

    private WindowsOperation() {
    }

    public static boolean isRunning(String exeProcName) {
        if (exeProcName == null || exeProcName.isBlank()) {
            return false;
        }
        String expected = exeProcName.trim().toLowerCase(Locale.ROOT);
        for (String processName : getTaskListProcessNames()) {
            String actual = processName.toLowerCase(Locale.ROOT);
            if (actual.equals(expected) || actual.startsWith(expected)) {
                return true;
            }
        }
        return false;
    }

    public static String runGetString(String procName) {
        try {
            Process process = startProcess(splitCommand(procName));
            return readProcessOutput(process);
        } catch (IOException | InterruptedException | IllegalArgumentException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }

    public static Process run(String procName) {
        try {
            return startProcess(splitCommand(procName));
        } catch (IOException | IllegalArgumentException e) {
            return null;
        }
    }

    public static CopyOnWriteArrayList<String> runGetAsLine(String procName) {
        String output = runGetString(procName);
        if (output == null) {
            return null;
        }

        CopyOnWriteArrayList<String> result = new CopyOnWriteArrayList<>();
        for (String line : output.split("\\R")) {
            if (!line.isEmpty()) {
                result.add(line);
            }
        }
        return result;
    }

    public static boolean isOccupied(File file) {
        if (file == null || file.isDirectory() || !file.exists()) {
            return false;
        }

        try (FileChannel channel = FileChannel.open(file.toPath(), StandardOpenOption.WRITE);
             FileLock ignored = channel.tryLock()) {
            return ignored == null;
        } catch (IOException | OverlappingFileLockException e) {
            return true;
        }
    }

    public static void taskKill(String exeName) {
        if (exeName == null || exeName.isBlank()) {
            return;
        }
        runAndWait(List.of("taskkill", "/F", "/IM", exeName.trim()));
    }

    public static void taskKill(String[] exeNames) {
        if (exeNames == null) {
            return;
        }
        for (String exeName : exeNames) {
            taskKill(exeName);
        }
    }

    public static ArrayList<String> confirmProcessName(String preName) {
        ArrayList<String> result = new ArrayList<>();
        if (preName == null || preName.isBlank()) {
            return result;
        }

        String prefix = preName.trim().toLowerCase(Locale.ROOT);
        for (String processName : getTaskListProcessNames()) {
            if (processName.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                result.add(processName);
            }
        }
        return result;
    }

    public static void killUnknownProcess(String[] prefixes) {
        if (prefixes == null) {
            return;
        }
        for (String prefix : prefixes) {
            for (String processName : confirmProcessName(prefix)) {
                taskKill(processName);
            }
        }
    }

    public static void killUnknownProcess(String prefix) {
        killUnknownProcess(new String[]{prefix});
    }

    public static String getCurrentUserName() {
        Map<String, String> environment = System.getenv();
        return environment.get("USERNAME");
    }

    public static String getCurrentComputerName() {
        Map<String, String> environment = System.getenv();
        return environment.get("COMPUTERNAME");
    }

    private static List<String> getTaskListProcessNames() {
        try {
            Process process = startProcess(List.of("tasklist", "/FO", "CSV", "/NH"));
            String output = readProcessOutput(process);
            ArrayList<String> result = new ArrayList<>();
            for (String line : output.split("\\R")) {
                String processName = firstCsvField(line);
                if (!processName.isBlank() && !processName.startsWith("INFO:")) {
                    result.add(processName);
                }
            }
            return result;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return List.of();
        }
    }

    private static Process startProcess(List<String> command) throws IOException {
        if (command == null || command.isEmpty()) {
            throw new IllegalArgumentException("Command cannot be empty");
        }
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        return builder.start();
    }

    private static void runAndWait(List<String> command) {
        try {
            Process process = startProcess(command);
            readProcessOutput(process);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static String readProcessOutput(Process process) throws IOException, InterruptedException {
        Objects.requireNonNull(process, "process");
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), COMMAND_OUTPUT_CHARSET))) {
            String line;
            while ((line = reader.readLine()) != null) {
                result.append(line).append(System.lineSeparator());
            }
        }
        process.waitFor();
        return result.toString();
    }

    private static List<String> splitCommand(String command) {
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("Command cannot be empty");
        }

        ArrayList<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < command.length(); i++) {
            char ch = command.charAt(i);
            if (ch == '"') {
                quoted = !quoted;
                continue;
            }
            if (Character.isWhitespace(ch) && !quoted) {
                if (!current.isEmpty()) {
                    parts.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }
            current.append(ch);
        }
        if (quoted) {
            throw new IllegalArgumentException("Unclosed quote in command");
        }
        if (!current.isEmpty()) {
            parts.add(current.toString());
        }
        if (parts.isEmpty()) {
            throw new IllegalArgumentException("Command cannot be empty");
        }
        return parts;
    }

    private static String firstCsvField(String line) {
        if (line == null || line.isBlank()) {
            return "";
        }
        String trimmed = line.trim();
        if (trimmed.startsWith("\"")) {
            int end = trimmed.indexOf('"', 1);
            return end > 1 ? trimmed.substring(1, end) : "";
        }
        int comma = trimmed.indexOf(',');
        return comma >= 0 ? trimmed.substring(0, comma).trim() : trimmed;
    }
}
