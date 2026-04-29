package top.ceroxe.api.net;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.Socket;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SecureSocketProtocolTest {

    @Test
    void bytesTransferIsTransparentForEveryByteValue() throws Exception {
        byte[] allBytes = new byte[256];
        for (int i = 0; i < allBytes.length; i++) {
            allBytes[i] = (byte) i;
        }

        try (SecureServerSocket server = new SecureServerSocket(0)) {
            CompletableFuture<byte[]> received = CompletableFuture.supplyAsync(() -> {
                try (SecureSocket accepted = server.accept()) {
                    return accepted.receiveBytes();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });

            try (SecureSocket client = new SecureSocket("127.0.0.1", server.getLocalPort())) {
                client.sendBytes(allBytes);
            }

            assertArrayEquals(allBytes, received.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void singleEofSentinelByteIsBusinessPayload() throws Exception {
        try (SecureServerSocket server = new SecureServerSocket(0)) {
            CompletableFuture<byte[]> received = CompletableFuture.supplyAsync(() -> {
                try (SecureSocket accepted = server.accept()) {
                    return accepted.receiveBytes();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });

            try (SecureSocket client = new SecureSocket("127.0.0.1", server.getLocalPort())) {
                client.sendBytes(new byte[]{0x04});
            }

            assertArrayEquals(new byte[]{0x04}, received.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void nullBytesAreExplicitEofFrame() throws Exception {
        try (SecureServerSocket server = new SecureServerSocket(0)) {
            CompletableFuture<byte[]> received = CompletableFuture.supplyAsync(() -> {
                try (SecureSocket accepted = server.accept()) {
                    return accepted.receiveBytes();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });

            try (SecureSocket client = new SecureSocket("127.0.0.1", server.getLocalPort())) {
                client.sendBytes(null);
            }

            assertNull(received.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void largePayloadAndEmptyPayloadRoundTrip() throws Exception {
        byte[] large = new byte[128 * 1024 + 7];
        for (int i = 0; i < large.length; i++) {
            large[i] = (byte) (i * 31);
        }

        try (SecureServerSocket server = new SecureServerSocket(0)) {
            CompletableFuture<byte[][]> received = CompletableFuture.supplyAsync(() -> {
                try (SecureSocket accepted = server.accept()) {
                    return new byte[][]{accepted.receiveBytes(), accepted.receiveBytes()};
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });

            try (SecureSocket client = new SecureSocket("127.0.0.1", server.getLocalPort())) {
                client.sendBytes(new byte[0]);
                client.sendBytes(large);
            }

            byte[][] packets = received.get(5, TimeUnit.SECONDS);
            assertArrayEquals(new byte[0], packets[0]);
            assertArrayEquals(large, packets[1]);
        }
    }

    @Test
    void stringsAndIntegersKeepTheirFrameTypes() throws Exception {
        try (SecureServerSocket server = new SecureServerSocket(0)) {
            CompletableFuture<Object[]> received = CompletableFuture.supplyAsync(() -> {
                try (SecureSocket accepted = server.accept()) {
                    String text = accepted.receiveStr();
                    int value = accepted.receiveInt();
                    IOException wrongType = assertThrows(IOException.class, accepted::receiveBytes);
                    return new Object[]{text, value, wrongType};
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });

            try (SecureSocket client = new SecureSocket("127.0.0.1", server.getLocalPort())) {
                client.sendStr("\u0004");
                client.sendInt(Integer.MIN_VALUE);
                client.sendStr("not-bytes");
            }

            Object[] result = received.get(5, TimeUnit.SECONDS);
            assertEquals("\u0004", result[0]);
            assertEquals(Integer.MIN_VALUE, result[1]);
            assertEquals("Unexpected frame type for byte receive: 1", ((IOException) result[2]).getMessage());
        }
    }

    @Test
    void invalidRawPacketBreaksConnectionInsteadOfBecomingPayload() throws Exception {
        try (SecureServerSocket server = new SecureServerSocket(0)) {
            CompletableFuture<IOException> serverError = CompletableFuture.supplyAsync(() -> {
                try (SecureSocket accepted = server.accept()) {
                    accepted.receiveBytes();
                    throw new AssertionError("Expected invalid encrypted frame to fail");
                } catch (IOException e) {
                    return e;
                }
            });

            try (Socket socket = new Socket("127.0.0.1", server.getLocalPort())) {
                socket.getOutputStream().write(new byte[]{0, 0, 0, 1, 0x55});
                socket.getOutputStream().flush();
            }

            IOException error = serverError.get(5, TimeUnit.SECONDS);
            assertEquals(false, error.getMessage().isBlank());
        }
    }

    @Test
    void sliceSendCopiesOnlyRequestedRange() throws Exception {
        byte[] source = new byte[]{9, 8, 7, 6, 5, 4};
        try (SecureServerSocket server = new SecureServerSocket(0)) {
            CompletableFuture<byte[]> received = CompletableFuture.supplyAsync(() -> {
                try (SecureSocket accepted = server.accept()) {
                    return accepted.receiveBytes();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });

            try (SecureSocket client = new SecureSocket("127.0.0.1", server.getLocalPort())) {
                client.sendBytes(source, 2, 3);
                Arrays.fill(source, (byte) 0);
            }

            assertArrayEquals(new byte[]{7, 6, 5}, received.get(5, TimeUnit.SECONDS));
        }
    }
}
