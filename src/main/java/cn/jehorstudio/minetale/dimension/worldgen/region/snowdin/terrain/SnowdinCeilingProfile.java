package cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.terrain;

import cn.jehorstudio.minetale.dimension.worldgen.WorldgenSamplingContext;
import cn.jehorstudio.minetale.dimension.worldgen.math.WorldgenMath;
import cn.jehorstudio.minetale.dimension.worldgen.network.settings.UndergroundSamplingSettings;
import cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.settings.SnowdinDomeNoiseSettings;
import cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.settings.SnowdinTerrainSettings;
import net.minecraft.util.Mth;

import java.util.Map;

public record SnowdinCeilingProfile(
        double warpedX,
        double warpedZ,
        double baseHeight,
        double primaryHeight,
        double secondaryHeight,
        double legacyDomeHeight,
        double karstDomeHeight,
        double ceilingReliefHeight,
        double clouds2Mask,
        double unclampedCeilingY,
        double ceilingY
) {
    private static final Settings DEFAULT_SETTINGS = Settings.createDefaults();

    public static SnowdinCeilingProfile sample(double x, double z, double floorY) {
        return sample(new WorldgenSamplingContext(0L, UndergroundSamplingSettings.defaults()), x, z, floorY, Settings.defaults());
    }

    public static SnowdinCeilingProfile sample(double x, double z, double floorY, Settings settings) {
        return sample(new WorldgenSamplingContext(0L, UndergroundSamplingSettings.defaults()), x, z, floorY, settings);
    }

    public static SnowdinCeilingProfile sample(WorldgenSamplingContext context, double x, double z, double floorY) {
        return sample(context, x, z, floorY, Settings.defaults());
    }

    public static SnowdinCeilingProfile sample(WorldgenSamplingContext context, double x, double z, double floorY, Settings settings) {
        double warpedX = x + WorldgenMath.signedValueNoise2d(
                x * settings.karstDomeWarpScale,
                z * settings.karstDomeWarpScale,
                context.channelSeed(6311)
        ) * settings.karstDomeWarpStrength;
        double warpedZ = z + WorldgenMath.signedValueNoise2d(
                x * settings.karstDomeWarpScale + settings.karstDomeWarpZNoiseXOffset,
                z * settings.karstDomeWarpScale + settings.karstDomeWarpZNoiseZOffset,
                context.channelSeed(6312)
        ) * settings.karstDomeWarpStrength;

        double primaryHeight = WorldgenMath.valueNoise2d(
                warpedX * settings.ceilingPrimaryNoiseScale,
                warpedZ * settings.ceilingPrimaryNoiseScale,
                context.channelSeed(6301)
        ) * settings.ceilingPrimaryNoiseStrength;
        double secondaryHeight = WorldgenMath.valueNoise2d(
                warpedX * settings.ceilingSecondaryNoiseScale,
                warpedZ * settings.ceilingSecondaryNoiseScale,
                context.channelSeed(6302)
        ) * settings.ceilingSecondaryNoiseStrength;
        double legacyDomeHeight = legacyDomeHeight(context, warpedX, warpedZ, settings);
        double karstDomeHeight = karstDomeHeight(context, warpedX, warpedZ, settings);
        double clouds2Mask = clouds2DomeMask(context, warpedX, warpedZ, settings);
        double ceilingReliefHeight = ceilingReliefHeight(context, warpedX, warpedZ, settings);

        double height = settings.ceilingBaseHeight
                + primaryHeight
                + secondaryHeight
                + Math.max(legacyDomeHeight, karstDomeHeight) * clouds2Mask
                + ceilingReliefHeight * clouds2Mask;
        double unclampedCeilingY = floorY + height;
        double ceilingY = Mth.clamp(
                unclampedCeilingY,
                floorY + SnowdinTerrainSettings.CEILING_MIN_ABOVE_FLOOR,
                Math.min(
                        floorY + SnowdinTerrainSettings.CEILING_MAX_ABOVE_FLOOR,
                        SnowdinTerrainSettings.CEILING_MAX_Y
                )
        );

        return new SnowdinCeilingProfile(
                warpedX,
                warpedZ,
                settings.ceilingBaseHeight,
                primaryHeight,
                secondaryHeight,
                legacyDomeHeight,
                karstDomeHeight,
                ceilingReliefHeight,
                clouds2Mask,
                unclampedCeilingY,
                ceilingY
        );
    }

    public double ceilingHeight(double floorY) {
        return ceilingY - floorY;
    }

    private static double legacyDomeHeight(WorldgenSamplingContext context, double x, double z, Settings settings) {
        double domeNoise = WorldgenMath.valueNoise2d(
                x * settings.domeNoiseScale + settings.domeNoiseXOffset,
                z * settings.domeNoiseScale + settings.domeNoiseZOffset,
                context.channelSeed(6303)
        );

        if (domeNoise <= settings.domeThreshold) {
            return 0.0;
        }

        return (domeNoise - settings.domeThreshold)
                / settings.domeNormalizeRange
                * settings.domeExtraHeight;
    }

    private static double karstDomeHeight(WorldgenSamplingContext context, double x, double z, Settings settings) {
        double domeField =
                WorldgenMath.valueNoise2d(
                        x * settings.karstDomePrimaryScale,
                        z * settings.karstDomePrimaryScale,
                        context.channelSeed(6321)
                ) * settings.karstDomePrimaryWeight
                        + WorldgenMath.valueNoise2d(
                        x * settings.karstDomeSecondaryScale,
                        z * settings.karstDomeSecondaryScale,
                        context.channelSeed(6322)
                ) * settings.karstDomeSecondaryWeight
                        + WorldgenMath.valueNoise2d(
                        x * settings.karstDomeTertiaryScale,
                        z * settings.karstDomeTertiaryScale,
                        context.channelSeed(6323)
                ) * settings.karstDomeTertiaryWeight;

        double dome = WorldgenMath.smoothstep(
                (domeField - settings.karstDomeThreshold)
                        / settings.karstDomeNormalizeRange
        ) * settings.karstDomeExtraHeight;

        double ridge = 1.0 - Math.abs(WorldgenMath.signedValueNoise2d(
                x * settings.karstRidgeScale,
                z * settings.karstRidgeScale,
                context.channelSeed(6324)
        ));
        ridge = WorldgenMath.smoothstep(ridge);

        double scallop = 1.0 - Math.abs(WorldgenMath.signedValueNoise2d(
                x * settings.scallopNoiseScale,
                z * settings.scallopNoiseScale,
                context.channelSeed(6326)
        ));
        scallop = WorldgenMath.smoothstep(scallop);

        return dome
                + ridge * settings.karstRidgeHeight
                + scallop * settings.scallopHeight;
    }

    private static double ceilingReliefHeight(WorldgenSamplingContext context, double x, double z, Settings settings) {
        double relief =
                WorldgenMath.signedValueNoise2d(
                        x * settings.ceilingReliefNoiseScale,
                        z * settings.ceilingReliefNoiseScale,
                        context.channelSeed(6331)
                ) * settings.ceilingReliefPrimaryWeight
                        + WorldgenMath.signedValueNoise2d(
                        x * settings.ceilingReliefNoiseScale
                                * settings.ceilingReliefSecondaryScaleMultiplier,
                        z * settings.ceilingReliefNoiseScale
                                * settings.ceilingReliefSecondaryScaleMultiplier,
                        context.channelSeed(6332)
                ) * settings.ceilingReliefSecondaryWeight
                        + WorldgenMath.signedValueNoise2d(
                        x * settings.ceilingReliefNoiseScale
                                * settings.ceilingReliefTertiaryScaleMultiplier,
                        z * settings.ceilingReliefNoiseScale
                                * settings.ceilingReliefTertiaryScaleMultiplier,
                        context.channelSeed(6333)
                ) * settings.ceilingReliefTertiaryWeight;

        return relief / settings.ceilingReliefWeightSum
                * settings.ceilingReliefHeight;
    }

    private static double clouds2DomeMask(WorldgenSamplingContext context, double x, double z, Settings settings) {
        double scale = settings.clouds2DomeScale;
        double clouds =
                WorldgenMath.valueNoise2d(x * scale, z * scale, context.channelSeed(6351))
                        * settings.clouds2Octave1Weight
                        + WorldgenMath.valueNoise2d(
                        x * scale * settings.clouds2Octave2Scale,
                        z * scale * settings.clouds2Octave2Scale,
                        context.channelSeed(6352)
                ) * settings.clouds2Octave2Weight
                        + WorldgenMath.valueNoise2d(
                        x * scale * settings.clouds2Octave3Scale,
                        z * scale * settings.clouds2Octave3Scale,
                        context.channelSeed(6353)
                ) * settings.clouds2Octave3Weight
                        + WorldgenMath.valueNoise2d(
                        x * scale * settings.clouds2Octave4Scale,
                        z * scale * settings.clouds2Octave4Scale,
                        context.channelSeed(6354)
                ) * settings.clouds2Octave4Weight;

        double contrasted = (clouds - 0.5) * settings.clouds2Contrast + 0.5;
        contrasted = WorldgenMath.smoothstep(contrasted);

        return Mth.lerp(
                contrasted,
                settings.clouds2MaskMin,
                settings.clouds2MaskMax
        );
    }

    public record Settings(
            double ceilingBaseHeight,
            double ceilingPrimaryNoiseScale,
            double ceilingPrimaryNoiseStrength,
            double ceilingSecondaryNoiseScale,
            double ceilingSecondaryNoiseStrength,
            double domeNoiseScale,
            double domeNoiseXOffset,
            double domeNoiseZOffset,
            double domeThreshold,
            double domeNormalizeRange,
            double domeExtraHeight,
            double karstDomeWarpScale,
            double karstDomeWarpStrength,
            double karstDomeWarpZNoiseXOffset,
            double karstDomeWarpZNoiseZOffset,
            double karstDomePrimaryScale,
            double karstDomeSecondaryScale,
            double karstDomeTertiaryScale,
            double karstDomePrimaryWeight,
            double karstDomeSecondaryWeight,
            double karstDomeTertiaryWeight,
            double karstDomeThreshold,
            double karstDomeNormalizeRange,
            double karstDomeExtraHeight,
            double karstRidgeScale,
            double karstRidgeHeight,
            double scallopNoiseScale,
            double scallopHeight,
            double clouds2DomeScale,
            double clouds2Octave1Weight,
            double clouds2Octave2Scale,
            double clouds2Octave2Weight,
            double clouds2Octave3Scale,
            double clouds2Octave3Weight,
            double clouds2Octave4Scale,
            double clouds2Octave4Weight,
            double clouds2Contrast,
            double clouds2MaskMin,
            double clouds2MaskMax,
            double ceilingReliefNoiseScale,
            double ceilingReliefHeight,
            double ceilingReliefPrimaryWeight,
            double ceilingReliefSecondaryScaleMultiplier,
            double ceilingReliefSecondaryWeight,
            double ceilingReliefTertiaryScaleMultiplier,
            double ceilingReliefTertiaryWeight,
            double ceilingReliefWeightSum
    ) {
        public static Settings defaults() {
            return DEFAULT_SETTINGS;
        }

        private static Settings createDefaults() {
            return new Settings(
                    SnowdinDomeNoiseSettings.CEILING_BASE_HEIGHT,
                    SnowdinDomeNoiseSettings.CEILING_PRIMARY_NOISE_SCALE,
                    SnowdinDomeNoiseSettings.CEILING_PRIMARY_NOISE_STRENGTH,
                    SnowdinDomeNoiseSettings.CEILING_SECONDARY_NOISE_SCALE,
                    SnowdinDomeNoiseSettings.CEILING_SECONDARY_NOISE_STRENGTH,
                    SnowdinDomeNoiseSettings.DOME_NOISE_SCALE,
                    SnowdinDomeNoiseSettings.DOME_NOISE_X_OFFSET,
                    SnowdinDomeNoiseSettings.DOME_NOISE_Z_OFFSET,
                    SnowdinDomeNoiseSettings.DOME_THRESHOLD,
                    SnowdinDomeNoiseSettings.DOME_NORMALIZE_RANGE,
                    SnowdinDomeNoiseSettings.DOME_EXTRA_HEIGHT,
                    SnowdinDomeNoiseSettings.KARST_DOME_WARP_SCALE,
                    SnowdinDomeNoiseSettings.KARST_DOME_WARP_STRENGTH,
                    SnowdinDomeNoiseSettings.KARST_DOME_WARP_Z_NOISE_X_OFFSET,
                    SnowdinDomeNoiseSettings.KARST_DOME_WARP_Z_NOISE_Z_OFFSET,
                    SnowdinDomeNoiseSettings.KARST_DOME_PRIMARY_SCALE,
                    SnowdinDomeNoiseSettings.KARST_DOME_SECONDARY_SCALE,
                    SnowdinDomeNoiseSettings.KARST_DOME_TERTIARY_SCALE,
                    SnowdinDomeNoiseSettings.KARST_DOME_PRIMARY_WEIGHT,
                    SnowdinDomeNoiseSettings.KARST_DOME_SECONDARY_WEIGHT,
                    SnowdinDomeNoiseSettings.KARST_DOME_TERTIARY_WEIGHT,
                    SnowdinDomeNoiseSettings.KARST_DOME_THRESHOLD,
                    SnowdinDomeNoiseSettings.KARST_DOME_NORMALIZE_RANGE,
                    SnowdinDomeNoiseSettings.KARST_DOME_EXTRA_HEIGHT,
                    SnowdinDomeNoiseSettings.KARST_RIDGE_SCALE,
                    SnowdinDomeNoiseSettings.KARST_RIDGE_HEIGHT,
                    SnowdinDomeNoiseSettings.SCALLOP_NOISE_SCALE,
                    SnowdinDomeNoiseSettings.SCALLOP_HEIGHT,
                    SnowdinDomeNoiseSettings.CLOUDS2_DOME_SCALE,
                    SnowdinDomeNoiseSettings.CLOUDS2_OCTAVE_1_WEIGHT,
                    SnowdinDomeNoiseSettings.CLOUDS2_OCTAVE_2_SCALE,
                    SnowdinDomeNoiseSettings.CLOUDS2_OCTAVE_2_WEIGHT,
                    SnowdinDomeNoiseSettings.CLOUDS2_OCTAVE_3_SCALE,
                    SnowdinDomeNoiseSettings.CLOUDS2_OCTAVE_3_WEIGHT,
                    SnowdinDomeNoiseSettings.CLOUDS2_OCTAVE_4_SCALE,
                    SnowdinDomeNoiseSettings.CLOUDS2_OCTAVE_4_WEIGHT,
                    SnowdinDomeNoiseSettings.CLOUDS2_CONTRAST,
                    SnowdinDomeNoiseSettings.CLOUDS2_MASK_MIN,
                    SnowdinDomeNoiseSettings.CLOUDS2_MASK_MAX,
                    SnowdinDomeNoiseSettings.CEILING_RELIEF_NOISE_SCALE,
                    SnowdinDomeNoiseSettings.CEILING_RELIEF_HEIGHT,
                    SnowdinDomeNoiseSettings.CEILING_RELIEF_PRIMARY_WEIGHT,
                    SnowdinDomeNoiseSettings.CEILING_RELIEF_SECONDARY_SCALE_MULTIPLIER,
                    SnowdinDomeNoiseSettings.CEILING_RELIEF_SECONDARY_WEIGHT,
                    SnowdinDomeNoiseSettings.CEILING_RELIEF_TERTIARY_SCALE_MULTIPLIER,
                    SnowdinDomeNoiseSettings.CEILING_RELIEF_TERTIARY_WEIGHT,
                    SnowdinDomeNoiseSettings.CEILING_RELIEF_WEIGHT_SUM
            );
        }

        public static Settings fromOverrides(Map<String, Double> overrides) {
            Settings defaults = defaults();
            return new Settings(
                    value(overrides, "snowdin.ceiling.base_height", defaults.ceilingBaseHeight),
                    value(overrides, "snowdin.ceiling.primary_noise_scale", defaults.ceilingPrimaryNoiseScale),
                    value(overrides, "snowdin.ceiling.primary_noise_strength", defaults.ceilingPrimaryNoiseStrength),
                    value(overrides, "snowdin.ceiling.secondary_noise_scale", defaults.ceilingSecondaryNoiseScale),
                    value(overrides, "snowdin.ceiling.secondary_noise_strength", defaults.ceilingSecondaryNoiseStrength),
                    value(overrides, "snowdin.ceiling.legacy_dome_noise_scale", defaults.domeNoiseScale),
                    value(overrides, "snowdin.ceiling.legacy_dome_noise_x_offset", defaults.domeNoiseXOffset),
                    value(overrides, "snowdin.ceiling.legacy_dome_noise_z_offset", defaults.domeNoiseZOffset),
                    value(overrides, "snowdin.ceiling.legacy_dome_threshold", defaults.domeThreshold),
                    value(overrides, "snowdin.ceiling.legacy_dome_normalize_range", defaults.domeNormalizeRange),
                    value(overrides, "snowdin.ceiling.legacy_dome_extra_height", defaults.domeExtraHeight),
                    value(overrides, "snowdin.ceiling.karst_warp_scale", defaults.karstDomeWarpScale),
                    value(overrides, "snowdin.ceiling.karst_warp_strength", defaults.karstDomeWarpStrength),
                    value(overrides, "snowdin.ceiling.karst_warp_z_noise_x_offset", defaults.karstDomeWarpZNoiseXOffset),
                    value(overrides, "snowdin.ceiling.karst_warp_z_noise_z_offset", defaults.karstDomeWarpZNoiseZOffset),
                    value(overrides, "snowdin.ceiling.karst_primary_scale", defaults.karstDomePrimaryScale),
                    value(overrides, "snowdin.ceiling.karst_secondary_scale", defaults.karstDomeSecondaryScale),
                    value(overrides, "snowdin.ceiling.karst_tertiary_scale", defaults.karstDomeTertiaryScale),
                    value(overrides, "snowdin.ceiling.karst_primary_weight", defaults.karstDomePrimaryWeight),
                    value(overrides, "snowdin.ceiling.karst_secondary_weight", defaults.karstDomeSecondaryWeight),
                    value(overrides, "snowdin.ceiling.karst_tertiary_weight", defaults.karstDomeTertiaryWeight),
                    value(overrides, "snowdin.ceiling.karst_threshold", defaults.karstDomeThreshold),
                    value(overrides, "snowdin.ceiling.karst_normalize_range", defaults.karstDomeNormalizeRange),
                    value(overrides, "snowdin.ceiling.karst_extra_height", defaults.karstDomeExtraHeight),
                    value(overrides, "snowdin.ceiling.karst_ridge_scale", defaults.karstRidgeScale),
                    value(overrides, "snowdin.ceiling.karst_ridge_height", defaults.karstRidgeHeight),
                    value(overrides, "snowdin.ceiling.scallop_noise_scale", defaults.scallopNoiseScale),
                    value(overrides, "snowdin.ceiling.scallop_height", defaults.scallopHeight),
                    value(overrides, "snowdin.ceiling.clouds2_dome_scale", defaults.clouds2DomeScale),
                    value(overrides, "snowdin.ceiling.clouds2_octave_1_weight", defaults.clouds2Octave1Weight),
                    value(overrides, "snowdin.ceiling.clouds2_octave_2_scale", defaults.clouds2Octave2Scale),
                    value(overrides, "snowdin.ceiling.clouds2_octave_2_weight", defaults.clouds2Octave2Weight),
                    value(overrides, "snowdin.ceiling.clouds2_octave_3_scale", defaults.clouds2Octave3Scale),
                    value(overrides, "snowdin.ceiling.clouds2_octave_3_weight", defaults.clouds2Octave3Weight),
                    value(overrides, "snowdin.ceiling.clouds2_octave_4_scale", defaults.clouds2Octave4Scale),
                    value(overrides, "snowdin.ceiling.clouds2_octave_4_weight", defaults.clouds2Octave4Weight),
                    value(overrides, "snowdin.ceiling.clouds2_contrast", defaults.clouds2Contrast),
                    value(overrides, "snowdin.ceiling.clouds2_mask_min", defaults.clouds2MaskMin),
                    value(overrides, "snowdin.ceiling.clouds2_mask_max", defaults.clouds2MaskMax),
                    value(overrides, "snowdin.ceiling.relief_noise_scale", defaults.ceilingReliefNoiseScale),
                    value(overrides, "snowdin.ceiling.relief_height", defaults.ceilingReliefHeight),
                    value(overrides, "snowdin.ceiling.relief_primary_weight", defaults.ceilingReliefPrimaryWeight),
                    value(overrides, "snowdin.ceiling.relief_secondary_scale_multiplier", defaults.ceilingReliefSecondaryScaleMultiplier),
                    value(overrides, "snowdin.ceiling.relief_secondary_weight", defaults.ceilingReliefSecondaryWeight),
                    value(overrides, "snowdin.ceiling.relief_tertiary_scale_multiplier", defaults.ceilingReliefTertiaryScaleMultiplier),
                    value(overrides, "snowdin.ceiling.relief_tertiary_weight", defaults.ceilingReliefTertiaryWeight),
                    value(overrides, "snowdin.ceiling.relief_weight_sum", defaults.ceilingReliefWeightSum)
            );
        }

        private static double value(Map<String, Double> overrides, String key, double fallback) {
            if (overrides == null) {
                return fallback;
            }
            return overrides.getOrDefault(key, fallback);
        }
    }
}
