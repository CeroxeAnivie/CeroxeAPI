package top.ceroxe.api;

import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.GlobalMemory;
import oshi.hardware.HardwareAbstractionLayer;
import oshi.hardware.NetworkIF;
import oshi.software.os.FileSystem;
import oshi.software.os.OSFileStore;
import oshi.software.os.OperatingSystem;
import oshi.util.FormatUtil;
import oshi.util.Util;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Java 21 OSHI 系统信息工具类
 * 功能：全能型系统监控与环境判断
 */
public class OshiUtils {

    // SystemInfo 实例初始化开销较大，且线程安全，建议静态持有
    private static final SystemInfo SYSTEM_INFO = new SystemInfo();
    private static final HardwareAbstractionLayer HARDWARE = SYSTEM_INFO.getHardware();
    private static final OperatingSystem OS = SYSTEM_INFO.getOperatingSystem();
    // 定义 OSHI 6.x 标准的系统家族字符串常量
    private static final String OS_WINDOWS = "Windows";
    private static final String OS_LINUX = "Linux";
    private static final String OS_MACOS = "macOS";
    private static final String OS_SOLARIS = "Solaris";
    private static final String OS_FREEBSD = "FreeBSD";
    private static final String OS_AIX = "AIX";
    private static final String OS_ANDROID = "Android";

    // 私有构造，防止实例化
    private OshiUtils() {
    }

    /**
     * 获取操作系统家族名称 (返回字符串，如 "Windows", "Linux")
     */
    public static String getOsFamily() {
        return OS.getFamily();
    }

    public static boolean isWindows() {
        return OS_WINDOWS.equals(getOsFamily());
    }

    public static boolean isLinux() {
        return OS_LINUX.equals(getOsFamily());
    }

    public static boolean isMacOS() {
        return OS_MACOS.equals(getOsFamily());
    }

    public static boolean isSolaris() {
        return OS_SOLARIS.equals(getOsFamily());
    }

    public static boolean isFreeBSD() {
        return OS_FREEBSD.equals(getOsFamily());
    }

    public static boolean isAix() {
        return OS_AIX.equals(getOsFamily());
    }

    public static boolean isAndroid() {
        return OS_ANDROID.equals(getOsFamily());
    }

    // ==========================================
    // 1. 操作系统判断 (OS Detection)
    // ==========================================


    // ==========================================
    // 2. 系统概况 (System Overview)
    // ==========================================

    /**
     * 获取操作系统详细描述 (e.g., "Microsoft Windows 11 build 22621")
     */
    public static String getOsString() {
        return OS.toString();
    }

    /**
     * 获取系统运行时长 (秒)
     */
    public static long getSystemUptime() {
        return OS.getSystemUptime();
    }

    /**
     * 获取系统启动时间
     */
    public static Instant getSystemBootTime() {
        return Instant.ofEpochSecond(OS.getSystemBootTime());
    }

    // ==========================================
    // 3. 硬件信息 - CPU
    // ==========================================

    public static CentralProcessor getProcessor() {
        return HARDWARE.getProcessor();
    }

    /**
     * 获取 CPU 型号名称
     */
    public static String getCpuModel() {
        return HARDWARE.getProcessor().getProcessorIdentifier().getName();
    }

    /**
     * 获取物理核心数
     */
    public static int getPhysicalProcessorCount() {
        return HARDWARE.getProcessor().getPhysicalProcessorCount();
    }

    /**
     * 获取逻辑核心数 (线程数)
     */
    public static int getLogicalProcessorCount() {
        return HARDWARE.getProcessor().getLogicalProcessorCount();
    }

    /**
     * 获取系统 CPU 使用率 (0.0 到 1.0 之间)
     * 注意：此方法会阻塞 1 秒以进行采样
     */
    public static double getCpuLoad() {
        CentralProcessor processor = HARDWARE.getProcessor();
        long[] prevTicks = processor.getSystemCpuLoadTicks();
        Util.sleep(1000); // 采样间隔
        return processor.getSystemCpuLoadBetweenTicks(prevTicks);
    }

    // ==========================================
    // 4. 硬件信息 - 内存 (Memory)
    // ==========================================

    public static GlobalMemory getMemory() {
        return HARDWARE.getMemory();
    }

    public static long getTotalMemory() {
        return HARDWARE.getMemory().getTotal();
    }

    public static long getAvailableMemory() {
        return HARDWARE.getMemory().getAvailable();
    }

