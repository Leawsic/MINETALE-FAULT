package cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.feature;

import cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.feature.settings.SnowdinIceLakeSettings;
import cn.jehorstudio.minetale.dimension.worldgen.RainbowCakeModel;
import cn.jehorstudio.minetale.dimension.worldgen.WorldgenSamplingContext;
import cn.jehorstudio.minetale.dimension.worldgen.math.WorldgenMath;
import cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.terrain.SnowdinSurfaceResolver;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public final class SnowdinIceLakeFeature {
    private SnowdinIceLakeFeature() {}

    public static boolean placeChunk(
            WorldGenLevel level,
            ChunkPos chunkPos,
            WorldgenSamplingContext samplingContext
    ) {
        RandomSource random = RandomSource.create(WorldgenMath.hash(
                chunkPos.x,
                0,
                chunkPos.z,
                samplingContext.channelSeed(8400)
        ));
        return placeChunk(level, chunkPos, samplingContext, random);
    }

    private static boolean placeChunk(
            WorldGenLevel level,
            ChunkPos chunkPos,
            WorldgenSamplingContext samplingContext,
            RandomSource random
    ) {
        BlockPos origin = chunkPos.getWorldPosition();
        SurfaceCache surfaces = new SurfaceCache(level, samplingContext);
        long seed = samplingContext.worldgenSeed();
        boolean placedAny = false;

        for (int i = 0; i < SnowdinIceLakeSettings.CANDIDATE_CENTERS_PER_CHUNK; i++) {
            int x = origin.getX() + random.nextInt(16);
            int z = origin.getZ() + random.nextInt(16);
            Optional<SnowdinSurfaceResolver.Surface> surface = surfaces.resolve(x, z);
            if (surface.isEmpty()) {
                continue;
            }
            LakeSiteType siteType = selectLakeSiteType(surfaces, surface.get());
            if (siteType == null) {
                continue;
            }
            if (!acceptLakeCenter(surface.get(), siteType, random)) {
                continue;
            }
            int radiusX = randomBetween(random, SnowdinIceLakeSettings.RADIUS_X_MIN, SnowdinIceLakeSettings.RADIUS_X_MAX);
            int radiusZ = randomBetween(random, SnowdinIceLakeSettings.RADIUS_Z_MIN, SnowdinIceLakeSettings.RADIUS_Z_MAX);
            LakeShape shape = LakeShape.create(seed, x, z, radiusX, radiusZ, random);
            if (shape.area() < SnowdinIceLakeSettings.MIN_LAKE_AREA_CELLS) {
                continue;
            }
            Basin basin = resolveBasin(level, surfaces, surface.get(), shape, siteType);
            if (basin == null) {
                continue;
            }
            placedAny |= carveLake(level, basin);
        }

        return placedAny;
    }

    private static LakeSiteType selectLakeSiteType(SurfaceCache surfaces, SnowdinSurfaceResolver.Surface center) {
        double plateauPresence = center.plateauPresence();
        if (plateauPresence <= SnowdinIceLakeSettings.MIX_MAX_PLATEAU_PRESENCE) {
            double valleyMask = mixValleyMask(surfaces, center);
            if (valleyMask > 0.0) {
                return new LakeSiteType(Mode.MIX_VALLEY, valleyMask);
            }
            return null;
        }

        if (plateauPresence < SnowdinIceLakeSettings.PLATEAU_MIN_PRESENCE) {
            return null;
        }

        double flatMask = plateauFlatMask(surfaces, center);
        if (flatMask <= 0.0) {
            return null;
        }
        return new LakeSiteType(Mode.PLATEAU_FLAT, flatMask);
    }

    private static boolean acceptLakeCenter(SnowdinSurfaceResolver.Surface center, LakeSiteType siteType, RandomSource random) {
        double baseChance = siteType.mode == Mode.MIX_VALLEY
                ? SnowdinIceLakeSettings.MIX_VALLEY_CHANCE_SCALE
                : SnowdinIceLakeSettings.PLATEAU_CHANCE_SCALE;
        double terrainWeight = SnowdinIceLakeSettings.spawnWeight(center.plateauPresence());
        double chance = Mth.clamp(SnowdinIceLakeSettings.SPAWN_CHANCE * baseChance * terrainWeight * siteType.mask, 0.0, 1.0);
        return random.nextDouble() < chance;
    }

    private static Basin resolveBasin(
            WorldGenLevel level,
            SurfaceCache surfaces,
            SnowdinSurfaceResolver.Surface center,
            LakeShape shape,
            LakeSiteType siteType
    ) {
        if (!matchesTerrainMask(center, siteType)) {
            return null;
        }

        int centerGroundY = center.groundPos().getY();
        int waterY = centerGroundY - SnowdinIceLakeSettings.WATER_SURFACE_INSET;
        if (hasExistingWaterNear(level, center.groundPos(), shape.maxRadius() + SnowdinIceLakeSettings.EXISTING_WATER_MARGIN, waterY)) {
            return null;
        }

        if (hasBlockingOverhead(level, center.groundPos().getX(), center.groundPos().getZ(), waterY)) {
            return null;
        }

        boolean[][] validCells = new boolean[shape.width()][shape.height()];
        int accepted = 0;
        int sampled = 0;
        int step = Math.max(1, SnowdinIceLakeSettings.BASIN_SAMPLE_STEP);
        int maxDelta = siteType.mode == Mode.PLATEAU_FLAT
                ? SnowdinIceLakeSettings.PLATEAU_FLAT_MAX_DELTA
                : SnowdinIceLakeSettings.MAX_SURFACE_DELTA;
        int centerX = center.groundPos().getX();
        int centerZ = center.groundPos().getZ();

        for (int dx = -shape.radiusX(); dx <= shape.radiusX(); dx += step) {
            for (int dz = -shape.radiusZ(); dz <= shape.radiusZ(); dz += step) {
                if (!shape.contains(dx, dz)) {
                    continue;
                }
                sampled++;
                Optional<SnowdinSurfaceResolver.Surface> sampledSurface = surfaces.resolve(centerX + dx, centerZ + dz);
                if (sampledSurface.isEmpty()) {
                    continue;
                }
                SnowdinSurfaceResolver.Surface surface = sampledSurface.get();
                int delta = Math.abs(surface.groundPos().getY() - centerGroundY);
                if (delta > maxDelta) {
                    continue;
                }
                if (!matchesTerrainMask(surface, siteType)) {
                    continue;
                }
                int localDepth = localDepth(shape, dx, dz);
                if (!isStableLakeColumn(level, centerX + dx, centerZ + dz, waterY, localDepth)) {
                    continue;
                }
                if (hasBlockingOverhead(level, centerX + dx, centerZ + dz, waterY)) {
                    continue;
                }
                validCells[dx + shape.radiusX()][dz + shape.radiusZ()] = true;
                accepted++;
            }
        }

        double minCoverage = siteType.mode == Mode.PLATEAU_FLAT
                ? SnowdinIceLakeSettings.PLATEAU_FLAT_MIN_COVERAGE
                : SnowdinIceLakeSettings.MIN_FLAT_COVERAGE;
        if (sampled == 0 || accepted / (double) sampled < minCoverage) {
            return null;
        }

        LakeShape basinShape = shape.filtered(validCells).eroded();
        if (basinShape.area() < SnowdinIceLakeSettings.MIN_LAKE_AREA_CELLS) {
            return null;
        }
        if (!hasStableShore(level, basinShape, center.groundPos(), waterY)) {
            return null;
        }
        return new Basin(center.groundPos(), waterY, basinShape);
    }

    private static boolean matchesTerrainMask(SnowdinSurfaceResolver.Surface surface, LakeSiteType siteType) {
        if (siteType.mode == Mode.MIX_VALLEY) {
            return surface.terraceMask() <= SnowdinIceLakeSettings.MAX_TERRACE_MASK;
        }
        return surface.terraceMask() >= SnowdinIceLakeSettings.PLATEAU_MIN_TERRACE_MASK
                && surface.plateauPresence() >= SnowdinIceLakeSettings.PLATEAU_MIN_PRESENCE;
    }

    private static double mixValleyMask(SurfaceCache surfaces, SnowdinSurfaceResolver.Surface center) {
        int radius = SnowdinIceLakeSettings.MIX_VALLEY_SAMPLE_RADIUS;
        int step = Math.max(1, SnowdinIceLakeSettings.MIX_VALLEY_SAMPLE_STEP);
        double rimSum = 0.0;
        int rimSamples = 0;
        double rimMin = Double.POSITIVE_INFINITY;
        double rimMax = Double.NEGATIVE_INFINITY;
        int centerX = center.groundPos().getX();
        int centerZ = center.groundPos().getZ();

        for (int dx = -radius; dx <= radius; dx += step) {
            for (int dz = -radius; dz <= radius; dz += step) {
                double dist = Math.sqrt(dx * dx + dz * dz);
                if (dist < radius * 0.55 || dist > radius * 1.30) {
                    continue;
                }
                Optional<SnowdinSurfaceResolver.Surface> sample = surfaces.resolve(centerX + dx, centerZ + dz);
                if (sample.isEmpty()) {
                    continue;
                }
                double y = sample.get().groundPos().getY();
                rimSum += y;
                rimSamples++;
                rimMin = Math.min(rimMin, y);
                rimMax = Math.max(rimMax, y);
            }
        }

        if (rimSamples < 8) {
            return 0.0;
        }
        double rimAvg = rimSum / rimSamples;
        double valleyDepth = rimAvg - center.groundPos().getY();
        if (valleyDepth < SnowdinIceLakeSettings.MIX_VALLEY_MIN_DEPTH) {
            return 0.0;
        }
        double rimRange = rimMax - rimMin;
        if (rimRange > SnowdinIceLakeSettings.MIX_VALLEY_MAX_RIM_HEIGHT_RANGE) {
            return 0.0;
        }
        double depthMask = smoothstep((valleyDepth - SnowdinIceLakeSettings.MIX_VALLEY_MIN_DEPTH) / 3.0);
        double rimMask = 1.0 - smoothstep((rimRange - 2.0) / Math.max(0.1, SnowdinIceLakeSettings.MIX_VALLEY_MAX_RIM_HEIGHT_RANGE - 2.0));
        return Mth.clamp(depthMask * rimMask, 0.0, 1.0);
    }

    private static double plateauFlatMask(SurfaceCache surfaces, SnowdinSurfaceResolver.Surface center) {
        int radius = SnowdinIceLakeSettings.PLATEAU_FLAT_SAMPLE_RADIUS;
        int step = Math.max(1, SnowdinIceLakeSettings.PLATEAU_FLAT_SAMPLE_STEP);
        int sampled = 0;
        int accepted = 0;
        int centerY = center.groundPos().getY();
        int centerX = center.groundPos().getX();
        int centerZ = center.groundPos().getZ();

        for (int dx = -radius; dx <= radius; dx += step) {
            for (int dz = -radius; dz <= radius; dz += step) {
                if (dx * dx + dz * dz > radius * radius) {
                    continue;
                }
                Optional<SnowdinSurfaceResolver.Surface> sample = surfaces.resolve(centerX + dx, centerZ + dz);
                if (sample.isEmpty()) {
                    continue;
                }
                sampled++;
                SnowdinSurfaceResolver.Surface surface = sample.get();
                if (surface.plateauPresence() < SnowdinIceLakeSettings.PLATEAU_MIN_PRESENCE) {
                    continue;
                }
                if (surface.terraceMask() < SnowdinIceLakeSettings.PLATEAU_MIN_TERRACE_MASK) {
                    continue;
                }
                if (Math.abs(surface.groundPos().getY() - centerY) > SnowdinIceLakeSettings.PLATEAU_FLAT_MAX_DELTA) {
                    continue;
                }
                accepted++;
            }
        }

        if (sampled < 12) {
            return 0.0;
        }
        double coverage = accepted / (double) sampled;
        if (coverage < SnowdinIceLakeSettings.PLATEAU_FLAT_MIN_COVERAGE) {
            return 0.0;
        }
        return smoothstep((coverage - SnowdinIceLakeSettings.PLATEAU_FLAT_MIN_COVERAGE)
                / Math.max(0.01, 1.0 - SnowdinIceLakeSettings.PLATEAU_FLAT_MIN_COVERAGE));
    }

    private static boolean carveLake(WorldGenLevel level, Basin basin) {
        LakeShape shape = basin.shape();
        BlockPos center = basin.center();
        int waterY = basin.waterY();
        int depth = SnowdinIceLakeSettings.DEPTH;
        int topClearance = Math.max(1, SnowdinIceLakeSettings.WATER_SURFACE_INSET);
        boolean placedAny = false;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (int dx = -shape.radiusX(); dx <= shape.radiusX(); dx++) {
            for (int dz = -shape.radiusZ(); dz <= shape.radiusZ(); dz++) {
                if (!shape.contains(dx, dz)) {
                    continue;
                }
                int localDepth = Math.max(1, 1 + Mth.ceil(shape.depthFactor(dx, dz) * (depth - 1)));
                for (int dy = -localDepth + 1; dy <= topClearance; dy++) {
                    cursor.set(center.getX() + dx, waterY + dy, center.getZ() + dz);
                    if (cursor.getY() <= level.getMinY() || cursor.getY() >= level.getMaxY()) {
                        continue;
                    }
                    BlockState current = level.getBlockState(cursor);
                    if (dy <= 0) {
                        if (!canReplaceForLake(current)) {
                            continue;
                        }
                        level.setBlock(cursor, dy == 0 ? Blocks.ICE.defaultBlockState() : Blocks.WATER.defaultBlockState(), 2);
                        markLakeAboveForPostProcessing(level, cursor);
                        placedAny = true;
                    } else if (!current.isAir() && current.getFluidState().isEmpty() && !current.is(BlockTags.FEATURES_CANNOT_REPLACE)) {
                        level.setBlock(cursor, Blocks.AIR.defaultBlockState(), 2);
                        markLakeAboveForPostProcessing(level, cursor);
                    }
                }
            }
        }

        return placedAny;
    }

    private static void markLakeAboveForPostProcessing(WorldGenLevel level, BlockPos basePos) {
        BlockPos.MutableBlockPos cursor = basePos.mutable();

        for (int i = 0; i < 2; i++) {
            cursor.move(Direction.UP);
            if (level.getBlockState(cursor).isAir()) {
                return;
            }

            level.getChunk(cursor).markPosForPostprocessing(cursor);
        }
    }

    private static int localDepth(LakeShape shape, int dx, int dz) {
        return Math.max(1, 1 + Mth.ceil(shape.depthFactor(dx, dz) * (SnowdinIceLakeSettings.DEPTH - 1)));
    }

    private static boolean isStableLakeColumn(WorldGenLevel level, int x, int z, int waterY, int localDepth) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        cursor.set(x, waterY - localDepth, z);
        if (!isSolidLakeShell(level, cursor, level.getBlockState(cursor))) {
            return false;
        }

        for (int y = waterY - localDepth + 1; y <= waterY + SnowdinIceLakeSettings.WATER_SURFACE_INSET; y++) {
            cursor.set(x, y, z);
            BlockState state = level.getBlockState(cursor);
            if (y <= waterY && !canReplaceForLake(state)) {
                return false;
            }
            if (y > waterY && state.is(BlockTags.FEATURES_CANNOT_REPLACE)) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasStableShore(WorldGenLevel level, LakeShape shape, BlockPos center, int waterY) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int[][] directions = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int dx = -shape.radiusX(); dx <= shape.radiusX(); dx++) {
            for (int dz = -shape.radiusZ(); dz <= shape.radiusZ(); dz++) {
                if (!shape.contains(dx, dz)) {
                    continue;
                }
                for (int[] direction : directions) {
                    int nx = dx + direction[0];
                    int nz = dz + direction[1];
                    if (shape.contains(nx, nz)) {
                        continue;
                    }
                    cursor.set(center.getX() + nx, waterY, center.getZ() + nz);
                    if (!isSolidLakeShell(level, cursor, level.getBlockState(cursor))) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static boolean hasBlockingOverhead(WorldGenLevel level, int x, int z, int waterY) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int minY = waterY + Math.max(2, SnowdinIceLakeSettings.WATER_SURFACE_INSET + 1);
        int maxY = Math.min(level.getMaxY() - 1, waterY + SnowdinIceLakeSettings.OVERHEAD_CLEARANCE);
        for (int y = minY; y <= maxY; y++) {
            cursor.set(x, y, z);
            BlockState state = level.getBlockState(cursor);
            if (!state.isAir() && !state.is(Blocks.SNOW) && !state.canBeReplaced()) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasExistingWaterNear(WorldGenLevel level, BlockPos center, int radius, int waterY) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int minY = Math.max(level.getMinY(), waterY - SnowdinIceLakeSettings.DEPTH - 1);
        int maxY = Math.min(level.getMaxY() - 1, waterY + SnowdinIceLakeSettings.WATER_SURFACE_INSET + 2);
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radius * radius) {
                    continue;
                }
                for (int y = minY; y <= maxY; y++) {
                    cursor.set(center.getX() + dx, y, center.getZ() + dz);
                    if (isWaterLike(level.getBlockState(cursor))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean isSolidLakeShell(WorldGenLevel level, BlockPos pos, BlockState state) {
        return state.isCollisionShapeFullBlock(level, pos) && !state.is(BlockTags.FEATURES_CANNOT_REPLACE);
    }

    private static boolean isWaterLike(BlockState state) {
        return !state.getFluidState().isEmpty()
                || state.is(Blocks.WATER)
                || state.is(Blocks.ICE)
                || state.is(Blocks.FROSTED_ICE)
                || state.is(Blocks.PACKED_ICE)
                || state.is(Blocks.BLUE_ICE);
    }

    private static int randomBetween(RandomSource random, int min, int max) {
        int lower = Math.min(min, max);
        int upper = Math.max(min, max);
        return lower + random.nextInt(upper - lower + 1);
    }

    private static boolean canReplaceForLake(BlockState state) {
        return !state.is(BlockTags.FEATURES_CANNOT_REPLACE) && !state.isAir();
    }

    private static double smoothstep(double value) {
        double t = Mth.clamp(value, 0.0, 1.0);
        return t * t * (3.0 - 2.0 * t);
    }

    private record Basin(BlockPos center, int waterY, LakeShape shape) {}

    private enum Mode {
        MIX_VALLEY,
        PLATEAU_FLAT
    }

    private record LakeSiteType(Mode mode, double mask) {}

    private record LakeShape(int radiusX, int radiusZ, boolean[][] cells, double[][] depthFactors, int area) {
        static LakeShape create(long seed, int centerX, int centerZ, int radiusX, int radiusZ, RandomSource random) {
            int width = radiusX * 2 + 1;
            int height = radiusZ * 2 + 1;
            boolean[][] cells = new boolean[width][height];
            double[][] depthFactors = new double[width][height];
            int lobeCount = randomBetween(random, SnowdinIceLakeSettings.SHAPE_LOBE_MIN, SnowdinIceLakeSettings.SHAPE_LOBE_MAX);
            double[] lobeX = new double[lobeCount];
            double[] lobeZ = new double[lobeCount];
            double[] lobeRadiusX = new double[lobeCount];
            double[] lobeRadiusZ = new double[lobeCount];

            for (int i = 0; i < lobeCount; i++) {
                double angle = random.nextDouble() * Math.PI * 2.0;
                double distance = random.nextDouble() * 0.42;
                lobeX[i] = Math.cos(angle) * distance;
                lobeZ[i] = Math.sin(angle) * distance;
                lobeRadiusX[i] = 0.42 + random.nextDouble() * 0.38;
                lobeRadiusZ[i] = 0.42 + random.nextDouble() * 0.38;
            }

            int area = 0;
            for (int dx = -radiusX; dx <= radiusX; dx++) {
                for (int dz = -radiusZ; dz <= radiusZ; dz++) {
                    double nx = dx / (double) radiusX;
                    double nz = dz / (double) radiusZ;
                    double score = 1.0 - nx * nx - nz * nz;
                    for (int i = 0; i < lobeCount; i++) {
                        double lnx = (nx - lobeX[i]) / lobeRadiusX[i];
                        double lnz = (nz - lobeZ[i]) / lobeRadiusZ[i];
                        score = Math.max(score, 1.0 - lnx * lnx - lnz * lnz);
                    }
                    double edgeNoise = WorldgenMath.valueNoise2d(
                            (centerX + dx) * SnowdinIceLakeSettings.EDGE_NOISE_SCALE,
                            (centerZ + dz) * SnowdinIceLakeSettings.EDGE_NOISE_SCALE,
                            seed ^ 8701L
                    );
                    double shaped = score + (edgeNoise - 0.5) * SnowdinIceLakeSettings.EDGE_NOISE_STRENGTH;
                    int ix = dx + radiusX;
                    int iz = dz + radiusZ;
                    if (shaped > 0.0) {
                        cells[ix][iz] = true;
                        depthFactors[ix][iz] = Mth.clamp(shaped, 0.0, 1.0);
                        area++;
                    }
                }
            }
            return new LakeShape(radiusX, radiusZ, cells, depthFactors, area);
        }

        int width() {
            return radiusX * 2 + 1;
        }

        int height() {
            return radiusZ * 2 + 1;
        }

        int maxRadius() {
            return Math.max(radiusX, radiusZ);
        }

        LakeShape filtered(boolean[][] validCells) {
            boolean[][] filtered = new boolean[width()][height()];
            int filteredArea = 0;
            for (int x = 0; x < width(); x++) {
                for (int z = 0; z < height(); z++) {
                    if (cells[x][z] && validCells[x][z]) {
                        filtered[x][z] = true;
                        filteredArea++;
                    }
                }
            }
            return new LakeShape(radiusX, radiusZ, filtered, depthFactors, filteredArea);
        }

        LakeShape eroded() {
            boolean[][] eroded = new boolean[width()][height()];
            int erodedArea = 0;
            for (int dx = -radiusX; dx <= radiusX; dx++) {
                for (int dz = -radiusZ; dz <= radiusZ; dz++) {
                    if (!contains(dx, dz)) {
                        continue;
                    }
                    int neighbors = 0;
                    if (contains(dx + 1, dz)) {
                        neighbors++;
                    }
                    if (contains(dx - 1, dz)) {
                        neighbors++;
                    }
                    if (contains(dx, dz + 1)) {
                        neighbors++;
                    }
                    if (contains(dx, dz - 1)) {
                        neighbors++;
                    }
                    if (neighbors >= 2) {
                        eroded[dx + radiusX][dz + radiusZ] = true;
                        erodedArea++;
                    }
                }
            }
            return new LakeShape(radiusX, radiusZ, eroded, depthFactors, erodedArea);
        }

        boolean contains(int dx, int dz) {
            int ix = dx + radiusX;
            int iz = dz + radiusZ;
            return ix >= 0 && ix < cells.length && iz >= 0 && iz < cells[ix].length && cells[ix][iz];
        }

        double depthFactor(int dx, int dz) {
            int ix = dx + radiusX;
            int iz = dz + radiusZ;
            if (ix < 0 || ix >= depthFactors.length || iz < 0 || iz >= depthFactors[ix].length) {
                return 0.0;
            }
            return depthFactors[ix][iz];
        }
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

        Optional<SnowdinSurfaceResolver.Surface> resolve(int x, int z) {
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
    }
}
