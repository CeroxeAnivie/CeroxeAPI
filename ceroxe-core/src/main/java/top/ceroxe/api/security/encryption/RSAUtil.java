package top.ceroxe.api.security.encryption;

import javax.crypto.Cipher;
import javax.crypto.NoSuchPaddingException;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Objects;

public final class RSAUtil {
    private static final String KEY_ALGORITHM = "RSA";
    private static final String TRANSFORMATION = "RSA/ECB/PKCS1Padding";

    private volatile PublicKey publicKey;
    private volatile PrivateKey privateKey;

    public RSAUtil(int keySize) {
        if (keySize < 1024) {
            throw new IllegalArgumentException("RSA key size must be at least 1024 bits");
        }
        try {
            KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance(KEY_ALGORITHM);
            keyPairGenerator.initialize(keySize);
            KeyPair keyPair = keyPairGenerator.generateKeyPair();
            this.publicKey = keyPair.getPublic();
            this.privateKey = keyPair.getPrivate();
        } catch (NoSuchAlgorithmException e) {
            throw new SecurityException("RSA algorithm not available", e);
        }
    }

    public RSAUtil(PublicKey publicKey, PrivateKey privateKey) {
        setPublicKey(publicKey);
        setPrivateKey(privateKey);
    }

    public byte[] encrypt(byte[] data) {
        if (data == null) {
            throw new IllegalArgumentException("data must not be null");
        }
        return encrypt(data, 0, data.length);
    }

    public byte[] encrypt(byte[] data, int inputOffset, int inputLen) {
        validateArrayRange(data, inputOffset, inputLen);
        try {
            Cipher cipher = newCipher(Cipher.ENCRYPT_MODE, publicKey);
            return cipher.doFinal(data, inputOffset, inputLen);
        } catch (GeneralSecurityException e) {
            throw new SecurityException("RSA encryption failed", e);
        }
    }

    public byte[] decrypt(byte[] data) {
        if (data == null) {
            throw new IllegalArgumentException("data must not be null");
        }
        return decrypt(data, 0, data.length);
    }

    public byte[] decrypt(byte[] data, int inputOffset, int inputLen) {
        validateArrayRange(data, inputOffset, inputLen);
        try {
            Cipher cipher = newCipher(Cipher.DECRYPT_MODE, privateKey);
            return cipher.doFinal(data, inputOffset, inputLen);
        } catch (GeneralSecurityException e) {
            throw new SecurityException("RSA decryption failed", e);
        }
    }

    public PrivateKey getPrivateKey() {
        return privateKey;
    }

    public void setPrivateKey(PrivateKey privateKey) {
        PrivateKey validatedKey = Objects.requireNonNull(privateKey, "privateKey");
        validateKey(Cipher.DECRYPT_MODE, validatedKey);
        this.privateKey = validatedKey;
    }

    public PublicKey getPublicKey() {
        return publicKey;
    }

    public void setPublicKey(PublicKey publicKey) {
        PublicKey validatedKey = Objects.requireNonNull(publicKey, "publicKey");
        validateKey(Cipher.ENCRYPT_MODE, validatedKey);
        this.publicKey = validatedKey;
    }

    private static Cipher newCipher(int mode, java.security.Key key)
            throws NoSuchPaddingException, NoSuchAlgorithmException, InvalidKeyException {
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(mode, key);
        return cipher;
    }

    private static void validateKey(int mode, java.security.Key key) {
        try {
            newCipher(mode, key);
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("Invalid RSA key", e);
        }
    }

    private static void validateArrayRange(byte[] data, int offset, int length) {
        if (data == null) {
            throw new IllegalArgumentException("data must not be null");
        }
        if (offset < 0 || length < 0 || offset > data.length - length) {
            throw new IllegalArgumentException("Invalid offset or length");
        }
    }
}
