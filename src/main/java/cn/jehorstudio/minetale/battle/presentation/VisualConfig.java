package cn.jehorstudio.minetale.battle.presentation;

import cn.jehorstudio.minetale.battle.logic.coordinate.CanonicalVec3;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import net.neoforged.neoforge.common.ModConfigSpec;

// 大写字段是源码默认值，同名方法返回配置覆盖后的生效值。
public final class VisualConfig {
    public static final int REMOTE_TRACK_MAX_SAMPLES = 8;
    public static final double REMOTE_SAMPLE_DELAY_SECONDS = 0.15D;
    public static final double REMOTE_HEALTH_DEGRADED_AFTER_SECONDS = 0.25D;
    public static final double REMOTE_HEALTH_LOST_AFTER_SECONDS = 1.0D;

    public static final double SCREEN_SHAKE_VIEWPORT_SCALE = 0.02D;
    public static final double SCREEN_SHAKE_SAMPLES_PER_SECOND = 24.0D;

    public static final double DEBUG_SCENE_TRANSITION_SECONDS = 1.4D;
    public static final double SCENE_RENDER_MODE_ENTER_DELAY_SECONDS = 2D;
    public static final double SCENE_RENDER_MODE_CROSSFADE_SECONDS = 2D;
    public static final double DEBUG_PROJECTION_TRANSITION_SECONDS = 0.45D;
    public static final double DEBUG_CAMERA_ROTATION_SECONDS = 0.18D;
    public static final double DEBUG_3D_VIEW_SCALE = 0.82D;
    public static final double DEBUG_ORBIT_INITIAL_YAW_DEGREES = 35.0D;
    public static final double DEBUG_ORBIT_INITIAL_PITCH_DEGREES = 22.5D;
    public static final double DEBUG_ORBIT_MAX_PITCH_DEGREES = 85.0D;
    public static final double DEBUG_ORBIT_ROTATION_STEP_DEGREES = 5.0D;
    public static final double DEBUG_ORBIT_DISTANCE = 7.5D;
    public static final double DEBUG_ORBIT_FOV_DEGREES = 45.0D;
    public static final double DEBUG_REMOTE_SOUL_ORBIT_RADIUS = 0.55D;
    public static final double DEBUG_REMOTE_SOUL_ORBIT_RADIANS_PER_SECOND = 1.4D;

    public static final float SCENE_NEAR_PLANE = 0.01F;
    public static final float SCENE_FAR_PLANE = 128.0F;
    public static final float SCENE_CLIP_DISTANCE_PADDING = 64.0F;
    public static final float SPRITE_PIXELS_PER_CANONICAL_UNIT = 80.0F;
    public static final float SOUL_MODEL_SCALE = 0.1F;
    public static final float SOUL_MODEL_FRONT_YAW_DEGREES = 90.0F;
    public static final float SOUL_MODEL_CAMERA_YAW_OFFSET_DEGREES = 120.0F;
    public static final float SOUL_TINT_LIGHT_RADIUS = 1.25F;
    // 表面点光强度独立于体积散射，不得作为体积光能量来源。
    public static final float SOUL_TINT_LIGHT_EMISSION_STRENGTH = 0.65F;
    public static final boolean SOUL_VOLUMETRIC_LIGHT_ENABLED = true;
    public static final int SOUL_POINT_SHADOW_FACE_SIZE = 256;
    public static final int SOUL_POINT_SHADOW_BORDER = 1;
    public static final float SOUL_POINT_SHADOW_NEAR_PLANE = 0.01F;
    public static final float SOUL_POINT_SHADOW_RADIAL_BIAS = 0.0020F;
    public static final float SOUL_VOLUMETRIC_RADIUS = 2.0F;
    public static final float SOUL_VOLUMETRIC_CORE_RADIUS = 0.166F;
    public static final float SOUL_VOLUMETRIC_CUTOFF_FEATHER = 1.0F;
    public static final float SOUL_VOLUMETRIC_PHASE_G = 0.0F;
    // 体积散射唯一强度，只乘可见 source integral，不参与介质消光、全局 Alpha 或表面点光。
    public static final float SOUL_VOLUMETRIC_INTENSITY = 360.0F;
    public static final float SOUL_VOLUMETRIC_RADIANCE_SOFT_CLIP = 0.333F;
    public static final float SOUL_VOLUMETRIC_GRID_TRANSMISSION = 0.5F;
    public static final float SOUL_VOLUMETRIC_SHADOW_STRENGTH = 1.0F;
    public static final float SOUL_VOLUMETRIC_MINIMUM_RADIANCE_MULTIPLIER = 0.0F;
    public static final float SOUL_VOLUMETRIC_SHADOW_SOURCE_GAIN = 6.0F;
    public static final float SOUL_VOLUMETRIC_SHADOW_EXTINCTION_GAIN = 0.1F;
    public static final boolean SOUL_VOLUMETRIC_TONE_MAPPING_ENABLED = false;
    public static final float SOUL_VOLUMETRIC_TONE_MAPPING_EXPOSURE = 1.0F;
    public static final float SOUL_VOLUMETRIC_TONE_MAPPING_CONTRAST = 1.0F;
    public static final float SOUL_VOLUMETRIC_TONE_MAPPING_PIVOT = 0.30F;
    public static final float SOUL_SURFACE_LIGHT_INTENSITY = 4.0F;
    public static final float SOUL_SURFACE_LIGHT_SHADOW_BIAS = 0.0020F;
    // 固定前缀积分规模与逐像素访问预算；预算耗尽时 Shader 走保守退化路径。
    public static final int SOUL_VOLUMETRIC_SOURCE_SEGMENTS = 16;
    public static final int SOUL_VOLUMETRIC_MAX_HIERARCHY_VISITS = 256;
    public static final int SOUL_VOLUMETRIC_MAX_TEXEL_VISITS = 128;
    public static final float SOUL_VOLUMETRIC_TRAVERSAL_EPSILON = 1.0E-5F;
    public static final SoulVolumetricDebugMode SOUL_VOLUMETRIC_DEBUG_MODE =
            SoulVolumetricDebugMode.NORMAL;

    public static final boolean SOUL_GUIDE_GRID_ENABLED = true;
    public static final float SOUL_GUIDE_GRID_DENSITY = 8.0F;
    // 偏移沿 BattleFrame 局部 +Z，即 canonical 下方。
    public static final float SOUL_GUIDE_GRID_HEIGHT_OFFSET = 0.0F;
    public static final float SOUL_GUIDE_GRID_LINE_WIDTH = 0.5F;
    public static final int SOUL_GUIDE_GRID_COLOR = 0x606060;
    public static final float SOUL_GUIDE_GRID_ALPHA = 0.8F;
    public static final int SOUL_GUIDE_FILL_COLOR = 0x525252;
    public static final float SOUL_GUIDE_FILL_ALPHA = 0.7F;

    // OFF 不分配 Bloom 专用目标。
    public enum PostQuality {
        OFF(0),
        LOW(4),
        MEDIUM(4),
        HIGH(9);

        private final int firstLevelFilterSamples;

        PostQuality(int firstLevelFilterSamples) {
            this.firstLevelFilterSamples = firstLevelFilterSamples;
        }

        public int firstLevelFilterSamples() {
            return this.firstLevelFilterSamples;
        }
    }

    public static final boolean POST_BLOOM_ENABLED = false;
    public static final PostQuality POST_BLOOM_QUALITY = PostQuality.MEDIUM;
    // 曲线作用于 *_s.png 的 B 通道编码能量，1 表示线性读取。
    public static final float POST_BLOOM_EMISSIVE_CURVE = 1.0F;
    public static final float POST_BLOOM_INTENSITY = 2.6F;
    public static final float POST_BLOOM_BLUR_RADIUS_PIXELS = 5.3F;

    public static final boolean ENVIRONMENT_BACKGROUND_ENABLED = true;
    public static final float ENVIRONMENT_BACKGROUND_INTENSITY = 0.10F;
    public static final float ENVIRONMENT_BACKGROUND_WIDTH_SCALE = 0.60F;
    public static final float ENVIRONMENT_BACKGROUND_BASE_LEVEL = 0.00F;
    public static final float ENVIRONMENT_BACKGROUND_COLOR_STRENGTH = 1.00F;
    public static final float ENVIRONMENT_BACKGROUND_TARGET_LUMA = 1.00F;
    public static final float ENVIRONMENT_BACKGROUND_ADAPTATION_STRENGTH = 0.00F;
    public static final float ENVIRONMENT_BACKGROUND_DITHER_STRENGTH_LSB = 1.80F;

    public static final boolean ENVIRONMENT_LIGHT_ENABLED = true;
    public static final float ENVIRONMENT_LIGHT_INTENSITY = 0.45F;
    public static final float ENVIRONMENT_LIGHT_WIDTH_SCALE = 1.00F;
    public static final float ENVIRONMENT_LIGHT_BASE_LEVEL = 0.00F;
    public static final float ENVIRONMENT_LIGHT_COLOR_STRENGTH = 1.00F;
    public static final float ENVIRONMENT_LIGHT_SPATIAL_VARIATION = 1.00F;

