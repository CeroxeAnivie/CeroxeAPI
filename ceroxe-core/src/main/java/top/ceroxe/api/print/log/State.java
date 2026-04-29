package top.ceroxe.api.print.log;

import java.util.Objects;

/**
 * 不可变日志状态记录类
 * 使用Java 21的record实现，自动生成equals, hashCode, toString方法
 */
public record State(LogType type, String subject, String content) {

    // 紧凑构造器，进行参数验证
    public State {
        Objects.requireNonNull(type, "Log type cannot be null");
        Objects.requireNonNull(subject, "Subject cannot be null");
        Objects.requireNonNull(content, "Content cannot be null");
    }

    // 默认值的静态工厂方法
    public static State ofDefault() {
        return new State(LogType.INFO, "defaultSubject", "defaultContent");
    }

    // 创建新实例的方法
    public State withType(LogType type) {
        return new State(type, this.subject, this.content);
    }

    public State withSubject(String subject) {
        return new State(this.type, subject, this.content);
    }

    public State withContent(String content) {
        return new State(this.type, this.subject, content);
    }

    // 兼容原始API的方法
    public LogType getType() {
        return type;
    }

    public String getSubject() {
        return subject;
    }

    public String getContent() {
        return content;
    }
}