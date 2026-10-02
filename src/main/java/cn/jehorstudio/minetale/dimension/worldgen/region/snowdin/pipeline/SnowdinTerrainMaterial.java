package cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.pipeline;

import cn.jehorstudio.minetale.dimension.worldgen.WorldgenSamplingContext;
import cn.jehorstudio.minetale.dimension.worldgen.math.WorldgenMath;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;

public final class SnowdinTerrainMaterial {
    private static final int DEEP_GEOROCK_MIN_DEPTH = 5;
    private static final int DEEP_GEOROCK_DEPTH_VARIATION = 3;
    private static final double CEILING_LITHO_DEPTH = 8.0;
    private static final double STALACTITE_BODY_MASK_THRESHOLD = 0.30;
    private static final double PILLAR_MASK_THRESHOLD = 0.32;
    private static final double PILLAR_GEO_START = 0.46;
    private static final double PILLAR_GEO_END = 0.74;
    private static final double FLOOR_SNOW_BLOCK_THRESHOLD = 0.28;

    private SnowdinTerrainMaterial() {}

    public static double deepGeoCutY(WorldgenSamplingContext context, double x, double z, double floorY) {
        int blockX = Mth.floor(x);
        int blockZ = Mth.floor(z);
        double noise = WorldgenMath.hashToUnit(blockX, 0, blockZ, context.channelSeed(7951));
        int depth = DEEP_GEOROCK_MIN_DEPTH + (int) Math.floor(noise * DEEP_GEOROCK_DEPTH_VARIATION);
        return floorY - depth;
    }

    public static BlockState terrainBlockState(
            int y,
            double floorY,
            double ceilingY,
            double deepGeoCutY,
            double stalactiteBodyMask,
            double pillarMask,
            double materialJitter,
            double ceilingLithologyNoise,
            SnowdinTerrainPalette palette
    ) {
        return blockState(terrainMaterialKind(
                y,
                floorY,
                ceilingY,
                deepGeoCutY,
                stalactiteBodyMask,
                pillarMask,
                materialJitter,
                ceilingLithologyNoise
        ), palette);
    }

    static MaterialKind terrainMaterialKind(
            int y,
            double floorY,
            double ceilingY,
            double deepGeoCutY,
            double stalactiteBodyMask,
            double pillarMask,
            double materialJitter,
            double ceilingLithologyNoise
    ) {
        if (y <= deepGeoCutY) {
            return MaterialKind.GEO_ROCK;
        }
        if (isStalactiteBody(y, ceilingY, stalactiteBodyMask)) {
            return MaterialKind.DRIPSTONE_BLOCK;
        }
        if (pillarMask >= PILLAR_MASK_THRESHOLD) {
            return pillarMaterialKind(y, floorY, ceilingY, materialJitter, ceilingLithologyNoise);
        }
        if (y >= ceilingY - CEILING_LITHO_DEPTH) {
            return ceilingLithologyKind(ceilingLithologyNoise);
        }
        return MaterialKind.SNOW_ROCK;
    }

    // 先消除确定性分支，材质 hash 只为真正需要随机映射的体素计算。
    public static BlockState terrainBlockState(
            WorldgenSamplingContext context,
            double x,
            int y,
            double z,
            double floorY,
            double ceilingY,
            double deepGeoCutY,
            double stalactiteBodyMask,
            double pillarMask,
            SnowdinTerrainPalette palette
    ) {
        return blockState(terrainMaterialKind(
                context,
                x,
                y,
                z,
                floorY,
                ceilingY,
                deepGeoCutY,
                stalactiteBodyMask,
                pillarMask
        ), palette);
    }

    static MaterialKind terrainMaterialKind(
            WorldgenSamplingContext context,
            double x,
            int y,
            double z,
            double floorY,
            double ceilingY,
            double deepGeoCutY,
            double stalactiteBodyMask,
            double pillarMask
    ) {
        if (y <= deepGeoCutY) {
            return MaterialKind.GEO_ROCK;
        }
        if (isStalactiteBody(y, ceilingY, stalactiteBodyMask)) {
            return MaterialKind.DRIPSTONE_BLOCK;
        }
        if (pillarMask >= PILLAR_MASK_THRESHOLD) {
            return pillarMaterialKind(
                    y,
                    floorY,
                    ceilingY,
                    materialJitter(context, x, y, z),
                    ceilingLithologyNoise(context, x, y, z)
            );
        }
        if (y >= ceilingY - CEILING_LITHO_DEPTH) {
            return ceilingLithologyKind(ceilingLithologyNoise(context, x, y, z));
        }
        return MaterialKind.SNOW_ROCK;
    }

    private static MaterialKind pillarMaterialKind(
            int y,
            double floorY,
            double ceilingY,
            double materialJitter,
            double ceilingLithologyNoise
    ) {
        double normalizedHeight = Mth.clamp(
                (y - floorY) / Math.max(ceilingY - floorY, 1.0),
                0.0,
                1.0
        );
        double upperLithologyFactor = WorldgenMath.smoothstep(
                (normalizedHeight - PILLAR_GEO_START) / (PILLAR_GEO_END - PILLAR_GEO_START)
        );
        MaterialKind upperLithology = ceilingLithologyKind(ceilingLithologyNoise);
        return materialJitter <= upperLithologyFactor ? upperLithology : MaterialKind.SNOW_ROCK;
    }

    public static double materialJitter(WorldgenSamplingContext context, double x, int y, double z) {
        int blockX = Mth.floor(x);
        int blockZ = Mth.floor(z);
        return WorldgenMath.hashToUnit(blockX, y, blockZ, context.channelSeed(7952));
    }

    public static double ceilingLithologyNoise(WorldgenSamplingContext context, double x, int y, double z) {
        int blockX = Mth.floor(x);
        int blockZ = Mth.floor(z);
        return WorldgenMath.hashToUnit(blockX, y, blockZ, context.channelSeed(7962));
    }

    public static boolean hasSnowBlockSurface(double floorSnowMacro) {
        return floorSnowMacro >= FLOOR_SNOW_BLOCK_THRESHOLD;
    }

    // 钟乳石归属必须复用列事实
    public static boolean isStalactiteBody(int y, double ceilingY, double stalactiteBodyMask) {
        return stalactiteBodyMask >= STALACTITE_BODY_MASK_THRESHOLD && y <= ceilingY;
    }

    private static MaterialKind ceilingLithologyKind(double noise) {
        if (noise < 0.45) {
            return MaterialKind.DEEPSLATE;
        }
        if (noise < 0.80) {
            return MaterialKind.GEO_ROCK;
        }
        return MaterialKind.TUFF;
    }

    private static BlockState blockState(MaterialKind material, SnowdinTerrainPalette palette) {
        return switch (material) {
            case SNOW_ROCK -> palette.snowRock();
            case GEO_ROCK -> palette.geoRock();
            case DEEPSLATE -> palette.deepslate();
            case TUFF -> palette.tuff();
            case DRIPSTONE_BLOCK -> palette.dripstoneBlock();
        };
    }

    enum MaterialKind {
        SNOW_ROCK,
        GEO_ROCK,
        DEEPSLATE,
        TUFF,
        DRIPSTONE_BLOCK
    }
}
