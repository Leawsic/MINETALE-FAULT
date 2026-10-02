package cn.jehorstudio.minetale.dimension.ebott;

import cn.jehorstudio.minetale.dimension.ebott.shaft.ShaftData;
import cn.jehorstudio.minetale.dimension.worldgen.CakeConfig;
import cn.jehorstudio.minetale.dimension.worldgen.WorldgenSamplingContext;
import cn.jehorstudio.minetale.dimension.worldgen.generator.UndergroundNoiseGenerator;
import cn.jehorstudio.minetale.dimension.worldgen.network.settings.UndergroundSamplingSettings;
import cn.jehorstudio.minetale.dimension.worldgen.region.origin.settings.OriginSettings;
import cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.pipeline.SnowdinNaturalTerrainFactsKernel;
import cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.settings.SnowdinTerrainSettings;
import cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.terrain.SnowdinSurfaceResolver;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.chunk.ChunkGenerator;

import java.util.function.IntBinaryOperator;

// 伊伯特穿越目标选择与目标端水平坐标的唯一入口。
public final class EbottDestination {
    // false 保留 Origin 几何，true 切换到 Snowdin 穹顶目标。
    public static final boolean ENABLE_SNOWDIN_TARGET = true;

    private static final CakeConfig CAKE = CakeConfig.defaults();
    static final int SNOWDIN_TARGET_PREVIEW_RADIUS = 128;
    private static final int SNOWDIN_CENTER_X = CAKE.originExitX();
    private static final int SNOWDIN_CENTER_Z = (int) (
            CAKE.transitionTunnelLength()
                    + CAKE.ruinsLength()
                    + CAKE.transitionTunnelLength()
                    + CAKE.snowdinLength() * 0.5
    );

    private EbottDestination() {
    }

    public static int centerX() {
        return ENABLE_SNOWDIN_TARGET ? SNOWDIN_CENTER_X : OriginSettings.centerX();
    }

    public static int centerZ() {
        return ENABLE_SNOWDIN_TARGET ? SNOWDIN_CENTER_Z : OriginSettings.centerZ();
    }

    // 预热、网络 payload 与 prepared renderer 必须共用该水平半径。
    public static int targetPreviewRadius(ShaftData.Profile profile) {
        return ENABLE_SNOWDIN_TARGET
                ? Math.max(SNOWDIN_TARGET_PREVIEW_RADIUS, profile.shaftEnvelopeRadius())
                : profile.shaftEnvelopeRadius();
    }

    // Snowdin 的最低写入高度限制在穹顶羽化带底部
    public static IntBinaryOperator targetMinYResolver(
            ServerLevel level,
            ChunkGenerator generator
    ) {
        int levelMinY = level.getMinY();
        int levelMaxY = level.getMaxY();
        if (!ENABLE_SNOWDIN_TARGET) {
            int originMinY = Math.max(levelMinY, OriginSettings.FLOOR_Y + 1);
            return (x, z) -> originMinY;
        }

        WorldgenSamplingContext context = samplingContext(level, generator);
        return (x, z) -> targetMinY(context, x, z, levelMinY, levelMaxY);
    }

    // Snowdin 接缝位于维度最高可放置方块的上表面。
    public static int targetSeamY(
            ServerLevel level,
            ChunkGenerator generator,
            ShaftData.Profile profile
    ) {
        if (!ENABLE_SNOWDIN_TARGET) {
            return profile.targetSeamY();
        }
        return targetSeamY(
                samplingContext(level, generator),
                profile,
                level.getMinY(),
                level.getMaxY()
        );
    }

    static int targetSeamY(
            WorldgenSamplingContext context,
            ShaftData.Profile profile,
            int levelMinY,
            int levelMaxY
    ) {
        if (!ENABLE_SNOWDIN_TARGET) {
            return profile.targetSeamY();
        }

        // getMaxY 返回最高可放置方块，接缝面须再加 1。
        return Math.addExact(levelMaxY, 1);
    }

    static int targetMinY(
            WorldgenSamplingContext context,
            int x,
            int z,
            int levelMinY,
            int levelMaxY
    ) {
        if (!ENABLE_SNOWDIN_TARGET) {
            return Math.max(levelMinY, OriginSettings.FLOOR_Y + 1);
        }

        SnowdinSurfaceResolver.NoiseSurface surface = SnowdinSurfaceResolver.computeNoiseSurface(
                context,
                x,
                z
        );
        if (surface == null) {
            throw new IllegalStateException(
                    "Ebott Snowdin target left Snowdin Region at (%d, %d)".formatted(x, z)
            );
        }

        int scanMinY = Math.max(
                levelMinY,
                Mth.floor(surface.ceilingY() - SnowdinTerrainSettings.CEILING_FEATHER)
        );
        int scanMaxY = Math.min(
                levelMaxY,
                Mth.ceil(surface.ceilingY() + SnowdinTerrainSettings.INSIDE_CEILING_MARGIN)
        );
        for (int y = scanMaxY; y >= scanMinY; y--) {
            if (SnowdinNaturalTerrainFactsKernel.shouldCarveSnowdinCave(
                    context,
                    x,
                    y,
                    z,
                    surface.floorY(),
                    surface.ceilingY(),
                    surface.terraceTopY(),
                    surface.terraceMask(),
                    surface.horizontalPathDistance()
            )) {
                return y;
            }
        }

        // 即使极端列没有自然空气，也只越过名义穹顶厚度
        return scanMinY;
    }

    private static WorldgenSamplingContext samplingContext(
            ServerLevel level,
            ChunkGenerator generator
    ) {
        UndergroundSamplingSettings settings = generator instanceof UndergroundNoiseGenerator underground
                ? underground.samplingSettings()
                : UndergroundSamplingSettings.defaults();
        return new WorldgenSamplingContext(level.getSeed(), settings);
    }
}
