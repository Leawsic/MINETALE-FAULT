package cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.terrain;

import cn.jehorstudio.minetale.dimension.worldgen.WorldgenSamplingContext;
import cn.jehorstudio.minetale.dimension.worldgen.math.WorldgenMath;
import cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.settings.SnowdinTerrainSettings;
import net.minecraft.util.Mth;

import java.util.Map;

public final class SnowdinGroundProfile {
    public static final SnowdinGroundProfile INSTANCE = new SnowdinGroundProfile();
    private static final Settings DEFAULT_SETTINGS = Settings.createDefaults();

    private SnowdinGroundProfile() {}

    public Floor floor(
            WorldgenSamplingContext context,
            double x,
            double z,
            double progress,
            double mainPathY,
            double horizontalPathDistance
    ) {
        return floor(context, x, z, progress, mainPathY, horizontalPathDistance, Settings.defaults());
    }

    public Floor floor(
            WorldgenSamplingContext context,
            double x,
            double z,
            double progress,
            double mainPathY,
            double horizontalPathDistance,
            Settings settings
    ) {
        double plateauPresence = plateauPresence(context, x, z, settings);
        return floor(
                context,
                x,
                z,
                progress,
                mainPathY,
                horizontalPathDistance,
                plateauPresence,
                settings
        );
    }

    // 接受已采样的 plateau presence，保证同列各地面分支使用同一 mask 事实。
    public Floor floor(
            WorldgenSamplingContext context,
            double x,
            double z,
            double progress,
            double mainPathY,
            double horizontalPathDistance,
            double plateauPresence,
            Settings settings
    ) {
        Floor mixFloor = sampleMixFloor(context, x, z, progress, mainPathY, horizontalPathDistance, settings);
        Floor plateauFloor = samplePlateauFloor(context, x, z, progress, mainPathY, horizontalPathDistance, settings);

        double floor = Mth.lerp(plateauPresence, mixFloor.floorY(), plateauFloor.floorY());
        double terraceBaseY = Mth.lerp(plateauPresence, mixFloor.terraceBaseY(), plateauFloor.terraceBaseY());

        return new Floor(
                Mth.clamp(floor, settings.floorMinY, settings.floorMaxY),
                Mth.clamp(terraceBaseY, settings.floorMinY, settings.floorMaxY)
        );
    }

    public Terrace terrace(
            WorldgenSamplingContext context,
            double x,
            double z,
            Floor floor,
            double ceilingY,
            double horizontalPathDistance
    ) {
        return terrace(context, x, z, floor, ceilingY, horizontalPathDistance, Settings.defaults());
    }

    public Terrace terrace(
            WorldgenSamplingContext context,
            double x,
            double z,
            Floor floor,
            double ceilingY,
            double horizontalPathDistance,
            Settings settings
    ) {
        return terrace(
                context,
                x,
                z,
                floor,
                ceilingY,
                horizontalPathDistance,
                plateauPresence(context, x, z, settings),
                settings
        );
    }

    public Terrace terrace(
            WorldgenSamplingContext context,
            double x,
            double z,
            Floor floor,
            double ceilingY,
            double horizontalPathDistance,
            double plateauPresence,
            Settings settings
    ) {
        double horizontalDistance = Math.abs(horizontalPathDistance);
        double terraceOffset = terraceOffset(context, x, z, horizontalDistance, settings);
        double availableHeight = ceilingY - floor.terraceBaseY() - settings.terraceCeilingClearance;
        double cappedOffset = Math.min(terraceOffset, Math.max(availableHeight, 0.0));
        double rawTopOffset = Math.max(cappedOffset, settings.terraceMinTopOffset);
        double rawTopY = floor.terraceBaseY() + rawTopOffset;

        double sideMask = valleySideMask(horizontalDistance, settings);
        double channelMask = terraceChannelMask(context, x, z, settings);
        double clearanceMask = WorldgenMath.smoothstep(
                (availableHeight - settings.terraceMinTopOffset)
                        / settings.terraceMinTopFade
        );
        double heightMask = WorldgenMath.smoothstep(
                (terraceOffset - settings.terraceMinTopOffset)
                        / settings.terraceMinTopFade
        );
        double visibleMask = WorldgenMath.smoothstep(
                (rawTopY - floor.floorY() - settings.terraceVisibleFloorOffset)
                        / settings.terraceVisibleFloorFade
        );

        double mask = Mth.clamp(sideMask * channelMask * clearanceMask * heightMask * visibleMask, 0.0, 1.0);
        mask *= plateauPresence;
        double flatOffset = terraceMacroFlatOffset(context, x, z, horizontalDistance, availableHeight, settings);
        double flatTopY = floor.terraceBaseY() + flatOffset;
        double flattenT = WorldgenMath.smoothstep(
                (mask - settings.terraceFlattenCoreThreshold)
                        / settings.terraceFlattenTransitionFade
        );
        double topY = Mth.lerp(flattenT, rawTopY, flatTopY);

        return new Terrace(topY, mask);
    }

