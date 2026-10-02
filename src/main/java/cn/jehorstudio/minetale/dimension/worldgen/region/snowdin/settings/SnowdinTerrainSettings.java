package cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.settings;

import cn.jehorstudio.minetale.dimension.worldgen.preview.PreviewSetting;
import cn.jehorstudio.minetale.dimension.worldgen.preview.PreviewSettingKind;

// Snowdin 大洞穴的共享地形常量；地面、台地、洞顶与钟乳石使用同一组参数
public final class SnowdinTerrainSettings {
    private SnowdinTerrainSettings() {}

    private static final double TERRACE_HEIGHT_SCALE = 1.5;

    // 台地水平与垂直缩放必须同时作用于对应频率、位移和高度参数。
    private static final double TERRACE_HORIZONTAL_SCALE = 2.0;

    // 地面与洞顶的反向位移共同定义扩展后的净高。
    public static final double GROUND_VERTICAL_SHIFT = -64.0;

    public static final double CEILING_VERTICAL_SHIFT = 64.0;

    public static final double CAVE_VERTICAL_EXPANSION = CEILING_VERTICAL_SHIFT - GROUND_VERTICAL_SHIFT;

    public static final double CEILING_DISTANCE_SCALE = 0.60;

    static final double CEILING_UNSCALED_BASE_HEIGHT = 50.0 + CAVE_VERTICAL_EXPANSION;

    private static final double CEILING_SHAPE_REFERENCE_SCALE = 0.75;

    private static final double CEILING_HEIGHT_REDUCTION_FROM_REFERENCE =
            CEILING_UNSCALED_BASE_HEIGHT
                    * (CEILING_SHAPE_REFERENCE_SCALE - CEILING_DISTANCE_SCALE);

    // 基础 carve、边缘羽化与柱体保留共同决定洞腔实体边界。
    public static final double MAIN_PATH_RADIUS_FACTOR = 1.28;

    public static final double FLOOR_FEATHER = 2.6;

    public static final double FLOOR_CARVE_OFFSET = 1.15;

    public static final double CEILING_FEATHER = 7.5;

    public static final double FULL_PILLAR_CARVE_MULTIPLIER = 0.08;

    public static final double CAVE_ROUGH_XZ_SCALE = 0.055;

    public static final double CAVE_ROUGH_Y_SCALE = 0.075;

    public static final double CAVE_ROUGH_STRENGTH = 0.035;

    public static final double CAVE_CARVE_STRENGTH = 0.97;

    public static final double INSIDE_FLOOR_MARGIN = 5.0;

    public static final double INSIDE_CEILING_MARGIN = 8.0;

    @PreviewSetting(group = "snowdin.ground", id = "floor_primary_noise_scale", min = 0.0001, max = 0.02, step = 0.0001, kind = PreviewSettingKind.SCALE)
    // 自然地面与过渡地面的 noise 参数分组独立，但最终汇入同一 floor profile。
    public static final double FLOOR_PRIMARY_NOISE_SCALE = 0.0014;

    @PreviewSetting(group = "snowdin.ground", id = "floor_primary_noise_strength", min = 0.0, max = 40.0, step = 0.25, kind = PreviewSettingKind.STRENGTH)
    public static final double FLOOR_PRIMARY_NOISE_STRENGTH = 10.0;

    @PreviewSetting(group = "snowdin.ground", id = "floor_secondary_noise_scale", min = 0.0001, max = 0.04, step = 0.0001, kind = PreviewSettingKind.SCALE)
    public static final double FLOOR_SECONDARY_NOISE_SCALE = 1.0E-4;

    @PreviewSetting(group = "snowdin.ground", id = "floor_secondary_noise_strength", min = 0.0, max = 20.0, step = 0.25, kind = PreviewSettingKind.STRENGTH)
    public static final double FLOOR_SECONDARY_NOISE_STRENGTH = 0.0;

    @PreviewSetting(group = "snowdin.ground", id = "plains_hills_mix.floor_primary_noise_scale", min = 0.0001, max = 0.02, step = 0.0001, kind = PreviewSettingKind.SCALE)
    // MIX_* 只控制 Region 过渡地面
    public static final double MIX_FLOOR_PRIMARY_NOISE_SCALE = 0.008;

