package top.ceroxe.api.utils;

import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * General interactive console with support for colored logs, file persistence, and command registration.
 * New feature: Support for registering default commands (when name is null or empty).
 * New feature: `execute` method for programmatic, blocking command execution with output capture.
 */
public class MyConsole {

    // ANSI color codes
    private static final String ANSI_RESET = "\u001b[0m";
    private static final String ANSI_GREEN = "\u001b[32m";
    private static final String ANSI_YELLOW = "\u001b[33m";
    private static final String ANSI_RED = "\u001b[31m";

    // Log levels
    private static final String LEVEL_INFO = "INFO";
    private static final String LEVEL_WARN = "WARN";
    private static final String LEVEL_ERROR = "ERROR";
    private static final String LEVEL_INPUT = "INPUT"; // Only for log file

    // ✅ New: ThreadLocal buffer for capturing output per-thread
    private static final ThreadLocal<StringBuilder> CAPTURE_BUFFER = new ThreadLocal<>();
    // File name format
    private static final DateTimeFormatter LOG_FILE_NAME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
                    .withZone(ZoneId.systemDefault());
    // Unified timestamp format
    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
            .withZone(ZoneId.systemDefault());
    private final Terminal terminal;
    private final LineReader lineReader;
    private final Map<String, CommandMeta> commands = new ConcurrentHashMap<>();
    private final boolean isAnsiSupported;
    private final Path logFile;
    private final ReentrantLock fileWriteLock = new ReentrantLock();
    public boolean printWelcome = false;
    private CommandMeta defaultCommand = null;
    private volatile boolean running = true;
    private Runnable shutdownHook = null;

    public MyConsole(String appName) throws IOException {
        this.terminal = TerminalBuilder.builder()
                .system(true)
                .jna(true)
                .build();

        this.isAnsiSupported = !Terminal.TYPE_DUMB.equals(terminal.getType());

        Path logDirectory = Path.of("logs");
        Files.createDirectories(logDirectory);
        String fileName = "console-" + LOG_FILE_NAME_FORMATTER.format(Instant.now()) + ".log";
        this.logFile = logDirectory.resolve(fileName);

        this.lineReader = LineReaderBuilder.builder()
                .terminal(terminal)
                .appName(appName != null ? appName : "Console")
                .build();

        registerCommand("help", "Show all available commands", args -> printHelp());
        registerCommand("exit", "Exit console", args -> shutdown());
    }

    public void registerCommand(String name, String description, Consumer<List<String>> executor) {
        if (executor == null) {
            throw new IllegalArgumentException("Command executor cannot be null");
        }

        if (name == null || name.trim().isEmpty()) {
            this.defaultCommand = new CommandMeta("(default)", description, executor);
            return;
        }

        String normalizedName = name.toLowerCase().trim();
        if (normalizedName.isEmpty()) {
            throw new IllegalArgumentException("Command name cannot be empty");
        }
        commands.put(normalizedName, new CommandMeta(name.trim(), description, executor));
    }

    public void setShutdownHook(Runnable hook) {
        this.shutdownHook = hook;
    }

    public void shutdown() {
        running = false;
        if (shutdownHook != null) {
            try {
                log("Console", "Executing shutdown hook...");
                shutdownHook.run();
            } catch (Exception e) {
                error("Console", "Error while executing shutdown hook", e);
            }
        }
        try {
            terminal.close();
        } catch (IOException ignored) {
        }
    }

    // --- Log API with source parameter ---

    public void log(String source, String message) {
        String consoleMsg = formatConsoleMessage(LEVEL_INFO, ANSI_GREEN, source, message);
        String logMsg = formatLogMessage(LEVEL_INFO, source, message); // Plain text for file/capture

        safePrintToConsole(consoleMsg);
        appendToLogFile(LEVEL_INFO, source, logMsg);

        // ✅ New: Capture output if buffer is present for the current thread
        StringBuilder captureBuffer = CAPTURE_BUFFER.get();
        if (captureBuffer != null) {
            captureBuffer.append(logMsg).append(System.lineSeparator());
        }
    }

    public void warn(String source, String message) {
        String consoleMsg = formatConsoleMessage(LEVEL_WARN, ANSI_YELLOW, source, message);
        String logMsg = formatLogMessage(LEVEL_WARN, source, message);

        safePrintToConsole(consoleMsg);
        appendToLogFile(LEVEL_WARN, source, logMsg);

        StringBuilder captureBuffer = CAPTURE_BUFFER.get();
        if (captureBuffer != null) {
            captureBuffer.append(logMsg).append(System.lineSeparator());
        }
    }

    public void error(String source, String message) {
        String consoleMsg = formatConsoleMessage(LEVEL_ERROR, ANSI_RED, source, message);
        String logMsg = formatLogMessage(LEVEL_ERROR, source, message);

        safePrintToConsole(consoleMsg);
        appendToLogFile(LEVEL_ERROR, source, logMsg);

        StringBuilder captureBuffer = CAPTURE_BUFFER.get();
        if (captureBuffer != null) {
            captureBuffer.append(logMsg).append(System.lineSeparator());
        }
    }

