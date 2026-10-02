package cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.settings;

import cn.jehorstudio.minetale.dimension.worldgen.preview.PreviewSetting;
import cn.jehorstudio.minetale.dimension.worldgen.preview.PreviewSettingKind;


// 只拥有 Snowdin 洞顶高度场与纹理 noise；地面、钟乳石和高度边界归 TerrainSettings。
public final class SnowdinDomeNoiseSettings {
    private SnowdinDomeNoiseSettings() {}

    @PreviewSetting(group = "snowdin.ceiling", id = "base_height", min = 64.0, max = 248.0, step = 0.5, kind = PreviewSettingKind.HEIGHT)
    // 基础净高随整体洞腔缩放，局部 noise 强度保持独立。
    public static final double CEILING_BASE_HEIGHT =
            SnowdinTerrainSettings.CEILING_UNSCALED_BASE_HEIGHT
                    * SnowdinTerrainSettings.CEILING_DISTANCE_SCALE;

    @PreviewSetting(group = "snowdin.ceiling", id = "primary_noise_scale", min = 0.0001, max = 0.02, step = 0.0001, kind = PreviewSettingKind.SCALE)
    public static final double CEILING_PRIMARY_NOISE_SCALE = 0.0016;

    @PreviewSetting(group = "snowdin.ceiling", id = "primary_noise_strength", min = 0.0, max = 80.0, step = 0.5, kind = PreviewSettingKind.STRENGTH)
    public static final double CEILING_PRIMARY_NOISE_STRENGTH = 24.0;

    @PreviewSetting(group = "snowdin.ceiling", id = "secondary_noise_scale", min = 0.0001, max = 0.04, step = 0.0001, kind = PreviewSettingKind.SCALE)
    public static final double CEILING_SECONDARY_NOISE_SCALE = 0.006;

    @PreviewSetting(group = "snowdin.ceiling", id = "secondary_noise_strength", min = 0.0, max = 40.0, step = 0.25, kind = PreviewSettingKind.STRENGTH)
    public static final double CEILING_SECONDARY_NOISE_STRENGTH = 7.0;

    @PreviewSetting(group = "snowdin.ceiling", id = "legacy_dome_noise_scale", min = 0.0001, max = 0.02, step = 0.0001, kind = PreviewSettingKind.SCALE)
    // 简单 dome 与 karst dome 取较强结果，不叠加额外高度。
    public static final double DOME_NOISE_SCALE = 0.0011;

    @PreviewSetting(group = "snowdin.ceiling", id = "legacy_dome_noise_x_offset", min = -512.0, max = 512.0, step = 1.0, kind = PreviewSettingKind.OFFSET)
    public static final double DOME_NOISE_X_OFFSET = 81.0;

    @PreviewSetting(group = "snowdin.ceiling", id = "legacy_dome_noise_z_offset", min = -512.0, max = 512.0, step = 1.0, kind = PreviewSettingKind.OFFSET)
    public static final double DOME_NOISE_Z_OFFSET = -37.0;

    @PreviewSetting(group = "snowdin.ceiling", id = "legacy_dome_threshold", min = 0.0, max = 1.0, step = 0.01, kind = PreviewSettingKind.THRESHOLD)
    public static final double DOME_THRESHOLD = 0.62;

    @PreviewSetting(group = "snowdin.ceiling", id = "legacy_dome_normalize_range", min = 0.01, max = 1.0, step = 0.01, kind = PreviewSettingKind.FACTOR)
    public static final double DOME_NORMALIZE_RANGE = 0.38;

    @PreviewSetting(group = "snowdin.ceiling", id = "legacy_dome_extra_height", min = 0.0, max = 80.0, step = 0.5, kind = PreviewSettingKind.HEIGHT)
    public static final double DOME_EXTRA_HEIGHT = 14.0;

    @PreviewSetting(group = "snowdin.ceiling", id = "karst_warp_scale", min = 0.0001, max = 0.02, step = 0.0001, kind = PreviewSettingKind.SCALE)
    // karst domain warp 的 X/Z 轴使用错开的 noise 场，避免镜像形变。
    public static final double KARST_DOME_WARP_SCALE = 0.0014;

    @PreviewSetting(group = "snowdin.ceiling", id = "karst_warp_strength", min = 0.0, max = 120.0, step = 0.5, kind = PreviewSettingKind.STRENGTH)
    public static final double KARST_DOME_WARP_STRENGTH = 24.0;

    @PreviewSetting(group = "snowdin.ceiling", id = "karst_warp_z_noise_x_offset", min = -512.0, max = 512.0, step = 1.0, kind = PreviewSettingKind.OFFSET)
    public static final double KARST_DOME_WARP_Z_NOISE_X_OFFSET = 91.0;