    @PreviewSetting(group = "snowdin.ground", id = "plains_hills_mix.floor_primary_noise_strength", min = 0.0, max = 40.0, step = 0.25, kind = PreviewSettingKind.STRENGTH)
    public static final double MIX_FLOOR_PRIMARY_NOISE_STRENGTH = 16.0;

    @PreviewSetting(group = "snowdin.ground", id = "plains_hills_mix.floor_secondary_noise_scale", min = 0.0001, max = 0.04, step = 0.0001, kind = PreviewSettingKind.SCALE)
    public static final double MIX_FLOOR_SECONDARY_NOISE_SCALE = 0.0175;

    @PreviewSetting(group = "snowdin.ground", id = "plains_hills_mix.floor_secondary_noise_strength", min = 0.0, max = 20.0, step = 0.25, kind = PreviewSettingKind.STRENGTH)
    public static final double MIX_FLOOR_SECONDARY_NOISE_STRENGTH = 2.0;

    @PreviewSetting(group = "snowdin.ground", id = "plains_hills_mix.broad_relief_noise_scale", min = 0.0001, max = 0.01, step = 0.0001, kind = PreviewSettingKind.SCALE)
    public static final double MIX_BROAD_RELIEF_NOISE_SCALE = 0.002;

    @PreviewSetting(group = "snowdin.ground", id = "plains_hills_mix.broad_relief_noise_strength", min = 0.0, max = 40.0, step = 0.25, kind = PreviewSettingKind.STRENGTH)
    public static final double MIX_BROAD_RELIEF_NOISE_STRENGTH = 16.0;

    @PreviewSetting(group = "snowdin.ground", id = "plains_hills_mix.wild_floor_main_path_offset", min = -80.0, max = 20.0, step = 0.5, kind = PreviewSettingKind.OFFSET)
    public static final double MIX_WILD_FLOOR_MAIN_PATH_OFFSET = -18.0;

    @PreviewSetting(group = "snowdin.ground", id = "plains_hills_mix.road_floor_main_path_offset", min = -40.0, max = 20.0, step = 0.5, kind = PreviewSettingKind.OFFSET)
    public static final double MIX_ROAD_FLOOR_MAIN_PATH_OFFSET = -6.5;

    @PreviewSetting(group = "snowdin.ground", id = "plains_hills_mix.road_floor_broad_noise_scale", min = 0.0, max = 1.0, step = 0.01, kind = PreviewSettingKind.FACTOR)
    public static final double MIX_ROAD_FLOOR_BROAD_NOISE_SCALE = 0.2;

    @PreviewSetting(group = "snowdin.ground", id = "plains_hills_mix.terrace_base_trench_depth", min = 0.0, max = 24.0, step = 0.25, kind = PreviewSettingKind.HEIGHT)
    public static final double MIX_TERRACE_BASE_TRENCH_DEPTH = 1.5;

    @PreviewSetting(group = "snowdin.ground", id = "plateau_mask.noise_scale", min = 0.0001, max = 0.02, step = 0.0001, kind = PreviewSettingKind.SCALE)
    // 台地 presence、domain warp 与阶梯 profile 必须保持相同的水平缩放基准。
    public static final double PLATEAU_MASK_NOISE_SCALE = 0.0012 / TERRACE_HORIZONTAL_SCALE;

    @PreviewSetting(group = "snowdin.ground", id = "plateau_mask.threshold", min = 0.0, max = 1.0, step = 0.01, kind = PreviewSettingKind.THRESHOLD)
    public static final double PLATEAU_MASK_THRESHOLD = 0.52;

    @PreviewSetting(group = "snowdin.ground", id = "plateau_mask.fade", min = 0.01, max = 1.0, step = 0.01, kind = PreviewSettingKind.FACTOR)
    public static final double PLATEAU_MASK_FADE = 0.02;

    @PreviewSetting(group = "snowdin.ground", id = "plateau_mask.warp_scale", min = 0.0001, max = 0.02, step = 0.0001, kind = PreviewSettingKind.SCALE)
    public static final double PLATEAU_MASK_WARP_SCALE = 0.0051 / TERRACE_HORIZONTAL_SCALE;

