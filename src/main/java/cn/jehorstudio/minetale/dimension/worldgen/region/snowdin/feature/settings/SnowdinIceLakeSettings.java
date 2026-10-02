package cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.feature.settings;


import cn.jehorstudio.minetale.dimension.worldgen.preview.PreviewSetting;
import cn.jehorstudio.minetale.dimension.worldgen.preview.PreviewSettingKind;
import java.util.Map;

public final class SnowdinIceLakeSettings {
    private SnowdinIceLakeSettings() {}

    @PreviewSetting(group = "snowdin.ice_lake", id = "candidate_centers_per_chunk", min = 0.0, max = 8.0, step = 1.0, kind = PreviewSettingKind.COUNT)
    public static final int CANDIDATE_CENTERS_PER_CHUNK = 8;

    @PreviewSetting(group = "snowdin.ice_lake", id = "spawn_chance", min = 0.0, max = 1.0, step = 0.001, kind = PreviewSettingKind.FACTOR)
    public static final double SPAWN_CHANCE = 0.14;

    @PreviewSetting(group = "snowdin.ice_lake", id = "noise_scale", min = 0.0001, max = 0.05, step = 0.0001, kind = PreviewSettingKind.SCALE)
    public static final double NOISE_SCALE = 0.0032;

    @PreviewSetting(group = "snowdin.ice_lake", id = "noise_threshold", min = 0.0, max = 1.0, step = 0.01, kind = PreviewSettingKind.THRESHOLD)
    public static final double NOISE_THRESHOLD = 0.45;

    @PreviewSetting(group = "snowdin.ice_lake", id = "noise_fade", min = 0.01, max = 0.5, step = 0.01, kind = PreviewSettingKind.FACTOR)
    public static final double NOISE_FADE = 0.24;

    @PreviewSetting(group = "snowdin.ice_lake", id = "plains_mix_weight", min = 0.0, max = 2.0, step = 0.01, kind = PreviewSettingKind.FACTOR)
    public static final double PLAINS_MIX_WEIGHT = 1.35;

    @PreviewSetting(group = "snowdin.ice_lake", id = "plateau_interior_weight", min = 0.0, max = 1.0, step = 0.01, kind = PreviewSettingKind.FACTOR)
    public static final double PLATEAU_INTERIOR_WEIGHT = 0.22;

    @PreviewSetting(group = "snowdin.ice_lake", id = "radius_x_min", min = 2.0, max = 32.0, step = 1.0, kind = PreviewSettingKind.OTHER)
    public static final int RADIUS_X_MIN = 7;

    @PreviewSetting(group = "snowdin.ice_lake", id = "radius_x_max", min = 2.0, max = 40.0, step = 1.0, kind = PreviewSettingKind.OTHER)
    public static final int RADIUS_X_MAX = 18;

    @PreviewSetting(group = "snowdin.ice_lake", id = "radius_z_min", min = 2.0, max = 32.0, step = 1.0, kind = PreviewSettingKind.OTHER)
    public static final int RADIUS_Z_MIN = 6;

    @PreviewSetting(group = "snowdin.ice_lake", id = "radius_z_max", min = 2.0, max = 40.0, step = 1.0, kind = PreviewSettingKind.OTHER)
    public static final int RADIUS_Z_MAX = 17;

    @PreviewSetting(group = "snowdin.ice_lake", id = "depth", min = 1.0, max = 6.0, step = 1.0, kind = PreviewSettingKind.HEIGHT)
    public static final int DEPTH = 3;

    @PreviewSetting(group = "snowdin.ice_lake", id = "shape_lobe_min", min = 1.0, max = 12.0, step = 1.0, kind = PreviewSettingKind.COUNT)
    public static final int SHAPE_LOBE_MIN = 4;

    @PreviewSetting(group = "snowdin.ice_lake", id = "shape_lobe_max", min = 1.0, max = 12.0, step = 1.0, kind = PreviewSettingKind.COUNT)
    public static final int SHAPE_LOBE_MAX = 7;

    @PreviewSetting(group = "snowdin.ice_lake", id = "edge_noise_scale", min = 0.01, max = 1.0, step = 0.01, kind = PreviewSettingKind.SCALE)
    public static final double EDGE_NOISE_SCALE = 0.18;

    @PreviewSetting(group = "snowdin.ice_lake", id = "edge_noise_strength", min = 0.0, max = 0.6, step = 0.01, kind = PreviewSettingKind.STRENGTH)
    public static final double EDGE_NOISE_STRENGTH = 0.34;

    @PreviewSetting(group = "snowdin.ice_lake", id = "min_lake_area_cells", min = 0.0, max = 512.0, step = 1.0, kind = PreviewSettingKind.COUNT)
    public static final int MIN_LAKE_AREA_CELLS = 70;

    @PreviewSetting(group = "snowdin.ice_lake", id = "max_surface_delta", min = 0.0, max = 8.0, step = 1.0, kind = PreviewSettingKind.HEIGHT)
    public static final int MAX_SURFACE_DELTA = 1;

    @PreviewSetting(group = "snowdin.ice_lake", id = "max_terrace_mask", min = 0.0, max = 1.0, step = 0.01, kind = PreviewSettingKind.THRESHOLD)
    public static final double MAX_TERRACE_MASK = 0.18;

    @PreviewSetting(group = "snowdin.ice_lake", id = "min_flat_coverage", min = 0.0, max = 1.0, step = 0.01, kind = PreviewSettingKind.FACTOR)
    public static final double MIN_FLAT_COVERAGE = 0.88;

