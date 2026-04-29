package top.ceroxe.api.security.encryption;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CryptoUtilTest {

    @Test
    void aesEncryptDecryptRoundTripsBytesAndBase64Text() {
        AESUtil aes = new AESUtil(256);
        byte[] plain = new byte[]{0, 1, 2, 3, 4, -1};

        byte[] encrypted = aes.encrypt(plain);
        assertArrayEquals(plain, aes.decrypt(encrypted));

        String text = "hello-\u0004-\uD83D\uDE00";
        String encryptedText = aes.encryptToBase64(text);
        assertNotEquals(text, encryptedText);
        assertArrayEquals(text.getBytes(StandardCharsets.UTF_8),
                aes.decryptFromBase64(encryptedText).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void aesKeyBytesAreDefensiveCopies() {
        AESUtil aes = new AESUtil(128);
        byte[] keyCopy = aes.getKeyBytes();
        byte originalFirstByte = keyCopy[0];
        keyCopy[0] ^= 0x7F;

        assertArrayEquals(new byte[]{originalFirstByte}, new byte[]{aes.getKeyBytes()[0]});
    }

    @Test
    void aesRejectsTamperedCiphertext() {
        AESUtil aes = new AESUtil(128);
        byte[] encrypted = aes.encrypt(new byte[]{1, 2, 3});
        encrypted[encrypted.length - 1] ^= 1;

        assertThrows(SecurityException.class, () -> aes.decrypt(encrypted));
    }

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