    @PreviewSetting(group = "snowdin.ceiling", id = "karst_warp_z_noise_z_offset", min = -512.0, max = 512.0, step = 1.0, kind = PreviewSettingKind.OFFSET)
    public static final double KARST_DOME_WARP_Z_NOISE_Z_OFFSET = -47.0;

    @PreviewSetting(group = "snowdin.ceiling", id = "karst_primary_scale", min = 0.0001, max = 0.02, step = 0.0001, kind = PreviewSettingKind.SCALE)
    // 三层 karst fBm 权重构成一个归一化穹顶强度。
    public static final double KARST_DOME_PRIMARY_SCALE = 0.00135;

    @PreviewSetting(group = "snowdin.ceiling", id = "karst_secondary_scale", min = 0.0001, max = 0.04, step = 0.0001, kind = PreviewSettingKind.SCALE)
    public static final double KARST_DOME_SECONDARY_SCALE = 0.0031;

    @PreviewSetting(group = "snowdin.ceiling", id = "karst_tertiary_scale", min = 0.0001, max = 0.08, step = 0.0001, kind = PreviewSettingKind.SCALE)
    public static final double KARST_DOME_TERTIARY_SCALE = 0.0072;

    @PreviewSetting(group = "snowdin.ceiling", id = "karst_primary_weight", min = 0.0, max = 1.0, step = 0.01, kind = PreviewSettingKind.FACTOR)
    public static final double KARST_DOME_PRIMARY_WEIGHT = 0.58;

    @PreviewSetting(group = "snowdin.ceiling", id = "karst_secondary_weight", min = 0.0, max = 1.0, step = 0.01, kind = PreviewSettingKind.FACTOR)
    public static final double KARST_DOME_SECONDARY_WEIGHT = 0.29;

    @PreviewSetting(group = "snowdin.ceiling", id = "karst_tertiary_weight", min = 0.0, max = 1.0, step = 0.01, kind = PreviewSettingKind.FACTOR)
    public static final double KARST_DOME_TERTIARY_WEIGHT = 0.13;

    @PreviewSetting(group = "snowdin.ceiling", id = "karst_threshold", min = 0.0, max = 1.0, step = 0.01, kind = PreviewSettingKind.THRESHOLD)
    public static final double KARST_DOME_THRESHOLD = 0.5;

    @PreviewSetting(group = "snowdin.ceiling", id = "karst_normalize_range", min = 0.01, max = 1.0, step = 0.01, kind = PreviewSettingKind.FACTOR)
    public static final double KARST_DOME_NORMALIZE_RANGE = 0.5;

    @PreviewSetting(group = "snowdin.ceiling", id = "karst_extra_height", min = 0.0, max = 80.0, step = 0.5, kind = PreviewSettingKind.HEIGHT)
    public static final double KARST_DOME_EXTRA_HEIGHT = 12.0;

    @PreviewSetting(group = "snowdin.ceiling", id = "karst_ridge_scale", min = 0.0001, max = 0.08, step = 0.0001, kind = PreviewSettingKind.SCALE)
    // ridge 与 scallop 只刻画局部洞顶
    public static final double KARST_RIDGE_SCALE = 0.0048;

    @PreviewSetting(group = "snowdin.ceiling", id = "karst_ridge_height", min = 0.0, max = 40.0, step = 0.25, kind = PreviewSettingKind.HEIGHT)
    public static final double KARST_RIDGE_HEIGHT = 5.0;

    @PreviewSetting(group = "snowdin.ceiling", id = "scallop_noise_scale", min = 0.0001, max = 0.16, step = 0.0001, kind = PreviewSettingKind.SCALE)
    public static final double SCALLOP_NOISE_SCALE = 0.02;

    @PreviewSetting(group = "snowdin.ceiling", id = "scallop_height", min = 0.0, max = 16.0, step = 0.1, kind = PreviewSettingKind.HEIGHT)
    public static final double SCALLOP_HEIGHT = 1.2;

    @PreviewSetting(group = "snowdin.ceiling", id = "clouds2_dome_scale", min = 0.0001, max = 0.20, step = 0.0005, kind = PreviewSettingKind.SCALE)
    // Clouds2 四层权重经对比度映射后夹在 MASK_MIN..MASK_MAX。
    public static final double CLOUDS2_DOME_SCALE = 0.055;

    @PreviewSetting(group = "snowdin.ceiling", id = "clouds2_octave_1_weight", min = 0.0, max = 1.0, step = 0.01, kind = PreviewSettingKind.FACTOR)
    public static final double CLOUDS2_OCTAVE_1_WEIGHT = 0.48;

