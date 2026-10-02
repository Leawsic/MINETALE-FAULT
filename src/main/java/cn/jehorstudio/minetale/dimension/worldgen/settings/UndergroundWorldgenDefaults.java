package cn.jehorstudio.minetale.dimension.worldgen.settings;

// 地下 worldgen Codec 的缺省值与外部 JSON 边界；数据包显式值优先。
public final class UndergroundWorldgenDefaults {
    private UndergroundWorldgenDefaults() {}

    // 采样 cell 同时控制路径距离近似精度与返回的参考 cell 坐标。
    public static final int CELL_SIZE = 48;

    // 防止外部配置将路径采样密度推到无意义范围。
    public static final int MIN_CELL_SIZE = 16;

    // Stage1 carve 与 BiomeSource 分别拥有缺省半径，但共享下面的合法区间。
    public static final double STAGE1_REGION_BASE_RADIUS = 18.0;

    public static final double BIOME_ROOM_BASE_RADIUS = 20.0;

    public static final double MIN_ROOM_BASE_RADIUS = 6.0;

    public static final double MAX_ROOM_BASE_RADIUS = 80.0;

    public static final double TUNNEL_BASE_RADIUS = 2.6;

    public static final double MIN_TUNNEL_BASE_RADIUS = 1.0;

    public static final double MAX_TUNNEL_BASE_RADIUS = 12.0;

    public static int clampCellSize(int cellSize) {
        return Math.max(MIN_CELL_SIZE, cellSize);
    }

    public static double clampRoomBaseRadius(double roomBaseRadius) {
        return Math.max(MIN_ROOM_BASE_RADIUS, Math.min(MAX_ROOM_BASE_RADIUS, roomBaseRadius));
    }

    public static double clampTunnelBaseRadius(double tunnelBaseRadius) {
        return Math.max(MIN_TUNNEL_BASE_RADIUS, Math.min(MAX_TUNNEL_BASE_RADIUS, tunnelBaseRadius));
    }

    public static double clampCarveStrength(double strength) {
        return Math.max(0.0, strength);
    }
}
