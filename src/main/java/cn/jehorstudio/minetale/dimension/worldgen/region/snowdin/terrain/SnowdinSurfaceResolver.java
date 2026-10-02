package cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.terrain;

import cn.jehorstudio.minetale.content.block.common.CommonBlocksRegistry;
import cn.jehorstudio.minetale.dimension.worldgen.RainbowCakeModel;
import cn.jehorstudio.minetale.dimension.worldgen.WorldgenSamplingContext;
import cn.jehorstudio.minetale.dimension.worldgen.math.WorldgenMath;
import cn.jehorstudio.minetale.dimension.worldgen.network.settings.UndergroundSamplingSettings;
import cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.settings.SnowdinTerrainSettings;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.IntFunction;

// Snowdin 地表的唯一查询边界：先计算纯 noise 语义，再按需解析真实或预测落点。
public final class SnowdinSurfaceResolver {
    private static final int SEARCH_BELOW = 8;
    private static final int SEARCH_ABOVE = 5;
    private static final int WATER_BUFFER_RADIUS = 2;
    private static final int WATER_BUFFER_DEPTH = 2;
    private static final double TERRACE_SURFACE_START = 0.12;
    private static final double TERRACE_SURFACE_FADE = 0.28;
    private static final int FLOOR_SNOW_SEARCH_BELOW = 8;
    private static final int FLOOR_SNOW_SEARCH_ABOVE = 4;

    private SnowdinSurfaceResolver() {}

    // 返回不访问方块的完整列语义，可由 biome、pipeline 与 Feature 共享。
    public static NoiseSurface computeNoiseSurface(WorldgenSamplingContext context, int x, int z) {
        RainbowCakeModel.CakeSample sample = RainbowCakeModel.create(context.worldgenSeed()).sample(x, z);
        return computeNoiseSurface(context, x, z, sample);
    }

    // 复用调用方已有的 CakeSample，结果语义与普通入口一致。
    public static NoiseSurface computeNoiseSurface(
            WorldgenSamplingContext context,
            int x,
            int z,
            RainbowCakeModel.CakeSample sample
    ) {
        if (sample.region() != RainbowCakeModel.CakeRegion.SNOWDIN) {
            return null;
        }

        double progress = sample.lineProgress();
        double mainPathY = sample.lineY() + SnowdinTerrainSettings.GROUND_VERTICAL_SHIFT;
        double horizontalPathDistance = Math.abs(sample.lateralDistance());
        double plateauPresence = SnowdinGroundProfile.INSTANCE.plateauPresence(context, x, z);
        SnowdinGroundProfile.Settings groundSettings = SnowdinGroundProfile.Settings.defaults();
        SnowdinGroundProfile.Floor floor = SnowdinGroundProfile.INSTANCE.floor(
                context,
                x,
                z,
                progress,
                mainPathY,
                horizontalPathDistance,
                plateauPresence,
                groundSettings
        );
        double floorY = floor.floorY();
        double ceilingY = SnowdinCeilingProfile.sample(context, x, z, floorY).ceilingY();
        SnowdinGroundProfile.Terrace terrace = SnowdinGroundProfile.INSTANCE.terrace(
                context,
                x,
                z,
                floor,
                ceilingY,
                horizontalPathDistance,
                plateauPresence,
                groundSettings
        );

        return new NoiseSurface(
                progress,
                mainPathY,
                horizontalPathDistance,
                floorY,
                floor.terraceBaseY(),
                terrace.topY(),
                preferredGroundY(floorY, terrace.topY(), terrace.mask()),
                ceilingY,
                plateauPresence,
                terrace.mask()
        );
    }

    // Snowtown footprint 验证只需要同源台地 top/mask
    public static SnowdinGroundProfile.Terrace computeUncappedTerrace(
            WorldgenSamplingContext context,
            int x,
            int z,
            RainbowCakeModel.CakeSample sample
    ) {
        if (sample.region() != RainbowCakeModel.CakeRegion.SNOWDIN) {
            return null;
        }

        double progress = sample.lineProgress();
        double mainPathY = sample.lineY() + SnowdinTerrainSettings.GROUND_VERTICAL_SHIFT;
        double horizontalPathDistance = Math.abs(sample.lateralDistance());
        SnowdinGroundProfile.Settings settings = SnowdinGroundProfile.Settings.defaults();
        double plateauPresence = SnowdinGroundProfile.INSTANCE.plateauPresence(context, x, z, settings);
        SnowdinGroundProfile.Floor floor = SnowdinGroundProfile.INSTANCE.floor(
                context,
                x,
                z,
                progress,
                mainPathY,
                horizontalPathDistance,
                plateauPresence,
                settings
        );
        return SnowdinGroundProfile.INSTANCE.uncappedTerrace(
                context,
                x,
                z,
                floor,
                horizontalPathDistance,
                plateauPresence,
                settings
        );
    }

