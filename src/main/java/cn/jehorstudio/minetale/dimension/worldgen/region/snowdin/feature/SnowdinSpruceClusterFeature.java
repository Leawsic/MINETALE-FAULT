package cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.feature;

import cn.jehorstudio.minetale.dimension.worldgen.registry.ModWorldgenKeys;
import cn.jehorstudio.minetale.dimension.worldgen.WorldgenSamplingContext;
import cn.jehorstudio.minetale.dimension.worldgen.RainbowCakeModel;
import cn.jehorstudio.minetale.dimension.worldgen.math.WorldgenMath;
import cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.feature.settings.SnowdinForestSettings;
import cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.terrain.SnowdinSurfaceResolver;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.TreeFeature;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

public final class SnowdinSpruceClusterFeature {
    private SnowdinSpruceClusterFeature() {}

    public static boolean placeChunk(
            WorldGenLevel level,
            ChunkGenerator generator,
            ChunkPos chunkPos,
            WorldgenSamplingContext samplingContext
    ) {
        RandomSource random = RandomSource.create(WorldgenMath.hash(
                chunkPos.x,
                0,
                chunkPos.z,
                samplingContext.channelSeed(8300)
        ));
        return placeChunk(level, generator, chunkPos, samplingContext, random);
    }

    private static boolean placeChunk(
            WorldGenLevel level,
            ChunkGenerator generator,
            ChunkPos chunkPos,
            WorldgenSamplingContext samplingContext,
            RandomSource random
    ) {
        Registry<ConfiguredFeature<?, ?>> registry = level.registryAccess().lookupOrThrow(Registries.CONFIGURED_FEATURE);
        Holder<ConfiguredFeature<?, ?>> tree = registry.get(ModWorldgenKeys.SNOWTOWN_SPRUCE).orElse(null);
        if (tree == null) {
            return false;
        }

        int chunkMinX = chunkPos.getMinBlockX();
        int chunkMinZ = chunkPos.getMinBlockZ();
        boolean placedAny = false;

        for (int i = 0; i < SnowdinForestSettings.CANDIDATE_CENTERS_PER_CHUNK; i++) {
            int centerX = chunkMinX + random.nextInt(16);
            int centerZ = chunkMinZ + random.nextInt(16);
            if (!acceptClusterCenter(samplingContext, centerX, centerZ, random)) {
                continue;
            }
            placedAny |= placeCluster(level, generator, random, tree.value(), samplingContext, centerX, centerZ);
        }

        return placedAny;
    }

    private static boolean acceptClusterCenter(WorldgenSamplingContext samplingContext, int x, int z, RandomSource random) {
        OptionalDouble sampledPlateau = snowdinPlateauPresence(samplingContext, x, z);
        if (sampledPlateau.isEmpty()) {
            return false;
        }
        double plateauPresence = sampledPlateau.getAsDouble();
        double spawnWeight = SnowdinForestSettings.spawnWeight(plateauPresence);
        if (spawnWeight < SnowdinForestSettings.SPAWN_WEIGHT_THRESHOLD) {
            return false;
        }
        double centerMask = clusterCenterMask(samplingContext, x, z);
        return random.nextDouble() < centerMask * spawnWeight;
    }

    private static OptionalDouble snowdinPlateauPresence(WorldgenSamplingContext context, double x, double z) {
        SnowdinSurfaceResolver.NoiseSurface surface = SnowdinSurfaceResolver.computeNoiseSurface(
                context,
                Mth.floor(x),
                Mth.floor(z)
        );
        if (surface == null) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(surface.plateauPresence());
    }

    private static boolean placeCluster(
            WorldGenLevel level,
            ChunkGenerator generator,
            RandomSource random,
            ConfiguredFeature<?, ?> tree,
            WorldgenSamplingContext samplingContext,
            int centerX,
            int centerZ
    ) {
        int minTrees = Math.min(SnowdinForestSettings.CLUSTER_MIN_TREES, SnowdinForestSettings.CLUSTER_MAX_TREES);
        int maxTrees = Math.max(SnowdinForestSettings.CLUSTER_MIN_TREES, SnowdinForestSettings.CLUSTER_MAX_TREES);
        int treeCount = minTrees + random.nextInt(maxTrees - minTrees + 1);
        boolean placedAny = false;
        SurfaceCache surfaces = new SurfaceCache(level, samplingContext);

        for (int i = 0; i < treeCount; i++) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            double radius = Math.sqrt(random.nextDouble()) * SnowdinForestSettings.CLUSTER_RADIUS;
            int x = Mth.floor(centerX + Math.cos(angle) * radius);
            int z = Mth.floor(centerZ + Math.sin(angle) * radius);
            Optional<SnowdinSurfaceResolver.Surface> surface = surfaces.resolve(x, z);
            if (surface.isEmpty()) {
                continue;
            }
            double weight = SnowdinForestSettings.spawnWeight(surface.get().plateauPresence());
            if (random.nextDouble() > weight) {
                continue;
            }
            BlockPos plantPos = surface.get().plantPos();
            if (!hasTreeClearance(level, plantPos, surface.get().ceilingY())) {
                continue;
            }
            if (tree.place(level, generator, random, plantPos)) {
                placedAny = true;
                surfaces.clear();
                placeSnowOnCanopy(level, plantPos);
            }
        }

