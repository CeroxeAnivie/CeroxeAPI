package top.ceroxe.api.utils.config;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

/**
 * 一个健壮、线程安全的简单行配置文件读取器。
 * <p>
 * 此类用于解析格式为 {@code key=value} 的配置文件，支持以 {@code #} 开头的注释。
 * 它是线程安全的，并提供了同步和异步两种加载方式。
 * <p>
 * 配置文件示例:
 * <pre>{@code
 * # This is a comment
 * server.host = localhost
 * server.port=8080 # Inline comment
 * }</pre>
 *
 * @author Optimized for Java 21
 */
public final class LineConfigReader {

    private static final String COMMENT_PREFIX = "#";
    private static final String KEY_VALUE_SEPARATOR = "=";

    private final Path configPath;
    /**
     * 使用 volatile 确保多线程环境下的可见性。
     * 一旦加载，它将是一个不可修改的 Map，从而保证线程安全。
     */
    private volatile Map<String, String> configElements;

    /**
     * 使用 Path 对象代替 File 对象，这是现代 Java I/O (NIO.2) 的推荐做法。
     *
     * @param configFile 要读取的配置文件
     */
    public LineConfigReader(java.io.File configFile) {
        this.configPath = configFile.toPath();
    }

    /**
     * 同步加载配置文件。此方法会阻塞直到文件读取和解析完成。
     *
     * @throws IOException 如果读取文件时发生 I/O 错误
     */
    public void load() throws IOException {
        // 使用 try-with-resources 确保资源自动关闭
        try (BufferedReader reader = Files.newBufferedReader(configPath)) {
            // 使用临时 Map 进行构建，避免加载过程中的线程安全问题
            Map<String, String> tempMap = new java.util.HashMap<>();
            String line;
            while ((line = reader.readLine()) != null) {
                parseLine(line, tempMap);
            }
            // 使用 Collections.unmodifiableMap 创建一个不可修改的视图
            // 并通过 volatile 赋值，原子性地发布给所有线程
            this.configElements = Collections.unmodifiableMap(tempMap);
        }
    }

    /**
     * 异步加载配置文件。此方法立即返回一个 {@link CompletableFuture}，
     * 不会阻塞调用线程。非常适合在虚拟线程中执行 I/O 密集型操作。
     *
     * @return 一个 {@link CompletableFuture}，在加载完成后完成
     */
    public CompletableFuture<Void> loadAsync() {
        // 创建一个 Executor，它的 execute 方法就是直接启动一个新的虚拟线程来执行任务
        Executor virtualThreadExecutor = task -> Thread.ofVirtual().start(task);

        // 将这个 Executor 传递给 CompletableFuture
        return CompletableFuture.runAsync(this::loadUnchecked, virtualThreadExecutor);
    }

    /**
     * 内部使用的无检查异常的加载方法，用于异步调用。
     */
    private void loadUnchecked() {
        try {
            load();
        } catch (IOException e) {
            // 在异步任务中，将受检异常包装为非受检异常抛出
            // CompletableFuture 会捕获并将其作为异常结果
            throw new CompletionException("Failed to load config file asynchronously", e);
        }
    }

    /**
     * 解析单行配置并填充到 map 中。
     */
    private void parseLine(String line, Map<String, String> map) {
        // 1. 使用 String.isBlank() (Java 11+) 更简洁地处理空行和纯空格行
        if (line == null || line.isBlank()) {
            return;
        }

        // 2. 移除注释。使用 limit=2 提高效率，避免不必要的分割
        String contentWithoutComment = line.split(COMMENT_PREFIX, 2)[0].trim();

        // 3. 跳过移除注释后为空的行
        if (contentWithoutComment.isEmpty()) {
            return;
        }

        // 4. 分割键值
        String[] parts = contentWithoutComment.split(KEY_VALUE_SEPARATOR, 2);
        if (parts.length < 2) {
            // 可选：记录警告，这里为了保持简洁而跳过无效行
            // System.err.println("Warning: Invalid config line format, skipping: " + line);
            return;
        }

        // 5. 修剪键和值前后的空格，并存入 map
        String key = parts[0].trim();
        String value = parts[1].trim();
        map.put(key, value);
    }

    /**
     * 检查配置是否已加载。
     *
     * @return 如果配置已成功加载，则返回 {@code true}
     */
    public boolean isLoaded() {
        return configElements != null;
    }

    /**
     * 获取配置项的值。
     *
     * @param key 配置项的键
     * @return 对应的值，如果键不存在或配置未加载，则返回 {@code null}
     */
    public String get(String key) {
        return isLoaded() ? configElements.get(key) : null;
    }

    /**
     * (新增) 使用 {@link Optional} 安全地获取配置项的值。
     * 这是现代 Java API 的推荐做法，可以更优雅地处理值可能不存在的情况。
     *
     * @param key 配置项的键
     * @return 一个包含值的 {@link Optional}，如果键不存在或配置未加载，则返回 {@link Optional#empty()}
     */
    public Optional<String> getOptional(String key) {
        return Optional.ofNullable(isLoaded() ? configElements.get(key) : null);
    }

    /**
     * 检查是否包含指定的键。
     *
     * @param key 要检查的键
     * @return 如果包含该键，则返回 {@code true}
     */
    public boolean containsKey(String key) {
        return isLoaded() && configElements.containsKey(key);
    }

    /**
     * 检查是否包含指定的值。
     *
     * @param value 要检查的值
     * @return 如果包含该值，则返回 {@code true}
     */
    public boolean containsValue(String value) {
        return isLoaded() && configElements.containsValue(value);
    }

    /**
     * 获取所有配置项的不可修改视图。
     * <p>
     * **重要**: 返回的 Map 是不可修改的，任何尝试修改的操作（如 put, remove）
     * 都将抛出 {@link UnsupportedOperationException}。这保证了内部数据的安全性和封装性。
     *
     * @return 包含所有配置项的不可修改 {@link Map}，如果配置未加载，则返回一个空的 Map
     */
    public Map<String, String> getConfigElements() {
        return isLoaded() ? configElements : Collections.emptyMap();
    }

    /**
     * 获取关联的配置文件路径。
     *
     * @return 配置文件的 {@link Path} 对象
     */
    public Path getConfigPath() {
        return configPath;
    }

    @Override
    public String toString() {
        return new StringJoiner(", ", LineConfigReader.class.getSimpleName() + "[", "]")
                .add("configPath=" + configPath)
                .add("isLoaded=" + isLoaded())
                .toString();
    }
}