    // 仅供已证明洞顶不构成约束的规划路径，跳过 ceiling noise。
    public Terrace uncappedTerrace(
            WorldgenSamplingContext context,
            double x,
            double z,
            Floor floor,
            double horizontalPathDistance,
            double plateauPresence,
            Settings settings
    ) {
        return terrace(
                context,
                x,
                z,
                floor,
                Double.POSITIVE_INFINITY,
                horizontalPathDistance,
                plateauPresence,
                settings
        );
    }

    public double plateauPresence(
            WorldgenSamplingContext context,
            double x,
            double z
    ) {
        return plateauPresence(context, x, z, Settings.defaults());
    }

    public double plateauPresence(
            WorldgenSamplingContext context,
            double x,
            double z,
            Settings settings
    ) {
        double warpX = x + WorldgenMath.signedValueNoise2d(
                x * settings.plateauMaskWarpScale,
                z * settings.plateauMaskWarpScale,
                context.channelSeed(6241)
        ) * settings.plateauMaskWarpStrength;
        double warpZ = z + WorldgenMath.signedValueNoise2d(
                x * settings.plateauMaskWarpScale + settings.plateauMaskWarpZNoiseXOffset,
                z * settings.plateauMaskWarpScale + settings.plateauMaskWarpZNoiseZOffset,
                context.channelSeed(6242)
        ) * settings.plateauMaskWarpStrength;

        double maskNoise = Mth.clamp(
                0.5 + WorldgenMath.signedValueNoise2d(
                        warpX * settings.plateauMaskNoiseScale,
                        warpZ * settings.plateauMaskNoiseScale,
                        context.channelSeed(6243)
                ) * 0.5,
                0.0,
                1.0
        );

        return WorldgenMath.smoothstep(
                (maskNoise - settings.plateauMaskThreshold)
                        / settings.plateauMaskFade
        );
    }

    public Floor mixFloor(
            WorldgenSamplingContext context,
            double x,
            double z,
            double progress,
            double mainPathY,
            double horizontalPathDistance,
            Settings settings
    ) {
        Floor mixFloor = sampleMixFloor(context, x, z, progress, mainPathY, horizontalPathDistance, settings);
        return new Floor(
                Mth.clamp(mixFloor.floorY(), settings.floorMinY, settings.floorMaxY),
                Mth.clamp(mixFloor.terraceBaseY(), settings.floorMinY, settings.floorMaxY)
        );
    }

    public Floor plateauFloor(
            WorldgenSamplingContext context,
            double x,
            double z,
            double progress,
            double mainPathY,
            double horizontalPathDistance,
            Settings settings
    ) {
        Floor plateauFloor = samplePlateauFloor(context, x, z, progress, mainPathY, horizontalPathDistance, settings);
        return new Floor(
                Mth.clamp(plateauFloor.floorY(), settings.floorMinY, settings.floorMaxY),
                Mth.clamp(plateauFloor.terraceBaseY(), settings.floorMinY, settings.floorMaxY)
        );
    }

    private static Floor sampleMixFloor(
            WorldgenSamplingContext context,
            double x,
            double z,
            double progress,
            double mainPathY,
            double horizontalPathDistance,
            Settings settings
    ) {
        double road = roadInfluence(horizontalPathDistance, settings);
        double broad =
                WorldgenMath.signedValueNoise2d(
                        x * settings.mixFloorPrimaryNoiseScale,
                        z * settings.mixFloorPrimaryNoiseScale,
                        context.channelSeed(6201)
                ) * settings.mixFloorPrimaryNoiseStrength
                        + WorldgenMath.signedValueNoise2d(
                        x * settings.mixFloorSecondaryNoiseScale,
                        z * settings.mixFloorSecondaryNoiseScale,
                        context.channelSeed(6202)
                ) * settings.mixFloorSecondaryNoiseStrength
                        + WorldgenMath.signedValueNoise2d(
                        x * settings.mixBroadReliefNoiseScale,
                        z * settings.mixBroadReliefNoiseScale,
                        context.channelSeed(6203)
                ) * settings.mixBroadReliefNoiseStrength;

        double wildFloor = mainPathY
                + settings.mixWildFloorMainPathOffset
                + broad;

        double roadWobble =
                WorldgenMath.signedValueNoise1d(
                        progress * settings.roadWobblePrimaryScale,
                        context.channelSeed(6211)
                ) * settings.roadWobblePrimaryStrength
                        + WorldgenMath.signedValueNoise1d(
                        progress * settings.roadWobbleSecondaryScale,
                        context.channelSeed(6212)
                ) * settings.roadWobbleSecondaryStrength;

        double roadFloor = mainPathY
                + settings.mixRoadFloorMainPathOffset
                + broad * settings.mixRoadFloorBroadNoiseScale
                + roadWobble;

        double trench = centerTrench(horizontalPathDistance, settings);
        double terraceBaseY = wildFloor - trench * settings.mixTerraceBaseTrenchDepth;
        double floor = Math.max(terraceBaseY, Mth.lerp(road, wildFloor, roadFloor));
        return new Floor(floor, terraceBaseY);
    }