        return placedAny;
    }

    private static void placeSnowOnCanopy(WorldGenLevel level, BlockPos plantPos) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos above = new BlockPos.MutableBlockPos();
        int minX = plantPos.getX() - 4;
        int maxX = plantPos.getX() + 4;
        int minY = plantPos.getY() + 2;
        int maxY = plantPos.getY() + 14;
        int minZ = plantPos.getZ() - 4;
        int maxZ = plantPos.getZ() + 4;
        BlockState snow = Blocks.SNOW.defaultBlockState();

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    cursor.set(x, y, z);
                    if (!level.getBlockState(cursor).is(BlockTags.LEAVES)) {
                        continue;
                    }
                    above.set(x, y + 1, z);
                    BlockState aboveState = level.getBlockState(above);
                    if (!aboveState.isAir() || !snow.canSurvive(level, above)) {
                        continue;
                    }
                    level.setBlock(above, snow, 2);
                }
            }
        }
    }

    public static double clusterCenterMask(WorldgenSamplingContext context, double x, double z) {
        double noise = WorldgenMath.valueNoise2d(
                x * SnowdinForestSettings.CLUSTER_NOISE_SCALE,
                z * SnowdinForestSettings.CLUSTER_NOISE_SCALE,
                context.channelSeed(8301)
        );
        return smoothstep((noise - SnowdinForestSettings.CLUSTER_NOISE_THRESHOLD) / SnowdinForestSettings.CLUSTER_NOISE_FADE);
    }

    private static boolean hasTreeClearance(WorldGenLevel level, BlockPos plantPos, double ceilingY) {
        if (plantPos.getY() + SnowdinForestSettings.TREE_CLEARANCE_HEIGHT >= ceilingY - 1.0) {
            return false;
        }
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int radius = SnowdinForestSettings.TREE_CLEARANCE_RADIUS;
        for (int y = 0; y <= SnowdinForestSettings.TREE_CLEARANCE_HEIGHT; y++) {
            int layerRadius = y < 3 ? 1 : radius;
            for (int dx = -layerRadius; dx <= layerRadius; dx++) {
                for (int dz = -layerRadius; dz <= layerRadius; dz++) {
                    cursor.set(plantPos.getX() + dx, plantPos.getY() + y, plantPos.getZ() + dz);
                    BlockState state = level.getBlockState(cursor);
                    if (!TreeFeature.validTreePos(level, cursor) && !state.canBeReplaced()) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static double smoothstep(double value) {
        double t = Mth.clamp(value, 0.0, 1.0);
        return t * t * (3.0 - 2.0 * t);
    }

    private static final class SurfaceCache {
        private final WorldGenLevel level;
        private final WorldgenSamplingContext samplingContext;
        private final RainbowCakeModel model;
        private final Map<Long, Optional<SnowdinSurfaceResolver.Surface>> cache = new HashMap<>();

        private SurfaceCache(WorldGenLevel level, WorldgenSamplingContext samplingContext) {
            this.level = level;
            this.samplingContext = samplingContext;
            this.model = RainbowCakeModel.create(samplingContext.worldgenSeed());
        }

        private Optional<SnowdinSurfaceResolver.Surface> resolve(int x, int z) {
            long key = (((long) x) << 32) ^ (z & 0xFFFFFFFFL);
            Optional<SnowdinSurfaceResolver.Surface> cached = cache.get(key);
            if (cached != null) {
                return cached;
            }
            Optional<SnowdinSurfaceResolver.Surface> surface = SnowdinSurfaceResolver.resolve(
                    level,
                    samplingContext,
                    model.sample(x, z),
                    x,
                    z
            );
            cache.put(key, surface);
            return surface;
        }

        private void clear() {
            cache.clear();
        }
    }
}
