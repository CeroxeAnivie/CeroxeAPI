package top.ceroxe.api.management.bufferedFile;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SizeCalculatorTest {

    @Test
    void convertsMibAndBytes() {
        assertEquals(2 * 1024 * 1024, SizeCalculator.mibToByte(2.0));
        assertEquals(2.0, SizeCalculator.byteToMib(2 * 1024 * 1024));
    }
}