    private static Floor samplePlateauFloor(
            WorldgenSamplingContext context,
            double x,
            double z,
            double progress,
            double mainPathY,
            double horizontalPathDistance,
            Settings settings
    ) {
        double road = roadInfluence(horizontalPathDistance, settings);
        double broad =
                WorldgenMath.signedValueNoise2d(
                        x * settings.floorPrimaryNoiseScale,
                        z * settings.floorPrimaryNoiseScale,
                        context.channelSeed(6201)
                ) * settings.floorPrimaryNoiseStrength
                        + WorldgenMath.signedValueNoise2d(
                        x * settings.floorSecondaryNoiseScale,
                        z * settings.floorSecondaryNoiseScale,
                        context.channelSeed(6202)
                ) * settings.floorSecondaryNoiseStrength;

        double wildFloor = mainPathY
                + settings.wildFloorMainPathOffset
                + broad;

        double roadWobble =
                WorldgenMath.signedValueNoise1d(
                        progress * settings.roadWobblePrimaryScale,
                        context.channelSeed(6211)
                ) * settings.roadWobblePrimaryStrength
                        + WorldgenMath.signedValueNoise1d(
                        progress * settings.roadWobbleSecondaryScale,
                        context.channelSeed(6212)
                ) * settings.roadWobbleSecondaryStrength;

        double roadFloor = mainPathY
                + settings.roadFloorMainPathOffset
                + broad * settings.roadFloorBroadNoiseScale
                + roadWobble;

        double trench = centerTrench(horizontalPathDistance, settings);
        double terraceBaseY = wildFloor - trench * settings.terraceCenterTrenchDepth;
        double floor = Math.max(terraceBaseY, Mth.lerp(road, wildFloor, roadFloor));
        return new Floor(floor, terraceBaseY);
    }

    public double terraceSolidMask(double y, Terrace terrace) {
        double belowTop = 1.0 - WorldgenMath.smoothstep(
                (y - terrace.topY() - SnowdinTerrainSettings.TERRACE_TOP_CARVE_OFFSET)
                        / SnowdinTerrainSettings.TERRACE_TOP_FEATHER
        );

        return Mth.clamp(terrace.mask() * belowTop, 0.0, 1.0);
    }

    public double pillarTerraceClearance(double y, Terrace terrace) {
        double aboveTop = WorldgenMath.smoothstep(
                (y - terrace.topY()) / SnowdinTerrainSettings.TERRACE_TOP_FEATHER
        );

        return 1.0 - Mth.clamp(terrace.mask() * aboveTop, 0.0, 1.0);
    }

    public double terraceTopPillarMask(double y, Terrace terrace) {
        double aboveTop = WorldgenMath.smoothstep(
                (y - terrace.topY() - SnowdinTerrainSettings.TERRACE_PILLAR_FLOOR_OFFSET)
                        / SnowdinTerrainSettings.TERRACE_PILLAR_FOOT_FEATHER
        );

        return Mth.clamp(terrace.mask() * aboveTop, 0.0, 1.0);
    }