    // 表示平行光在 Battle world 中的传播方向。
    public static final CanonicalVec3 GLOBAL_LIGHT_DIRECTION =
            new CanonicalVec3(-0.33D, -0.26D, 0.91D);
    public static final BattleRenderMode BATTLE_RENDER_MODE = BattleRenderMode.RENDERED;
    public static final float DIFFUSE_LIGHT_INTENSITY = 0.8F;
    public static final float SPECULAR_LIGHT_INTENSITY = 0.25F;
    public static final float SPECULAR_SHININESS = 32.0F;
    public static final boolean SHADOWS_ENABLED = true;
    public static final int SHADOW_MAP_SIZE = 2048;
    public static final float SHADOW_ORTHOGRAPHIC_RADIUS = 3.0F;
    public static final float SHADOW_BIAS = 0.0015F;
    public static final float SHADOW_STRENGTH = 0.65F;
    public static final float EXTRUDED_IMAGE_SIDE_BRIGHTNESS_REDUCTION = 0.1F;

    public static final boolean VOLUMETRIC_SHADOWS_ENABLED = true;
    // 固定密度积分区间数；修改时必须同步 interval_trace.fsh。
    public static final int VOLUMETRIC_DENSITY_SEGMENTS = 8;
    public static final float VOLUMETRIC_BASE_DENSITY = 0.50F;
    public static final float VOLUMETRIC_EDGE_FALLOFF = 0.7F;
    public static final float VOLUMETRIC_SCATTERING_STRENGTH = 0.48F;
    public static final float VOLUMETRIC_SHADOW_STRENGTH = 0.94F;
    public static final float VOLUMETRIC_SHADOW_SCATTERING_MULTIPLIER = 0.06F;
    public static final float VOLUMETRIC_BELOW_GRID_SCATTERING_MULTIPLIER = 0.32F;
    // 将动态分段接缝推到辅助网格下方。
    public static final float VOLUMETRIC_GRID_SCATTERING_BOUNDARY_OFFSET = 1.0F / 128.0F;
    public static final float VOLUMETRIC_MIN_OPTICAL_DEPTH = 0.0001F;
    public static final int VOLUMETRIC_BASE_FOG_COLOR = 0x101114;
    public static final int VOLUMETRIC_SUN_SCATTER_COLOR = 0xB8B2A6;
    public static final float VOLUMETRIC_MINIMUM_FOG_MULTIPLIER = 0.08F;
    public static final float VOLUMETRIC_SHADOW_OPTICAL_DEPTH_GAIN = 2.0F;
    public static final float VOLUMETRIC_SHADOW_EXTINCTION_GAIN = 0.0F;
    public static final boolean VOLUMETRIC_TONE_MAPPING_ENABLED = true;
    public static final float VOLUMETRIC_TONE_MAPPING_EXPOSURE = 0.90F;
    public static final float VOLUMETRIC_TONE_MAPPING_CONTRAST = 1.18F;
    public static final float VOLUMETRIC_TONE_MAPPING_PIVOT = 0.30F;
    public static final float VOLUMETRIC_DITHER_STRENGTH_LSB = 0.75F;
    public static final int VOLUMETRIC_MINMAX_BASE_BLOCK_SIZE = 4;
    // 单像素遍历预算；耗尽后对剩余区间执行一次保守查询。
    public static final int VOLUMETRIC_MAX_HIERARCHY_VISITS = 512;
    public static final int VOLUMETRIC_MAX_LEAF_VISITS = 256;
    public static final float VOLUMETRIC_TRAVERSAL_EPSILON = 1.0E-6F;
    public static final float VOLUMETRIC_PARALLEL_UV_THRESHOLD = 1.0E-5F;
    public static final boolean VOLUMETRIC_SHADOW_CACHE_ENABLED = true;
    public static final VolumetricDebugMode VOLUMETRIC_DEBUG_MODE = VolumetricDebugMode.NORMAL;

    public enum VolumetricDebugMode {
        NORMAL,
        SHADOW_UV_ROI,
        MINMAX_LEVEL,
        HIERARCHY_VISIT_COUNT,
        LEAF_VISIT_COUNT,
        TRAVERSAL_OVERFLOW,
        TOTAL_OPTICAL_DEPTH,
        SHADOW_OPTICAL_DEPTH,
        SHADOW_RATIO,
        FINAL_VOLUME_COLOR
    }

    public enum SoulVolumetricDebugMode {
        NORMAL,
        REQUIRED_FACE,
        HIERARCHY_VISIT_COUNT,
        RAW_TEXEL_VISIT_COUNT,
        TRAVERSAL_OVERFLOW,
        SHADOW_RATIO
    }

    public enum BattleRenderMode {
        UNMODIFIED,
        RENDERED
    }

    // 实时调参白名单比 TOML 规格更窄；私有构造防止调用方绕过验证范围。
    public static final class LiveOption<T> {
        private final String configName;
        private final Class<T> valueType;
        private final Predicate<T> validator;

        private LiveOption(String configName, Class<T> valueType, Predicate<T> validator) {
            this.configName = configName;
            this.valueType = valueType;
            this.validator = validator;
        }

        public String configName() {
            return this.configName;
        }
    }

    public static final LiveOption<Boolean> LIVE_VOLUMETRIC_SHADOWS_ENABLED =
            liveBoolean("VOLUMETRIC_SHADOWS_ENABLED");
    public static final LiveOption<Float> LIVE_VOLUMETRIC_BASE_DENSITY =
            liveFloat("VOLUMETRIC_BASE_DENSITY", 0.0F, 2.0F);
    public static final LiveOption<Float> LIVE_VOLUMETRIC_EDGE_FALLOFF =
            liveFloat("VOLUMETRIC_EDGE_FALLOFF", 0.1F, 4.0F);
    public static final LiveOption<Float> LIVE_VOLUMETRIC_SCATTERING_STRENGTH =
            liveFloat("VOLUMETRIC_SCATTERING_STRENGTH", 0.0F, 2.0F);
    public static final LiveOption<Float> LIVE_VOLUMETRIC_SHADOW_STRENGTH =
            liveFloat("VOLUMETRIC_SHADOW_STRENGTH", 0.0F, 1.0F);
    public static final LiveOption<Float> LIVE_VOLUMETRIC_BELOW_GRID_SCATTERING_MULTIPLIER =
            liveFloat("VOLUMETRIC_BELOW_GRID_SCATTERING_MULTIPLIER", 0.0F, 1.0F);
    public static final LiveOption<Integer> LIVE_VOLUMETRIC_BASE_FOG_COLOR =
            liveRgb("VOLUMETRIC_BASE_FOG_COLOR");
    public static final LiveOption<Integer> LIVE_VOLUMETRIC_SUN_SCATTER_COLOR =
            liveRgb("VOLUMETRIC_SUN_SCATTER_COLOR");
    public static final LiveOption<Float> LIVE_VOLUMETRIC_MINIMUM_FOG_MULTIPLIER =
            liveFloat("VOLUMETRIC_MINIMUM_FOG_MULTIPLIER", 0.0F, 1.0F);
    public static final LiveOption<Float> LIVE_VOLUMETRIC_SHADOW_OPTICAL_DEPTH_GAIN =
            liveFloat("VOLUMETRIC_SHADOW_OPTICAL_DEPTH_GAIN", 1.0F, 8.0F);
    public static final LiveOption<Float> LIVE_VOLUMETRIC_SHADOW_EXTINCTION_GAIN =
            liveFloat("VOLUMETRIC_SHADOW_EXTINCTION_GAIN", 0.0F, 4.0F);
    public static final LiveOption<Boolean> LIVE_VOLUMETRIC_TONE_MAPPING_ENABLED =
            liveBoolean("VOLUMETRIC_TONE_MAPPING_ENABLED");
    public static final LiveOption<Float> LIVE_VOLUMETRIC_TONE_MAPPING_EXPOSURE =
            liveFloat("VOLUMETRIC_TONE_MAPPING_EXPOSURE", 0.25F, 2.5F);
    public static final LiveOption<Float> LIVE_VOLUMETRIC_TONE_MAPPING_CONTRAST =
            liveFloat("VOLUMETRIC_TONE_MAPPING_CONTRAST", 0.5F, 2.0F);
    public static final LiveOption<Float> LIVE_VOLUMETRIC_TONE_MAPPING_PIVOT =
            liveFloat("VOLUMETRIC_TONE_MAPPING_PIVOT", 0.05F, 0.8F);
    public static final LiveOption<Float> LIVE_VOLUMETRIC_DITHER_STRENGTH_LSB =
            liveFloat("VOLUMETRIC_DITHER_STRENGTH_LSB", 0.0F, 3.0F);

