package cn.jehorstudio.minetale.dimension.worldgen.region.origin.terrain;

import cn.jehorstudio.minetale.dimension.ebott.EbottDestination;
import cn.jehorstudio.minetale.dimension.ebott.shaft.ShaftData;
import cn.jehorstudio.minetale.dimension.ebott.shaft.ShaftGenerator;
import cn.jehorstudio.minetale.dimension.worldgen.CakeConfig;
import cn.jehorstudio.minetale.dimension.worldgen.math.WorldgenMath;
import cn.jehorstudio.minetale.dimension.worldgen.region.origin.pipeline.OriginColumnFacts;
import cn.jehorstudio.minetale.dimension.worldgen.region.origin.settings.OriginSettings;

// 只从坐标派生 ORIGIN 地形事实
public final class OriginTerrainFactsKernel {
    private static final double WALL_NOISE_SCALE = 0.018;
    private static final double CEILING_NOISE_SCALE = 0.024;
    private static final int WALL_CHANNEL = 0x4F11;
    private static final int CEILING_CHANNEL = 0xCE11;

    private OriginTerrainFactsKernel() {
    }

    public static OriginColumnFacts sample(long seed, int x, int z) {
        CakeConfig config = CakeConfig.defaults();
        boolean inside = x >= config.originMinX()
                && x < config.originMaxXExclusive()
                && z >= config.originMinZ()
                && z < config.originMaxZExclusive();
        if (!inside) {
            return new OriginColumnFacts(
                    false,
                    OriginTerrainZone.OUTSIDE,
                    OriginSettings.FLOOR_Y,
                    OriginSettings.FLOOR_Y,
                    false,
                    false
            );
        }

        boolean shaftAir = false;
        boolean shaftWall = false;
        if (!EbottDestination.ENABLE_SNOWDIN_TARGET) {
            ShaftData shaft = ShaftData.create(seed, ShaftData.DEFAULT_SOURCE_SEAM_Y);
            ShaftData.Profile profile = shaft.profile();
            ShaftGenerator.Classification shaftClassification = ShaftGenerator.classifyTarget(
                    profile,
                    x - OriginSettings.centerX(),
                    shaft.fallbackTargetArrivalY(),
                    z - OriginSettings.centerZ()
            );
            shaftAir = shaftClassification == ShaftGenerator.Classification.SHAFT_AIR;
            shaftWall = shaftClassification == ShaftGenerator.Classification.SHAFT_WALL;
        }
        boolean exit = z >= config.originCenterZ()
                && Math.abs(x - config.originExitX()) <= OriginSettings.EXIT_HALF_WIDTH;

        double dx = x - config.originCenterX();
        double dz = z - config.originCenterZ();
        double wallNoise = WorldgenMath.signedValueNoise2d(
                x * WALL_NOISE_SCALE,
                z * WALL_NOISE_SCALE,
                WorldgenMath.channelSeed(seed, WALL_CHANNEL)
        );
        double cavernRadius = OriginSettings.CAVERN_BASE_RADIUS
                + wallNoise * OriginSettings.CAVERN_RADIUS_VARIATION;
        boolean cavern = dx * dx + dz * dz <= cavernRadius * cavernRadius;

        OriginTerrainZone zone;
        if (shaftAir) {
            zone = OriginTerrainZone.SHAFT;
        } else if (exit) {
            zone = OriginTerrainZone.EXIT;
        } else if (cavern) {
            zone = OriginTerrainZone.CAVERN;
        } else {
            zone = OriginTerrainZone.SOLID;
        }

        double ceilingNoise = WorldgenMath.signedValueNoise2d(
                x * CEILING_NOISE_SCALE,
                z * CEILING_NOISE_SCALE,
                WorldgenMath.channelSeed(seed, CEILING_CHANNEL)
        );
        int ceilingY = exit
                ? OriginSettings.EXIT_CEILING_Y
                : OriginSettings.CAVERN_BASE_CEILING_Y
                        + (int) StrictMath.round(
                                ceilingNoise * OriginSettings.CAVERN_CEILING_VARIATION);

        long centerDx = (long) x - OriginSettings.centerX();
        long centerDz = (long) z - OriginSettings.centerZ();
        long protectedRadius = OriginSettings.FLOWER_LANDING_PROTECTED_RADIUS;
        boolean flowerProtected = centerDx * centerDx + centerDz * centerDz
                <= protectedRadius * protectedRadius;

        return new OriginColumnFacts(
                true,
                zone,
                OriginSettings.FLOOR_Y,
                ceilingY,
                flowerProtected,
                shaftWall
        );
    }
}
