package top.ceroxe.api.security.encryption;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RSAUtilTest {

    @Test
    void rsaEncryptDecryptRoundTripsAndRejectsInvalidRanges() {
        RSAUtil rsa = new RSAUtil(2048);
        byte[] data = "rsa-payload".getBytes(StandardCharsets.UTF_8);

        assertArrayEquals(data, rsa.decrypt(rsa.encrypt(data)));
        assertArrayEquals(new byte[]{'s', 'a'}, rsa.decrypt(rsa.encrypt(data, 1, 2)));
        assertThrows(IllegalArgumentException.class, () -> rsa.encrypt(data, -1, 1));
        assertThrows(IllegalArgumentException.class, () -> rsa.decrypt(null));
    }

    @Test
    void rsaRejectsTooSmallKeySize() {
        assertThrows(IllegalArgumentException.class, () -> new RSAUtil(512));
    }
}
