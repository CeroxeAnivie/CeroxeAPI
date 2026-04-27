package fun.ceroxe.api.print;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PrinterTest {

    @Test
    void formatsColorOnlyWhenStyleIsNone() {
        assertEquals("\033[31merror\033[0m",
                Printer.getFormatLogString("error", Printer.color.RED, Printer.style.NONE));
    }

    @Test
    void formatsColorAndStyleForSupportedStyles() {
        assertEquals("\033[36;1mok\033[0m",
                Printer.getFormatLogString("ok", Printer.color.GREEN, Printer.style.BOLD));
    }

    @Test
    void rejectsNullContent() {
        assertThrows(NullPointerException.class,
                () -> Printer.getFormatLogString(null, Printer.color.GREEN, Printer.style.NONE));
    }
}
