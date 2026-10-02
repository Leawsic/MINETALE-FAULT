package cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.pipeline;

import cn.jehorstudio.minetale.dimension.worldgen.RainbowCakeModel;
import cn.jehorstudio.minetale.dimension.worldgen.WorldgenSamplingContext;
import cn.jehorstudio.minetale.dimension.worldgen.math.WorldgenMath;
import cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.element.pillar.SnowdinPillarMask;
import cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.element.stalactite.SnowdinStalactiteMask;
import cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.settings.SnowdinTerrainSettings;
import cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.terrain.SnowdinGroundProfile;
import cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.terrain.SnowdinSurfaceResolver;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Optional;
import java.util.OptionalInt;

// 只从 seed、坐标、设置与 RainbowCakeModel 推导自然列事实
public final class SnowdinNaturalTerrainFactsKernel {
    private static final double SNOWDIN_CAVE_AIR_THRESHOLD = 0.65;
    private static final double FLOOR_SNOW_MACRO_NOISE_SCALE = 0.03;

    private SnowdinNaturalTerrainFactsKernel() {}

    public static Optional<SnowdinColumnFacts> sample(
            WorldgenSamplingContext context,
            int x,
            int z,
            int minY,
            int maxY
    ) {
        RainbowCakeModel model = RainbowCakeModel.create(context.worldgenSeed());
        return sample(context, model, x, z, minY, maxY);
    }

    public static Optional<SnowdinColumnFacts> sample(
            WorldgenSamplingContext context,
            RainbowCakeModel model,
            int x,
            int z,
            int minY,
            int maxY
    ) {
        RainbowCakeModel.CakeSample sample = model.sample(x, z);
        SnowdinSurfaceResolver.NoiseSurface surface = SnowdinSurfaceResolver.computeNoiseSurface(
                context,
                x,
                z,
                sample
        );
        if (surface == null) {
            return Optional.empty();
        }

        SnowdinPillarMask.PreparedColumn pillarColumn = preparePillarColumn(
                context,
                x,
                z,
                surface.horizontalPathDistance()
        );
        SnowdinStalactiteMask.PreparedColumn stalactiteColumn = prepareStalactiteColumn(
                context,
                x,
                z,
                surface.floorY(),
                surface.ceilingY()
        );
        SnowdinGroundProfile.Terrace terrace = new SnowdinGroundProfile.Terrace(
                surface.terraceTopY(),
                surface.terraceMask()
        );
        double deepGeoCutY = SnowdinTerrainMaterial.deepGeoCutY(context, x, z, surface.floorY());

        OptionalInt supportY = SnowdinSurfaceResolver.resolveFloorAnchorY(
                surface.preferredGroundY(),
                surface.ceilingY(),
                minY,
                maxY,
                y -> predictedStage3BlockState(
                        context,
                        surface,
                        pillarColumn,
                        stalactiteColumn,
                        terrace,
                        deepGeoCutY,
                        x,
                        y,
                        z
                ),
                SnowdinSurfaceResolver.FloorSurfaceType.SNOW_BLOCK_REPLACEMENT
        );
        double pillarMaskAtSurface = supportY.isPresent()
                ? pillarMaskAt(pillarColumn, terrace, surface, supportY.getAsInt())
                : Double.NaN;

        return Optional.of(new SnowdinColumnFacts(
                sample,
                surface.floorY(),
                surface.ceilingY(),
                surface.terraceTopY(),
                surface.terraceMask(),
                surface.plateauPresence(),
                supportY,
                pillarMaskAtSurface
        ));
    }

    private static BlockState predictedStage3BlockState(
            WorldgenSamplingContext context,
            SnowdinSurfaceResolver.NoiseSurface surface,
            SnowdinPillarMask.PreparedColumn pillarColumn,
            SnowdinStalactiteMask.PreparedColumn stalactiteColumn,
            SnowdinGroundProfile.Terrace terrace,
            double deepGeoCutY,
            int x,
            int y,
            int z
    ) {
        double stalactiteBodyMask = stalactiteBodyMask(stalactiteColumn, y);
        double pillarMask = pillarMask(
                pillarColumn,
                y,
                surface.floorY(),
                surface.ceilingY(),
                terrace
        );
        if (shouldCarveSnowdinCave(
                context,
                x,
                y,
                z,
                surface.floorY(),
                surface.ceilingY(),
                terrace.topY(),
                terrace.mask(),
                stalactiteBodyMask,
                pillarMask
        )) {
            return Blocks.AIR.defaultBlockState();
        }

        return SnowdinTerrainMaterial.terrainBlockState(
                context,
                x,
                y,
                z,
                surface.floorY(),
                surface.ceilingY(),
                deepGeoCutY,
                stalactiteBodyMask,
                pillarMask,
                SnowdinTerrainPalette.defaults()
        );
    }