    public void error(String source, String message, Throwable throwable) {
        StringWriter stringWriter = new StringWriter();
        PrintWriter printWriter = new PrintWriter(stringWriter);
        printWriter.println(message);
        throwable.printStackTrace(printWriter);
        printWriter.flush();

        String fullMessage = stringWriter.toString();
        String briefMessage = message + ": " + throwable.getMessage();

        String consoleMsg = formatConsoleMessage(LEVEL_ERROR, ANSI_RED, source, briefMessage);
        safePrintToConsole(consoleMsg);

        // For file and capture, we use the full stack trace
        String logMsg = formatLogMessage(LEVEL_ERROR, source, fullMessage);
        appendToLogFile(LEVEL_ERROR, source, logMsg);

        StringBuilder captureBuffer = CAPTURE_BUFFER.get();
        if (captureBuffer != null) {
            captureBuffer.append(logMsg).append(System.lineSeparator());
        }
    }

    /**
     * Start the console main loop (runs in background thread).
     */
    public void start() {
        Thread consoleThread = new Thread(() -> {
            try {
                if (printWelcome) {
                    log("Console", "Console started. Type 'help' to see available commands, 'exit' to quit.");
                }

                while (running) {
                    String input = lineReader.readLine("> ");
                    if (input == null) break;

                    processInput(input);
                }
            } catch (Exception e) {
                error("System", "Unexpected exception in console", e);
            } finally {
                try {
                    terminal.close();
                } catch (IOException ignored) {
                }
            }
        }, "Console-Thread");

        consoleThread.setDaemon(false);
        consoleThread.start();
    }

    /**
     * ✅ New: Execute a command programmatically.
     * This method is thread-safe and blocks until the command execution is complete.
     * It simulates user input by printing "> command" to the console and returns the
     * output generated by the command as a string.
     *
     * @param command The command string to execute.
     * @return The full output of the command, or an empty string if the command is invalid.
     */
    public String execute(String command) {
        if (command == null) {
            return "";
        }

        String trimmedCommand = command.trim();
        if (trimmedCommand.isEmpty()) {
            return "";
        }

        // 1. Simulate user input on the console
        safePrintToConsole("> " + trimmedCommand);

        // 2. Set up a thread-local buffer to capture output
        CAPTURE_BUFFER.set(new StringBuilder());

        try {
            // 3. Process the command. This will block until the command's executor finishes.
            // All log/warn/error calls within the command will be captured.
            processInput(trimmedCommand);
        } finally {
            // 4. Retrieve the captured output, clean up the ThreadLocal, and return it.
            StringBuilder buffer = CAPTURE_BUFFER.get();
            CAPTURE_BUFFER.remove(); // Crucial to prevent memory leaks!
            if (buffer != null) {
                return buffer.toString();
            }
        }
        return ""; // Fallback
    }


    private void processInput(String input) {
        appendToLogFile(LEVEL_INPUT, "User", input);

        String[] tokens = input.split("\\s+");
        String cmdName = tokens[0].toLowerCase();
        List<String> args = Arrays.asList(tokens).subList(1, tokens.length);

        CommandMeta cmd = commands.get(cmdName);
        if (cmd != null) {
            try {
                cmd.executor.accept(args);
            } catch (Exception e) {
                error("Command", "Error executing command", e);
            }
        } else if (this.defaultCommand != null) {
            try {
                this.defaultCommand.executor.accept(Arrays.asList(tokens));
            } catch (Exception e) {
                error("DefaultCommand", "Error executing default command", e);
            }
        } else {
            log("Console", "Unknown command: '" + cmdName + "'. Type 'help' to see available commands.");
        }
    }

    // --- Private helper methods ---

    private void safePrintToConsole(String message) {
        lineReader.printAbove(message);
    }

    private String formatConsoleMessage(String level, String ansiColor, String source, String message) {
        String timestamp = TIMESTAMP_FORMATTER.format(LocalDateTime.now());
        if (isAnsiSupported) {
            return String.format("[%s %s%s%s] [%s]: %s",
                    timestamp,
                    ansiColor,
                    level,
                    ANSI_RESET,
                    source,
                    message);
        } else {
            // Fallback to non-ANSI if not supported
            return formatLogMessage(level, source, message);
        }
    }

    /**
     * ✅ New: Helper to create a plain-text log message without ANSI colors.
     * Used for file logging and output capture.
     */
    private String formatLogMessage(String level, String source, String message) {
        String timestamp = TIMESTAMP_FORMATTER.format(Instant.now());
        return String.format("[%s %s] [%s]: %s", timestamp, level, source, message);
    }

    private void printHelp() {
        if (commands.isEmpty() && defaultCommand == null) {
            log("Console", "No commands available.");
        } else {
            StringBuilder helpText = new StringBuilder("Available commands:\n");
            commands.values().stream()
                    .sorted(Comparator.comparing(cmd -> cmd.name))
                    .forEach(cmd ->
                            helpText.append(String.format("  %-15s %s%n", cmd.name, cmd.description))
                    );
            if (defaultCommand != null) {
                helpText.append(String.format("  %-15s %s%n", "(default)", defaultCommand.description));
            }
            log("Console", helpText.toString().trim());
        }
    }

    private void appendToLogFile(String level, String source, String message) {
        fileWriteLock.lock();
        try {
            // The message passed here is already formatted by formatLogMessage
            String logEntry = String.format("%s%n", message);
            Files.writeString(logFile, logEntry, StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("Failed to write to log file: " + e.getMessage());
        } finally {
            fileWriteLock.unlock();
        }
    }

    public File getLogFile() {
        return logFile.toFile();
    }

    private record CommandMeta(String name, String description, Consumer<List<String>> executor) {
    }
}
