package cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.feature.settings;


import cn.jehorstudio.minetale.dimension.worldgen.preview.PreviewSetting;
import cn.jehorstudio.minetale.dimension.worldgen.preview.PreviewSettingKind;
import java.util.Map;

public final class SnowdinForestSettings {
    private SnowdinForestSettings() {}

    @PreviewSetting(group = "snowdin.forest.cluster", id = "candidate_centers_per_chunk", min = 0.0, max = 16.0, step = 1.0, kind = PreviewSettingKind.COUNT)
    public static final int CANDIDATE_CENTERS_PER_CHUNK = 5;

    @PreviewSetting(group = "snowdin.forest.cluster", id = "cluster_min_trees", min = 1.0, max = 16.0, step = 1.0, kind = PreviewSettingKind.COUNT)
    public static final int CLUSTER_MIN_TREES = 5;

    @PreviewSetting(group = "snowdin.forest.cluster", id = "cluster_max_trees", min = 1.0, max = 24.0, step = 1.0, kind = PreviewSettingKind.COUNT)
    public static final int CLUSTER_MAX_TREES = 12;

    @PreviewSetting(group = "snowdin.forest.cluster", id = "cluster_radius", min = 1.0, max = 18.0, step = 0.5, kind = PreviewSettingKind.OTHER)
    public static final double CLUSTER_RADIUS = 8.0;

    @PreviewSetting(group = "snowdin.forest.cluster", id = "cluster_noise_scale", min = 0.0001, max = 0.08, step = 0.0001, kind = PreviewSettingKind.SCALE)
    public static final double CLUSTER_NOISE_SCALE = 0.010;

    @PreviewSetting(group = "snowdin.forest.cluster", id = "cluster_noise_threshold", min = 0.0, max = 1.0, step = 0.01, kind = PreviewSettingKind.THRESHOLD)
    public static final double CLUSTER_NOISE_THRESHOLD = 0.42;

    @PreviewSetting(group = "snowdin.forest.cluster", id = "cluster_noise_fade", min = 0.01, max = 0.5, step = 0.01, kind = PreviewSettingKind.FACTOR)
    public static final double CLUSTER_NOISE_FADE = 0.18;

    @PreviewSetting(group = "snowdin.forest.terrain_weight", id = "plains_mix_weight", min = 0.0, max = 2.0, step = 0.01, kind = PreviewSettingKind.FACTOR)
    public static final double PLAINS_MIX_WEIGHT = 1.0;

    @PreviewSetting(group = "snowdin.forest.terrain_weight", id = "plateau_interior_weight", min = 0.0, max = 1.0, step = 0.01, kind = PreviewSettingKind.FACTOR)
    public static final double PLATEAU_INTERIOR_WEIGHT = 0.18;

    @PreviewSetting(group = "snowdin.forest.terrain_weight", id = "plateau_edge_boost", min = 0.0, max = 2.0, step = 0.01, kind = PreviewSettingKind.FACTOR)
    public static final double PLATEAU_EDGE_BOOST = 0.85;

    @PreviewSetting(group = "snowdin.forest.terrain_weight", id = "spawn_weight_threshold", min = 0.0, max = 1.0, step = 0.01, kind = PreviewSettingKind.THRESHOLD)
    public static final double SPAWN_WEIGHT_THRESHOLD = 0.20;

    @PreviewSetting(group = "snowdin.forest.tree_shape", id = "tree_clearance_height", min = 4.0, max = 32.0, step = 1.0, kind = PreviewSettingKind.HEIGHT)
    public static final int TREE_CLEARANCE_HEIGHT = 16;

    @PreviewSetting(group = "snowdin.forest.tree_shape", id = "tree_clearance_radius", min = 1.0, max = 6.0, step = 1.0, kind = PreviewSettingKind.OTHER)
    public static final int TREE_CLEARANCE_RADIUS = 3;

    @PreviewSetting(group = "snowdin.forest.sapling", id = "sapling_min_growth_brightness", min = 0.0, max = 15.0, step = 1.0, kind = PreviewSettingKind.THRESHOLD)
    public static final int SAPLING_MIN_GROWTH_BRIGHTNESS = 0;

    @PreviewSetting(group = "snowdin.forest.sapling", id = "sapling_growth_chance_denominator", min = 1.0, max = 32.0, step = 1.0, kind = PreviewSettingKind.COUNT)
    public static final int SAPLING_GROWTH_CHANCE_DENOMINATOR = 7;

    public static double mixWeight(double plateauPresence) {
        return 1.0 - plateauPresence;
    }

    public static double plateauEdgeMask(double plateauPresence) {
        return clamp01(1.0 - Math.abs(plateauPresence * 2.0 - 1.0));
    }

    public static double spawnWeight(double plateauPresence) {
        double mixWeight = mixWeight(plateauPresence);
        double edge = plateauEdgeMask(plateauPresence);
        return clamp01(mixWeight * PLAINS_MIX_WEIGHT
                + plateauPresence * PLATEAU_INTERIOR_WEIGHT
                + edge * PLATEAU_EDGE_BOOST);
    }

    public static double spawnWeight(double plateauPresence, Map<String, Double> overrides) {
        double mixWeight = mixWeight(plateauPresence);
        double edge = plateauEdgeMask(plateauPresence);
        return clamp01(mixWeight * value(overrides, "snowdin.forest.terrain_weight.plains_mix_weight", PLAINS_MIX_WEIGHT)
                + plateauPresence * value(overrides, "snowdin.forest.terrain_weight.plateau_interior_weight", PLATEAU_INTERIOR_WEIGHT)
                + edge * value(overrides, "snowdin.forest.terrain_weight.plateau_edge_boost", PLATEAU_EDGE_BOOST));
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

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
