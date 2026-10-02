package cn.jehorstudio.minetale.dimension.ebott.transition.client.render;

// 纯 CPU 结界纹理采样，保证游戏动态纹理与离线预览使用同一外观函数。
public final class BarrierMaterial {
    private BarrierMaterial() {
    }

    // 按 NativeImage.setPixelABGR 的通道顺序打包 RGBA8。
    public static int pixelAbgr(int x, int y) {
        int resolution = BarrierSettings.MEMBRANE_TEXTURE_RESOLUTION;
        float u = (x + 0.5F) / resolution;
        float v = (y + 0.5F) / resolution;
        float cloud = BarrierSettings.CLOUD_BASE
                + sin(u * BarrierSettings.CLOUD_X_FREQUENCY
                        + sin(v * BarrierSettings.CLOUD_X_WARP_FREQUENCY)
                        * BarrierSettings.CLOUD_X_WARP) * BarrierSettings.CLOUD_X_AMPLITUDE
                + sin(v * BarrierSettings.CLOUD_Y_FREQUENCY
                        - u * BarrierSettings.CLOUD_Y_SKEW) * BarrierSettings.CLOUD_Y_AMPLITUDE
                + sin((u + v) * BarrierSettings.CLOUD_DIAGONAL_FREQUENCY)
                        * BarrierSettings.CLOUD_DIAGONAL_AMPLITUDE;
        cloud = clamp(cloud + ((stableNoise(x + y * resolution) & 0xFF) / 255.0F - 0.5F)
                * BarrierSettings.CLOUD_GRAIN_AMPLITUDE, 0.0F, 1.0F);

        float dx = (u - 0.5F) * 2.0F;
        float dy = (v - 0.5F) * 2.0F;
        float normalizedRadius = (float) Math.sqrt(dx * dx + dy * dy);
        float edge = clamp((normalizedRadius - BarrierSettings.BODY_EDGE_START)
                / BarrierSettings.BODY_EDGE_WIDTH, 0.0F, 1.0F);
        edge = edge * edge * (3.0F - 2.0F * edge);

        int red = colorChannel(lerp(edge,
                lerp(cloud, channel(BarrierSettings.BODY_DARK_COLOR, 16),
                        channel(BarrierSettings.BODY_LIGHT_COLOR, 16)),
                channel(BarrierSettings.BODY_EDGE_COLOR, 16)));
        int green = colorChannel(lerp(edge,
                lerp(cloud, channel(BarrierSettings.BODY_DARK_COLOR, 8),
                        channel(BarrierSettings.BODY_LIGHT_COLOR, 8)),
                channel(BarrierSettings.BODY_EDGE_COLOR, 8)));
        int blue = colorChannel(lerp(edge,
                lerp(cloud, channel(BarrierSettings.BODY_DARK_COLOR, 0),
                        channel(BarrierSettings.BODY_LIGHT_COLOR, 0)),
                channel(BarrierSettings.BODY_EDGE_COLOR, 0)));
        return 0xFF000000 | blue << 16 | green << 8 | red;
    }

    private static int stableNoise(int index) {
        int value = index * 0x9E3779B9;
        value ^= value >>> 16;
        value *= 0x7FEB352D;
        value ^= value >>> 15;
        return value;
    }

    private static int channel(int color, int shift) {
        return color >> shift & 0xFF;
    }

    private static int colorChannel(float value) {
        return clamp(Math.round(value), 0, 255);
    }

    private static float lerp(float amount, float first, float second) {
        return first + amount * (second - first);
    }

    private static float sin(float value) {
        return (float) Math.sin(value);
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
