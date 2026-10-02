package cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.pipeline;

import cn.jehorstudio.minetale.content.block.common.CommonBlocksRegistry;
import cn.jehorstudio.minetale.dimension.worldgen.WorldgenSamplingContext;
import cn.jehorstudio.minetale.dimension.worldgen.math.WorldgenMath;
import cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.terrain.SnowdinSurfaceResolver;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.PointedDripstoneBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DripstoneThickness;
import net.minecraft.world.level.chunk.ChunkAccess;

import java.util.OptionalInt;

public final class SnowdinFeatureScatter {
    private static final double STALACTITE_SCATTER_MASK_MAX = 0.12;
    private static final double CEILING_SCATTER_PATCH_CHANCE = 0.07;
    private static final int CEILING_SCATTER_VERTICAL_RANGE = 6;
    private static final int CEILING_SCATTER_TIP_MIN_LENGTH = 1;
    private static final int CEILING_SCATTER_TIP_MAX_LENGTH = 4;
    private static final int FLOOR_SNOW_SEARCH_BELOW = 8;
    private static final int FLOOR_SNOW_SEARCH_ABOVE = 4;

    private SnowdinFeatureScatter() {}

    public static void apply(
            ChunkAccess chunk,
            WorldgenSamplingContext samplingContext,
            int x,
            int z,
            double floorY,
            double ceilingY,
            double terraceTopY,
            double terraceMask,
            double floorSnowMacro,
            double[] stalactiteBodyMask,
            int minY,
            int maxY,
            int cachedFloorSurfaceY,
            BlockPos.MutableBlockPos cursor,
            BlockPos.MutableBlockPos aboveCursor,
            BlockPos.MutableBlockPos belowCursor
    ) {
        applyCeilingScatterDripstone(
                chunk,
                samplingContext,
                x,
                z,
                ceilingY,
                stalactiteBodyMask,
                minY,
                maxY,
                cursor,
                belowCursor
        );
        applyFloorSnowCover(
                chunk,
                samplingContext,
                x,
                z,
                floorY,
                ceilingY,
                terraceTopY,
                terraceMask,
                floorSnowMacro,
                minY,
                maxY,
                cachedFloorSurfaceY,
                cursor,
                aboveCursor
        );
    }

    private static void applyFloorSnowCover(
            ChunkAccess chunk,
            WorldgenSamplingContext samplingContext,
            int x,
            int z,
            double floorY,
            double ceilingY,
            double terraceTopY,
            double terraceMask,
            double floorSnowMacro,
            int minY,
            int maxY,
            int cachedFloorSurfaceY,
            BlockPos.MutableBlockPos cursor,
            BlockPos.MutableBlockPos aboveCursor
    ) {
        double preferredGroundY = SnowdinSurfaceResolver.preferredGroundY(floorY, terraceTopY, terraceMask);
        OptionalInt supportY = resolveFloorSnowCoverSupportY(
                chunk,
                x,
                z,
                ceilingY,
                minY,
                maxY,
                cachedFloorSurfaceY,
                preferredGroundY,
                cursor,
                aboveCursor
        );
        if (supportY.isEmpty()) {
            supportY = SnowdinSurfaceResolver.resolveFloorAnchorY(
                preferredGroundY,
                ceilingY,
                minY,
                maxY,
                y -> {
                    cursor.set(x, y, z);
                    return chunk.getBlockState(cursor);
                },
                SnowdinSurfaceResolver.FloorSurfaceType.SNOW_COVER
            );
        }
        if (supportY.isEmpty()) {
            return;
        }

        int y = supportY.getAsInt();
        aboveCursor.set(x, y + 1, z);
        BlockState snowCover = pickFloorSnowCoverState(samplingContext, x, y, z, floorSnowMacro);
        chunk.setBlockState(aboveCursor, snowCover);
    }

    private static OptionalInt resolveFloorSnowCoverSupportY(
            ChunkAccess chunk,
            int x,
            int z,
            double ceilingY,
            int minY,
            int maxY,
            int cachedFloorSurfaceY,
            double preferredGroundY,
            BlockPos.MutableBlockPos cursor,
            BlockPos.MutableBlockPos aboveCursor
    ) {
        if (cachedFloorSurfaceY == Integer.MIN_VALUE) {
            return OptionalInt.empty();
        }
        int floorBandMin = Math.max(minY, Mth.floor(preferredGroundY) - FLOOR_SNOW_SEARCH_BELOW);
        int floorBandMax = Math.min(maxY - 1, Mth.floor(preferredGroundY) + FLOOR_SNOW_SEARCH_ABOVE);
        if (cachedFloorSurfaceY < floorBandMin || cachedFloorSurfaceY > floorBandMax || cachedFloorSurfaceY >= ceilingY - 3.0) {
            return OptionalInt.empty();
        }

        cursor.set(x, cachedFloorSurfaceY, z);
        if (!SnowdinSurfaceResolver.isFloorSnowCoverSupport(chunk.getBlockState(cursor))) {
            return OptionalInt.empty();
        }
        aboveCursor.set(x, cachedFloorSurfaceY + 1, z);
        if (!chunk.getBlockState(aboveCursor).isAir()) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(cachedFloorSurfaceY);
    }