    private static double pillarMaskAt(
            SnowdinPillarMask.PreparedColumn pillarColumn,
            SnowdinGroundProfile.Terrace terrace,
            SnowdinSurfaceResolver.NoiseSurface surface,
            int y
    ) {
        return pillarMask(
                pillarColumn,
                y,
                surface.floorY(),
                surface.ceilingY(),
                terrace
        );
    }

    public static boolean shouldCarveSnowdinCave(
            double y,
            double floorY,
            double ceilingY,
            double terraceTopY,
            double terraceMask,
            double stalactiteMask,
            double pillarMask,
            double caveRough
    ) {
        double carve = caveCarveBase(
                y,
                floorY,
                ceilingY,
                terraceTopY,
                terraceMask,
                stalactiteMask,
                pillarMask
        );
        carve += caveRough * SnowdinTerrainSettings.CAVE_ROUGH_STRENGTH;

        return isCarved(carve);
    }

    // 只有粗略值接近 carve 阈值时，3D noise 才可能改变结果。
    public static boolean shouldCarveSnowdinCave(
            WorldgenSamplingContext context,
            double x,
            double y,
            double z,
            double floorY,
            double ceilingY,
            double terraceTopY,
            double terraceMask,
            double stalactiteMask,
            double pillarMask
    ) {
        double carve = caveCarveBase(
                y,
                floorY,
                ceilingY,
                terraceTopY,
                terraceMask,
                stalactiteMask,
                pillarMask
        );
        double roughRange = Math.abs(SnowdinTerrainSettings.CAVE_ROUGH_STRENGTH);
        if (isCarved(carve - roughRange)) {
            return true;
        }
        if (!isCarved(carve + roughRange)) {
            return false;
        }
        return isCarved(carve + caveRough(context, x, y, z) * SnowdinTerrainSettings.CAVE_ROUGH_STRENGTH);
    }

    static boolean caveRoughRequired(
            double y,
            double floorY,
            double ceilingY,
            double terraceTopY,
            double terraceMask,
            double stalactiteMask,
            double pillarMask
    ) {
        double carve = caveCarveBase(
                y,
                floorY,
                ceilingY,
                terraceTopY,
                terraceMask,
                stalactiteMask,
                pillarMask
        );
        double roughRange = Math.abs(SnowdinTerrainSettings.CAVE_ROUGH_STRENGTH);
        return !isCarved(carve - roughRange) && isCarved(carve + roughRange);
    }

    private static double caveCarveBase(
            double y,
            double floorY,
            double ceilingY,
            double terraceTopY,
            double terraceMask,
            double stalactiteMask,
            double pillarMask
    ) {
        double carve = caveInteriorMask(y, floorY, ceilingY);
        carve *= Mth.lerp(stalactiteMask, 1.0, SnowdinTerrainSettings.FULL_STALACTITE_CARVE_MULTIPLIER);
        carve *= Mth.lerp(pillarMask, 1.0, SnowdinTerrainSettings.FULL_PILLAR_CARVE_MULTIPLIER);
        carve *= Mth.lerp(
                terraceSolidMask(y, terraceTopY, terraceMask),
                1.0,
                SnowdinTerrainSettings.TERRACE_FULL_CARVE_MULTIPLIER
        );
        return carve;
    }

    private static boolean isCarved(double carve) {
        return Mth.clamp(carve, 0.0, 1.0)
                * SnowdinTerrainSettings.CAVE_CARVE_STRENGTH > SNOWDIN_CAVE_AIR_THRESHOLD;
    }

    public static boolean shouldCarveSnowdinCave(
            WorldgenSamplingContext context,
            double x,
            double y,
            double z,
            double floorY,
            double ceilingY,
            double terraceTopY,
            double terraceMask,
            double horizontalPathDistance
    ) {
        double stalactite = stalactiteBodyMask(context, x, y, z, floorY, ceilingY);
        SnowdinPillarMask.PreparedColumn pillarColumn = preparePillarColumn(
                context,
                x,
                z,
                horizontalPathDistance
        );
        double pillar = pillarMask(
                pillarColumn,
                y,
                floorY,
                ceilingY,
                new SnowdinGroundProfile.Terrace(terraceTopY, terraceMask)
        );
        return shouldCarveSnowdinCave(
                context,
                x,
                y,
                z,
                floorY,
                ceilingY,
                terraceTopY,
                terraceMask,
                stalactite,
                pillar
        );
    }

