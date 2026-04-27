package fun.ceroxe.api.security.encryption;

import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * AES-GCM 加密工具类 (Java 21 高并发优化版)
 * <p>
 * 优化点：
 * 1. 使用 ThreadLocal 复用 Cipher 和 SecureRandom，彻底消除对象创建开销和 synchronized 锁竞争。
 * 2. 保持 AES/GCM/NoPadding 算法，提供数据完整性校验。
 * 3. 零内存拷贝优化：直接在 Cipher 中操作输出数组。
 */
public class AESUtil {
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128; // bits
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    // 核心优化：ThreadLocal 缓存 Cipher 实例，避免重复 getInstance 的高昂开销
    // initialValue 延迟加载，确保每个虚拟线程拥有独立的 Cipher 实例，互不干扰
    private static final ThreadLocal<Cipher> ENCRYPT_CIPHER = ThreadLocal.withInitial(AESUtil::initCipher);
    private static final ThreadLocal<Cipher> DECRYPT_CIPHER = ThreadLocal.withInitial(AESUtil::initCipher);

    // 核心优化：SecureRandom 初始化极其耗时且存在锁竞争，高并发必须使用 ThreadLocal
    private static final ThreadLocal<SecureRandom> THREAD_LOCAL_RANDOM = ThreadLocal.withInitial(SecureRandom::new);

    private final SecretKey key;
    private final byte[] keyBytes;

    public AESUtil(int keySize) {
        try {
            KeyGenerator keyGen = KeyGenerator.getInstance("AES");
            // 使用当前线程的随机源生成 Key，速度极快
            keyGen.init(keySize, THREAD_LOCAL_RANDOM.get());
            this.key = keyGen.generateKey();
            byte[] encodedKey = this.key.getEncoded();
            validateAesKey(encodedKey);
            this.keyBytes = Arrays.copyOf(encodedKey, encodedKey.length);
        } catch (NoSuchAlgorithmException e) {
            throw new SecurityException("AES algorithm not available", e);
        }
    }

    public AESUtil(SecretKey key) {
        if (key == null) {
            throw new IllegalArgumentException("AES key must not be null");
        }
        byte[] encodedKey = key.getEncoded();
        validateAesKey(encodedKey);
        this.key = key;
        this.keyBytes = Arrays.copyOf(encodedKey, encodedKey.length);
    }

    public AESUtil(String encodedKeyString) {
        if (encodedKeyString == null) {
            throw new IllegalArgumentException("Encoded key must not be null");
        }
        byte[] decodedKey = Base64.getDecoder().decode(encodedKeyString);
        validateAesKey(decodedKey);
        this.key = new SecretKeySpec(decodedKey, "AES");
        this.keyBytes = Arrays.copyOf(decodedKey, decodedKey.length);
    }

    private static void validateAesKey(byte[] encodedKey) {
        if (encodedKey == null) {
            throw new IllegalArgumentException("AES key must expose encoded bytes");
        }
        int length = encodedKey.length;
        if (length != 16 && length != 24 && length != 32) {
            throw new IllegalArgumentException("AES key must be 128, 192, or 256 bits");
        }
    }

    private static Cipher initCipher() {
        try {
            return Cipher.getInstance(TRANSFORMATION);
        } catch (NoSuchAlgorithmException | NoSuchPaddingException e) {
            throw new SecurityException("Failed to initialize AES/GCM cipher", e);
        }
    }

    private static void validateArrayBounds(byte[] array, int offset, int length) {
        if (array == null) throw new IllegalArgumentException("Input array must not be null");
        if (offset < 0 || length < 0 || offset + length > array.length) {
            throw new IllegalArgumentException("Invalid offset or length");
        }
    }

    public byte[] encrypt(byte[] plaintext) {
        if (plaintext == null) throw new IllegalArgumentException("Plaintext must not be null");
        return encryptInternal(plaintext, 0, plaintext.length);
    }

    public byte[] encrypt(byte[] data, int inputOffset, int inputLen) {
        validateArrayBounds(data, inputOffset, inputLen);
        return encryptInternal(data, inputOffset, inputLen);
    }

    private byte[] encryptInternal(byte[] data, int offset, int length) {
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            // 从 ThreadLocal 获取随机数生成器，无锁且极速
            THREAD_LOCAL_RANDOM.get().nextBytes(iv);

            Cipher cipher = ENCRYPT_CIPHER.get();
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));

            int outputSize = cipher.getOutputSize(length);
            // 优化：一次性分配所需内存，避免 ByteBuffer 额外开销
            byte[] output = new byte[GCM_IV_LENGTH + outputSize];

            // 1. 复制 IV 到头部
            System.arraycopy(iv, 0, output, 0, GCM_IV_LENGTH);

            // 2. 执行加密直接写入 output 数组 (outputOffset = IV_LENGTH)
            cipher.doFinal(data, offset, length, output, GCM_IV_LENGTH);

            return output;
        } catch (GeneralSecurityException e) {
            throw new SecurityException("Encryption failed", e);
        }
    }

    public byte[] decrypt(byte[] encryptedData) {
        if (encryptedData == null) throw new IllegalArgumentException("Encrypted data must not be null");
        return decryptInternal(encryptedData, 0, encryptedData.length);
    }

    public byte[] decrypt(byte[] data, int inputOffset, int inputLen) {
        validateArrayBounds(data, inputOffset, inputLen);
        return decryptInternal(data, inputOffset, inputLen);
    }

    private byte[] decryptInternal(byte[] data, int offset, int length) {
        if (length <= GCM_IV_LENGTH) {
            throw new IllegalArgumentException("Encrypted data too short");
        }

        try {
            // 从数据中提取 IV 参数
            GCMParameterSpec params = new GCMParameterSpec(GCM_TAG_LENGTH, data, offset, GCM_IV_LENGTH);

            Cipher cipher = DECRYPT_CIPHER.get();
            cipher.init(Cipher.DECRYPT_MODE, key, params);

            // 计算密文偏移量：offset + IV_LENGTH
            // 密文长度：length - IV_LENGTH
            return cipher.doFinal(data, offset + GCM_IV_LENGTH, length - GCM_IV_LENGTH);
        } catch (AEADBadTagException e) {
            throw new SecurityException("Authentication failed - data may be tampered", e);
        } catch (GeneralSecurityException e) {
            throw new SecurityException("Decryption failed", e);
        }
    }

    public byte[] generateIv() {
        byte[] iv = new byte[GCM_IV_LENGTH];
        THREAD_LOCAL_RANDOM.get().nextBytes(iv);
        return iv;
    }

    public String encryptToBase64(String plaintext) {
        if (plaintext == null) throw new IllegalArgumentException("Plaintext must not be null");
        byte[] encrypted = encrypt(plaintext.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(encrypted);
    }

    public String decryptFromBase64(String base64Ciphertext) {
        if (base64Ciphertext == null) throw new IllegalArgumentException("Ciphertext must not be null");
        byte[] encrypted = Base64.getDecoder().decode(base64Ciphertext);
        byte[] decrypted = decrypt(encrypted);
        return new String(decrypted, StandardCharsets.UTF_8);
    }

    public String getEncodedKey() {
        return Base64.getEncoder().encodeToString(keyBytes);
    }

    public byte[] getKeyBytes() {
        return Arrays.copyOf(keyBytes, keyBytes.length);
    }

    public SecretKey getKey() {
        return key;
    }
}
