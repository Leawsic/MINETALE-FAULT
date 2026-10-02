package cn.jehorstudio.minetale.dimension.ebott.transition.client.render;

// 结界视觉常量：颜色为 0xRRGGBB，距离为方块，时间为秒。
public final class BarrierSettings {
    // 纹理与开口几何
    public static final int MEMBRANE_TEXTURE_RESOLUTION = 1024;
    public static final int EDGE_DISTANCE_TEXTURE_RESOLUTION = 256;
    // 离线预览没有会话几何时使用该开口半径。
    public static final float PREVIEW_RADIUS = 12.0F;
    public static final float PLANE_OFFSET = 1.0F / 128.0F;
    public static final float EDGE_PLANE_DEPTH = 1.0F / 32.0F;
    public static final float EDGE_WIDTH = 2.15F;
    public static final float EDGE_TEXTURE_PADDING = 1.0F;
    public static final float EDGE_DISTANCE_OVERSCAN_PIXELS = 2.0F;

    // 通行状态对应的表现
    public static final float PASSABLE_ALPHA = 0.34F;
    // 离线预览默认模拟开放状态的本体透明度。
    public static final float PREVIEW_BASE_ALPHA = PASSABLE_ALPHA;
    public static final float PASSABLE_FADE_SECONDS = 0.45F;
    public static final float MAX_FRAME_DELTA_SECONDS = 0.10F;

    // 本体颜色与云纹
    public static final int BODY_DARK_COLOR = 0x34242F;
    public static final int BODY_LIGHT_COLOR = 0x9A6A7C;
    public static final int BODY_EDGE_COLOR = 0xE0B8C2;
    public static final float BODY_EDGE_START = 0.76F;
    public static final float BODY_EDGE_WIDTH = 0.28F;
    public static final float BODY_EDGE_ALPHA_STRENGTH = 0.72F;
    public static final float CLOUD_BASE = 0.50F;
    public static final float CLOUD_X_FREQUENCY = 18.0F;
    public static final float CLOUD_X_WARP_FREQUENCY = 11.0F;
    public static final float CLOUD_X_WARP = 2.4F;
    public static final float CLOUD_X_AMPLITUDE = 0.19F;
    public static final float CLOUD_Y_FREQUENCY = 25.0F;
    public static final float CLOUD_Y_SKEW = 8.0F;
    public static final float CLOUD_Y_AMPLITUDE = 0.17F;
    public static final float CLOUD_DIAGONAL_FREQUENCY = 41.0F;
    public static final float CLOUD_DIAGONAL_AMPLITUDE = 0.08F;
    public static final float CLOUD_GRAIN_AMPLITUDE = 0.10F;

    private BarrierSettings() {
    }

}
