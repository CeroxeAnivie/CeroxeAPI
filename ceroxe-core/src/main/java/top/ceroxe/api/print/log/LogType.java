package top.ceroxe.api.print.log;

import top.ceroxe.api.print.Printer;

/**
 * 日志类型枚举
 * 增加了优先级和显示名称，支持日志级别过滤
 */
public enum LogType {
    INFO("INFO", Printer.color.GREEN, 0),
    WARNING("WARNING", Printer.color.YELLOW, 1),
    ERROR("ERROR", Printer.color.RED, 2);

    private final String displayName;
    private final int colorCode;
    private final int priority;

    LogType(String displayName, int colorCode, int priority) {
        this.displayName = displayName;
        this.colorCode = colorCode;
        this.priority = priority;
    }

    public String getDisplayName() {
        return displayName;
    }

    public int getColorCode() {
        return colorCode;
    }

    public int getPriority() {
        return priority;
    }

    // 日志级别过滤
    public boolean isLoggable(LogType minLevel) {
        return this.priority >= minLevel.priority;
    }
}