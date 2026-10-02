package cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.element.pillar.settings;

// 石柱通过降低 cave carve mask 保留实体体积，不作为放置型 Feature 生成。
public final class SnowdinPillarSettings {
    private SnowdinPillarSettings() {}

    // CELL_SIZE、阈值与邻域半径共同决定候选密度和跨 cell 采样覆盖。
    public static final double CELL_SIZE = 83.45;

    public static final double GENERATION_THRESHOLD = 0.0;

    public static final int NEIGHBOR_CELL_RADIUS = 1;

    // 道路清空、中心扰动与倾斜只改变候选外形，不得破坏柱体上下连续性。
    public static final double ROAD_CLEAR_START_DISTANCE = 12.0;

    public static final double ROAD_CLEAR_FADE_DISTANCE = 24.0;

    public static final double CENTER_JITTER_CELL_SCALE = 0.10;

    public static final double LEAN_STRENGTH = 2.4;

    public static final double NORMALIZED_HEIGHT_RANGE_SCALE = 2.0;

    // 风格编号必须处于 STYLE_COUNT 范围，并与 mask 中的 profile 分支一一对应。
    public static final int STYLE_COUNT = 4;

    public static final int STYLE_WAIST = 1;

    public static final int STYLE_TOP_HEAVY = 2;

    public static final int STYLE_WAVE = 3;

    public static final double ANGLE_TWIST_SCALE = 1.35;

    public static final double ANGLE_TWIST_STRENGTH = 0.12;

    public static final double ANGLE_CELL_X_SCALE = 0.37;

    public static final double ANGLE_CELL_Z_SCALE = 0.37;

    public static final double ANGLE_TWIST_CELL_X_OFFSET_SCALE = 0.17;

    public static final double ANGLE_TWIST_CELL_Z_OFFSET_SCALE = 0.11;

    // 两轴半径独立采样，形成具有稳定间距的椭圆截面。
    public static final double RADIUS_X_BASE = 15.0;

    public static final double RADIUS_X_RANGE = 19.0;

    public static final double RADIUS_X_NOISE_CELL_X_SCALE = 0.29;

    public static final double RADIUS_X_NOISE_CELL_Z_SCALE = 0.29;

    public static final double RADIUS_Z_BASE = 17.0;

    public static final double RADIUS_Z_RANGE = 25.0;

    public static final double TERRACE_TOP_RADIUS_SCALE = 0.42;

    public static final double RADIUS_Z_NOISE_CELL_X_SCALE = 0.41;

    public static final double RADIUS_Z_NOISE_CELL_Z_SCALE = 0.41;

    // 岩壁变体的概率由模数和百分比阈值共同定义；阈值为 0 时禁用。
    public static final int WALL_STYLE_HASH_MODULO = 100;

    public static final int WALL_STYLE_CHANCE_PERCENT = 0;

    public static final double WALL_STYLE_Z_RADIUS_SCALE = 1.28;

    public static final double WALL_STYLE_X_RADIUS_SCALE = 0.90;

    // 纵向 profile 同时负责收腰、柱脚和柱顶连接，并在末端统一夹取。
    public static final double WAIST_STRENGTH = 0.18;

    public static final double FOOT_FLARE_STRENGTH = 0.42;

    public static final double FOOT_FLARE_POWER = 4.0;

    public static final double CAP_FLARE_STRENGTH = 0.30;

    public static final double CAP_FLARE_POWER = 4.0;

    public static final double TIER_NOISE_VERTICAL_SCALE = 2.2;

    public static final double TIER_NOISE_CELL_X_OFFSET_SCALE = 0.23;

    public static final double TIER_NOISE_CELL_Z_OFFSET_SCALE = 0.19;

    public static final double TIER_RADIUS_BASE = 0.92;

    public static final double TIER_RADIUS_STRENGTH = 0.16;

    public static final double NOISE_CENTER = 0.5;

    // 表面 noise 只能扰动边界，不能大到切断柱体主体。
    public static final double CRACK_XZ_SCALE = 0.018;

    public static final double CRACK_Y_SCALE = 0.030;

    public static final double CRACK_STRENGTH = 0.045;

    // 各风格参数由 STYLE_* 编号选择，基础 profile 不额外保存同类状态。
    public static final double STYLE_WAIST_BASE = 0.94;

    public static final double STYLE_WAIST_STRENGTH = 0.12;

    public static final double STYLE_WAIST_CENTER = 0.52;

    public static final double STYLE_WAIST_DISTANCE_SCALE = 2.0;

    public static final double STYLE_WAIST_POWER = 1.7;

    public static final double STYLE_TOP_HEAVY_BASE = 0.94;

    public static final double STYLE_TOP_HEAVY_STRENGTH = 0.12;

    public static final double STYLE_TOP_HEAVY_START = 0.58;

    public static final double STYLE_TOP_HEAVY_FADE = 0.22;

    public static final double STYLE_WAVE_VERTICAL_SCALE = 2.0;

    public static final double STYLE_WAVE_STRENGTH = 0.055;

    // profile 上下界是连续性保护；X/Z 可采用不同最大值。
    public static final double PROFILE_MIN = 0.74;

    public static final double PROFILE_X_MAX = 1.42;

    public static final double PROFILE_Z_MAX = 1.48;

    public static final double PROFILE_Z_TIER_BASE = 0.96;

    public static final double PROFILE_Z_TIER_STRENGTH = 0.08;

    // 边缘 3D noise 与水平细节 noise 叠加形成最终截面起伏。
    public static final double EDGE_NOISE_XZ_SCALE = 0.040;

    public static final double EDGE_NOISE_Y_SCALE = 0.020;

    public static final double EDGE_NOISE_STRENGTH = 0.08;

    public static final double EDGE_DETAIL_NOISE_SCALE = 0.075;

    public static final double EDGE_DETAIL_NOISE_STRENGTH = 0.035;
}