    @PreviewSetting(group = "snowdin.ground", id = "plateau_mask.warp_strength", min = 0.0, max = 120.0, step = 0.5, kind = PreviewSettingKind.STRENGTH)
    public static final double PLATEAU_MASK_WARP_STRENGTH = 36.5 * TERRACE_HORIZONTAL_SCALE;

    @PreviewSetting(group = "snowdin.ground", id = "plateau_mask.warp_z_noise_x_offset", min = -512.0, max = 512.0, step = 1.0, kind = PreviewSettingKind.OFFSET)
    public static final double PLATEAU_MASK_WARP_Z_NOISE_X_OFFSET = 137.0;

    @PreviewSetting(group = "snowdin.ground", id = "plateau_mask.warp_z_noise_z_offset", min = -512.0, max = 512.0, step = 1.0, kind = PreviewSettingKind.OFFSET)
    public static final double PLATEAU_MASK_WARP_Z_NOISE_Z_OFFSET = -89.0;

    @PreviewSetting(group = "snowdin.ground", id = "terrace_warp_scale", min = 0.0001, max = 0.04, step = 0.0001, kind = PreviewSettingKind.SCALE)
    public static final double TERRACE_WARP_SCALE = 0.0095 / TERRACE_HORIZONTAL_SCALE;

    @PreviewSetting(group = "snowdin.ground", id = "terrace_warp_strength", min = 0.0, max = 40.0, step = 0.25, kind = PreviewSettingKind.STRENGTH)
    public static final double TERRACE_WARP_STRENGTH = 9.0 * TERRACE_HORIZONTAL_SCALE;

    @PreviewSetting(group = "snowdin.ground", id = "terrace_primary_noise_scale", min = 0.0001, max = 0.06, step = 0.0001, kind = PreviewSettingKind.SCALE)
    public static final double TERRACE_PRIMARY_NOISE_SCALE = 0.0139 / TERRACE_HORIZONTAL_SCALE;

    @PreviewSetting(group = "snowdin.ground", id = "terrace_secondary_noise_scale", min = 0.00005, max = 0.12, step = 0.00005, kind = PreviewSettingKind.SCALE)
    public static final double TERRACE_SECONDARY_NOISE_SCALE = 1.0E-4 / TERRACE_HORIZONTAL_SCALE;

    @PreviewSetting(group = "snowdin.ground", id = "terrace_detail_noise_scale", min = 0.0001, max = 0.20, step = 0.0001, kind = PreviewSettingKind.SCALE)
    public static final double TERRACE_DETAIL_NOISE_SCALE = 0.0617 / TERRACE_HORIZONTAL_SCALE;

    @PreviewSetting(group = "snowdin.ground", id = "terrace_valley_floor_half_width", min = 0.0, max = 80.0, step = 0.5, kind = PreviewSettingKind.OTHER)
    public static final double TERRACE_VALLEY_FLOOR_HALF_WIDTH = 18.0;

    @PreviewSetting(group = "snowdin.ground", id = "terrace_valley_fade_width", min = 0.1, max = 40.0, step = 0.25, kind = PreviewSettingKind.OTHER)
    public static final double TERRACE_VALLEY_FADE_WIDTH = 2.0;

    @PreviewSetting(group = "snowdin.ground", id = "terrace_max_top_offset", min = 0.0, max = 96.0, step = 0.5, kind = PreviewSettingKind.HEIGHT)
    public static final double TERRACE_MAX_TOP_OFFSET = 50.0 * TERRACE_HEIGHT_SCALE;

    @PreviewSetting(group = "snowdin.ground", id = "terrace_plateau_size", min = 0.0, max = 96.0, step = 0.5, kind = PreviewSettingKind.HEIGHT)
    public static final double TERRACE_PLATEAU_SIZE = 40.0 * TERRACE_HEIGHT_SCALE;

    @PreviewSetting(group = "snowdin.ground", id = "terrace_plateau_steps", min = 1.0, max = 16.0, step = 1.0, kind = PreviewSettingKind.COUNT)
    public static final int TERRACE_PLATEAU_STEPS = 5;