    private static BlockState pickFloorSnowCoverState(
            WorldgenSamplingContext samplingContext,
            int x,
            int y,
            int z,
            double floorSnowMacro
    ) {
        if (floorSnowMacro >= 0.28) {
            return Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, 2);
        }
        if (floorSnowMacro >= 0.16) {
            return Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, 2);
        }
        int layers = 1 + (int) Math.floor(WorldgenMath.hashToUnit(x, y, z, samplingContext.channelSeed(7966)) * 2.0);
        layers = Mth.clamp(layers, 1, 2);
        return Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, layers);
    }

    private static void applyCeilingScatterDripstone(
            ChunkAccess chunk,
            WorldgenSamplingContext samplingContext,
            int x,
            int z,
            double ceilingY,
            double[] stalactiteBodyMask,
            int minY,
            int maxY,
            BlockPos.MutableBlockPos cursor,
            BlockPos.MutableBlockPos belowCursor
    ) {
        int ceilingBase = Mth.floor(ceilingY);
        int searchMaxY = Math.min(maxY - 1, ceilingBase);
        int searchMinY = Math.max(minY + 1, ceilingBase - CEILING_SCATTER_VERTICAL_RANGE);

        for (int y = searchMaxY; y >= searchMinY; y--) {
            cursor.set(x, y, z);
            BlockState anchor = chunk.getBlockState(cursor);
            if (!isCeilingLithology(anchor)) {
                continue;
            }

            belowCursor.set(x, y - 1, z);
            if (!chunk.getBlockState(belowCursor).isAir()) {
                continue;
            }

            double mask = stalactiteBodyMask[y - minY];
            if (mask > STALACTITE_SCATTER_MASK_MAX) {
                continue;
            }

            double chance = WorldgenMath.hashToUnit(x, y, z, samplingContext.channelSeed(7964));
            if (chance > CEILING_SCATTER_PATCH_CHANCE) {
                continue;
            }

            chunk.setBlockState(cursor, Blocks.DRIPSTONE_BLOCK.defaultBlockState());
            int tipLength = randomIntInclusive(
                    samplingContext,
                    x,
                    y,
                    z,
                    7965,
                    CEILING_SCATTER_TIP_MIN_LENGTH,
                    CEILING_SCATTER_TIP_MAX_LENGTH
            );
            placeHangingPointedDripstone(chunk, x, y - 1, z, tipLength, minY, cursor);
            return;
        }
    }

    private static boolean isCeilingLithology(BlockState state) {
        return state.is(CommonBlocksRegistry.GEO_ROCK.get())
                || state.is(Blocks.DEEPSLATE)
                || state.is(Blocks.ANDESITE);
    }

    private static int randomIntInclusive(
            WorldgenSamplingContext samplingContext,
            int x,
            int y,
            int z,
            int channelSalt,
            int min,
            int max
    ) {
        double noise = WorldgenMath.hashToUnit(x, y, z, samplingContext.channelSeed(channelSalt));
        int value = min + (int) Math.floor(noise * (max - min + 1));
        return Mth.clamp(value, min, max);
    }

    private static void placeHangingPointedDripstone(
            ChunkAccess chunk,
            int x,
            int topY,
            int z,
            int desiredLength,
            int minY,
            BlockPos.MutableBlockPos cursor
    ) {
        int placed = 0;
        for (int i = 0; i < desiredLength; i++) {
            int y = topY - i;
            if (y < minY) {
                break;
            }
            cursor.set(x, y, z);
            if (!chunk.getBlockState(cursor).isAir()) {
                break;
            }
            placed++;
        }

        if (placed <= 0) {
            return;
        }

        for (int i = 0; i < placed; i++) {
            int y = topY - i;
            cursor.set(x, y, z);
            chunk.setBlockState(cursor, pointedDripstoneDownState(i, placed));
        }
    }

    private static BlockState pointedDripstoneDownState(int index, int total) {
        DripstoneThickness thickness;
        if (total == 1) {
            thickness = DripstoneThickness.TIP;
        } else if (index == total - 1) {
            thickness = DripstoneThickness.TIP;
        } else if (index == total - 2) {
            thickness = DripstoneThickness.FRUSTUM;
        } else if (index == 0) {
            thickness = DripstoneThickness.BASE;
        } else {
            thickness = DripstoneThickness.MIDDLE;
        }

        return Blocks.POINTED_DRIPSTONE.defaultBlockState()
                .setValue(PointedDripstoneBlock.TIP_DIRECTION, Direction.DOWN)
                .setValue(PointedDripstoneBlock.THICKNESS, thickness)
                .setValue(PointedDripstoneBlock.WATERLOGGED, false);
    }
}