    public static double caveInteriorMask(double y, double floorY, double ceilingY) {
        double aboveFloor = WorldgenMath.smoothstep(
                (y - floorY - SnowdinTerrainSettings.FLOOR_CARVE_OFFSET)
                        / SnowdinTerrainSettings.FLOOR_FEATHER
        );
        double belowCeiling = WorldgenMath.smoothstep(
                (ceilingY - y) / SnowdinTerrainSettings.CEILING_FEATHER
        );
        return aboveFloor * belowCeiling;
    }

    public static double terraceSolidMask(double y, double terraceTopY, double terraceMask) {
        return SnowdinGroundProfile.INSTANCE.terraceSolidMask(y, new SnowdinGroundProfile.Terrace(terraceTopY, terraceMask));
    }

    public static double caveRough(WorldgenSamplingContext context, double x, double y, double z) {
        return WorldgenMath.signedValueNoise3d(
                x * SnowdinTerrainSettings.CAVE_ROUGH_XZ_SCALE,
                y * SnowdinTerrainSettings.CAVE_ROUGH_Y_SCALE,
                z * SnowdinTerrainSettings.CAVE_ROUGH_XZ_SCALE,
                context.channelSeed(6501)
        );
    }

    public static double stalactiteBodyMask(
            WorldgenSamplingContext context,
            double x,
            double y,
            double z,
            double floorY,
            double ceilingY
    ) {
        return SnowdinStalactiteMask.INSTANCE.sample(context, x, y, z, floorY, ceilingY);
    }

    public static SnowdinStalactiteMask.PreparedColumn prepareStalactiteColumn(
            WorldgenSamplingContext context,
            double x,
            double z,
            double floorY,
            double ceilingY
    ) {
        return SnowdinStalactiteMask.INSTANCE.prepare(context, x, z, floorY, ceilingY);
    }

    public static double stalactiteBodyMask(SnowdinStalactiteMask.PreparedColumn column, double y) {
        return SnowdinStalactiteMask.INSTANCE.sample(column, y);
    }

    public static double pillarMask(
            WorldgenSamplingContext context,
            double x,
            double y,
            double z,
            double horizontalPathDistance,
            double floorY,
            double ceilingY,
            double terraceTopY,
            double terraceMask
    ) {
        return pillarMask(
                preparePillarColumn(context, x, z, horizontalPathDistance),
                y,
                floorY,
                ceilingY,
                new SnowdinGroundProfile.Terrace(terraceTopY, terraceMask)
        );
    }

    public static SnowdinPillarMask.PreparedColumn preparePillarColumn(
            WorldgenSamplingContext context,
            double x,
            double z,
            double horizontalPathDistance
    ) {
        return SnowdinPillarMask.INSTANCE.prepare(context, x, z, horizontalPathDistance);
    }

    public static double pillarMask(
            SnowdinPillarMask.PreparedColumn pillarColumn,
            double y,
            double floorY,
            double ceilingY,
            double terraceTopY,
            double terraceMask
    ) {
        return pillarMask(
                pillarColumn,
                y,
                floorY,
                ceilingY,
                new SnowdinGroundProfile.Terrace(terraceTopY, terraceMask)
        );
    }

    public static double pillarMask(
            SnowdinPillarMask.PreparedColumn pillarColumn,
            double y,
            double floorY,
            double ceilingY,
            SnowdinGroundProfile.Terrace terrace
    ) {
        double pillarClearance = SnowdinGroundProfile.INSTANCE.pillarTerraceClearance(y, terrace);
        double pillar = pillarClearance == 0.0
                ? 0.0
                : SnowdinPillarMask.INSTANCE.sample(pillarColumn, y, floorY, ceilingY) * pillarClearance;
        double terraceTopMask = SnowdinGroundProfile.INSTANCE.terraceTopPillarMask(y, terrace);
        if (terraceTopMask == 0.0) {
            return pillar;
        }
        double terracePillar = SnowdinPillarMask.INSTANCE.sampleTerraceTop(
                pillarColumn,
                y,
                terrace.topY(),
                ceilingY
        ) * terraceTopMask;
        return Math.max(pillar, terracePillar);
    }

    public static double floorSnowMacro(WorldgenSamplingContext context, double x, double z) {
        return WorldgenMath.valueNoise2d(
                x * FLOOR_SNOW_MACRO_NOISE_SCALE,
                z * FLOOR_SNOW_MACRO_NOISE_SCALE,
                context.channelSeed(7963)
        );
    }
}