    @PreviewSetting(group = "snowdin.ground", id = "terrace_curved_top_height", min = 0.0, max = 32.0, step = 0.25, kind = PreviewSettingKind.HEIGHT)
    public static final double TERRACE_CURVED_TOP_HEIGHT = 7.0 * TERRACE_HEIGHT_SCALE;

    @PreviewSetting(group = "snowdin.ground", id = "terrace_curved_top_steps", min = 1.0, max = 24.0, step = 1.0, kind = PreviewSettingKind.COUNT)
    public static final int TERRACE_CURVED_TOP_STEPS = 5;

    @PreviewSetting(group = "snowdin.ground", id = "terrace_secondary_noise_strength", min = 0.0, max = 8.0, step = 0.1, kind = PreviewSettingKind.STRENGTH)
    public static final double TERRACE_SECONDARY_NOISE_STRENGTH = 0.0;

    @PreviewSetting(group = "snowdin.ground", id = "terrace_detail_noise_strength", min = 0.0, max = 4.0, step = 0.05, kind = PreviewSettingKind.STRENGTH)
    public static final double TERRACE_DETAIL_NOISE_STRENGTH = 0.0;

    @PreviewSetting(group = "snowdin.ground", id = "terrace_channel_warp_scale", min = 0.0001, max = 0.03, step = 0.0001, kind = PreviewSettingKind.SCALE)
    // channel 与中心沟槽只重塑台地顶部，不改变其所属列的基础地面。
    public static final double TERRACE_CHANNEL_WARP_SCALE = 0.0178 / TERRACE_HORIZONTAL_SCALE;

    @PreviewSetting(group = "snowdin.ground", id = "terrace_channel_warp_strength", min = 0.0, max = 160.0, step = 1.0, kind = PreviewSettingKind.STRENGTH)
    public static final double TERRACE_CHANNEL_WARP_STRENGTH = 60.0 * TERRACE_HORIZONTAL_SCALE;

    @PreviewSetting(group = "snowdin.ground", id = "terrace_channel_noise_scale", min = 0.0001, max = 0.04, step = 0.0001, kind = PreviewSettingKind.SCALE)
    public static final double TERRACE_CHANNEL_NOISE_SCALE = 0.0065 / TERRACE_HORIZONTAL_SCALE;

    @PreviewSetting(group = "snowdin.ground", id = "terrace_channel_width", min = 0.0, max = 1.0, step = 0.01, kind = PreviewSettingKind.FACTOR)
    public static final double TERRACE_CHANNEL_WIDTH = 0.12;

    @PreviewSetting(group = "snowdin.ground", id = "terrace_channel_fade", min = 0.01, max = 1.0, step = 0.01, kind = PreviewSettingKind.FACTOR)
    public static final double TERRACE_CHANNEL_FADE = 0.01;

    @PreviewSetting(group = "snowdin.ground", id = "terrace_center_trench_depth", min = 0.0, max = 24.0, step = 0.25, kind = PreviewSettingKind.HEIGHT)
    public static final double TERRACE_CENTER_TRENCH_DEPTH = 4.0;

    @PreviewSetting(group = "snowdin.ground", id = "terrace_center_trench_half_width", min = 0.0, max = 40.0, step = 0.25, kind = PreviewSettingKind.OTHER)
    public static final double TERRACE_CENTER_TRENCH_HALF_WIDTH = 7.0;

    @PreviewSetting(group = "snowdin.ground", id = "terrace_center_trench_fade", min = 0.1, max = 40.0, step = 0.25, kind = PreviewSettingKind.OTHER)
    public static final double TERRACE_CENTER_TRENCH_FADE = 10.0;

    @PreviewSetting(group = "snowdin.ground", id = "terrace_step_height", min = 0.25, max = 16.0, step = 0.25, kind = PreviewSettingKind.HEIGHT)
    public static final double TERRACE_STEP_HEIGHT = 5.0 * TERRACE_HEIGHT_SCALE;

    @PreviewSetting(group = "snowdin.ground", id = "terrace_edge_blend", min = 0.001, max = 0.5, step = 0.001, kind = PreviewSettingKind.FACTOR)
    public static final double TERRACE_EDGE_BLEND = 0.001;