    @PreviewSetting(group = "snowdin.ice_lake", id = "basin_sample_step", min = 1.0, max = 6.0, step = 1.0, kind = PreviewSettingKind.COUNT)
    public static final int BASIN_SAMPLE_STEP = 1;

    @PreviewSetting(group = "snowdin.ice_lake", id = "water_surface_inset", min = 0.0, max = 3.0, step = 1.0, kind = PreviewSettingKind.HEIGHT)
    public static final int WATER_SURFACE_INSET = 1;

    @PreviewSetting(group = "snowdin.ice_lake", id = "overhead_clearance", min = 2.0, max = 16.0, step = 1.0, kind = PreviewSettingKind.HEIGHT)
    public static final int OVERHEAD_CLEARANCE = 7;

    @PreviewSetting(group = "snowdin.ice_lake", id = "existing_water_margin", min = 0.0, max = 12.0, step = 1.0, kind = PreviewSettingKind.OTHER)
    public static final int EXISTING_WATER_MARGIN = 5;

    @PreviewSetting(group = "snowdin.ice_lake", id = "mix_max_plateau_presence", min = 0.0, max = 1.0, step = 0.01, kind = PreviewSettingKind.THRESHOLD)
    public static final double MIX_MAX_PLATEAU_PRESENCE = 0.42;

    @PreviewSetting(group = "snowdin.ice_lake", id = "mix_valley_sample_radius", min = 4.0, max = 48.0, step = 1.0, kind = PreviewSettingKind.OTHER)
    public static final int MIX_VALLEY_SAMPLE_RADIUS = 12;

    @PreviewSetting(group = "snowdin.ice_lake", id = "mix_valley_sample_step", min = 1.0, max = 8.0, step = 1.0, kind = PreviewSettingKind.COUNT)
    public static final int MIX_VALLEY_SAMPLE_STEP = 2;

    @PreviewSetting(group = "snowdin.ice_lake", id = "mix_valley_min_depth", min = 0.0, max = 16.0, step = 0.1, kind = PreviewSettingKind.HEIGHT)
    public static final double MIX_VALLEY_MIN_DEPTH = 1.6;

    @PreviewSetting(group = "snowdin.ice_lake", id = "mix_valley_max_rim_height_range", min = 0.0, max = 24.0, step = 0.25, kind = PreviewSettingKind.HEIGHT)
    public static final double MIX_VALLEY_MAX_RIM_HEIGHT_RANGE = 4.0;

    @PreviewSetting(group = "snowdin.ice_lake", id = "mix_valley_chance_scale", min = 0.0, max = 4.0, step = 0.01, kind = PreviewSettingKind.FACTOR)
    public static final double MIX_VALLEY_CHANCE_SCALE = 2.4;

    @PreviewSetting(group = "snowdin.ice_lake", id = "plateau_min_presence", min = 0.0, max = 1.0, step = 0.01, kind = PreviewSettingKind.THRESHOLD)
    public static final double PLATEAU_MIN_PRESENCE = 0.62;

    @PreviewSetting(group = "snowdin.ice_lake", id = "plateau_flat_sample_radius", min = 4.0, max = 64.0, step = 1.0, kind = PreviewSettingKind.OTHER)
    public static final int PLATEAU_FLAT_SAMPLE_RADIUS = 14;

    @PreviewSetting(group = "snowdin.ice_lake", id = "plateau_flat_sample_step", min = 1.0, max = 8.0, step = 1.0, kind = PreviewSettingKind.COUNT)
    public static final int PLATEAU_FLAT_SAMPLE_STEP = 2;

    @PreviewSetting(group = "snowdin.ice_lake", id = "plateau_flat_max_delta", min = 0.0, max = 12.0, step = 1.0, kind = PreviewSettingKind.HEIGHT)
    public static final int PLATEAU_FLAT_MAX_DELTA = 1;

    @PreviewSetting(group = "snowdin.ice_lake", id = "plateau_flat_min_coverage", min = 0.0, max = 1.0, step = 0.01, kind = PreviewSettingKind.FACTOR)
    public static final double PLATEAU_FLAT_MIN_COVERAGE = 0.92;

    @PreviewSetting(group = "snowdin.ice_lake", id = "plateau_min_terrace_mask", min = 0.0, max = 1.0, step = 0.01, kind = PreviewSettingKind.THRESHOLD)
    public static final double PLATEAU_MIN_TERRACE_MASK = 0.72;

    @PreviewSetting(group = "snowdin.ice_lake", id = "plateau_chance_scale", min = 0.0, max = 4.0, step = 0.01, kind = PreviewSettingKind.FACTOR)
    public static final double PLATEAU_CHANCE_SCALE = 1.8;

    public static double spawnWeight(double plateauPresence) {
        return Math.max(0.0, Math.min(1.0, (1.0 - plateauPresence) * PLAINS_MIX_WEIGHT
                + plateauPresence * PLATEAU_INTERIOR_WEIGHT));
    }

    public static double spawnWeight(double plateauPresence, Map<String, Double> overrides) {
        return Math.max(0.0, Math.min(1.0, (1.0 - plateauPresence) * value(overrides, "snowdin.ice_lake.plains_mix_weight", PLAINS_MIX_WEIGHT)
                + plateauPresence * value(overrides, "snowdin.ice_lake.plateau_interior_weight", PLATEAU_INTERIOR_WEIGHT)));
    }

    public static double value(Map<String, Double> overrides, String key, double fallback) {
        if (overrides == null) {
            return fallback;
        }
        return overrides.getOrDefault(key, fallback);
    }

    public static int intValue(Map<String, Double> overrides, String key, int fallback) {
        return (int) Math.round(value(overrides, key, fallback));
    }
}