    private static double terraceOffset(WorldgenSamplingContext context, double x, double z, double horizontalDistance, Settings settings) {
        double warpedX = x + WorldgenMath.signedValueNoise2d(
                x * settings.terraceWarpScale,
                z * settings.terraceWarpScale,
                context.channelSeed(6221)
        ) * settings.terraceWarpStrength;
        double warpedZ = z + WorldgenMath.signedValueNoise2d(
                x * settings.terraceWarpScale + 73.0,
                z * settings.terraceWarpScale - 41.0,
                context.channelSeed(6222)
        ) * settings.terraceWarpStrength;

        double plateauNoise = Mth.clamp(
                0.5 + WorldgenMath.signedValueNoise2d(
                        warpedX * settings.terracePrimaryNoiseScale,
                        warpedZ * settings.terracePrimaryNoiseScale,
                        context.channelSeed(6223)
                ) * 0.5,
                0.0,
                1.0
        );
        double plateauRoot = Math.sqrt(plateauNoise);
        double height = canyonStep(
                plateauRoot,
                settings.terracePlateauSteps
        ) * settings.terracePlateauSize;
        height += canyonStep(
                plateauRoot,
                settings.terraceCurvedTopSteps
        ) * settings.terraceCurvedTopHeight;
        height += WorldgenMath.signedValueNoise2d(
                warpedX * settings.terraceSecondaryNoiseScale,
                warpedZ * settings.terraceSecondaryNoiseScale,
                context.channelSeed(6224)
        ) * settings.terraceSecondaryNoiseStrength;
        height += WorldgenMath.signedValueNoise2d(
                warpedX * settings.terraceDetailNoiseScale,
                warpedZ * settings.terraceDetailNoiseScale,
                context.channelSeed(6225)
        ) * settings.terraceDetailNoiseStrength;

        height = Mth.clamp(
                height,
                0.0,
                settings.terraceMaxTopOffset
        );

        return terracedHeight(
                height,
                settings.terraceStepHeight,
                settings.terraceEdgeBlend
        );
    }

    private static double canyonStep(double heightScale, int scaleTo) {
        int clampTo100 = (int) (heightScale * scaleTo * scaleTo);

        return Mth.clamp(Math.round(clampTo100 / (float) scaleTo) / (double) scaleTo, 0.0, 1.0);
    }

    private static double terracedHeight(double height, double stepHeight, double edgeBlend) {
        double lower = Math.floor(height / stepHeight) * stepHeight;
        double upper = lower + stepHeight;
        double stepProgress = (height - lower) / stepHeight;
        double transitionStart = 1.0 - edgeBlend;
        double transition = WorldgenMath.smoothstep(
                (stepProgress - transitionStart) / edgeBlend
        );

        return Mth.lerp(transition, lower, upper);
    }

    private static double valleySideMask(double horizontalDistance, Settings settings) {
        return WorldgenMath.smoothstep(
                (horizontalDistance - settings.terraceValleyFloorHalfWidth)
                        / settings.terraceValleyFadeWidth
        );
    }

    private static double terraceChannelMask(WorldgenSamplingContext context, double x, double z, Settings settings) {
        double channelX = x + WorldgenMath.signedValueNoise2d(
                x * settings.terraceChannelWarpScale,
                z * settings.terraceChannelWarpScale,
                context.channelSeed(6228)
        ) * settings.terraceChannelWarpStrength;
        double channelZ = z + WorldgenMath.signedValueNoise2d(
                x * settings.terraceChannelWarpScale + 51.0,
                z * settings.terraceChannelWarpScale - 67.0,
                context.channelSeed(6229)
        ) * settings.terraceChannelWarpStrength;
        double channel = Math.abs(WorldgenMath.signedValueNoise2d(
                channelX * settings.terraceChannelNoiseScale,
                channelZ * settings.terraceChannelNoiseScale,
                context.channelSeed(6230)
        ));

        return WorldgenMath.smoothstep(
                (channel - settings.terraceChannelWidth)
                        / settings.terraceChannelFade
        );
    }

    private static double centerTrench(double horizontalPathDistance, Settings settings) {
        double d = Math.abs(horizontalPathDistance);

        return 1.0 - WorldgenMath.smoothstep(
                (d - settings.terraceCenterTrenchHalfWidth)
                        / settings.terraceCenterTrenchFade
        );
    }

    private static double roadInfluence(double horizontalPathDistance) {
        return roadInfluence(horizontalPathDistance, Settings.defaults());
    }

    private static double roadInfluence(double horizontalPathDistance, Settings settings) {
        double d = Math.abs(horizontalPathDistance);

        return 1.0 - WorldgenMath.smoothstep(
                (d - settings.roadInfluenceCoreDistance)
                        / settings.roadInfluenceFadeDistance
        );
    }

    private static double terraceMacroFlatOffset(
            WorldgenSamplingContext context,
            double x,
            double z,
            double horizontalDistance,
            double availableHeight,
            Settings settings
    ) {
        double macroOffset = terraceOffsetMacro(context, x, z, horizontalDistance, settings);
        return clampTerraceOffset(macroOffset, availableHeight, settings);
    }

    private static double clampTerraceOffset(double offset, double availableHeight, Settings settings) {
        double capped = Math.min(offset, Math.max(availableHeight, 0.0));
        return Math.max(capped, settings.terraceMinTopOffset);
    }

