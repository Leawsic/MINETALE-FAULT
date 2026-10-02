package cn.jehorstudio.minetale.dimension.worldgen;

// Rainbow Cake 的 Z/line_progress 参数；所有 worldgen 子系统共享 defaults() 以保持区域规划一致。
public record CakeConfig(
        int originWidth,
        int originLength,
        int originCenterX,
        int originExitX,
        double transitionTunnelLength,
        double ruinsLength,
        double snowdinLength,
        double waterfallLength,
        double hotLandLength,
        double mainLineY,
        double mainLinePrimaryWarpScale,
        double mainLinePrimaryWarpStrength,
        double mainLineSecondaryWarpScale,
        double mainLineSecondaryWarpStrength
) {
    public static final int ORIGIN_WIDTH = 320;
    public static final int ORIGIN_LENGTH = 320;
    public static final int ORIGIN_CENTER_X = 0;
    public static final int ORIGIN_EXIT_X = 0;
    public static final double TRANSITION_TUNNEL_LENGTH = 160.0;
    public static final double RUINS_LENGTH = 900.0;
    public static final double SNOWDIN_LENGTH = 1600.0;
    public static final double WATERFALL_LENGTH = 900.0;
    public static final double HOT_LAND_LENGTH = 900.0;
    public static final double MAIN_LINE_Y = 86.0;
    public static final double MAIN_LINE_PRIMARY_WARP_SCALE = 0.0018;
    public static final double MAIN_LINE_PRIMARY_WARP_STRENGTH = 180.0;
    public static final double MAIN_LINE_SECONDARY_WARP_SCALE = 0.0065;
    public static final double MAIN_LINE_SECONDARY_WARP_STRENGTH = 42.0;

    private static final CakeConfig DEFAULTS = new CakeConfig(
            ORIGIN_WIDTH,
            ORIGIN_LENGTH,
            ORIGIN_CENTER_X,
            ORIGIN_EXIT_X,
            TRANSITION_TUNNEL_LENGTH,
            RUINS_LENGTH,
            SNOWDIN_LENGTH,
            WATERFALL_LENGTH,
            HOT_LAND_LENGTH,
            MAIN_LINE_Y,
            MAIN_LINE_PRIMARY_WARP_SCALE,
            MAIN_LINE_PRIMARY_WARP_STRENGTH,
            MAIN_LINE_SECONDARY_WARP_SCALE,
            MAIN_LINE_SECONDARY_WARP_STRENGTH
    );

    public CakeConfig {
        requireChunkAlignedPositive("originWidth", originWidth);
        requireChunkAlignedPositive("originLength", originLength);
    }

    public static CakeConfig defaults() {
        return DEFAULTS;
    }

    public int originMinX() {
        return originCenterX - originWidth / 2;
    }

    public int originMaxXExclusive() {
        return originMinX() + originWidth;
    }

    public int originMinZ() {
        return -originLength;
    }

    public int originMaxZExclusive() {
        return 0;
    }

    public int originCenterZ() {
        return originMinZ() + originLength / 2;
    }

    private static void requireChunkAlignedPositive(String name, int value) {
        if (value <= 0 || value % 16 != 0) {
            throw new IllegalArgumentException(name + " must be positive and divisible by 16: " + value);
        }
    }
}