    public static final LiveOption<Boolean> LIVE_SOUL_VOLUMETRIC_LIGHT_ENABLED =
            liveBoolean("SOUL_VOLUMETRIC_LIGHT_ENABLED");
    public static final LiveOption<Float> LIVE_SOUL_VOLUMETRIC_RADIUS =
            liveFloat("SOUL_VOLUMETRIC_RADIUS", 0.1F, 4.0F);
    public static final LiveOption<Float> LIVE_SOUL_VOLUMETRIC_CORE_RADIUS =
            liveFloat("SOUL_VOLUMETRIC_CORE_RADIUS", 0.01F, 1.0F);
    public static final LiveOption<Float> LIVE_SOUL_VOLUMETRIC_CUTOFF_FEATHER =
            liveFloat("SOUL_VOLUMETRIC_CUTOFF_FEATHER", 0.01F, 1.0F);
    public static final LiveOption<Float> LIVE_SOUL_VOLUMETRIC_PHASE_G =
            liveFloat("SOUL_VOLUMETRIC_PHASE_G", -0.8F, 0.8F);
    public static final LiveOption<Float> LIVE_SOUL_VOLUMETRIC_INTENSITY =
            liveFloat("SOUL_VOLUMETRIC_INTENSITY", 0.0F, 2_000.0F);
    public static final LiveOption<Float> LIVE_SOUL_VOLUMETRIC_RADIANCE_SOFT_CLIP =
            liveFloat("SOUL_VOLUMETRIC_RADIANCE_SOFT_CLIP", 0.05F, 2.0F);
    public static final LiveOption<Float> LIVE_SOUL_VOLUMETRIC_GRID_TRANSMISSION =
            liveFloat("SOUL_VOLUMETRIC_GRID_TRANSMISSION", 0.0F, 1.0F);
    public static final LiveOption<Float> LIVE_SOUL_VOLUMETRIC_SHADOW_STRENGTH =
            liveFloat("SOUL_VOLUMETRIC_SHADOW_STRENGTH", 0.0F, 1.0F);
    public static final LiveOption<Float> LIVE_SOUL_VOLUMETRIC_MINIMUM_RADIANCE_MULTIPLIER =
            liveFloat("SOUL_VOLUMETRIC_MINIMUM_RADIANCE_MULTIPLIER", 0.0F, 1.0F);
    public static final LiveOption<Float> LIVE_SOUL_VOLUMETRIC_SHADOW_SOURCE_GAIN =
            liveFloat("SOUL_VOLUMETRIC_SHADOW_SOURCE_GAIN", 1.0F, 8.0F);
    public static final LiveOption<Float> LIVE_SOUL_VOLUMETRIC_SHADOW_EXTINCTION_GAIN =
            liveFloat("SOUL_VOLUMETRIC_SHADOW_EXTINCTION_GAIN", 0.0F, 8.0F);
    public static final LiveOption<Boolean> LIVE_SOUL_VOLUMETRIC_TONE_MAPPING_ENABLED =
            liveBoolean("SOUL_VOLUMETRIC_TONE_MAPPING_ENABLED");
    public static final LiveOption<Float> LIVE_SOUL_VOLUMETRIC_TONE_MAPPING_EXPOSURE =
            liveFloat("SOUL_VOLUMETRIC_TONE_MAPPING_EXPOSURE", 0.25F, 2.5F);
    public static final LiveOption<Float> LIVE_SOUL_VOLUMETRIC_TONE_MAPPING_CONTRAST =
            liveFloat("SOUL_VOLUMETRIC_TONE_MAPPING_CONTRAST", 0.5F, 2.0F);
    public static final LiveOption<Float> LIVE_SOUL_VOLUMETRIC_TONE_MAPPING_PIVOT =
            liveFloat("SOUL_VOLUMETRIC_TONE_MAPPING_PIVOT", 0.05F, 0.8F);

    public static final LiveOption<Boolean> LIVE_SOUL_GUIDE_GRID_ENABLED =
            liveBoolean("SOUL_GUIDE_GRID_ENABLED");

    public static final LiveOption<Boolean> LIVE_POST_BLOOM_ENABLED =
            liveBoolean("POST_BLOOM_ENABLED");
    public static final LiveOption<PostQuality> LIVE_POST_BLOOM_QUALITY =
            liveEnum("POST_BLOOM_QUALITY", PostQuality.class);
    public static final LiveOption<Float> LIVE_POST_BLOOM_EMISSIVE_CURVE =
            liveFloat("POST_BLOOM_EMISSIVE_CURVE", 0.1F, 4.0F);
    public static final LiveOption<Float> LIVE_POST_BLOOM_INTENSITY =
            liveFloat("POST_BLOOM_INTENSITY", 0.0F, 16.0F);
    public static final LiveOption<Float> LIVE_POST_BLOOM_BLUR_RADIUS_PIXELS =
            liveFloat("POST_BLOOM_BLUR_RADIUS_PIXELS", 0.0F, 32.0F);

    public static final LiveOption<Boolean> LIVE_ENVIRONMENT_BACKGROUND_ENABLED =
            liveBoolean("ENVIRONMENT_BACKGROUND_ENABLED");
    public static final LiveOption<Float> LIVE_ENVIRONMENT_BACKGROUND_INTENSITY =
            liveFloat("ENVIRONMENT_BACKGROUND_INTENSITY", 0.0F, 4.0F);
    public static final LiveOption<Float> LIVE_ENVIRONMENT_BACKGROUND_WIDTH_SCALE =
            liveFloat("ENVIRONMENT_BACKGROUND_WIDTH_SCALE", 0.5F, 3.0F);
    public static final LiveOption<Float> LIVE_ENVIRONMENT_BACKGROUND_BASE_LEVEL =
            liveFloat("ENVIRONMENT_BACKGROUND_BASE_LEVEL", 0.0F, 1.0F);
    public static final LiveOption<Float> LIVE_ENVIRONMENT_BACKGROUND_COLOR_STRENGTH =
            liveFloat("ENVIRONMENT_BACKGROUND_COLOR_STRENGTH", 0.0F, 1.0F);
    public static final LiveOption<Float> LIVE_ENVIRONMENT_BACKGROUND_TARGET_LUMA =
            liveFloat("ENVIRONMENT_BACKGROUND_TARGET_LUMA", 0.01F, 1.0F);
    public static final LiveOption<Float> LIVE_ENVIRONMENT_BACKGROUND_ADAPTATION_STRENGTH =
            liveFloat("ENVIRONMENT_BACKGROUND_ADAPTATION_STRENGTH", 0.0F, 1.0F);
    public static final LiveOption<Float> LIVE_ENVIRONMENT_BACKGROUND_DITHER_STRENGTH_LSB =
            liveFloat("ENVIRONMENT_BACKGROUND_DITHER_STRENGTH_LSB", 0.0F, 3.0F);
    public static final LiveOption<Boolean> LIVE_ENVIRONMENT_LIGHT_ENABLED =
            liveBoolean("ENVIRONMENT_LIGHT_ENABLED");
    public static final LiveOption<Float> LIVE_ENVIRONMENT_LIGHT_INTENSITY =
            liveFloat("ENVIRONMENT_LIGHT_INTENSITY", 0.0F, 1.0F);
    public static final LiveOption<Float> LIVE_ENVIRONMENT_LIGHT_WIDTH_SCALE =
            liveFloat("ENVIRONMENT_LIGHT_WIDTH_SCALE", 0.5F, 3.0F);
    public static final LiveOption<Float> LIVE_ENVIRONMENT_LIGHT_BASE_LEVEL =
            liveFloat("ENVIRONMENT_LIGHT_BASE_LEVEL", 0.0F, 1.0F);
    public static final LiveOption<Float> LIVE_ENVIRONMENT_LIGHT_COLOR_STRENGTH =
            liveFloat("ENVIRONMENT_LIGHT_COLOR_STRENGTH", 0.0F, 1.0F);
    public static final LiveOption<Float> LIVE_ENVIRONMENT_LIGHT_SPATIAL_VARIATION =
            liveFloat("ENVIRONMENT_LIGHT_SPATIAL_VARIATION", 0.0F, 1.0F);

    public static final List<LiveOption<?>> VISUAL_EFFECTS_LIVE_OPTIONS = List.of(
            LIVE_VOLUMETRIC_SHADOWS_ENABLED,
            LIVE_VOLUMETRIC_BASE_DENSITY,
            LIVE_VOLUMETRIC_EDGE_FALLOFF,
            LIVE_VOLUMETRIC_SCATTERING_STRENGTH,
            LIVE_VOLUMETRIC_SHADOW_STRENGTH,
            LIVE_VOLUMETRIC_BELOW_GRID_SCATTERING_MULTIPLIER,
            LIVE_VOLUMETRIC_BASE_FOG_COLOR,
            LIVE_VOLUMETRIC_SUN_SCATTER_COLOR,
            LIVE_VOLUMETRIC_MINIMUM_FOG_MULTIPLIER,
            LIVE_VOLUMETRIC_SHADOW_OPTICAL_DEPTH_GAIN,
            LIVE_VOLUMETRIC_SHADOW_EXTINCTION_GAIN,
            LIVE_VOLUMETRIC_TONE_MAPPING_ENABLED,
            LIVE_VOLUMETRIC_TONE_MAPPING_EXPOSURE,
            LIVE_VOLUMETRIC_TONE_MAPPING_CONTRAST,
            LIVE_VOLUMETRIC_TONE_MAPPING_PIVOT,
            LIVE_VOLUMETRIC_DITHER_STRENGTH_LSB,
            LIVE_SOUL_VOLUMETRIC_LIGHT_ENABLED,
            LIVE_SOUL_VOLUMETRIC_RADIUS,
            LIVE_SOUL_VOLUMETRIC_CORE_RADIUS,
            LIVE_SOUL_VOLUMETRIC_CUTOFF_FEATHER,
            LIVE_SOUL_VOLUMETRIC_PHASE_G,
            LIVE_SOUL_VOLUMETRIC_INTENSITY,
            LIVE_SOUL_VOLUMETRIC_RADIANCE_SOFT_CLIP,
            LIVE_SOUL_VOLUMETRIC_GRID_TRANSMISSION,
            LIVE_SOUL_VOLUMETRIC_SHADOW_STRENGTH,
            LIVE_SOUL_VOLUMETRIC_MINIMUM_RADIANCE_MULTIPLIER,
            LIVE_SOUL_VOLUMETRIC_SHADOW_SOURCE_GAIN,
            LIVE_SOUL_VOLUMETRIC_SHADOW_EXTINCTION_GAIN,
            LIVE_SOUL_VOLUMETRIC_TONE_MAPPING_ENABLED,
            LIVE_SOUL_VOLUMETRIC_TONE_MAPPING_EXPOSURE,
            LIVE_SOUL_VOLUMETRIC_TONE_MAPPING_CONTRAST,
            LIVE_SOUL_VOLUMETRIC_TONE_MAPPING_PIVOT,
            LIVE_SOUL_GUIDE_GRID_ENABLED,
            LIVE_POST_BLOOM_ENABLED,
            LIVE_POST_BLOOM_QUALITY,
            LIVE_POST_BLOOM_EMISSIVE_CURVE,
            LIVE_POST_BLOOM_INTENSITY,
            LIVE_POST_BLOOM_BLUR_RADIUS_PIXELS,
            LIVE_ENVIRONMENT_BACKGROUND_ENABLED,
            LIVE_ENVIRONMENT_BACKGROUND_INTENSITY,
            LIVE_ENVIRONMENT_BACKGROUND_WIDTH_SCALE,
            LIVE_ENVIRONMENT_BACKGROUND_BASE_LEVEL,
            LIVE_ENVIRONMENT_BACKGROUND_COLOR_STRENGTH,
            LIVE_ENVIRONMENT_BACKGROUND_TARGET_LUMA,
            LIVE_ENVIRONMENT_BACKGROUND_ADAPTATION_STRENGTH,
            LIVE_ENVIRONMENT_BACKGROUND_DITHER_STRENGTH_LSB,
            LIVE_ENVIRONMENT_LIGHT_ENABLED,
            LIVE_ENVIRONMENT_LIGHT_INTENSITY,
            LIVE_ENVIRONMENT_LIGHT_WIDTH_SCALE,
            LIVE_ENVIRONMENT_LIGHT_BASE_LEVEL,
            LIVE_ENVIRONMENT_LIGHT_COLOR_STRENGTH,
            LIVE_ENVIRONMENT_LIGHT_SPATIAL_VARIATION
    );