    public static Optional<Surface> resolve(WorldGenLevel level, long seed, UndergroundSamplingSettings settings, int x, int z) {
        WorldgenSamplingContext context = new WorldgenSamplingContext(seed, settings);
        NoiseSurface noise = computeNoiseSurface(context, x, z);
        return resolve(level, noise, x, z);
    }

    public static Optional<Surface> resolve(
            WorldGenLevel level,
            WorldgenSamplingContext context,
            RainbowCakeModel.CakeSample sample,
            int x,
            int z
    ) {
        NoiseSurface noise = computeNoiseSurface(context, x, z, sample);
        return resolve(level, noise, x, z);
    }

    private static Optional<Surface> resolve(WorldGenLevel level, NoiseSurface noise, int x, int z) {
        if (noise == null) {
            return Optional.empty();
        }

        int minY = Math.max(level.getMinY(), Mth.floor(noise.preferredGroundY()) - SEARCH_BELOW);
        int maxY = Math.min(level.getMaxY() - 1, Mth.floor(noise.preferredGroundY()) + SEARCH_ABOVE);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos above = new BlockPos.MutableBlockPos();

        for (int y = maxY; y >= minY; y--) {
            cursor.set(x, y, z);
            BlockState ground = level.getBlockState(cursor);
            if (!isPlantableGround(ground)) {
                continue;
            }
            if (y >= noise.ceilingY() - 3.0) {
                continue;
            }

            above.set(x, y + 1, z);
            BlockState aboveState = level.getBlockState(above);
            if (!aboveState.getFluidState().isEmpty()) {
                continue;
            }
            if (!aboveState.isAir() && !aboveState.is(Blocks.SNOW) && !aboveState.canBeReplaced()) {
                continue;
            }
            if (hasNearbyWater(level, above, WATER_BUFFER_RADIUS, WATER_BUFFER_DEPTH)) {
                continue;
            }

            return Optional.of(new Surface(
                    above.immutable(),
                    cursor.immutable(),
                    noise.plateauPresence(),
                    noise.terraceMask(),
                    noise.ceilingY()
            ));
        }

        return Optional.empty();
    }

    public static Optional<Surface> resolvePredictedColumn(WorldgenSamplingContext context, NoiseColumn column, LevelHeightAccessor height, int x, int z) {
        NoiseSurface noise = computeNoiseSurface(context, x, z);
        return resolvePredictedColumn(context, noise, column, height, x, z);
    }

    public static Optional<Surface> resolvePredictedColumn(
            WorldgenSamplingContext context,
            NoiseSurface noise,
            NoiseColumn column,
            LevelHeightAccessor height,
            int x,
            int z
    ) {
        if (noise == null || column == null) {
            return Optional.empty();
        }

        OptionalInt supportY = resolveFloorAnchorY(
                noise,
                height,
                column::getBlock,
                FloorSurfaceType.SNOW_COVER
        );
        if (supportY.isPresent()) {
            int plantY = supportY.getAsInt() + predictedSnowCoverAnchorYOffset(context, x, z);
            BlockPos plant = new BlockPos(x, plantY, z);
            return Optional.of(new Surface(plant, plant.below(), noise.plateauPresence(), noise.terraceMask(), noise.ceilingY()));
        }
        return Optional.empty();
    }

    // 推荐地表按 terrace mask 在 floor 与 terrace top 间插值，不等同于 floorY。
    public static double preferredGroundY(double floorY, double terraceTopY, double terraceMask) {
        double terraceT = WorldgenMath.smoothstep((terraceMask - TERRACE_SURFACE_START) / TERRACE_SURFACE_FADE);
        return Mth.lerp(terraceT, floorY, terraceTopY);
    }

    // Biome 垂直范围必须使用同一列的 floor 与 ceiling 事实。
    public static boolean isInsideBiomeHeight(NoiseSurface surface, double blockY) {
        return blockY >= surface.floorY() - SnowdinTerrainSettings.INSIDE_FLOOR_MARGIN
                && blockY <= surface.ceilingY() + SnowdinTerrainSettings.INSIDE_CEILING_MARGIN;
    }

