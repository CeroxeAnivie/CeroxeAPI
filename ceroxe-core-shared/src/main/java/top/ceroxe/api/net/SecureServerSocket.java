package top.ceroxe.api.net;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class SecureServerSocket implements Closeable {
    private static final int RECEIVE_BUFFER_SIZE = 128 * 1024;
    private static volatile int ZOMBIE_DEFENSE_TIMEOUT = 5_000;

    private final ServerSocket serverSocket;
    private final Set<String> ignoreIPs = ConcurrentHashMap.newKeySet();

    public SecureServerSocket(int port) throws IOException {
        this.serverSocket = new ServerSocket();
        this.serverSocket.setReuseAddress(true);
        this.serverSocket.setReceiveBufferSize(RECEIVE_BUFFER_SIZE);
        this.serverSocket.bind(new InetSocketAddress(port));
    }

    public static int getZombieDefenseTimeout() {
        return ZOMBIE_DEFENSE_TIMEOUT;
    }

    public static boolean setZombieDefenseTimeout(int zombieDefenseTimeout) {
        if (zombieDefenseTimeout >= 0) {
            ZOMBIE_DEFENSE_TIMEOUT = zombieDefenseTimeout;
            return true;
        }
        return false;
    }

    public void addIgnoreIP(String ip) {
        ignoreIPs.add(ip);
    }

    public boolean removeIgnoreIP(String ip) {
        return ignoreIPs.remove(ip);
    }

    public CopyOnWriteArrayList<String> getIgnoreIPs() {
        return new CopyOnWriteArrayList<>(ignoreIPs);
    }

    public SecureSocket accept() throws IOException {
        while (!serverSocket.isClosed()) {
            Socket socket = null;
            try {
                socket = serverSocket.accept();
                InetAddress inetAddress = socket.getInetAddress();
                if (inetAddress == null) {
                    closeSocketQuietly(socket);
                    continue;
                }

                if (ignoreIPs.contains(inetAddress.getHostAddress())) {
                    closeSocketQuietly(socket);
                    continue;
                }

                configureSocket(socket);
                SecureSocket secureSocket = new SecureSocket(socket);
                secureSocket.initServerMode();
                return secureSocket;
            } catch (SocketException e) {
                if (serverSocket.isClosed()) {
                    throw e;
                }
                closeSocketQuietly(socket);
            } catch (IOException e) {
                closeSocketQuietly(socket);
            }
        }
        throw new SocketException("ServerSocket is closed");
    }

    private void configureSocket(Socket socket) throws SocketException {
        socket.setSoTimeout(ZOMBIE_DEFENSE_TIMEOUT);
        socket.setKeepAlive(true);
        socket.setTcpNoDelay(true);
    }

    private void closeSocketQuietly(Socket socket) {
        if (socket != null && !socket.isClosed()) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    @Override
    public void close() throws IOException {
        serverSocket.close();
    }

    public boolean isClosed() {
        return serverSocket.isClosed();
    }

    public int getLocalPort() {
        return serverSocket.getLocalPort();
    }

    public InetAddress getInetAddress() {
        return serverSocket.getInetAddress();
    }
}