    private static final Set<String> INTERNAL_CONSTANTS = Set.of(
            "VOLUMETRIC_DENSITY_SEGMENTS",
            "VOLUMETRIC_MINMAX_BASE_BLOCK_SIZE"
    );
    private static final Object CONFIG_LOCK = new Object();
    private static final Map<String, ModConfigSpec.ConfigValue<?>> CONFIG_VALUES = new LinkedHashMap<>();
    private static volatile Map<String, Object> effectiveValues = Map.of();
    private static boolean liveValuesDirty;
    public static final ModConfigSpec SPEC;
    private static final ModConfigSpec.DoubleValue GLOBAL_LIGHT_DIRECTION_X;
    private static final ModConfigSpec.DoubleValue GLOBAL_LIGHT_DIRECTION_Y;
    private static final ModConfigSpec.DoubleValue GLOBAL_LIGHT_DIRECTION_Z;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        Map<String, Object> defaults = new LinkedHashMap<>();
        for (Field field : VisualConfig.class.getFields()) {
            if (!isConfigurationDefaultField(field) || field.getName().equals("GLOBAL_LIGHT_DIRECTION")) continue;
            try {
                Object defaultValue = field.get(null);
                defaults.put(field.getName(), defaultValue);
                String category = category(field.getName());
                builder.translation("minetale.configuration." + category).push(category);
                String path = field.getName().toLowerCase(Locale.ROOT);
                builder.translation("minetale.configuration." + path);
                builder.comment(configurationDescription(field.getName()));
                ModConfigSpec.ConfigValue<?> value = defaultValue instanceof Enum<?> enumValue
                        ? defineEnum(builder, path, enumValue)
                        : defineConfigValue(builder, path, defaultValue);
                CONFIG_VALUES.put(field.getName(), value);
                builder.pop();
            } catch (IllegalAccessException exception) {
                throw new IllegalStateException("无法读取 VisualConfig 字段: " + field.getName(), exception);
            }
        }
        CanonicalVec3 defaultDirection = GLOBAL_LIGHT_DIRECTION;
        builder.translation("minetale.configuration.lighting").push("lighting");
        builder.translation("minetale.configuration.global_light_direction_x");
        GLOBAL_LIGHT_DIRECTION_X = builder.comment("全局平行光方向 X 分量。")
                .defineInRange("global_light_direction_x", defaultDirection.x(), -1.0D, 1.0D);
        builder.translation("minetale.configuration.global_light_direction_y");
        GLOBAL_LIGHT_DIRECTION_Y = builder.comment("全局平行光方向 Y 分量。")
                .defineInRange("global_light_direction_y", defaultDirection.y(), -1.0D, 1.0D);
        builder.translation("minetale.configuration.global_light_direction_z");
        GLOBAL_LIGHT_DIRECTION_Z = builder.comment("全局平行光方向 Z 分量。")
                .defineInRange("global_light_direction_z", defaultDirection.z(), -1.0D, 1.0D);
        builder.pop();
        SPEC = builder.build();
        defaults.put("GLOBAL_LIGHT_DIRECTION", defaultDirection);
        effectiveValues = Map.copyOf(defaults);
    }

    // 原子发布已验证配置，源码默认值字段始终保持不变。
    public static void apply() {
        synchronized (CONFIG_LOCK) {
            Map<String, Object> applied = new LinkedHashMap<>();
            boolean normalized = false;
            for (Map.Entry<String, ModConfigSpec.ConfigValue<?>> entry : CONFIG_VALUES.entrySet()) {
                Field field = publicField(entry.getKey());
                Object configuredValue = entry.getValue().get();
                if (field.getType() == float.class && configuredValue instanceof Number number) {
                    double canonicalValue = Double.parseDouble(Float.toString(number.floatValue()));
                    if (Double.compare(number.doubleValue(), canonicalValue) != 0) {
                        setConfigValue(entry.getValue(), canonicalValue);
                        configuredValue = canonicalValue;
                        normalized = true;
                    }
                }
                applied.put(entry.getKey(), coerceConfigValue(configuredValue, field.getType()));
            }
            applied.put("GLOBAL_LIGHT_DIRECTION", new CanonicalVec3(
                    GLOBAL_LIGHT_DIRECTION_X.get(), GLOBAL_LIGHT_DIRECTION_Y.get(), GLOBAL_LIGHT_DIRECTION_Z.get()));
            effectiveValues = Map.copyOf(applied);
            liveValuesDirty = false;
            if (normalized) {
                SPEC.save();
            }
        }
    }

    public static <T> T liveValue(LiveOption<T> option) {
        Objects.requireNonNull(option, "option");
        return option.valueType.cast(effectiveValue(option.configName));
    }

    public static <T> T liveDefaultValue(LiveOption<T> option) {
        Objects.requireNonNull(option, "option");
        try {
            return option.valueType.cast(publicField(option.configName).get(null));
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("无法读取 VisualConfig 默认值: " + option.configName, exception);
        }
    }

    // 立即发布到后续帧但暂不落盘，以防滑条移动触发连续配置写入。
    public static <T> void setLiveValue(LiveOption<T> option, T value) {
        Objects.requireNonNull(option, "option");
        Objects.requireNonNull(value, "value");
        if (!VISUAL_EFFECTS_LIVE_OPTIONS.contains(option)) {
            throw new IllegalArgumentException("不是视效实时调参白名单项: " + option.configName);
        }
        if (!option.valueType.isInstance(value) || !option.validator.test(value)) {
            throw new IllegalArgumentException("视效实时调参值越界: " + option.configName + "=" + value);
        }

        synchronized (CONFIG_LOCK) {
            ModConfigSpec.ConfigValue<?> configValue = CONFIG_VALUES.get(option.configName);
            if (configValue == null) {
                throw new IllegalStateException("找不到视效配置值: " + option.configName);
            }
            Field field = publicField(option.configName);
            Object storedValue = toStoredConfigValue(value, field.getType());
            if (!configValue.getSpec().test(storedValue)) {
                throw new IllegalArgumentException("视效配置规格拒绝值: " + option.configName + "=" + value);
            }
            Object effective = coerceConfigValue(storedValue, field.getType());
            if (Objects.equals(effectiveValues.get(option.configName), effective)) {
                return;
            }

            setConfigValue(configValue, storedValue);
            Map<String, Object> updated = new LinkedHashMap<>(effectiveValues);
            updated.put(option.configName, effective);
            effectiveValues = Map.copyOf(updated);
            liveValuesDirty = true;
        }
    }

    // 三个方向分量必须在同一锁内发布。
    public static void setLiveLightDirection(CanonicalVec3 direction) {
        Objects.requireNonNull(direction, "direction");
        if (!Double.isFinite(direction.x()) || !Double.isFinite(direction.y()) || !Double.isFinite(direction.z())
                || Math.abs(direction.x()) > 1.0D || Math.abs(direction.y()) > 1.0D
                || Math.abs(direction.z()) > 1.0D || direction.lengthSquared() <= 1.0E-8D) {
            throw new IllegalArgumentException("无效的实时平行光方向: " + direction);
        }

        synchronized (CONFIG_LOCK) {
            if (!GLOBAL_LIGHT_DIRECTION_X.getSpec().test(direction.x())
                    || !GLOBAL_LIGHT_DIRECTION_Y.getSpec().test(direction.y())
                    || !GLOBAL_LIGHT_DIRECTION_Z.getSpec().test(direction.z())) {
                throw new IllegalArgumentException("平行光方向超出配置规格: " + direction);
            }
            GLOBAL_LIGHT_DIRECTION_X.set(direction.x());
            GLOBAL_LIGHT_DIRECTION_Y.set(direction.y());
            GLOBAL_LIGHT_DIRECTION_Z.set(direction.z());
            Map<String, Object> updated = new LinkedHashMap<>(effectiveValues);
            updated.put("GLOBAL_LIGHT_DIRECTION", direction);
            effectiveValues = Map.copyOf(updated);
            liveValuesDirty = true;
        }
    }

    // 将拖动期间积累的实时值一次写回同一客户端配置文件。
    public static void flushLiveValues() {
        synchronized (CONFIG_LOCK) {
            if (!liveValuesDirty || !SPEC.isLoaded()) {
                return;
            }
            SPEC.save();
            liveValuesDirty = false;
        }
    }

    private static LiveOption<Boolean> liveBoolean(String configName) {
        return new LiveOption<>(configName, Boolean.class, ignored -> true);
    }

    private static LiveOption<Float> liveFloat(String configName, float minimum, float maximum) {
        return new LiveOption<>(configName, Float.class,
                value -> Float.isFinite(value) && value >= minimum && value <= maximum);
    }

    private static LiveOption<Integer> liveInteger(String configName, int minimum, int maximum) {
        return new LiveOption<>(configName, Integer.class,
                value -> value >= minimum && value <= maximum);
    }

    private static <E extends Enum<E>> LiveOption<E> liveEnum(String configName, Class<E> valueType) {
        return new LiveOption<>(configName, valueType, Objects::nonNull);
    }

    private static LiveOption<Integer> liveRgb(String configName) {
        return new LiveOption<>(configName, Integer.class, value -> value >= 0 && value <= 0xFFFFFF);
    }

    private static Object toStoredConfigValue(Object value, Class<?> targetType) {
        if (targetType == float.class && value instanceof Number number) {
            return Double.parseDouble(Float.toString(number.floatValue()));
        }
        if (targetType == double.class && value instanceof Number number) {
            return number.doubleValue();
        }
        if (targetType == int.class && value instanceof Number number) {
            return number.intValue();
        }
        return value;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void setConfigValue(ModConfigSpec.ConfigValue<?> configValue, Object value) {
        ((ModConfigSpec.ConfigValue) configValue).set(value);
    }

    private static Field publicField(String name) {
        try {
            return VisualConfig.class.getField(name);
        } catch (NoSuchFieldException exception) {
            throw new IllegalStateException("找不到 VisualConfig 默认值常量: " + name, exception);
        }
    }

    private static boolean isConfigurationDefaultField(Field field) {
        if (!Modifier.isStatic(field.getModifiers()) || !Modifier.isFinal(field.getModifiers())
                || INTERNAL_CONSTANTS.contains(field.getName())) {
            return false;
        }
        Class<?> type = field.getType();
        return type == boolean.class || type == int.class || type == float.class || type == double.class
                || type.isEnum() || type == CanonicalVec3.class;
    }

    private static Object effectiveValue(String name) {
        Object value = effectiveValues.get(name);
        if (value == null) {
            throw new IllegalArgumentException("不是可配置的 VisualConfig 默认值: " + name);
        }
        return value;
    }

    public static int REMOTE_TRACK_MAX_SAMPLES() {
        return (int) effectiveValue("REMOTE_TRACK_MAX_SAMPLES");
    }

    public static double REMOTE_SAMPLE_DELAY_SECONDS() {
        return (double) effectiveValue("REMOTE_SAMPLE_DELAY_SECONDS");
    }

    public static double REMOTE_HEALTH_DEGRADED_AFTER_SECONDS() {
        return (double) effectiveValue("REMOTE_HEALTH_DEGRADED_AFTER_SECONDS");
    }

    public static double REMOTE_HEALTH_LOST_AFTER_SECONDS() {
        return (double) effectiveValue("REMOTE_HEALTH_LOST_AFTER_SECONDS");
    }

    public static double SCREEN_SHAKE_VIEWPORT_SCALE() {
        return (double) effectiveValue("SCREEN_SHAKE_VIEWPORT_SCALE");
    }

    public static double SCREEN_SHAKE_SAMPLES_PER_SECOND() {
        return (double) effectiveValue("SCREEN_SHAKE_SAMPLES_PER_SECOND");
    }

    public static double DEBUG_SCENE_TRANSITION_SECONDS() {
        return (double) effectiveValue("DEBUG_SCENE_TRANSITION_SECONDS");
    }

    public static double SCENE_RENDER_MODE_ENTER_DELAY_SECONDS() {
        return nonNegativeSeconds(
                "SCENE_RENDER_MODE_ENTER_DELAY_SECONDS",
                (double) effectiveValue("SCENE_RENDER_MODE_ENTER_DELAY_SECONDS")
        );
    }

    public static double SCENE_RENDER_MODE_CROSSFADE_SECONDS() {
        return nonNegativeSeconds(
                "SCENE_RENDER_MODE_CROSSFADE_SECONDS",
                (double) effectiveValue("SCENE_RENDER_MODE_CROSSFADE_SECONDS")
        );
    }

    public static double DEBUG_PROJECTION_TRANSITION_SECONDS() {
        return (double) effectiveValue("DEBUG_PROJECTION_TRANSITION_SECONDS");
    }

    public static double DEBUG_CAMERA_ROTATION_SECONDS() {
        return (double) effectiveValue("DEBUG_CAMERA_ROTATION_SECONDS");
    }

    public static double DEBUG_3D_VIEW_SCALE() {
        return (double) effectiveValue("DEBUG_3D_VIEW_SCALE");
    }

    public static double DEBUG_ORBIT_INITIAL_YAW_DEGREES() {
        return (double) effectiveValue("DEBUG_ORBIT_INITIAL_YAW_DEGREES");
    }

    public static double DEBUG_ORBIT_INITIAL_PITCH_DEGREES() {
        return (double) effectiveValue("DEBUG_ORBIT_INITIAL_PITCH_DEGREES");
    }

    public static double DEBUG_ORBIT_MAX_PITCH_DEGREES() {
        return (double) effectiveValue("DEBUG_ORBIT_MAX_PITCH_DEGREES");
    }

    public static double DEBUG_ORBIT_ROTATION_STEP_DEGREES() {
        return (double) effectiveValue("DEBUG_ORBIT_ROTATION_STEP_DEGREES");
    }

    public static double DEBUG_ORBIT_DISTANCE() {
        return (double) effectiveValue("DEBUG_ORBIT_DISTANCE");
    }

    public static double DEBUG_ORBIT_FOV_DEGREES() {
        return (double) effectiveValue("DEBUG_ORBIT_FOV_DEGREES");
    }

    public static double DEBUG_REMOTE_SOUL_ORBIT_RADIUS() {
        return (double) effectiveValue("DEBUG_REMOTE_SOUL_ORBIT_RADIUS");
    }

    public static double DEBUG_REMOTE_SOUL_ORBIT_RADIANS_PER_SECOND() {
        return (double) effectiveValue("DEBUG_REMOTE_SOUL_ORBIT_RADIANS_PER_SECOND");
    }

    public static float SCENE_NEAR_PLANE() {
        return (float) effectiveValue("SCENE_NEAR_PLANE");
    }

    public static float SCENE_FAR_PLANE() {
        return (float) effectiveValue("SCENE_FAR_PLANE");
    }

    public static float SCENE_CLIP_DISTANCE_PADDING() {
        return (float) effectiveValue("SCENE_CLIP_DISTANCE_PADDING");
    }

    public static float SPRITE_PIXELS_PER_CANONICAL_UNIT() {
        return (float) effectiveValue("SPRITE_PIXELS_PER_CANONICAL_UNIT");
    }

    public static float SOUL_MODEL_SCALE() {
        return (float) effectiveValue("SOUL_MODEL_SCALE");
    }

    public static float SOUL_MODEL_FRONT_YAW_DEGREES() {
        return (float) effectiveValue("SOUL_MODEL_FRONT_YAW_DEGREES");
    }

    public static float SOUL_MODEL_CAMERA_YAW_OFFSET_DEGREES() {
        return (float) effectiveValue("SOUL_MODEL_CAMERA_YAW_OFFSET_DEGREES");
    }

    public static float SOUL_TINT_LIGHT_RADIUS() {
        return (float) effectiveValue("SOUL_TINT_LIGHT_RADIUS");
    }

    public static float SOUL_TINT_LIGHT_EMISSION_STRENGTH() {
        return (float) effectiveValue("SOUL_TINT_LIGHT_EMISSION_STRENGTH");
    }

    public static boolean SOUL_VOLUMETRIC_LIGHT_ENABLED() {
        return (boolean) effectiveValue("SOUL_VOLUMETRIC_LIGHT_ENABLED");
    }

    public static int SOUL_POINT_SHADOW_FACE_SIZE() {
        return (int) effectiveValue("SOUL_POINT_SHADOW_FACE_SIZE");
    }

    public static int SOUL_POINT_SHADOW_BORDER() {
        return (int) effectiveValue("SOUL_POINT_SHADOW_BORDER");
    }

    public static float SOUL_POINT_SHADOW_NEAR_PLANE() {
        return (float) effectiveValue("SOUL_POINT_SHADOW_NEAR_PLANE");
    }

    public static float SOUL_POINT_SHADOW_RADIAL_BIAS() {
        return (float) effectiveValue("SOUL_POINT_SHADOW_RADIAL_BIAS");
    }

    public static float SOUL_VOLUMETRIC_RADIUS() {
        return (float) effectiveValue("SOUL_VOLUMETRIC_RADIUS");
    }

    public static float SOUL_VOLUMETRIC_CORE_RADIUS() {
        return (float) effectiveValue("SOUL_VOLUMETRIC_CORE_RADIUS");
    }

    public static float SOUL_VOLUMETRIC_CUTOFF_FEATHER() {
        return (float) effectiveValue("SOUL_VOLUMETRIC_CUTOFF_FEATHER");
    }

    public static float SOUL_VOLUMETRIC_PHASE_G() {
        return (float) effectiveValue("SOUL_VOLUMETRIC_PHASE_G");
    }

    public static float SOUL_VOLUMETRIC_INTENSITY() {
        return (float) effectiveValue("SOUL_VOLUMETRIC_INTENSITY");
    }

    public static float SOUL_VOLUMETRIC_RADIANCE_SOFT_CLIP() {
        return (float) effectiveValue("SOUL_VOLUMETRIC_RADIANCE_SOFT_CLIP");
    }

    public static float SOUL_VOLUMETRIC_GRID_TRANSMISSION() {
        return (float) effectiveValue("SOUL_VOLUMETRIC_GRID_TRANSMISSION");
    }

    public static float SOUL_VOLUMETRIC_SHADOW_STRENGTH() {
        return (float) effectiveValue("SOUL_VOLUMETRIC_SHADOW_STRENGTH");
    }

    public static float SOUL_VOLUMETRIC_MINIMUM_RADIANCE_MULTIPLIER() {
        return (float) effectiveValue("SOUL_VOLUMETRIC_MINIMUM_RADIANCE_MULTIPLIER");
    }

    public static float SOUL_VOLUMETRIC_SHADOW_SOURCE_GAIN() {
        return (float) effectiveValue("SOUL_VOLUMETRIC_SHADOW_SOURCE_GAIN");
    }

    public static float SOUL_VOLUMETRIC_SHADOW_EXTINCTION_GAIN() {
        return (float) effectiveValue("SOUL_VOLUMETRIC_SHADOW_EXTINCTION_GAIN");
    }

    public static boolean SOUL_VOLUMETRIC_TONE_MAPPING_ENABLED() {
        return (boolean) effectiveValue("SOUL_VOLUMETRIC_TONE_MAPPING_ENABLED");
    }

    public static float SOUL_VOLUMETRIC_TONE_MAPPING_EXPOSURE() {
        return (float) effectiveValue("SOUL_VOLUMETRIC_TONE_MAPPING_EXPOSURE");
    }

    public static float SOUL_VOLUMETRIC_TONE_MAPPING_CONTRAST() {
        return (float) effectiveValue("SOUL_VOLUMETRIC_TONE_MAPPING_CONTRAST");
    }

    public static float SOUL_VOLUMETRIC_TONE_MAPPING_PIVOT() {
        return (float) effectiveValue("SOUL_VOLUMETRIC_TONE_MAPPING_PIVOT");
    }

    public static float SOUL_SURFACE_LIGHT_INTENSITY() {
        return (float) effectiveValue("SOUL_SURFACE_LIGHT_INTENSITY");
    }

    public static float SOUL_SURFACE_LIGHT_SHADOW_BIAS() {
        return (float) effectiveValue("SOUL_SURFACE_LIGHT_SHADOW_BIAS");
    }

    public static int SOUL_VOLUMETRIC_SOURCE_SEGMENTS() {
        return (int) effectiveValue("SOUL_VOLUMETRIC_SOURCE_SEGMENTS");
    }

    public static int SOUL_VOLUMETRIC_MAX_HIERARCHY_VISITS() {
        return (int) effectiveValue("SOUL_VOLUMETRIC_MAX_HIERARCHY_VISITS");
    }

    public static int SOUL_VOLUMETRIC_MAX_TEXEL_VISITS() {
        return (int) effectiveValue("SOUL_VOLUMETRIC_MAX_TEXEL_VISITS");
    }

    public static float SOUL_VOLUMETRIC_TRAVERSAL_EPSILON() {
        return (float) effectiveValue("SOUL_VOLUMETRIC_TRAVERSAL_EPSILON");
    }

    public static SoulVolumetricDebugMode SOUL_VOLUMETRIC_DEBUG_MODE() {
        return (SoulVolumetricDebugMode) effectiveValue("SOUL_VOLUMETRIC_DEBUG_MODE");
    }

    public static boolean SOUL_GUIDE_GRID_ENABLED() {
        return (boolean) effectiveValue("SOUL_GUIDE_GRID_ENABLED");
    }

    public static float SOUL_GUIDE_GRID_DENSITY() {
        return (float) effectiveValue("SOUL_GUIDE_GRID_DENSITY");
    }

    public static float SOUL_GUIDE_GRID_HEIGHT_OFFSET() {
        return (float) effectiveValue("SOUL_GUIDE_GRID_HEIGHT_OFFSET");
    }

    public static float SOUL_GUIDE_GRID_LINE_WIDTH() {
        return (float) effectiveValue("SOUL_GUIDE_GRID_LINE_WIDTH");
    }

    public static int SOUL_GUIDE_GRID_COLOR() {
        return (int) effectiveValue("SOUL_GUIDE_GRID_COLOR");
    }

    public static float SOUL_GUIDE_GRID_ALPHA() {
        return (float) effectiveValue("SOUL_GUIDE_GRID_ALPHA");
    }

    public static int SOUL_GUIDE_FILL_COLOR() {
        return (int) effectiveValue("SOUL_GUIDE_FILL_COLOR");
    }

    public static float SOUL_GUIDE_FILL_ALPHA() {
        return (float) effectiveValue("SOUL_GUIDE_FILL_ALPHA");
    }

    public static boolean POST_BLOOM_ENABLED() {
        return (boolean) effectiveValue("POST_BLOOM_ENABLED");
    }

    public static PostQuality POST_BLOOM_QUALITY() {
        return (PostQuality) effectiveValue("POST_BLOOM_QUALITY");
    }

    public static float POST_BLOOM_EMISSIVE_CURVE() {
        return (float) effectiveValue("POST_BLOOM_EMISSIVE_CURVE");
    }

    public static float POST_BLOOM_INTENSITY() {
        return (float) effectiveValue("POST_BLOOM_INTENSITY");
    }

    public static float POST_BLOOM_BLUR_RADIUS_PIXELS() {
        return (float) effectiveValue("POST_BLOOM_BLUR_RADIUS_PIXELS");
    }

    public static boolean ENVIRONMENT_BACKGROUND_ENABLED() {
        return (boolean) effectiveValue("ENVIRONMENT_BACKGROUND_ENABLED");
    }

    public static float ENVIRONMENT_BACKGROUND_INTENSITY() {
        return (float) effectiveValue("ENVIRONMENT_BACKGROUND_INTENSITY");
    }

    public static float ENVIRONMENT_BACKGROUND_WIDTH_SCALE() {
        return (float) effectiveValue("ENVIRONMENT_BACKGROUND_WIDTH_SCALE");
    }

    public static float ENVIRONMENT_BACKGROUND_BASE_LEVEL() {
        return (float) effectiveValue("ENVIRONMENT_BACKGROUND_BASE_LEVEL");
    }

    public static float ENVIRONMENT_BACKGROUND_COLOR_STRENGTH() {
        return (float) effectiveValue("ENVIRONMENT_BACKGROUND_COLOR_STRENGTH");
    }

    public static float ENVIRONMENT_BACKGROUND_TARGET_LUMA() {
        return (float) effectiveValue("ENVIRONMENT_BACKGROUND_TARGET_LUMA");
    }

    public static float ENVIRONMENT_BACKGROUND_ADAPTATION_STRENGTH() {
        return (float) effectiveValue("ENVIRONMENT_BACKGROUND_ADAPTATION_STRENGTH");
    }

    public static float ENVIRONMENT_BACKGROUND_DITHER_STRENGTH_LSB() {
        return (float) effectiveValue("ENVIRONMENT_BACKGROUND_DITHER_STRENGTH_LSB");
    }

    public static boolean ENVIRONMENT_LIGHT_ENABLED() {
        return (boolean) effectiveValue("ENVIRONMENT_LIGHT_ENABLED");
    }

    public static float ENVIRONMENT_LIGHT_INTENSITY() {
        return (float) effectiveValue("ENVIRONMENT_LIGHT_INTENSITY");
    }

    public static float ENVIRONMENT_LIGHT_WIDTH_SCALE() {
        return (float) effectiveValue("ENVIRONMENT_LIGHT_WIDTH_SCALE");
    }

    public static float ENVIRONMENT_LIGHT_BASE_LEVEL() {
        return (float) effectiveValue("ENVIRONMENT_LIGHT_BASE_LEVEL");
    }

    public static float ENVIRONMENT_LIGHT_COLOR_STRENGTH() {
        return (float) effectiveValue("ENVIRONMENT_LIGHT_COLOR_STRENGTH");
    }

    public static float ENVIRONMENT_LIGHT_SPATIAL_VARIATION() {
        return (float) effectiveValue("ENVIRONMENT_LIGHT_SPATIAL_VARIATION");
    }

    public static CanonicalVec3 GLOBAL_LIGHT_DIRECTION() {
        return (CanonicalVec3) effectiveValue("GLOBAL_LIGHT_DIRECTION");
    }

    public static BattleRenderMode BATTLE_RENDER_MODE() {
        return (BattleRenderMode) effectiveValue("BATTLE_RENDER_MODE");
    }

    public static float DIFFUSE_LIGHT_INTENSITY() {
        return (float) effectiveValue("DIFFUSE_LIGHT_INTENSITY");
    }

    public static float SPECULAR_LIGHT_INTENSITY() {
        return (float) effectiveValue("SPECULAR_LIGHT_INTENSITY");
    }

    public static float SPECULAR_SHININESS() {
        return (float) effectiveValue("SPECULAR_SHININESS");
    }

    public static boolean SHADOWS_ENABLED() {
        return (boolean) effectiveValue("SHADOWS_ENABLED");
    }

    public static int SHADOW_MAP_SIZE() {
        return (int) effectiveValue("SHADOW_MAP_SIZE");
    }

    public static float SHADOW_ORTHOGRAPHIC_RADIUS() {
        return (float) effectiveValue("SHADOW_ORTHOGRAPHIC_RADIUS");
    }

    public static float SHADOW_BIAS() {
        return (float) effectiveValue("SHADOW_BIAS");
    }

    public static float SHADOW_STRENGTH() {
        return (float) effectiveValue("SHADOW_STRENGTH");
    }

    public static float EXTRUDED_IMAGE_SIDE_BRIGHTNESS_REDUCTION() {
        return (float) effectiveValue("EXTRUDED_IMAGE_SIDE_BRIGHTNESS_REDUCTION");
    }

    public static boolean VOLUMETRIC_SHADOWS_ENABLED() {
        return (boolean) effectiveValue("VOLUMETRIC_SHADOWS_ENABLED");
    }

    public static float VOLUMETRIC_BASE_DENSITY() {
        return (float) effectiveValue("VOLUMETRIC_BASE_DENSITY");
    }

    public static float VOLUMETRIC_EDGE_FALLOFF() {
        return (float) effectiveValue("VOLUMETRIC_EDGE_FALLOFF");
    }

    public static float VOLUMETRIC_SCATTERING_STRENGTH() {
        return (float) effectiveValue("VOLUMETRIC_SCATTERING_STRENGTH");
    }

    public static float VOLUMETRIC_SHADOW_STRENGTH() {
        return (float) effectiveValue("VOLUMETRIC_SHADOW_STRENGTH");
    }

    public static float VOLUMETRIC_SHADOW_SCATTERING_MULTIPLIER() {
        return (float) effectiveValue("VOLUMETRIC_SHADOW_SCATTERING_MULTIPLIER");
    }

    public static float VOLUMETRIC_BELOW_GRID_SCATTERING_MULTIPLIER() {
        return (float) effectiveValue("VOLUMETRIC_BELOW_GRID_SCATTERING_MULTIPLIER");
    }

    public static float VOLUMETRIC_GRID_SCATTERING_BOUNDARY_OFFSET() {
        return (float) effectiveValue("VOLUMETRIC_GRID_SCATTERING_BOUNDARY_OFFSET");
    }

    public static float VOLUMETRIC_MIN_OPTICAL_DEPTH() {
        return (float) effectiveValue("VOLUMETRIC_MIN_OPTICAL_DEPTH");
    }

    public static int VOLUMETRIC_BASE_FOG_COLOR() {
        return (int) effectiveValue("VOLUMETRIC_BASE_FOG_COLOR");
    }

    public static int VOLUMETRIC_SUN_SCATTER_COLOR() {
        return (int) effectiveValue("VOLUMETRIC_SUN_SCATTER_COLOR");
    }

    public static float VOLUMETRIC_MINIMUM_FOG_MULTIPLIER() {
        return (float) effectiveValue("VOLUMETRIC_MINIMUM_FOG_MULTIPLIER");
    }

    public static float VOLUMETRIC_SHADOW_OPTICAL_DEPTH_GAIN() {
        return (float) effectiveValue("VOLUMETRIC_SHADOW_OPTICAL_DEPTH_GAIN");
    }

    public static float VOLUMETRIC_SHADOW_EXTINCTION_GAIN() {
        return (float) effectiveValue("VOLUMETRIC_SHADOW_EXTINCTION_GAIN");
    }

    public static boolean VOLUMETRIC_TONE_MAPPING_ENABLED() {
        return (boolean) effectiveValue("VOLUMETRIC_TONE_MAPPING_ENABLED");
    }

    public static float VOLUMETRIC_TONE_MAPPING_EXPOSURE() {
        return (float) effectiveValue("VOLUMETRIC_TONE_MAPPING_EXPOSURE");
    }

    public static float VOLUMETRIC_TONE_MAPPING_CONTRAST() {
        return (float) effectiveValue("VOLUMETRIC_TONE_MAPPING_CONTRAST");
    }

    public static float VOLUMETRIC_TONE_MAPPING_PIVOT() {
        return (float) effectiveValue("VOLUMETRIC_TONE_MAPPING_PIVOT");
    }

    public static float VOLUMETRIC_DITHER_STRENGTH_LSB() {
        return (float) effectiveValue("VOLUMETRIC_DITHER_STRENGTH_LSB");
    }

    public static int VOLUMETRIC_MAX_HIERARCHY_VISITS() {
        return (int) effectiveValue("VOLUMETRIC_MAX_HIERARCHY_VISITS");
    }

    public static int VOLUMETRIC_MAX_LEAF_VISITS() {
        return (int) effectiveValue("VOLUMETRIC_MAX_LEAF_VISITS");
    }

    public static float VOLUMETRIC_TRAVERSAL_EPSILON() {
        return (float) effectiveValue("VOLUMETRIC_TRAVERSAL_EPSILON");
    }

    public static float VOLUMETRIC_PARALLEL_UV_THRESHOLD() {
        return (float) effectiveValue("VOLUMETRIC_PARALLEL_UV_THRESHOLD");
    }

    public static boolean VOLUMETRIC_SHADOW_CACHE_ENABLED() {
        return (boolean) effectiveValue("VOLUMETRIC_SHADOW_CACHE_ENABLED");
    }

    public static VolumetricDebugMode VOLUMETRIC_DEBUG_MODE() {
        return (VolumetricDebugMode) effectiveValue("VOLUMETRIC_DEBUG_MODE");
    }


    private static ModConfigSpec.ConfigValue<?> defineConfigValue(
            ModConfigSpec.Builder builder, String path, Object defaultValue
    ) {
        return switch (defaultValue) {
            case Boolean value -> builder.define(path, value);
            case Integer value -> builder.defineInRange(path, value, 0, Integer.MAX_VALUE);
            case Float value -> builder.defineInRange(
                    path, Double.parseDouble(Float.toString(value)), -100_000.0D, 100_000.0D);
            case Double value -> builder.defineInRange(path, value, -100_000.0D, 100_000.0D);
            default -> throw new IllegalArgumentException("不支持的配置类型: " + defaultValue.getClass().getName());
        };
    }

    private static double nonNegativeSeconds(String name, double value) {
        if (!Double.isFinite(value) || value < 0.0D) {
            throw new IllegalStateException(name + " must be finite and >= 0.");
        }
        return value;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static ModConfigSpec.ConfigValue<?> defineEnum(
            ModConfigSpec.Builder builder, String path, Enum<?> defaultValue
    ) {
        return builder.defineEnum(path, (Enum) defaultValue);
    }

    private static Object coerceConfigValue(Object value, Class<?> targetType) {
        if (!(value instanceof Number number)) return value;
        if (targetType == int.class) return number.intValue();
        if (targetType == float.class) return number.floatValue();
        if (targetType == double.class) return number.doubleValue();
        throw new IllegalArgumentException("不支持的数值配置类型: " + targetType.getName());
    }

    private static String category(String name) {
        if (name.startsWith("REMOTE_")) return "network";
        if (name.startsWith("SCREEN_SHAKE")) return "feedback";
        if (name.startsWith("DEBUG_")) return "debug";
        if (name.startsWith("SCENE_") || name.startsWith("SPRITE_")) return "scene";
        if (name.startsWith("POST_")) return "post_processing";
        if (name.startsWith("ENVIRONMENT_BACKGROUND_")
                || name.startsWith("ENVIRONMENT_LIGHT_")) return "environment";
        if (name.startsWith("SOUL_GUIDE_")) return "soul_guide";
        if (name.startsWith("SOUL_")) return "soul_lighting";
        if (name.startsWith("VOLUMETRIC_")) return "volumetric";
        if (name.contains("LIGHT") || name.startsWith("SHADOW") || name.startsWith("BATTLE_RENDER") || name.startsWith("EXTRUDED_")) return "lighting";
        return "general";
    }

    private static String configurationName(String name) {
        String translated = name
                .replace("SCENE_RENDER_MODE_ENTER_DELAY_SECONDS", "进入 3D 渲染模式淡化延迟（秒）")
                .replace("SCENE_RENDER_MODE_CROSSFADE_SECONDS", "渲染模式交叉淡化时长（秒）")
                .replace("ENVIRONMENT_LIGHT_SPATIAL_VARIATION", "位置变化强度")
                .replace("ENVIRONMENT_LIGHT_COLOR_STRENGTH", "环境颜色保留")
                .replace("ENVIRONMENT_LIGHT_WIDTH_SCALE", "环境光光瓣宽度")
                .replace("ENVIRONMENT_LIGHT_BASE_LEVEL", "环境光全局底色")
                .replace("ENVIRONMENT_LIGHT_INTENSITY", "环境光强度")
                .replace("ENVIRONMENT_LIGHT_ENABLED", "启用环境光")
                .replace("ENVIRONMENT_BACKGROUND_ADAPTATION_STRENGTH", "背景亮度适应强度")
                .replace("ENVIRONMENT_BACKGROUND_TARGET_LUMA", "背景目标亮度")
                .replace("ENVIRONMENT_BACKGROUND_DITHER_STRENGTH_LSB", "背景抖动强度（LSB）")
                .replace("ENVIRONMENT_BACKGROUND_COLOR_STRENGTH", "背景色彩保留")
                .replace("ENVIRONMENT_BACKGROUND_WIDTH_SCALE", "背景光晕宽度")
                .replace("ENVIRONMENT_BACKGROUND_BASE_LEVEL", "全局环境底色")
                .replace("ENVIRONMENT_BACKGROUND_INTENSITY", "背景光晕强度")
                .replace("ENVIRONMENT_BACKGROUND_ENABLED", "启用环境背景")
                .replace("VOLUMETRIC_DITHER_STRENGTH_LSB", "体积抖动强度（LSB）")
                 .replace("SOUL_GUIDE_GRID_ENABLED", "启用辅助网格")
                 .replace("POST_BLOOM_BLUR_RADIUS_PIXELS", "Bloom 模糊半径（像素）")
                 .replace("POST_BLOOM_EMISSIVE_CURVE", "Bloom 自发光曲线")
                 .replace("POST_BLOOM_QUALITY", "Bloom 质量")
                 .replace("POST_BLOOM_INTENSITY", "Bloom 强度")
                 .replace("POST_BLOOM_ENABLED", "启用 Bloom")
                .replace("REMOTE", "远端").replace("TRACK", "轨道").replace("MAX", "最大")
                .replace("SAMPLES", "采样数").replace("SAMPLE", "采样").replace("DELAY", "延迟")
                .replace("HEALTH", "连接状态").replace("DEGRADED", "下降").replace("LOST", "丢失")
                .replace("AFTER", "判定时限").replace("SECONDS", "（秒）")
                .replace("SCREEN_SHAKE", "屏幕震动").replace("VIEWPORT", "视口")
                .replace("SCALE", "缩放").replace("PER_SECOND", "每秒")
                .replace("DEBUG", "调试").replace("SCENE", "场景").replace("TRANSITION", "过渡")
                .replace("PROJECTION", "投影").replace("CAMERA", "相机").replace("ROTATION", "旋转")
                .replace("3D_VIEW", "3D 视图").replace("ORBIT", "轨道").replace("INITIAL", "初始")
                .replace("YAW", "偏航角").replace("PITCH", "俯仰角").replace("DEGREES", "（度）")
                .replace("STEP", "步长").replace("DISTANCE", "距离").replace("FOV", "视野")
                .replace("RADIANS", "弧度").replace("RADIUS", "半径")
                .replace("NEAR_PLANE", "近裁剪面").replace("FAR_PLANE", "远裁剪面")
                .replace("CLIP_DISTANCE_PADDING", "裁剪距离余量")
                .replace("SPRITE_PIXELS", "精灵像素数").replace("CANONICAL_UNIT", "canonical 单位")
                .replace("SOUL", "Soul").replace("MODEL", "模型").replace("FRONT", "正面")
                .replace("OFFSET", "偏移").replace("TINT_LIGHT", "表面点光")
                .replace("VOLUMETRIC", "体积光").replace("POINT_SHADOW", "点阴影")
                .replace("FACE_SIZE", "单面分辨率").replace("BORDER", "隔离边界")
                .replace("RADIAL_BIAS", "径向深度偏移").replace("CORE_RADIUS", "核心半径")
                .replace("CUTOFF_FEATHER", "边界羽化").replace("PHASE_G", "相函数 G")
                .replace("INTENSITY", "强度").replace("RADIANCE_SOFT_CLIP", "辐亮度软压缩阈值")
                .replace("GRID_TRANSMISSION", "网格透射率").replace("SURFACE_LIGHT", "表面光")
                .replace("MINIMUM_RADIANCE_MULTIPLIER", "最低辐亮度倍率")
                .replace("SHADOW_SOURCE_GAIN", "阴影 source integral 增益")
                .replace("SHADOW_BIAS", "阴影偏移").replace("SOURCE_SEGMENTS", "光源积分区间数")
                .replace("HIERARCHY_VISITS", "层次节点访问上限").replace("TEXEL_VISITS", "纹素访问上限")
                .replace("LEAF_VISITS", "叶节点访问上限").replace("TRAVERSAL_EPSILON", "遍历 epsilon")
                .replace("GUIDE_GRID", "辅助网格").replace("DENSITY", "密度")
                .replace("HEIGHT", "高度").replace("LINE_WIDTH", "线宽").replace("FILL", "填充面")
                .replace("POST", "后处理").replace("BLOOM", "Bloom")
                .replace("BLUR_RADIUS_PIXELS", "模糊半径（像素）")
                .replace("THRESHOLD", "阈值")
                .replace("COLOR", "颜色").replace("ALPHA", "透明度").replace("ENABLED", "启用")
                .replace("BATTLE_RENDER_MODE", "战斗渲染方式")
                .replace("DIFFUSE_LIGHT", "漫反射光").replace("SPECULAR_LIGHT", "镜面反射光")
                .replace("SPECULAR_SHININESS", "镜面高光指数").replace("SHADOWS", "阴影")
                .replace("SHADOW_MAP_SIZE", "阴影贴图分辨率")
                .replace("SHADOW_ORTHOGRAPHIC_RADIUS", "阴影正交相机覆盖半径")
                .replace("SHADOW_STRENGTH", "阴影强度")
                .replace("EXTRUDED_IMAGE_SIDE_BRIGHTNESS_REDUCTION", "挤出图片侧面亮度降幅")
                .replace("BASE_DENSITY", "基础消光密度").replace("EDGE_FALLOFF", "边缘密度衰减指数")
                .replace("SCATTERING_STRENGTH", "散射强度")
                .replace("SHADOW_SCATTERING_MULTIPLIER", "阴影区散射倍率")
                .replace("BELOW_GRID_SCATTERING_MULTIPLIER", "网格下方散射倍率")
                .replace("GRID_SCATTERING_BOUNDARY_OFFSET", "网格散射分界偏移")
                .replace("MIN_OPTICAL_DEPTH", "最小光学厚度")
                .replace("BASE_FOG_COLOR", "基础雾颜色").replace("SUN_SCATTER_COLOR", "太阳散射颜色")
                .replace("MINIMUM_FOG_MULTIPLIER", "最小雾色倍率")
                .replace("SHADOW_OPTICAL_DEPTH_GAIN", "阴影光学厚度增益")
                .replace("SHADOW_EXTINCTION_GAIN", "阴影消光增益")
                .replace("TONE_MAPPING", "色调映射").replace("EXPOSURE", "曝光")
                .replace("CONTRAST", "对比度").replace("PIVOT", "支点")
                .replace("PARALLEL_UV_THRESHOLD", "近似平行光 UV 阈值")
                .replace("SHADOW_CACHE", "阴影缓存").replace("DEBUG_MODE", "调试输出")
                .replace("GLOBAL", "全局").replace("DIRECTION", "方向")
                .replace("ORTHOGRAPHIC", "正交").replace("SHADOW", "阴影")
                .replace("EMISSION", "发光").replace("LIGHT", "光照")
                .replace("GUIDE", "辅助").replace("GRID", "网格")
                .replace("SCATTERING", "散射").replace("SCATTER", "散射")
                .replace("STRENGTH", "强度").replace("BOUNDARY", "边界")
                .replace("PADDING", "余量").replace("CLIP", "裁剪")
                .replace("CORE", "核心").replace("BASE", "基础")
                .replace("FOG", "雾").replace("SUN", "太阳")
                .replace("MODE", "模式").replace("PER", "每")
                .replace('_', ' ').trim();
        return translated.replaceAll(" +", " ");
    }

    private static String configurationDescription(String name) {
        return "控制“" + configurationName(name) + "”。修改后立即应用于后续战斗渲染帧。";
    }

    public static void addConfigurationTranslations(BiConsumer<String, String> translations) {
        translations.accept("minetale.configuration.title", "MineTale 配置");
        translations.accept("minetale.configuration.combat", "战斗");
        translations.accept("minetale.configuration.network", "远端表现");
        translations.accept("minetale.configuration.feedback", "画面反馈");
        translations.accept("minetale.configuration.debug", "调试视图");
        translations.accept("minetale.configuration.scene", "场景与模型");
        translations.accept("minetale.configuration.soul_guide", "Soul 辅助网格");
        translations.accept("minetale.configuration.post_processing", "战斗画面后处理");
        translations.accept("minetale.configuration.environment", "战斗环境背景");
        translations.accept("minetale.configuration.soul_lighting", "Soul 光照与体积光");
        translations.accept("minetale.configuration.lighting", "全局光照与阴影");
        translations.accept("minetale.configuration.volumetric", "盒形体积介质");
        translations.accept("minetale.configuration.general", "通用");
        for (Field field : VisualConfig.class.getFields()) {
            if (isConfigurationDefaultField(field)
                    && !field.getName().equals("GLOBAL_LIGHT_DIRECTION")) {
                translations.accept("minetale.configuration." + field.getName().toLowerCase(Locale.ROOT),
                        configurationName(field.getName()));
                translations.accept("minetale.configuration." + field.getName().toLowerCase(Locale.ROOT) + ".tooltip",
                        configurationDescription(field.getName()));
            }
        }
        translations.accept("minetale.configuration.global_light_direction_x", "全局平行光方向 X");
        translations.accept("minetale.configuration.global_light_direction_y", "全局平行光方向 Y");
        translations.accept("minetale.configuration.global_light_direction_z", "全局平行光方向 Z");
        translations.accept("minetale.configuration.global_light_direction_x.tooltip", "全局平行光在战斗世界空间内传播方向的 X 分量。");
        translations.accept("minetale.configuration.global_light_direction_y.tooltip", "全局平行光在战斗世界空间内传播方向的 Y 分量。");
        translations.accept("minetale.configuration.global_light_direction_z.tooltip", "全局平行光在战斗世界空间内传播方向的 Z 分量。");
    }

    private VisualConfig() {
    }
}