    @PreviewSetting(group = "snowdin.ground", id = "terrace_min_top_offset", min = 0.0, max = 24.0, step = 0.25, kind = PreviewSettingKind.HEIGHT)
    public static final double TERRACE_MIN_TOP_OFFSET = 4.0;

    @PreviewSetting(group = "snowdin.ground", id = "terrace_min_top_fade", min = 0.1, max = 24.0, step = 0.25, kind = PreviewSettingKind.OTHER)
    public static final double TERRACE_MIN_TOP_FADE = 1.0;

    public static final double TERRACE_TOP_CARVE_OFFSET = 0.7;

    public static final double TERRACE_TOP_FEATHER = 1.2;

    public static final double TERRACE_FULL_CARVE_MULTIPLIER = 0.015;

    @PreviewSetting(group = "snowdin.ground", id = "terrace_ceiling_clearance", min = 0.0, max = 48.0, step = 0.5, kind = PreviewSettingKind.HEIGHT)
    public static final double TERRACE_CEILING_CLEARANCE = 14.0;

    @PreviewSetting(group = "snowdin.ground", id = "terrace_visible_floor_offset", min = 0.0, max = 8.0, step = 0.1, kind = PreviewSettingKind.HEIGHT)
    public static final double TERRACE_VISIBLE_FLOOR_OFFSET = 0.6;

    @PreviewSetting(group = "snowdin.ground", id = "terrace_visible_floor_fade", min = 0.1, max = 16.0, step = 0.1, kind = PreviewSettingKind.OTHER)
    public static final double TERRACE_VISIBLE_FLOOR_FADE = 3.0;

    @PreviewSetting(group = "snowdin.ground", id = "terrace_flatten_window_radius", min = 0.0, max = 64.0, step = 1.0, kind = PreviewSettingKind.OTHER)
    public static final double TERRACE_FLATTEN_WINDOW_RADIUS = 18.0;

    @PreviewSetting(group = "snowdin.ground", id = "terrace_flatten_core_threshold", min = 0.0, max = 1.0, step = 0.01, kind = PreviewSettingKind.THRESHOLD)
    public static final double TERRACE_FLATTEN_CORE_THRESHOLD = 0.7;

    @PreviewSetting(group = "snowdin.ground", id = "terrace_flatten_transition_fade", min = 0.01, max = 0.5, step = 0.01, kind = PreviewSettingKind.FACTOR)
    public static final double TERRACE_FLATTEN_TRANSITION_FADE = 0.04;

    public static final double TERRACE_PILLAR_FLOOR_OFFSET = 0.4;

    public static final double TERRACE_PILLAR_FOOT_FEATHER = 1.4;

    @PreviewSetting(group = "snowdin.ground", id = "wild_floor_main_path_offset", min = -80.0, max = 20.0, step = 0.5, kind = PreviewSettingKind.OFFSET)
    // 道路影响在野外与道路 profile 之间平滑混合，并使用独立 wobble。
    public static final double WILD_FLOOR_MAIN_PATH_OFFSET = -22.0;

    @PreviewSetting(group = "snowdin.ground", id = "road_floor_main_path_offset", min = -40.0, max = 20.0, step = 0.5, kind = PreviewSettingKind.OFFSET)
    public static final double ROAD_FLOOR_MAIN_PATH_OFFSET = -5.0;

    @PreviewSetting(group = "snowdin.ground", id = "road_floor_broad_noise_scale", min = 0.0, max = 1.0, step = 0.01, kind = PreviewSettingKind.FACTOR)
    public static final double ROAD_FLOOR_BROAD_NOISE_SCALE = 0.16;

    @PreviewSetting(group = "snowdin.ground", id = "road_wobble_primary_scale", min = 0.0001, max = 0.08, step = 0.0001, kind = PreviewSettingKind.SCALE)
    public static final double ROAD_WOBBLE_PRIMARY_SCALE = 0.01;

    @PreviewSetting(group = "snowdin.ground", id = "road_wobble_primary_strength", min = 0.0, max = 8.0, step = 0.1, kind = PreviewSettingKind.STRENGTH)
    public static final double ROAD_WOBBLE_PRIMARY_STRENGTH = 1.1;

