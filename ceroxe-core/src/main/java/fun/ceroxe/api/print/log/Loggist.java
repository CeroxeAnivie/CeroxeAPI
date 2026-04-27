package fun.ceroxe.api.print.log;

import fun.ceroxe.api.print.Printer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

public class Loggist implements AutoCloseable {

    // 使用Java 21的虚拟线程
    private static final ThreadFactory VIRTUAL_THREAD_FACTORY = Thread.ofVirtual().name("loggist-writer-").factory();

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter
            .ofPattern("yyyy.MM.dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());
    // 优化：批处理最大条数
    private static final int BATCH_SIZE = 512;
    // 优化：换行符缓存
    private static final byte[] LINE_SEPARATOR_BYTES = System.lineSeparator().getBytes(StandardCharsets.UTF_8);
    private static final int BUFFER_CAPACITY = 64 * 1024;
    public static int WINDOWS_VERSION = -1;
    // 队列容量保持不变，但在高并发下批处理能更快消费
    private final BlockingQueue<LogEvent> logQueue = new ArrayBlockingQueue<>(10000);
    private final ExecutorService executor = Executors.newSingleThreadExecutor(VIRTUAL_THREAD_FACTORY);
    private final AtomicBoolean isOpen = new AtomicBoolean(false);
    private final AtomicBoolean isShutdown = new AtomicBoolean(false);
    private final Path logFilePath;
    // 优化：扩大缓冲区至 64KB 以减少系统调用
    private final ByteBuffer buffer = ByteBuffer.allocateDirect(BUFFER_CAPACITY);
    private final ReentrantLock fileLock = new ReentrantLock();
    private FileChannel fileChannel;

    public Loggist(String logFilePath) {
        this.logFilePath = Paths.get(Objects.requireNonNull(logFilePath));
        initializeFile();
        startAsyncWriter();
    }

    public Loggist(java.io.File logFile) {
        this(Objects.requireNonNull(logFile).getPath());
    }

    private void initializeFile() {
        try {
            if (!Files.exists(logFilePath)) {
                Path parent = logFilePath.toAbsolutePath().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.createFile(logFilePath);
            }

            fileChannel = FileChannel.open(
                    logFilePath,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND
            );

            isOpen.set(true);
        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize log file", e);
        }
    }

    /**
     * 启动异步写入线程。
     * 优化：实现 Batching (批处理) 写入模式。
     * 虚拟线程在等待锁或IO时会挂起，非常适合这种模式。
     */
    private void startAsyncWriter() {
        executor.submit(() -> {
            // 复用对象以减少GC压力
            List<LogEvent> batchBuffer = new ArrayList<>(BATCH_SIZE);
            StringBuilder stringBuilder = new StringBuilder(1024);

            // 简单的秒级时间戳缓存
            long lastSecond = -1;
            String cachedTimeStr = "";

            try {
                while (!isShutdown.get()) {
                    // 1. 阻塞获取第一个元素 (虚拟线程在此处挂起，不占用OS线程)
                    LogEvent event = logQueue.take();
                    batchBuffer.add(event);

                    // 2. 尝试获取更多元素以进行批处理 (非阻塞)
                    logQueue.drainTo(batchBuffer, BATCH_SIZE - 1);

                    // 3. 批量写入文件
                    fileLock.lock();
                    try {
                        if (!isOpen.get()) break;

                        for (LogEvent e : batchBuffer) {
                            // 时间戳缓存逻辑
                            long currentSecond = e.timestamp().getEpochSecond();
                            if (currentSecond != lastSecond) {
                                lastSecond = currentSecond;
                                cachedTimeStr = FORMATTER.format(e.timestamp());
                            }

                            // 高性能字符串构建，替代 String.format
                            appendLogMessage(stringBuilder, cachedTimeStr, e);

                            byte[] bytes = stringBuilder.toString().getBytes(StandardCharsets.UTF_8);
                            stringBuilder.setLength(0); // 清空builder

                            writeBytesToChannel(bytes);
                        }

                        // 批次处理完后，如果缓冲区有数据，建议刷入通道（但不强制刷盘fsync，保持性能）
                        if (buffer.position() > 0) {
                            flushBufferLocked();
                        }
                    } finally {
                        fileLock.unlock();
                        batchBuffer.clear();
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                System.err.println("Error in async log writer: " + e.getMessage());
            }

            // 关闭时的剩余日志处理
            drainRemainingLogs(batchBuffer, stringBuilder);
        });
    }

    /**
     * 优化：移除 String.format，使用 StringBuilder 手动拼接。
     * 格式保持不变：[%s]  [%s] [%s] %s%n
     */
    private void appendLogMessage(StringBuilder sb, String timeStr, LogEvent event) {
        sb.append('[').append(timeStr).append("]  [")
                .append(event.type().getDisplayName()).append("] [")
                .append(event.subject()).append("] ")
                .append(event.content())
                .append(System.lineSeparator());
    }

    // 处理关闭时队列中剩余的日志
    private void drainRemainingLogs(List<LogEvent> batchBuffer, StringBuilder stringBuilder) {
        batchBuffer.clear();
        logQueue.drainTo(batchBuffer);
        if (batchBuffer.isEmpty()) return;

        fileLock.lock();
        try {
            if (!isOpen.get()) return;
            for (LogEvent e : batchBuffer) {
                // 关闭时不再做时间缓存优化，直接格式化确保准确
                String time = FORMATTER.format(e.timestamp());
                appendLogMessage(stringBuilder, time, e);

                byte[] bytes = stringBuilder.toString().getBytes(StandardCharsets.UTF_8);
                stringBuilder.setLength(0);

                writeBytesToChannel(bytes);
            }
            if (buffer.position() > 0) {
                flushBufferLocked();
            }
        } catch (Exception e) {
            System.err.println("Error writing remaining log on shutdown: " + e.getMessage());
        } finally {
            fileLock.unlock();
        }
    }

    // 优化：不再使用 String.format
    private String formatLogMessage(LogEvent event) {
        String time = FORMATTER.format(event.timestamp());
        StringBuilder sb = new StringBuilder(128);
        sb.append('[').append(time).append("]  [")
                .append(event.type().getDisplayName()).append("] [")
                .append(event.subject()).append("] ")
                .append(event.content())
                .append(System.lineSeparator());
        return sb.toString();
    }

    public void say(State state) {
        Objects.requireNonNull(state);

        LogEvent event = new LogEvent(
                Instant.now(),
                state.type(),
                state.subject(),
                state.content()
        );

        // 异步写入队列
        if (!logQueue.offer(event)) {
            System.err.println("Log queue is full, dropping log message");
        }

        // 控制台输出 - 保持同步以符合"预期执行结果"
        // 优化：根据版本预判，避免内部多次判断
        if (WINDOWS_VERSION == -1 || WINDOWS_VERSION >= 22000) {
            System.out.println(getLogString(state));
        } else {
            System.out.println(getNoColString(state));
        }
    }

    public void sayNoNewLine(State state) {
        Objects.requireNonNull(state);

        LogEvent event = new LogEvent(
                Instant.now(),
                state.type(),
                state.subject(),
                state.content()
        );

        if (!logQueue.offer(event)) {
            System.err.println("Log queue is full, dropping log message");
        }

        if (WINDOWS_VERSION == -1 || WINDOWS_VERSION >= 22000) {
            System.out.print(getLogString(state));
        } else {
            System.out.print(getNoColString(state));
        }
    }

    // 优化：使用 StringBuilder 链式调用减少中间对象
    public String getLogString(State state) {
        String time = FORMATTER.format(Instant.now());
        // 预估容量：颜色代码 + 时间 + 内容
        StringBuilder result = new StringBuilder(128 + state.content().length());

        result.append(Printer.getFormatLogString("[", Printer.color.PURPLE, Printer.style.NONE));
        result.append(Printer.getFormatLogString(time, Printer.color.YELLOW, Printer.style.NONE));
        result.append(Printer.getFormatLogString("]", Printer.color.PURPLE, Printer.style.NONE));
        result.append("  ");

        result.append(Printer.getFormatLogString("[", Printer.color.PURPLE, Printer.style.NONE));
        if (state.type() == LogType.ERROR) {
            result.append(Printer.getFormatLogString("ERROR", Printer.color.RED, Printer.style.NONE));
        } else if (state.type() == LogType.INFO) {
            result.append(Printer.getFormatLogString("INFO", Printer.color.GREEN, Printer.style.NONE));
        } else {
            result.append(Printer.getFormatLogString("WARNING", Printer.color.YELLOW, Printer.style.NONE));
        }
        result.append(Printer.getFormatLogString("]", Printer.color.PURPLE, Printer.style.NONE));

        result.append(" ");
        result.append(Printer.getFormatLogString("[", Printer.color.PURPLE, Printer.style.NONE));
        result.append(Printer.getFormatLogString(state.subject(), Printer.color.ORANGE, Printer.style.NONE));
        result.append(Printer.getFormatLogString("]", Printer.color.PURPLE, Printer.style.NONE));

        result.append(" ");
        result.append(state.content());
        return result.toString();
    }

    // 优化：StringBuilder 链式调用
    public String getNoColString(State state) {
        String time = FORMATTER.format(Instant.now());
        StringBuilder result = new StringBuilder(64 + state.content().length());

        result.append("[").append(time).append("]  [");

        if (state.type() == LogType.ERROR) {
            result.append("ERROR");
        } else if (state.type() == LogType.INFO) {
            result.append("INFO");
        } else {
            result.append("WARNING");
        }

        result.append("] [").append(state.subject()).append("] ")
                .append(state.content());

        return result.toString();
    }

    public void disableColor() {
        WINDOWS_VERSION = 1000;
    }

    public Path getLogFilePath() {
        return logFilePath;
    }

    public java.io.File getLogFile() {
        return logFilePath.toFile();
    }

    public void openWriteChannel() {
        if (!isOpen.get()) {
            initializeFile();
        }
    }

    public void closeWriteChannel() {
        fileLock.lock();
        try {
            if (buffer.position() > 0) {
                flushBufferLocked();
            }
            if (fileChannel != null && fileChannel.isOpen()) {
                fileChannel.close();
            }
            isOpen.set(false);
        } catch (IOException e) {
            throw new RuntimeException("Failed to close log file", e);
        } finally {
            fileLock.unlock();
        }
    }

    public void write(String str, boolean isNewLine) {
        if (!isOpen.get()) return;
        Objects.requireNonNull(str, "str");

        // 优化：避免不必要的字符串拼接和正则
        fileLock.lock();
        try {
            byte[] bytes = str.getBytes(StandardCharsets.UTF_8);

            // 检查空间：字符串 + 可能的换行符
            int required = bytes.length + (isNewLine ? LINE_SEPARATOR_BYTES.length : 0);

            if (required > BUFFER_CAPACITY) {
                writeBytesToChannel(bytes);
                if (isNewLine) {
                    writeBytesToChannel(LINE_SEPARATOR_BYTES);
                }
                return;
            }

            if (buffer.remaining() < required) {
                flushBufferLocked();
            }

            buffer.put(bytes);
            if (isNewLine) {
                buffer.put(LINE_SEPARATOR_BYTES);
            }
        } catch (IOException e) {
            isOpen.set(false);
            throw new RuntimeException("Failed to write log", e);
        } finally {
            fileLock.unlock();
        }
    }

    public boolean isOpenChannel() {
        return isOpen.get();
    }

    private void writeBytesToChannel(byte[] bytes) throws IOException {
        if (bytes.length > BUFFER_CAPACITY) {
            flushBufferLocked();
            ByteBuffer directWrite = ByteBuffer.wrap(bytes);
            while (directWrite.hasRemaining()) {
                fileChannel.write(directWrite);
            }
            return;
        }

        if (buffer.remaining() < bytes.length) {
            flushBufferLocked();
        }
        buffer.put(bytes);
    }

    private void flushBufferLocked() throws IOException {
        buffer.flip();
        while (buffer.hasRemaining()) {
            fileChannel.write(buffer);
        }
        buffer.clear();
    }

    private void gc() {
        closeWriteChannel();
        System.gc();
    }

    @Override
    public void close() {
        if (isShutdown.getAndSet(true)) {
            return;
        }

        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                System.err.println("Log writer did not terminate in 5 seconds.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        closeWriteChannel();
    }

    private record LogEvent(Instant timestamp, LogType type, String subject, String content) {
    }
}
