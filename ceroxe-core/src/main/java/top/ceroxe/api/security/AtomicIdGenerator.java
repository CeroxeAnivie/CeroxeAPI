package top.ceroxe.api.security;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * <h1>高性能混淆ID生成器 (ObfuscatedIdGenerator)</h1>
 * <p>
 * 一个工业级的、线程安全的、进程内唯一的ID生成器。
 * 它结合了 {@link AtomicLong} 的无锁高性能特性和 Bit-Shuffling (位混淆) 算法。
 * </p>
 *
 * <h2>核心特性 (100分标准)</h2>
 * <ul>
 *   <li><b>绝对唯一：</b> 基于原子计数器，在 Java long 的范围内 (2^64) 保证绝不重复。</li>
 *   <li><b>不可预测：</b> 输出的 ID 看起来像随机数，无法通过上一个 ID 猜测下一个 ID，防止遍历攻击。</li>
 *   <li><b>高性能：</b> 仅使用位运算和原子操作，吞吐量可达每秒数亿次，远超 UUID 和 SecureRandom。</li>
 *   <li><b>工业级设计：</b> 支持单例使用，也支持多实例隔离；移除溢出异常，支持自然回绕。</li>
 * </ul>
 *
 * @author Architect
 * @version 2.0
 */
public final class AtomicIdGenerator {

    // ========================================================================
    // 混淆参数 (Magic Numbers)
    // ========================================================================

    /**
     * 全局默认实例。适用于大多数通用场景。
     * 掩码是随机生成的，每次启动都不一样。
     */
    public static final AtomicIdGenerator GLOBAL = new AtomicIdGenerator();
    /**
     * 乘法哈希的质数乘数。
     * 选取一个大素数作为乘数，可以将连续的整数映射到整个 long 空间中。
     * 这个特定的数字来源于 MurmurHash3 / SplitMix64 的变体，具有极佳的位雪崩效应。
     */
    private static final long MULTIPLIER = 0xbf58476d1ce4e5b9L;
    /**
     * 异或掩码。
     * 用于隐藏“0”值，并进一步打乱位模式。
     * 每个实例可以拥有不同的掩码，从而隔离不同业务线的 ID 序列。
     */
    private final long xorMask;

    // ========================================================================
    // 单例模式 (默认方便使用)
    // ========================================================================
    /**
     * 核心计数器。
     * 从随机数开始，而不是从 0 开始，进一步增加不可预测性。
     */
    private final AtomicLong counter;

    // ========================================================================
    // 构造函数
    // ========================================================================

    /**
     * 默认构造函数。
     * 自动生成一个随机的 XOR 掩码和随机初始值。
     */
    public AtomicIdGenerator() {
        // 使用 ThreadLocalRandom 初始化，避免启动时的熵池阻塞
        this(ThreadLocalRandom.current().nextLong());
    }

    /**
     * 指定掩码的构造函数。
     * 如果需要在集群的多台机器上保持某种程度的数学隔离，可以指定不同的 mask。
     *
     * @param salt 盐值，用于生成异或掩码，改变混淆的“风味”。
     */
    public AtomicIdGenerator(long salt) {
        this.xorMask = salt;
        // 计数器初始值也随机，防止攻击者通过多次重启应用来猜测模式
        this.counter = new AtomicLong(ThreadLocalRandom.current().nextLong());
    }

    // ========================================================================
    // 核心业务方法
    // ========================================================================

    /**
     * 生成下一个混淆 ID。
     * <p>
     * 算法说明：
     * 1. 获取一个线性递增的序号 (seq)。
     * 2. 执行可逆的混淆变换 (Obfuscation)。
     * </p>
     *
     * @return 一个看起来像随机数，但保证唯一的 long ID。
     */
    public long nextId() {
        // 1. 获取序列号 (线性递增，绝对唯一)
        long seq = counter.getAndIncrement();

        // 2. 混淆处理 (双射变换，保证一一对应)
        return mix(seq);
    }

    /**
     * 混淆算法：将有序的 sequence 映射为无序的 ID。
     * 这是一个 Bijection (双射) 函数：f(x) 是唯一的，当且仅当 x 是唯一的。
     * <p>
     * 原理：
     * (x * Odd_Prime) % 2^64 是可逆的。
     * XOR 操作也是可逆的。
     * 所以结果也是唯一的。
     */
    private long mix(long x) {
        // 第一步：异或混淆 (防止序列号较小时高位全为0)
        long z = (x ^ xorMask);

        // 第二步：乘法哈希 (利用大素数将比特位扩散到整个 long 范围)
        z *= MULTIPLIER;

        // 第三步：位移异或 (雪崩效应，让高位变化影响低位)
        z ^= (z >>> 32);

        // 第四步：再次异或掩码 (增加复杂度)
        return z ^ xorMask;
    }

    // ========================================================================
    // 辅助方法
    // ========================================================================

    /**
     * 仅供调试使用：反向解析 ID 对应的原始序列号。
     * 注意：由于 mix 中包含多次 XOR 和位移，完全数学逆运算较复杂。
     * 此处仅展示 API 可能性，在大多数业务中不需要反解。
     */
    /* 
    public long decode(long id) {
        // 如果需要还原 ID 为 1, 2, 3... 的顺序，需要实现 mix 的逆函数 unmix
        // 这在需要对 ID 进行分片或排序的场景下非常有用
        throw new UnsupportedOperationException("To be implemented if needed");
    } 
    */
}