    private static double terraceOffsetMacro(
            WorldgenSamplingContext context,
            double x,
            double z,
            double horizontalDistance,
            Settings settings
    ) {
        double warpedX = x + WorldgenMath.signedValueNoise2d(
                x * settings.terraceWarpScale,
                z * settings.terraceWarpScale,
                context.channelSeed(6221)
        ) * settings.terraceWarpStrength;
        double warpedZ = z + WorldgenMath.signedValueNoise2d(
                x * settings.terraceWarpScale + 73.0,
                z * settings.terraceWarpScale - 41.0,
                context.channelSeed(6222)
        ) * settings.terraceWarpStrength;

        double plateauNoise = Mth.clamp(
                0.5 + WorldgenMath.signedValueNoise2d(
                        warpedX * settings.terracePrimaryNoiseScale,
                        warpedZ * settings.terracePrimaryNoiseScale,
                        context.channelSeed(6223)
                ) * 0.5,
                0.0,
                1.0
        );
        double plateauRoot = Math.sqrt(plateauNoise);

        double macroHeight = canyonStep(
                plateauRoot,
                settings.terracePlateauSteps
        ) * settings.terracePlateauSize;
        macroHeight += canyonStep(
                plateauRoot,
                settings.terraceCurvedTopSteps
        ) * settings.terraceCurvedTopHeight;

        macroHeight = Mth.clamp(
                macroHeight,
                0.0,
                settings.terraceMaxTopOffset
        );

        return terracedHeight(
                macroHeight,
                settings.terraceStepHeight,
                settings.terraceEdgeBlend
        );
    }

    public record Floor(double floorY, double terraceBaseY) {}

    public record Terrace(double topY, double mask) {}