    public static boolean isPlantableGround(BlockState state) {
        return state.is(CommonBlocksRegistry.SNOW_ROCK.get()) || state.is(Blocks.SNOW_BLOCK);
    }

    public static boolean isFloorSnowCoverSupport(BlockState state) {
        return state.is(CommonBlocksRegistry.SNOW_ROCK.get())
                || state.is(CommonBlocksRegistry.GEO_ROCK.get())
                || state.is(Blocks.SNOW_BLOCK)
                || state.is(Blocks.DEEPSLATE)
                || state.is(Blocks.ANDESITE);
    }

    public static boolean isFloorSnowBlockReplacementSupport(BlockState state) {
        return state.is(CommonBlocksRegistry.SNOW_ROCK.get())
                || state.is(CommonBlocksRegistry.GEO_ROCK.get())
                || state.is(Blocks.DEEPSLATE)
                || state.is(Blocks.ANDESITE);
    }

    // 在推荐高度附近解析表层装饰共用的地表锚点。
    public static OptionalInt resolveFloorAnchorY(
            NoiseSurface noise,
            LevelHeightAccessor height,
            IntFunction<BlockState> blockAtY,
            FloorSurfaceType surfaceType
    ) {
        if (noise == null) {
            return OptionalInt.empty();
        }
        return resolveFloorAnchorY(noise.preferredGroundY(), noise.ceilingY(), height.getMinY(), height.getMaxY() - 1, blockAtY, surfaceType);
    }

    // 只在调用方给定的垂直搜索带内解析 floor 锚点。
    public static OptionalInt resolveFloorAnchorY(
            double preferredGroundY,
            double ceilingY,
            int minY,
            int maxY,
            IntFunction<BlockState> blockAtY,
            FloorSurfaceType surfaceType
    ) {
        int floorBandMin = Math.max(minY, Mth.floor(preferredGroundY) - FLOOR_SNOW_SEARCH_BELOW);
        int floorBandMax = Math.min(maxY - 1, Mth.floor(preferredGroundY) + FLOOR_SNOW_SEARCH_ABOVE);
        if (floorBandMin > floorBandMax) {
            return OptionalInt.empty();
        }

        for (int y = floorBandMax; y >= floorBandMin; y--) {
            BlockState support = blockAtY.apply(y);
            if (!supportsFloorSurface(surfaceType, support)) {
                continue;
            }
            if (y >= ceilingY - 3.0) {
                continue;
            }

            BlockState above = blockAtY.apply(y + 1);
            if (!above.isAir()) {
                continue;
            }
            return OptionalInt.of(y);
        }
        return OptionalInt.empty();
    }

    public static int predictedSnowCoverAnchorYOffset(WorldgenSamplingContext context, int x, int z) {
        return 1;
    }

    private static boolean supportsFloorSurface(FloorSurfaceType surfaceType, BlockState state) {
        return switch (surfaceType) {
            case SNOW_COVER -> isFloorSnowCoverSupport(state);
            case SNOW_BLOCK_REPLACEMENT -> isFloorSnowBlockReplacementSupport(state);
        };
    }

    private static boolean hasNearbyWater(WorldGenLevel level, BlockPos origin, int radius, int depth) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -depth; dy <= 1; dy++) {
                    cursor.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    BlockState state = level.getBlockState(cursor);
                    if (isWaterLike(state)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean isWaterLike(BlockState state) {
        return !state.getFluidState().isEmpty()
                || state.is(Blocks.WATER)
                || state.is(Blocks.ICE)
                || state.is(Blocks.FROSTED_ICE)
                || state.is(Blocks.PACKED_ICE)
                || state.is(Blocks.BLUE_ICE);
    }

    // 不依赖 BlockState 的不可变列语义快照。
    public record NoiseSurface(
            double progress,
            double mainPathY,
            double horizontalPathDistance,
            double floorY,
            double terraceBaseY,
            double terraceTopY,
            double preferredGroundY,
            double ceilingY,
            double plateauPresence,
            double terraceMask
    ) {}

    // 在列语义基础上解析出的实际可用落点。
    public record Surface(
            BlockPos plantPos,
            BlockPos groundPos,
            double plateauPresence,
            double terraceMask,
            double ceilingY
    ) {}

    public enum FloorSurfaceType {
        SNOW_COVER,
        SNOW_BLOCK_REPLACEMENT
    }
}
