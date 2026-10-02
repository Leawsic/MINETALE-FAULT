package cn.jehorstudio.minetale.dimension.worldgen.region.origin.settings;

import cn.jehorstudio.minetale.dimension.worldgen.CakeConfig;

// ORIGIN 各生成阶段共用的几何常量，不要在阶段内另行定义。
public final class OriginSettings {
    public static final int FLOOR_Y = 86;
    public static final int FLOWER_LANDING_PROTECTED_RADIUS = 24;
    public static final int EXIT_HALF_WIDTH = 8;
    public static final int EXIT_CEILING_Y = 98;
    public static final int CAVERN_BASE_RADIUS = 104;
    public static final int CAVERN_RADIUS_VARIATION = 10;
    public static final int CAVERN_BASE_CEILING_Y = 122;
    public static final int CAVERN_CEILING_VARIATION = 6;

    // Biome 垂直判定在地板与洞顶两侧保留该余量。
    public static final double INSIDE_FLOOR_MARGIN = 5.0;
    public static final double INSIDE_CEILING_MARGIN = 8.0;

    private OriginSettings() {
    }

    public static int centerX() {
        return CakeConfig.defaults().originCenterX();
    }

    public static int centerZ() {
        return CakeConfig.defaults().originCenterZ();
    }

    public static int exitX() {
        return CakeConfig.defaults().originExitX();
    }
}
