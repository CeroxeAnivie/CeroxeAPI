package top.ceroxe.api.net;

import top.ceroxe.api.security.encryption.AESUtil;

import javax.crypto.KeyAgreement;
import javax.crypto.spec.SecretKeySpec;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 工业级安全套接字实现。
 *
 * <p>1.0.0 起，业务帧采用显式帧类型而不是复用 payload 值承载 EOF。
 * 这样才能保证二进制转发对单字节 0x04 不再产生协议级冲突。
 */
public class SecureSocket implements Closeable {
    private static final int BUFFER_SIZE = 32 * 1024;
    private static final String KEY_EXCHANGE_ALGO = "X25519";
    private static final int INT_PAYLOAD_LENGTH = Integer.BYTES;

    private static final byte FRAME_TYPE_STRING = 0x01;
    private static final byte FRAME_TYPE_BYTES = 0x02;
    private static final byte FRAME_TYPE_INT = 0x03;
    private static final byte FRAME_TYPE_EOF = 0x04;

    private static volatile int MAX_ALLOWED_PACKET_SIZE = 64 * 1024 * 1024;
    private static volatile int DEFAULT_CONNECT_TIMEOUT_MILLIS = 5_000;

    private static final ThreadLocal<KeyPairGenerator> KEY_PAIR_GEN_TL = ThreadLocal.withInitial(() -> {
        try {
            return KeyPairGenerator.getInstance(KEY_EXCHANGE_ALGO);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    });

    private static final ThreadLocal<KeyFactory> KEY_FACTORY_TL = ThreadLocal.withInitial(() -> {
        try {
            return KeyFactory.getInstance(KEY_EXCHANGE_ALGO);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    });

    private final AtomicBoolean connectionClosed = new AtomicBoolean(false);
    private final AtomicBoolean connectionBroken = new AtomicBoolean(false);
    private final ReentrantLock handshakeLock = new ReentrantLock();
    private final ReentrantLock writeLock = new ReentrantLock();
    private final ReentrantLock readLock = new ReentrantLock();

    private Socket socket;
    private BufferedInputStream inputStream;
    private BufferedOutputStream outputStream;
    private AESUtil aesUtil;

    private volatile boolean handshakeCompleted = false;
    private boolean isServerMode = false;

    public SecureSocket(String host, int port) throws IOException {
        this(host, port, DEFAULT_CONNECT_TIMEOUT_MILLIS);
    }

    public SecureSocket(String host, int port, int connectTimeoutMillis) throws IOException {
        this();
        connect(host, port, connectTimeoutMillis);
        performClientHandshake();
    }

    public SecureSocket(Proxy proxy, String host, int port) throws IOException {
        this(proxy, host, port, DEFAULT_CONNECT_TIMEOUT_MILLIS);
    }

    public SecureSocket(Proxy proxy, String host, int port, int connectTimeoutMillis) throws IOException {
        this(new Socket(Objects.requireNonNull(proxy, "proxy")));
        connect(host, port, connectTimeoutMillis);
        performClientHandshake();
    }

    public SecureSocket(Socket socket) throws IOException {
        this.socket = Objects.requireNonNull(socket, "socket");
        configureSocket(socket);
        if (socket.isConnected()) {
            initStreams();
        }
    }

    public SecureSocket() throws IOException {
        this.socket = new Socket();
        configureSocket(this.socket);
    }

    public static int getMaxAllowedPacketSize() {
        return MAX_ALLOWED_PACKET_SIZE;
    }

    public static void setMaxAllowedPacketSize(int size) {
        if (size <= 0) {
            throw new IllegalArgumentException("Size must be positive");
        }
        MAX_ALLOWED_PACKET_SIZE = size;
    }

    public static int getDefaultConnectTimeoutMillis() {
        return DEFAULT_CONNECT_TIMEOUT_MILLIS;
    }

    public static void setDefaultConnectTimeoutMillis(int timeoutMillis) {
        if (timeoutMillis < 0) {
            throw new IllegalArgumentException("Timeout must be zero or positive");
        }
        DEFAULT_CONNECT_TIMEOUT_MILLIS = timeoutMillis;
    }

    protected void initServerMode() {
        this.isServerMode = true;
        this.handshakeCompleted = false;
    }

    public void connect(String host, int port) throws IOException {
        connect(host, port, DEFAULT_CONNECT_TIMEOUT_MILLIS);
    }

    public void connect(String host, int port, int connectTimeoutMillis) throws IOException {
        connect(new InetSocketAddress(host, port), connectTimeoutMillis);
    }

    public void connect(SocketAddress endpoint, int connectTimeoutMillis) throws IOException {
        Objects.requireNonNull(endpoint, "endpoint");
        if (connectTimeoutMillis < 0) {
            throw new IllegalArgumentException("Timeout must be zero or positive");
        }
        if (connectionClosed.get()) {
            throw new SocketException("Socket is closed");
        }
        if (socket.isConnected()) {
            throw new SocketException("Socket is already connected");
        }

        socket.connect(endpoint, connectTimeoutMillis);
        initStreams();
        handshakeCompleted = false;
        isServerMode = false;
        aesUtil = null;
    }

    private void configureSocket(Socket socket) {
        try {
            if (!socket.getKeepAlive()) {
                socket.setKeepAlive(true);
            }
            if (!socket.getTcpNoDelay()) {
                socket.setTcpNoDelay(true);
            }
        } catch (SocketException ignored) {
        }
    }

    private void initStreams() throws IOException {
        if (inputStream != null && outputStream != null) {
            return;
        }
        if (!socket.isConnected()) {
            throw new SocketException("Socket is not connected");
        }
        this.inputStream = new BufferedInputStream(socket.getInputStream(), BUFFER_SIZE);
        this.outputStream = new SilentBufferedOutputStream(socket.getOutputStream(), BUFFER_SIZE, connectionBroken);
    }

    private void ensureStreamsInitialized() throws IOException {
        if (inputStream != null && outputStream != null) {
            return;
        }
        if (connectionClosed.get() || connectionBroken.get()) {
            throw new SocketException("Connection closed");
        }
        initStreams();
    }

    private void ensureHandshake() throws IOException {
        ensureStreamsInitialized();
        if (handshakeCompleted) {
            return;
        }

        handshakeLock.lock();
        try {
            if (handshakeCompleted) {
                return;
            }
            if (connectionBroken.get() || connectionClosed.get()) {
                throw new IOException("Connection closed");
            }

            if (isServerMode) {
                try {
                    performServerHandshake();
                    socket.setSoTimeout(0);
                } catch (Exception e) {
                    close();
                    if (e instanceof IOException ioException) {
                        throw ioException;
                    }
                    throw new IOException("Handshake failed", e);
                }
            } else if (aesUtil == null) {
                performClientHandshake();
            }

            handshakeCompleted = true;
        } finally {
            handshakeLock.unlock();
        }
    }

    private void performServerHandshake() throws Exception {
        try {
            KeyPair keyPair = KEY_PAIR_GEN_TL.get().generateKeyPair();
            byte[] pubKey = keyPair.getPublic().getEncoded();
            writeRawPacketInternal(pubKey);

            byte[] otherKey = readRawPacketInternal();
            KeyFactory keyFactory = KEY_FACTORY_TL.get();
            PublicKey otherPublicKey = keyFactory.generatePublic(new X509EncodedKeySpec(otherKey));

            generateSharedSecret(keyPair, otherPublicKey);
        } catch (SocketTimeoutException e) {
            throw new IOException("Handshake timeout - client unresponsive (Zombie Connection blocked)", e);
        }
    }

    private void performClientHandshake() throws IOException {
        ensureStreamsInitialized();
        handshakeLock.lock();
        try {
            if (handshakeCompleted) {
                return;
            }

            byte[] serverKey = readRawPacketInternal();
            KeyFactory keyFactory = KEY_FACTORY_TL.get();
            PublicKey serverPublicKey = keyFactory.generatePublic(new X509EncodedKeySpec(serverKey));

            KeyPair keyPair = KEY_PAIR_GEN_TL.get().generateKeyPair();
            writeRawPacketInternal(keyPair.getPublic().getEncoded());

            generateSharedSecret(keyPair, serverPublicKey);
            handshakeCompleted = true;
        } catch (Exception e) {
            close();
            if (e instanceof IOException ioException) {
                throw ioException;
            }
            throw new IOException("Client handshake failed", e);
        } finally {
            handshakeLock.unlock();
        }
    }

    private void generateSharedSecret(KeyPair keyPair, PublicKey otherKey) throws Exception {
        KeyAgreement ka = KeyAgreement.getInstance(KEY_EXCHANGE_ALGO);
        ka.init(keyPair.getPrivate());
        ka.doPhase(otherKey, true);
        byte[] secret = ka.generateSecret();

        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        byte[] aesKey = sha.digest(secret);
        this.aesUtil = new AESUtil(new SecretKeySpec(aesKey, "AES"));
    }

    private void writeRawPacketInternal(byte[] data) throws IOException {
        Objects.requireNonNull(data, "data");
        if (connectionBroken.get()) {
            throw new IOException("Connection broken");
        }

        int len = data.length;
        byte[] header = {
                (byte) (len >> 24),
                (byte) (len >> 16),
                (byte) (len >> 8),
                (byte) len
        };

        writeLock.lock();
        try {
            outputStream.write(header);
            outputStream.write(data);
            outputStream.flush();
        } finally {
            writeLock.unlock();
        }
    }

    private byte[] readRawPacketInternal() throws IOException {
        if (connectionBroken.get()) {
            throw new IOException("Connection broken");
        }

        byte[] lenBytes = new byte[4];
        int readTotal = 0;

        try {
            while (readTotal < 4) {
                int count = inputStream.read(lenBytes, readTotal, 4 - readTotal);
                if (count < 0) {
                    throw new EOFException("Connection closed by peer");
                }
                readTotal += count;
            }
        } catch (SocketTimeoutException e) {
            if (readTotal > 0) {
                markConnectionBroken();
                throw new IOException("Read timed out during packet header - connection corrupt", e);
            }
            throw e;
        } catch (IOException e) {
            if (readTotal > 0) {
                markConnectionBroken();
            }
            throw e;
        }

        int len = ((lenBytes[0] & 0xFF) << 24)
                | ((lenBytes[1] & 0xFF) << 16)
                | ((lenBytes[2] & 0xFF) << 8)
                | (lenBytes[3] & 0xFF);

        if (len < 0) {
            throw new IOException("Negative packet length: " + len);
        }
        if (len > MAX_ALLOWED_PACKET_SIZE) {
            markConnectionBroken();
            throw new IOException("Packet too large: " + len + " (Max: " + MAX_ALLOWED_PACKET_SIZE + ")");
        }
        if (len == 0) {
            return new byte[0];
        }

        try {
            byte[] data = inputStream.readNBytes(len);
            if (data.length < len) {
                markConnectionBroken();
                throw new EOFException("Expected " + len + " bytes, but connection closed");
            }
            return data;
        } catch (SocketTimeoutException e) {
            markConnectionBroken();
            throw new IOException("Read timed out during packet body - connection corrupt", e);
        } catch (IOException e) {
            markConnectionBroken();
            throw e;
        }
    }

    public int sendStr(String message) throws IOException {
        ensureHandshake();
        if (connectionBroken.get()) {
            return -1;
        }

        if (message == null) {
            return sendEncryptedFrame(FRAME_TYPE_EOF, new byte[0]);
        }
        return sendEncryptedFrame(FRAME_TYPE_STRING, message.getBytes(StandardCharsets.UTF_8));
    }

    public String receiveStr() throws IOException {
        return receiveStr(0);
    }

    public String receiveStr(int timeoutMillis) throws IOException {
        ensureHandshake();
        EncryptedFrame frame = receiveFrame(timeoutMillis);
        if (frame.type == FRAME_TYPE_EOF) {
            return null;
        }
        if (frame.type != FRAME_TYPE_STRING) {
            throw new IOException("Unexpected frame type for string receive: " + frame.type);
        }
        return new String(frame.payload, StandardCharsets.UTF_8);
    }

    private byte[] receiveRawInternal(int timeoutMillis) throws IOException {
        if (connectionBroken.get()) {
            throw new IOException("Connection broken");
        }

        int originalTimeout = socket.getSoTimeout();
        boolean timeoutChanged = false;
        try {
            if (timeoutMillis > 0 && originalTimeout != timeoutMillis) {
                socket.setSoTimeout(timeoutMillis);
                timeoutChanged = true;
            }
            return readRawPacketInternal();
        } catch (SocketTimeoutException e) {
            throw e;
        } catch (IOException e) {
            if (isBrokenPipeException(e)) {
                markConnectionBroken();
            }
            throw e;
        } finally {
            if (timeoutChanged) {
                try {
                    socket.setSoTimeout(originalTimeout);
                } catch (Exception ignored) {
                }
            }
        }
    }

    public int sendBytes(byte[] data) throws IOException {
        ensureHandshake();
        if (data == null) {
            return sendEncryptedFrame(FRAME_TYPE_EOF, new byte[0]);
        }
        return sendEncryptedFrame(FRAME_TYPE_BYTES, data);
    }

    public int sendBytes(byte[] data, int offset, int length) throws IOException {
        ensureHandshake();
        if (data == null) {
            return sendEncryptedFrame(FRAME_TYPE_EOF, new byte[0]);
        }

        validateArrayRange(data, offset, length);
        byte[] toSend = new byte[length];
        System.arraycopy(data, offset, toSend, 0, length);
        return sendEncryptedFrame(FRAME_TYPE_BYTES, toSend);
    }

    public byte[] receiveBytes() throws IOException {
        return receiveBytes(0);
    }

    public byte[] receiveBytes(int timeoutMillis) throws IOException {
        ensureHandshake();
        EncryptedFrame frame = receiveFrame(timeoutMillis);
        if (frame.type == FRAME_TYPE_EOF) {
            return null;
        }
        if (frame.type != FRAME_TYPE_BYTES) {
            throw new IOException("Unexpected frame type for byte receive: " + frame.type);
        }
        return frame.payload;
    }

    public int sendInt(int value) throws IOException {
        ensureHandshake();
        byte[] intBytes = new byte[]{
                (byte) (value >> 24),
                (byte) (value >> 16),
                (byte) (value >> 8),
                (byte) value
        };
        return sendEncryptedFrame(FRAME_TYPE_INT, intBytes);
    }

    public int receiveInt() throws IOException {
        return receiveInt(0);
    }

    public int receiveInt(int timeoutMillis) throws IOException {
        ensureHandshake();
        EncryptedFrame frame = receiveFrame(timeoutMillis);
        if (frame.type != FRAME_TYPE_INT) {
            throw new IOException("Unexpected frame type for int receive: " + frame.type);
        }
        if (frame.payload.length != INT_PAYLOAD_LENGTH) {
            throw new IOException("Invalid int payload length: " + frame.payload.length);
        }
        return ((frame.payload[0] & 0xFF) << 24)
                | ((frame.payload[1] & 0xFF) << 16)
                | ((frame.payload[2] & 0xFF) << 8)
                | (frame.payload[3] & 0xFF);
    }

    private static void validateArrayRange(byte[] data, int offset, int length) {
        if (offset < 0 || length < 0 || offset > data.length - length) {
            throw new IllegalArgumentException("Invalid offset or length");
        }
    }

    private int sendEncryptedFrame(byte frameType, byte[] payload) throws IOException {
        byte[] plainFrame = buildFrame(frameType, payload);
        try {
            byte[] encrypted = aesUtil.encrypt(plainFrame);
            writeRawPacketInternal(encrypted);
            return 4 + encrypted.length;
        } catch (IOException e) {
            handleIOException(e);
            throw e;
        }
    }

    private static byte[] buildFrame(byte frameType, byte[] payload) {
        Objects.requireNonNull(payload, "payload");
        byte[] frame = new byte[payload.length + 1];
        frame[0] = frameType;
        if (payload.length > 0) {
            System.arraycopy(payload, 0, frame, 1, payload.length);
        }
        return frame;
    }

    private EncryptedFrame receiveFrame(int timeoutMillis) throws IOException {
        byte[] decrypted = receiveDecrypted(timeoutMillis);
        return decodeFrame(decrypted);
    }

    private EncryptedFrame decodeFrame(byte[] decrypted) throws IOException {
        if (decrypted.length == 0) {
            throw new IOException("Invalid frame: missing frame type");
        }

        byte frameType = decrypted[0];
        int payloadLength = decrypted.length - 1;
        byte[] payload = new byte[payloadLength];
        if (payloadLength > 0) {
            System.arraycopy(decrypted, 1, payload, 0, payloadLength);
        }

        if (frameType == FRAME_TYPE_EOF && payloadLength != 0) {
            throw new IOException("Invalid EOF frame payload length: " + payloadLength);
        }
        if (frameType == FRAME_TYPE_INT && payloadLength != INT_PAYLOAD_LENGTH) {
            throw new IOException("Invalid int frame payload length: " + payloadLength);
        }
        if (frameType != FRAME_TYPE_STRING
                && frameType != FRAME_TYPE_BYTES
                && frameType != FRAME_TYPE_INT
                && frameType != FRAME_TYPE_EOF) {
            throw new IOException("Unknown frame type: " + frameType);
        }

        return new EncryptedFrame(frameType, payload);
    }

    private byte[] receiveDecrypted(int timeoutMillis) throws IOException {
        readLock.lock();
        try {
            if (connectionBroken.get()) {
                throw new IOException("Connection broken");
            }

            byte[] encrypted = receiveRawInternal(timeoutMillis);
            if (encrypted.length == 0) {
                markConnectionBroken();
                throw new IOException("Invalid encrypted packet: empty raw packet");
            }

            try {
                return aesUtil.decrypt(encrypted);
            } catch (RuntimeException e) {
                markConnectionBroken();
                throw new IOException("Encrypted frame authentication failed", e);
            }
        } finally {
            readLock.unlock();
        }
    }

    private void handleIOException(IOException e) throws IOException {
        if (isBrokenPipeException(e)) {
            markConnectionBroken();
        }
        throw e;
    }

    private boolean isBrokenPipeException(IOException e) {
        if (e instanceof SocketException || e instanceof EOFException) {
            String message = e.getMessage();
            if (message == null) {
                return true;
            }
            message = message.toLowerCase();
            return message.contains("broken pipe")
                    || message.contains("connection reset")
                    || message.contains("socket closed")
                    || message.contains("connection closed")
                    || message.contains("software caused connection abort");
        }
        return false;
    }

    private void markConnectionBroken() {
        if (connectionBroken.compareAndSet(false, true)) {
            try {
                close();
            } catch (IOException ignored) {
            }
        }
    }

    @Override
    public void close() throws IOException {
        if (connectionClosed.getAndSet(true)) {
            return;
        }

        connectionBroken.set(true);

        try {
            if (inputStream != null) {
                inputStream.close();
            }
        } catch (Throwable ignored) {
        }

        try {
            if (outputStream != null) {
                outputStream.close();
            }
        } catch (Throwable ignored) {
        }

        try {
            if (socket != null) {
                socket.close();
            }
        } catch (Throwable ignored) {
        }
    }

    public void shutdownInput() throws IOException {
        if (socket != null && !socket.isClosed()) {
            socket.shutdownInput();
        }
    }

    public void shutdownOutput() throws IOException {
        if (socket != null && !socket.isClosed()) {
            socket.shutdownOutput();
        }
    }

    public boolean isInputShutdown() {
        return socket.isInputShutdown();
    }

    public boolean isOutputShutdown() {
        return socket.isOutputShutdown();
    }

    public boolean isClosed() {
        return connectionClosed.get() || connectionBroken.get();
    }

    public boolean isConnected() {
        return socket.isConnected() && !isClosed();
    }

    public boolean isConnectionBroken() {
        return connectionBroken.get();
    }

    public int getPort() {
        return socket.getPort();
    }

    public int getLocalPort() {
        return socket.getLocalPort();
    }

    public InetAddress getInetAddress() {
        return socket.getInetAddress();
    }

    public InetAddress getLocalAddress() {
        return socket.getLocalAddress();
    }

    public int getSoTimeout() throws SocketException {
        return socket.getSoTimeout();
    }

    public void setSoTimeout(int timeout) throws SocketException {
        socket.setSoTimeout(timeout);
    }

    public SocketAddress getRemoteSocketAddress() {
        return socket.getRemoteSocketAddress();
    }

    public SocketAddress getLocalSocketAddress() {
        return socket.getLocalSocketAddress();
    }

    public boolean getKeepAlive() throws SocketException {
        return socket.getKeepAlive();
    }

    public void setKeepAlive(boolean on) throws SocketException {
        socket.setKeepAlive(on);
    }

    public boolean getTcpNoDelay() throws SocketException {
        return socket.getTcpNoDelay();
    }

    public void setTcpNoDelay(boolean on) throws SocketException {
        socket.setTcpNoDelay(on);
    }

    public int getReceiveBufferSize() throws SocketException {
        return socket.getReceiveBufferSize();
    }

    public void setReceiveBufferSize(int size) throws SocketException {
        socket.setReceiveBufferSize(size);
    }

    public int getSendBufferSize() throws SocketException {
        return socket.getSendBufferSize();
    }

    public void setSendBufferSize(int size) throws SocketException {
        socket.setSendBufferSize(size);
    }

    private static final class EncryptedFrame {
        private final byte type;
        private final byte[] payload;

        private EncryptedFrame(byte type, byte[] payload) {
            this.type = type;
            this.payload = payload;
        }
    }

    private static class SilentBufferedOutputStream extends BufferedOutputStream {
        private final AtomicBoolean connectionBroken;

        private SilentBufferedOutputStream(OutputStream out, int size, AtomicBoolean connectionBroken) {
            super(out, size);
            this.connectionBroken = connectionBroken;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            if (connectionBroken.get()) {
                throw new IOException("Connection broken");
            }
            super.write(b, off, len);
        }

        @Override
        public void flush() throws IOException {
            if (connectionBroken.get()) {
                return;
            }
            super.flush();
        }
    }
}
