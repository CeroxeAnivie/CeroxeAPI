package top.ceroxe.api.management.bufferedFile;

public class SizeCalculator {
    public static double mibToByte(double mib) {
        return mib * 1024 * 1024;
    }

    public static double byteToMib(int byteNum) {
        return (double) byteNum / 1024 / 1024;
    }
}
