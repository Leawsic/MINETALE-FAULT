package cn.jehorstudio.minetale.dimension.worldgen.biome.settings;

// 只控制地下网络样本到 Biome 的映射，不参与地形挖空。
public final class UndergroundBiomeSettings {
    private UndergroundBiomeSettings() {}

    // BiomeSource 输入是 quart 坐标，网络采样使用方块坐标。
    public static final double QUART_TO_BLOCK_SCALE = 4.0;

    // 控制 region/tunnel 过渡的 hash 斑块尺度。
    public static final double TRANSITION_NOISE_CELL_SIZE = 10.0;

    // mask 与分母必须对应，保证低 16 位稳定归一化到 0..1。
    public static final int TRANSITION_HASH_MASK = 0xFFFF;
    public static final double TRANSITION_HASH_NORMALIZER = 65535.0;
}