    @PreviewSetting(group = "snowdin.ground", id = "road_wobble_secondary_scale", min = 0.0001, max = 0.16, step = 0.0001, kind = PreviewSettingKind.SCALE)
    public static final double ROAD_WOBBLE_SECONDARY_SCALE = 0.035;

    @PreviewSetting(group = "snowdin.ground", id = "road_wobble_secondary_strength", min = 0.0, max = 4.0, step = 0.05, kind = PreviewSettingKind.STRENGTH)
    public static final double ROAD_WOBBLE_SECONDARY_STRENGTH = 0.45;

    @PreviewSetting(group = "snowdin.ground", id = "floor_min_y", min = -64.0, max = 64.0, step = 1.0, kind = PreviewSettingKind.HEIGHT)
    // 最终地面高度必须夹在该区间内。
    public static final double FLOOR_MIN_Y = -44.0;

    @PreviewSetting(group = "snowdin.ground", id = "floor_max_y", min = -32.0, max = 116.0, step = 1.0, kind = PreviewSettingKind.HEIGHT)
    public static final double FLOOR_MAX_Y = 51.0;

    // 洞顶由基础 noise、dome、karst、Clouds2 mask 与 relief 合成后再受高度边界约束。
    public static final double CEILING_BASE_HEIGHT = 54.0 + CAVE_VERTICAL_EXPANSION;

    public static final double CEILING_PRIMARY_NOISE_SCALE = 0.0016;

    public static final double CEILING_PRIMARY_NOISE_STRENGTH = 28.0;

    public static final double CEILING_SECONDARY_NOISE_SCALE = 0.006;

    public static final double CEILING_SECONDARY_NOISE_STRENGTH = 8.0;

    public static final double DOME_NOISE_SCALE = 0.0011;

    public static final double DOME_NOISE_X_OFFSET = 81.0;

    public static final double DOME_NOISE_Z_OFFSET = -37.0;

    public static final double DOME_THRESHOLD = 0.62;

    public static final double DOME_NORMALIZE_RANGE = 0.38;

    public static final double DOME_EXTRA_HEIGHT = 18.0;

    public static final double KARST_DOME_WARP_SCALE = 0.0014;

    public static final double KARST_DOME_WARP_STRENGTH = 24.0;

    public static final double KARST_DOME_WARP_Z_NOISE_X_OFFSET = 91.0;

    public static final double KARST_DOME_WARP_Z_NOISE_Z_OFFSET = -47.0;

    public static final double KARST_DOME_PRIMARY_SCALE = 0.00135;

    public static final double KARST_DOME_SECONDARY_SCALE = 0.0031;

    public static final double KARST_DOME_TERTIARY_SCALE = 0.0072;

    public static final double KARST_DOME_PRIMARY_WEIGHT = 0.58;

    public static final double KARST_DOME_SECONDARY_WEIGHT = 0.29;

    public static final double KARST_DOME_TERTIARY_WEIGHT = 0.13;

    public static final double KARST_DOME_THRESHOLD = 0.50;

    public static final double KARST_DOME_NORMALIZE_RANGE = 0.50;

    public static final double KARST_DOME_EXTRA_HEIGHT = 14.0;

    public static final double KARST_RIDGE_SCALE = 0.0048;

    public static final double KARST_RIDGE_HEIGHT = 5.0;

    public static final double SOLUTION_POCKET_CELL_SIZE = 72.0;

    public static final double SOLUTION_POCKET_RADIUS = 0.62;

    public static final double SOLUTION_POCKET_POWER = 2.3;

    public static final double SOLUTION_POCKET_EXTRA_HEIGHT = 0.0;

    public static final double SCALLOP_NOISE_SCALE = 0.020;

    public static final double SCALLOP_HEIGHT = 1.2;

    public static final double CLOUDS2_DOME_SCALE = 0.055;

    public static final double CLOUDS2_OCTAVE_1_WEIGHT = 0.48;

    public static final double CLOUDS2_OCTAVE_2_SCALE = 2.03;

    public static final double CLOUDS2_OCTAVE_2_WEIGHT = 0.27;

    public static final double CLOUDS2_OCTAVE_3_SCALE = 4.11;