    public record Settings(
            double floorPrimaryNoiseScale,
            double floorPrimaryNoiseStrength,
            double floorSecondaryNoiseScale,
            double floorSecondaryNoiseStrength,
            double mixFloorPrimaryNoiseScale,
            double mixFloorPrimaryNoiseStrength,
            double mixFloorSecondaryNoiseScale,
            double mixFloorSecondaryNoiseStrength,
            double mixBroadReliefNoiseScale,
            double mixBroadReliefNoiseStrength,
            double mixWildFloorMainPathOffset,
            double mixRoadFloorMainPathOffset,
            double mixRoadFloorBroadNoiseScale,
            double mixTerraceBaseTrenchDepth,
            double plateauMaskNoiseScale,
            double plateauMaskThreshold,
            double plateauMaskFade,
            double plateauMaskWarpScale,
            double plateauMaskWarpStrength,
            double plateauMaskWarpZNoiseXOffset,
            double plateauMaskWarpZNoiseZOffset,
            double terraceWarpScale,
            double terraceWarpStrength,
            double terracePrimaryNoiseScale,
            double terraceSecondaryNoiseScale,
            double terraceDetailNoiseScale,
            double terraceValleyFloorHalfWidth,
            double terraceValleyFadeWidth,
            double terraceMaxTopOffset,
            double terracePlateauSize,
            int terracePlateauSteps,
            double terraceCurvedTopHeight,
            int terraceCurvedTopSteps,
            double terraceSecondaryNoiseStrength,
            double terraceDetailNoiseStrength,
            double terraceChannelWarpScale,
            double terraceChannelWarpStrength,
            double terraceChannelNoiseScale,
            double terraceChannelWidth,
            double terraceChannelFade,
            double terraceCenterTrenchDepth,
            double terraceCenterTrenchHalfWidth,
            double terraceCenterTrenchFade,
            double terraceStepHeight,
            double terraceEdgeBlend,
            double terraceMinTopOffset,
            double terraceMinTopFade,
            double terraceCeilingClearance,
            double terraceVisibleFloorOffset,
            double terraceVisibleFloorFade,
            double terraceFlattenWindowRadius,
            double terraceFlattenCoreThreshold,
            double terraceFlattenTransitionFade,
            double wildFloorMainPathOffset,
            double roadFloorMainPathOffset,
            double roadFloorBroadNoiseScale,
            double roadWobblePrimaryScale,
            double roadWobblePrimaryStrength,
            double roadWobbleSecondaryScale,
            double roadWobbleSecondaryStrength,
            double floorMinY,
            double floorMaxY,
            double roadInfluenceCoreDistance,
            double roadInfluenceFadeDistance
    ) {
        public static Settings defaults() {
            return DEFAULT_SETTINGS;
        }

        private static Settings createDefaults() {
            return new Settings(
                    SnowdinTerrainSettings.FLOOR_PRIMARY_NOISE_SCALE,
                    SnowdinTerrainSettings.FLOOR_PRIMARY_NOISE_STRENGTH,
                    SnowdinTerrainSettings.FLOOR_SECONDARY_NOISE_SCALE,
                    SnowdinTerrainSettings.FLOOR_SECONDARY_NOISE_STRENGTH,
                    SnowdinTerrainSettings.MIX_FLOOR_PRIMARY_NOISE_SCALE,
                    SnowdinTerrainSettings.MIX_FLOOR_PRIMARY_NOISE_STRENGTH,
                    SnowdinTerrainSettings.MIX_FLOOR_SECONDARY_NOISE_SCALE,
                    SnowdinTerrainSettings.MIX_FLOOR_SECONDARY_NOISE_STRENGTH,
                    SnowdinTerrainSettings.MIX_BROAD_RELIEF_NOISE_SCALE,
                    SnowdinTerrainSettings.MIX_BROAD_RELIEF_NOISE_STRENGTH,
                    SnowdinTerrainSettings.MIX_WILD_FLOOR_MAIN_PATH_OFFSET,
                    SnowdinTerrainSettings.MIX_ROAD_FLOOR_MAIN_PATH_OFFSET,
                    SnowdinTerrainSettings.MIX_ROAD_FLOOR_BROAD_NOISE_SCALE,
                    SnowdinTerrainSettings.MIX_TERRACE_BASE_TRENCH_DEPTH,
                    SnowdinTerrainSettings.PLATEAU_MASK_NOISE_SCALE,
                    SnowdinTerrainSettings.PLATEAU_MASK_THRESHOLD,
                    SnowdinTerrainSettings.PLATEAU_MASK_FADE,
                    SnowdinTerrainSettings.PLATEAU_MASK_WARP_SCALE,
                    SnowdinTerrainSettings.PLATEAU_MASK_WARP_STRENGTH,
                    SnowdinTerrainSettings.PLATEAU_MASK_WARP_Z_NOISE_X_OFFSET,
                    SnowdinTerrainSettings.PLATEAU_MASK_WARP_Z_NOISE_Z_OFFSET,
                    SnowdinTerrainSettings.TERRACE_WARP_SCALE,
                    SnowdinTerrainSettings.TERRACE_WARP_STRENGTH,
                    SnowdinTerrainSettings.TERRACE_PRIMARY_NOISE_SCALE,
                    SnowdinTerrainSettings.TERRACE_SECONDARY_NOISE_SCALE,
                    SnowdinTerrainSettings.TERRACE_DETAIL_NOISE_SCALE,
                    SnowdinTerrainSettings.TERRACE_VALLEY_FLOOR_HALF_WIDTH,
                    SnowdinTerrainSettings.TERRACE_VALLEY_FADE_WIDTH,
                    SnowdinTerrainSettings.TERRACE_MAX_TOP_OFFSET,
                    SnowdinTerrainSettings.TERRACE_PLATEAU_SIZE,
                    SnowdinTerrainSettings.TERRACE_PLATEAU_STEPS,
                    SnowdinTerrainSettings.TERRACE_CURVED_TOP_HEIGHT,
                    SnowdinTerrainSettings.TERRACE_CURVED_TOP_STEPS,
                    SnowdinTerrainSettings.TERRACE_SECONDARY_NOISE_STRENGTH,
                    SnowdinTerrainSettings.TERRACE_DETAIL_NOISE_STRENGTH,
                    SnowdinTerrainSettings.TERRACE_CHANNEL_WARP_SCALE,
                    SnowdinTerrainSettings.TERRACE_CHANNEL_WARP_STRENGTH,
                    SnowdinTerrainSettings.TERRACE_CHANNEL_NOISE_SCALE,
                    SnowdinTerrainSettings.TERRACE_CHANNEL_WIDTH,
                    SnowdinTerrainSettings.TERRACE_CHANNEL_FADE,
                    SnowdinTerrainSettings.TERRACE_CENTER_TRENCH_DEPTH,
                    SnowdinTerrainSettings.TERRACE_CENTER_TRENCH_HALF_WIDTH,
                    SnowdinTerrainSettings.TERRACE_CENTER_TRENCH_FADE,
                    SnowdinTerrainSettings.TERRACE_STEP_HEIGHT,
                    SnowdinTerrainSettings.TERRACE_EDGE_BLEND,
                    SnowdinTerrainSettings.TERRACE_MIN_TOP_OFFSET,
                    SnowdinTerrainSettings.TERRACE_MIN_TOP_FADE,
                    SnowdinTerrainSettings.TERRACE_CEILING_CLEARANCE,
                    SnowdinTerrainSettings.TERRACE_VISIBLE_FLOOR_OFFSET,
                    SnowdinTerrainSettings.TERRACE_VISIBLE_FLOOR_FADE,
                    SnowdinTerrainSettings.TERRACE_FLATTEN_WINDOW_RADIUS,
                    SnowdinTerrainSettings.TERRACE_FLATTEN_CORE_THRESHOLD,
                    SnowdinTerrainSettings.TERRACE_FLATTEN_TRANSITION_FADE,
                    SnowdinTerrainSettings.WILD_FLOOR_MAIN_PATH_OFFSET,
                    SnowdinTerrainSettings.ROAD_FLOOR_MAIN_PATH_OFFSET,
                    SnowdinTerrainSettings.ROAD_FLOOR_BROAD_NOISE_SCALE,
                    SnowdinTerrainSettings.ROAD_WOBBLE_PRIMARY_SCALE,
                    SnowdinTerrainSettings.ROAD_WOBBLE_PRIMARY_STRENGTH,
                    SnowdinTerrainSettings.ROAD_WOBBLE_SECONDARY_SCALE,
                    SnowdinTerrainSettings.ROAD_WOBBLE_SECONDARY_STRENGTH,
                    SnowdinTerrainSettings.FLOOR_MIN_Y,
                    SnowdinTerrainSettings.FLOOR_MAX_Y,
                    SnowdinTerrainSettings.ROAD_INFLUENCE_CORE_DISTANCE,
                    SnowdinTerrainSettings.ROAD_INFLUENCE_FADE_DISTANCE
            );
        }

        public static Settings fromOverrides(Map<String, Double> overrides) {
            Settings defaults = defaults();
            return new Settings(
                    value(overrides, "snowdin.ground.floor_primary_noise_scale", defaults.floorPrimaryNoiseScale),
                    value(overrides, "snowdin.ground.floor_primary_noise_strength", defaults.floorPrimaryNoiseStrength),
                    value(overrides, "snowdin.ground.floor_secondary_noise_scale", defaults.floorSecondaryNoiseScale),
                    value(overrides, "snowdin.ground.floor_secondary_noise_strength", defaults.floorSecondaryNoiseStrength),
                    value(overrides, "snowdin.ground.plains_hills_mix.floor_primary_noise_scale", defaults.mixFloorPrimaryNoiseScale),
                    value(overrides, "snowdin.ground.plains_hills_mix.floor_primary_noise_strength", defaults.mixFloorPrimaryNoiseStrength),
                    value(overrides, "snowdin.ground.plains_hills_mix.floor_secondary_noise_scale", defaults.mixFloorSecondaryNoiseScale),
                    value(overrides, "snowdin.ground.plains_hills_mix.floor_secondary_noise_strength", defaults.mixFloorSecondaryNoiseStrength),
                    value(overrides, "snowdin.ground.plains_hills_mix.broad_relief_noise_scale", defaults.mixBroadReliefNoiseScale),
                    value(overrides, "snowdin.ground.plains_hills_mix.broad_relief_noise_strength", defaults.mixBroadReliefNoiseStrength),
                    value(overrides, "snowdin.ground.plains_hills_mix.wild_floor_main_path_offset", defaults.mixWildFloorMainPathOffset),
                    value(overrides, "snowdin.ground.plains_hills_mix.road_floor_main_path_offset", defaults.mixRoadFloorMainPathOffset),
                    value(overrides, "snowdin.ground.plains_hills_mix.road_floor_broad_noise_scale", defaults.mixRoadFloorBroadNoiseScale),
                    value(overrides, "snowdin.ground.plains_hills_mix.terrace_base_trench_depth", defaults.mixTerraceBaseTrenchDepth),
                    value(overrides, "snowdin.ground.plateau_mask.noise_scale", defaults.plateauMaskNoiseScale),
                    value(overrides, "snowdin.ground.plateau_mask.threshold", defaults.plateauMaskThreshold),
                    value(overrides, "snowdin.ground.plateau_mask.fade", defaults.plateauMaskFade),
                    value(overrides, "snowdin.ground.plateau_mask.warp_scale", defaults.plateauMaskWarpScale),
                    value(overrides, "snowdin.ground.plateau_mask.warp_strength", defaults.plateauMaskWarpStrength),
                    value(overrides, "snowdin.ground.plateau_mask.warp_z_noise_x_offset", defaults.plateauMaskWarpZNoiseXOffset),
                    value(overrides, "snowdin.ground.plateau_mask.warp_z_noise_z_offset", defaults.plateauMaskWarpZNoiseZOffset),
                    value(overrides, "snowdin.ground.terrace_warp_scale", defaults.terraceWarpScale),
                    value(overrides, "snowdin.ground.terrace_warp_strength", defaults.terraceWarpStrength),
                    value(overrides, "snowdin.ground.terrace_primary_noise_scale", defaults.terracePrimaryNoiseScale),
                    value(overrides, "snowdin.ground.terrace_secondary_noise_scale", defaults.terraceSecondaryNoiseScale),
                    value(overrides, "snowdin.ground.terrace_detail_noise_scale", defaults.terraceDetailNoiseScale),
                    value(overrides, "snowdin.ground.terrace_valley_floor_half_width", defaults.terraceValleyFloorHalfWidth),
                    value(overrides, "snowdin.ground.terrace_valley_fade_width", defaults.terraceValleyFadeWidth),
                    value(overrides, "snowdin.ground.terrace_max_top_offset", defaults.terraceMaxTopOffset),
                    value(overrides, "snowdin.ground.terrace_plateau_size", defaults.terracePlateauSize),
                    (int) Math.round(value(overrides, "snowdin.ground.terrace_plateau_steps", defaults.terracePlateauSteps)),
                    value(overrides, "snowdin.ground.terrace_curved_top_height", defaults.terraceCurvedTopHeight),
                    (int) Math.round(value(overrides, "snowdin.ground.terrace_curved_top_steps", defaults.terraceCurvedTopSteps)),
                    value(overrides, "snowdin.ground.terrace_secondary_noise_strength", defaults.terraceSecondaryNoiseStrength),
                    value(overrides, "snowdin.ground.terrace_detail_noise_strength", defaults.terraceDetailNoiseStrength),
                    value(overrides, "snowdin.ground.terrace_channel_warp_scale", defaults.terraceChannelWarpScale),
                    value(overrides, "snowdin.ground.terrace_channel_warp_strength", defaults.terraceChannelWarpStrength),
                    value(overrides, "snowdin.ground.terrace_channel_noise_scale", defaults.terraceChannelNoiseScale),
                    value(overrides, "snowdin.ground.terrace_channel_width", defaults.terraceChannelWidth),
                    value(overrides, "snowdin.ground.terrace_channel_fade", defaults.terraceChannelFade),
                    value(overrides, "snowdin.ground.terrace_center_trench_depth", defaults.terraceCenterTrenchDepth),
                    value(overrides, "snowdin.ground.terrace_center_trench_half_width", defaults.terraceCenterTrenchHalfWidth),
                    value(overrides, "snowdin.ground.terrace_center_trench_fade", defaults.terraceCenterTrenchFade),
                    value(overrides, "snowdin.ground.terrace_step_height", defaults.terraceStepHeight),
                    value(overrides, "snowdin.ground.terrace_edge_blend", defaults.terraceEdgeBlend),
                    value(overrides, "snowdin.ground.terrace_min_top_offset", defaults.terraceMinTopOffset),
                    value(overrides, "snowdin.ground.terrace_min_top_fade", defaults.terraceMinTopFade),
                    value(overrides, "snowdin.ground.terrace_ceiling_clearance", defaults.terraceCeilingClearance),
                    value(overrides, "snowdin.ground.terrace_visible_floor_offset", defaults.terraceVisibleFloorOffset),
                    value(overrides, "snowdin.ground.terrace_visible_floor_fade", defaults.terraceVisibleFloorFade),
                    value(overrides, "snowdin.ground.terrace_flatten_window_radius", defaults.terraceFlattenWindowRadius),
                    value(overrides, "snowdin.ground.terrace_flatten_core_threshold", defaults.terraceFlattenCoreThreshold),
                    value(overrides, "snowdin.ground.terrace_flatten_transition_fade", defaults.terraceFlattenTransitionFade),
                    value(overrides, "snowdin.ground.wild_floor_main_path_offset", defaults.wildFloorMainPathOffset),
                    value(overrides, "snowdin.ground.road_floor_main_path_offset", defaults.roadFloorMainPathOffset),
                    value(overrides, "snowdin.ground.road_floor_broad_noise_scale", defaults.roadFloorBroadNoiseScale),
                    value(overrides, "snowdin.ground.road_wobble_primary_scale", defaults.roadWobblePrimaryScale),
                    value(overrides, "snowdin.ground.road_wobble_primary_strength", defaults.roadWobblePrimaryStrength),
                    value(overrides, "snowdin.ground.road_wobble_secondary_scale", defaults.roadWobbleSecondaryScale),
                    value(overrides, "snowdin.ground.road_wobble_secondary_strength", defaults.roadWobbleSecondaryStrength),
                    value(overrides, "snowdin.ground.floor_min_y", defaults.floorMinY),
                    value(overrides, "snowdin.ground.floor_max_y", defaults.floorMaxY),
                    value(overrides, "snowdin.ground.road_influence_core_distance", defaults.roadInfluenceCoreDistance),
                    value(overrides, "snowdin.ground.road_influence_fade_distance", defaults.roadInfluenceFadeDistance)
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