    public static long getUsedMemory() {
        return getTotalMemory() - getAvailableMemory();
    }

    /**
     * 获取格式化后的内存信息字符串
     */
    public static String getMemoryInfoReadable() {
        GlobalMemory memory = HARDWARE.getMemory();
        return "Total: " + FormatUtil.formatBytes(memory.getTotal()) +
                ", Available: " + FormatUtil.formatBytes(memory.getAvailable()) +
                ", Used: " + FormatUtil.formatBytes(memory.getTotal() - memory.getAvailable());
    }

    // ==========================================
    // 5. 硬件信息 - 磁盘 (Disk)
    // ==========================================

    /**
     * 获取所有文件系统/分区信息
     */
    public static List<OSFileStore> getFileStores() {
        FileSystem fileSystem = OS.getFileSystem();
        return fileSystem.getFileStores();
    }

    /**
     * 获取简化的磁盘使用情况报告
     */
    public static List<String> getDiskUsageReport() {
        return getFileStores().stream()
                .map(fs -> String.format("Disk: %s (%s) | Total: %s | Free: %s | Type: %s",
                        fs.getName(),
                        fs.getMount(),
                        FormatUtil.formatBytes(fs.getTotalSpace()),
                        FormatUtil.formatBytes(fs.getUsableSpace()),
                        fs.getType()))
                .collect(Collectors.toList());
    }

    // ==========================================
    // 6. 硬件信息 - 网络 (Network)
    // ==========================================

    public static List<NetworkIF> getNetworkInterfaces() {
        return HARDWARE.getNetworkIFs();
    }

    /**
     * 获取本机 IP 地址列表 (过滤掉回环地址)
     */
    public static List<String> getIpAddresses() {
        return getNetworkInterfaces().stream()
                .flatMap(net -> Arrays.stream(net.getIPv4addr()))
                .filter(ip -> !ip.equals("127.0.0.1"))
                .collect(Collectors.toList());
    }

    /**
     * 获取网络接口概况
     */
    public static String getNetworkInfoReadable() {
        StringBuilder sb = new StringBuilder();
        for (NetworkIF net : getNetworkInterfaces()) {
            sb.append("Name: ").append(net.getDisplayName());
            sb.append(" (").append(net.getName()).append(")");
            sb.append(" | MAC: ").append(net.getMacaddr());
            sb.append(" | IPv4: ").append(Arrays.toString(net.getIPv4addr()));
            sb.append("\n");
        }
        return sb.toString();
    }

    // ==========================================
    // 7. 硬件信息 - 主板与固件 (Baseboard)
    // ==========================================

    public static String getMotherboardManufacturer() {
        return HARDWARE.getComputerSystem().getBaseboard().getManufacturer();
    }

    public static String getMotherboardModel() {
        return HARDWARE.getComputerSystem().getBaseboard().getModel();
    }

    // ==========================================
    // 8. 调试/打印所有信息
    // ==========================================

    /**
     * 打印完整的系统诊断报告
     */
    public static void printFullSystemReport() {
        System.out.println("================ SYSTEM REPORT ================");
        System.out.println("OS: " + getOsString());
        System.out.println("Family: " + getOsFamily());
        System.out.println("Uptime: " + FormatUtil.formatElapsedSecs(getSystemUptime()));
        System.out.println("-----------------------------------------------");
        System.out.println("CPU: " + getCpuModel());
        System.out.println("Physical Cores: " + getPhysicalProcessorCount());
        System.out.println("Logical Cores: " + getLogicalProcessorCount());
        System.out.printf("CPU Load: %.1f%%%n", getCpuLoad() * 100);
        System.out.println("-----------------------------------------------");
        System.out.println("Memory: " + getMemoryInfoReadable());
        System.out.println("-----------------------------------------------");
        System.out.println("Disks:");
        getDiskUsageReport().forEach(System.out::println);
        System.out.println("-----------------------------------------------");
        System.out.println("Network Interfaces:");
        System.out.println(getNetworkInfoReadable());
        System.out.println("===============================================");
    }

    // Main 方法用于测试
    public static void main(String[] args) {
        // 测试判断方法
        System.out.println("Is Windows? " + OshiUtils.isWindows());
        System.out.println("Is Linux? " + OshiUtils.isLinux());
        System.out.println("Is MacOS? " + OshiUtils.isMacOS());

        System.out.println("\n正在生成系统报告 (需采样CPU，请稍候 1 秒)...");
        OshiUtils.printFullSystemReport();
    }
}