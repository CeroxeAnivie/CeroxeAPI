package top.ceroxe.api.print;

import java.util.Objects;

public final class Printer {
    private Printer() {
    }

    public static String getFormatLogString(String content, int colour, int type) {
        Objects.requireNonNull(content, "Content cannot be null");

        if (type == style.BOLD || type == style.ITALIC || type == style.UNDERSCORE) {
            return String.format("\033[%d;%dm%s\033[0m", colour, type, content);
        }
        return String.format("\033[%dm%s\033[0m", colour, content);
    }

    public static void print(String content, int colour, int type) {
        System.out.println(getFormatLogString(content, colour, type));
    }

    public static void printNoNewLine(String content, int colour, int type) {
        System.out.print(getFormatLogString(content, colour, type));
    }

    public static final class color {
        public static final int RED = 31;
        public static final int YELLOW = 32;
        public static final int ORANGE = 33;
        public static final int BLUE = 34;
        public static final int PURPLE = 35;
        public static final int GREEN = 36;

        private color() {
        }
    }

    public static final class style {
        public static final int NONE = 0;
        public static final int BOLD = 1;
        public static final int ITALIC = 3;
        public static final int UNDERSCORE = 4;

        private style() {
        }
    }
}