    @PreviewSetting(group = "snowdin.ceiling", id = "clouds2_octave_2_scale", min = 0.1, max = 12.0, step = 0.05, kind = PreviewSettingKind.SCALE)
    public static final double CLOUDS2_OCTAVE_2_SCALE = 2.03;

    @PreviewSetting(group = "snowdin.ceiling", id = "clouds2_octave_2_weight", min = 0.0, max = 1.0, step = 0.01, kind = PreviewSettingKind.FACTOR)
    public static final double CLOUDS2_OCTAVE_2_WEIGHT = 0.27;

    @PreviewSetting(group = "snowdin.ceiling", id = "clouds2_octave_3_scale", min = 0.1, max = 16.0, step = 0.05, kind = PreviewSettingKind.SCALE)
    public static final double CLOUDS2_OCTAVE_3_SCALE = 4.11;

    @PreviewSetting(group = "snowdin.ceiling", id = "clouds2_octave_3_weight", min = 0.0, max = 1.0, step = 0.01, kind = PreviewSettingKind.FACTOR)
    public static final double CLOUDS2_OCTAVE_3_WEIGHT = 0.16;

    @PreviewSetting(group = "snowdin.ceiling", id = "clouds2_octave_4_scale", min = 0.1, max = 24.0, step = 0.05, kind = PreviewSettingKind.SCALE)
    public static final double CLOUDS2_OCTAVE_4_SCALE = 8.37;

    @PreviewSetting(group = "snowdin.ceiling", id = "clouds2_octave_4_weight", min = 0.0, max = 1.0, step = 0.01, kind = PreviewSettingKind.FACTOR)
    public static final double CLOUDS2_OCTAVE_4_WEIGHT = 0.09;

    @PreviewSetting(group = "snowdin.ceiling", id = "clouds2_contrast", min = 0.1, max = 5.0, step = 0.05, kind = PreviewSettingKind.FACTOR)
    public static final double CLOUDS2_CONTRAST = 1.55;

    @PreviewSetting(group = "snowdin.ceiling", id = "clouds2_mask_min", min = 0.0, max = 2.0, step = 0.01, kind = PreviewSettingKind.MASK)
    public static final double CLOUDS2_MASK_MIN = 0.42;

    @PreviewSetting(group = "snowdin.ceiling", id = "clouds2_mask_max", min = 0.0, max = 2.0, step = 0.01, kind = PreviewSettingKind.MASK)
    public static final double CLOUDS2_MASK_MAX = 1.28;

    @PreviewSetting(group = "snowdin.ceiling", id = "relief_noise_scale", min = 0.0001, max = 0.16, step = 0.0005, kind = PreviewSettingKind.SCALE)
    // relief 权重之和必须与 WEIGHT_SUM 对应，保持总高度幅度稳定。
    public static final double CEILING_RELIEF_NOISE_SCALE = 0.028;

    @PreviewSetting(group = "snowdin.ceiling", id = "relief_height", min = 0.0, max = 40.0, step = 0.25, kind = PreviewSettingKind.HEIGHT)
    public static final double CEILING_RELIEF_HEIGHT = 13.0;

    @PreviewSetting(group = "snowdin.ceiling", id = "relief_primary_weight", min = 0.0, max = 1.0, step = 0.01, kind = PreviewSettingKind.FACTOR)
    public static final double CEILING_RELIEF_PRIMARY_WEIGHT = 0.65;

    @PreviewSetting(group = "snowdin.ceiling", id = "relief_secondary_scale_multiplier", min = 0.1, max = 12.0, step = 0.05, kind = PreviewSettingKind.SCALE)
    public static final double CEILING_RELIEF_SECONDARY_SCALE_MULTIPLIER = 2.9;

    @PreviewSetting(group = "snowdin.ceiling", id = "relief_secondary_weight", min = 0.0, max = 1.0, step = 0.01, kind = PreviewSettingKind.FACTOR)
    public static final double CEILING_RELIEF_SECONDARY_WEIGHT = 0.35;

    @PreviewSetting(group = "snowdin.ceiling", id = "relief_tertiary_scale_multiplier", min = 0.1, max = 20.0, step = 0.05, kind = PreviewSettingKind.SCALE)
    public static final double CEILING_RELIEF_TERTIARY_SCALE_MULTIPLIER = 6.0;

    @PreviewSetting(group = "snowdin.ceiling", id = "relief_tertiary_weight", min = 0.0, max = 1.0, step = 0.01, kind = PreviewSettingKind.FACTOR)
    public static final double CEILING_RELIEF_TERTIARY_WEIGHT = 0.18;

    @PreviewSetting(group = "snowdin.ceiling", id = "relief_weight_sum", min = 0.01, max = 4.0, step = 0.01, kind = PreviewSettingKind.FACTOR)
    public static final double CEILING_RELIEF_WEIGHT_SUM = 1.18;
}