    public static final double CLOUDS2_OCTAVE_3_WEIGHT = 0.16;

    public static final double CLOUDS2_OCTAVE_4_SCALE = 8.37;

    public static final double CLOUDS2_OCTAVE_4_WEIGHT = 0.09;

    public static final double CLOUDS2_CONTRAST = 1.55;

    public static final double CLOUDS2_MASK_MIN = 0.42;

    public static final double CLOUDS2_MASK_MAX = 1.28;

    public static final double CEILING_RELIEF_NOISE_SCALE = 0.028;

    public static final double CEILING_RELIEF_HEIGHT = 11.0;

    public static final double CEILING_RELIEF_PRIMARY_WEIGHT = 0.65;

    public static final double CEILING_RELIEF_SECONDARY_SCALE_MULTIPLIER = 2.9;

    public static final double CEILING_RELIEF_SECONDARY_WEIGHT = 0.35;

    public static final double CEILING_RELIEF_TERTIARY_SCALE_MULTIPLIER = 6.0;

    public static final double CEILING_RELIEF_TERTIARY_WEIGHT = 0.18;

    public static final double CEILING_RELIEF_WEIGHT_SUM = 1.18;

    // 钟乳石候选密度、长度、半径与 carve 保留量共同定义连续实体。
    public static final double STALACTITE_CELL_SIZE = 21.2;

    public static final int STALACTITE_NEIGHBOR_CELL_RADIUS = 1;

    public static final double STALACTITE_GENERATION_THRESHOLD = 0.48;

    public static final double STALACTITE_LENGTH_BASE = 9.0;

    public static final double STALACTITE_LENGTH_RANGE = 18.0;

    public static final double STALACTITE_MAX_HEIGHT_FRACTION = 0.42;

    public static final double STALACTITE_RADIUS_BASE = 3.2;

    public static final double STALACTITE_RADIUS_RANGE = 5.0;

    public static final double STALACTITE_CENTER_JITTER_CELL_SCALE = 0.28;

    public static final double STALACTITE_RADIUS_TAPER_POWER = 1.05;

    public static final double STALACTITE_MIN_RADIUS = 1.2;

    public static final double STALACTITE_VISIBLE_LENGTH_FRACTION = 0.90;

    public static final double STALACTITE_ROOT_BULGE_FRACTION = 0.16;

    public static final double STALACTITE_ROOT_BULGE_STRENGTH = 0.35;

    public static final double STALACTITE_LEAN_STRENGTH = 1.8;

    public static final double STALACTITE_EDGE_NOISE_SCALE = 0.18;

    public static final double STALACTITE_EDGE_NOISE_STRENGTH = 0.18;

    public static final double FULL_STALACTITE_CARVE_MULTIPLIER = 0.03;

    // 洞顶相对地面和世界顶层的边界必须为钟乳石与局部起伏保留空间。
    public static final double CEILING_MIN_ABOVE_FLOOR =
            (36.0 + CAVE_VERTICAL_EXPANSION) * CEILING_SHAPE_REFERENCE_SCALE
                    - CEILING_HEIGHT_REDUCTION_FROM_REFERENCE;

    public static final double CEILING_MAX_ABOVE_FLOOR =
            (96.0 + CAVE_VERTICAL_EXPANSION) * CEILING_SHAPE_REFERENCE_SCALE
                    - CEILING_HEIGHT_REDUCTION_FROM_REFERENCE;

    public static final double CEILING_MAX_Y = 248.0 + CEILING_VERTICAL_SHIFT;

    @PreviewSetting(group = "snowdin.ground", id = "road_influence_core_distance", min = 0.0, max = 80.0, step = 0.5, kind = PreviewSettingKind.OTHER)
    // 道路保护从核心距离开始，在 fade 范围内过渡到野外地形。
    public static final double ROAD_INFLUENCE_CORE_DISTANCE = 15.0;

    @PreviewSetting(group = "snowdin.ground", id = "road_influence_fade_distance", min = 0.1, max = 160.0, step = 0.5, kind = PreviewSettingKind.OTHER)
    public static final double ROAD_INFLUENCE_FADE_DISTANCE = 50.0;
}
