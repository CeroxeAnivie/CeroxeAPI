package top.ceroxe.api.net;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

public class TcpPingUtil {

    /**
     * 精确探测指定端口的 TCP 延迟
     *
     * @param host      域名或IP (例如: p.ceroxe.fun)
     * @param port      目标端口 (例如: 80, 443, 25565)
     * @param timeoutMs 超时时间 (毫秒)
     * @return 延迟(ms)，如果无法连通或超时返回 -1
     */
    public static int ping(String host, int port, int timeoutMs) {
        InetSocketAddress address = null;

        try {
            // 1. 先解析 DNS (不计入网络延迟时间，因为这取决于本地 DNS 缓存速度)
            // 如果 DNS 解析本身就挂了，这里会直接抛异常
            address = new InetSocketAddress(host, port);

            if (address.isUnresolved()) {
                System.err.println("DNS 解析失败: " + host);
                return -1;
            }
        } catch (Exception e) {
            return -1;
        }

        long start = System.nanoTime();

        // 2. try-with-resources 语法：
        // 这里的 socket 对象在花括号结束时（或者 return 时），会自动调用 close() 方法。
        // 确保 100% 释放系统资源，没有任何句柄残留。
        try (Socket socket = new Socket()) {

            // 优化设置：关闭 Nagle 算法，测速更准
            socket.setTcpNoDelay(true);
            // 设置读取超时（虽然主要看 connect 超时，但这是一个好习惯）
            socket.setSoTimeout(timeoutMs);

            // 3. 核心动作：建立连接 (SYN -> SYN-ACK -> ACK)
            // 只有这一步的时间才是真实的网络延迟
            socket.connect(address, timeoutMs);

            long end = System.nanoTime();

            // 连接成功，计算耗时
            return (int) ((end - start) / 1_000_000);

        } catch (IOException e) {
            // 捕获所有网络异常：
            // - SocketTimeoutException (超时)
            // - ConnectException (连接拒绝，端口没开)
            // - NoRouteToHostException (不可达)
            return -1;
        }
        // 这里 socket 已经绝对被销毁了
    }
}