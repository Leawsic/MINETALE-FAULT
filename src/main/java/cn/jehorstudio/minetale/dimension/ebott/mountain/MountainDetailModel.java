package cn.jehorstudio.minetale.dimension.ebott.mountain;

import cn.jehorstudio.minetale.dimension.worldgen.math.WorldgenMath;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.WorldgenRandom;

// 每个地表列只做一次确定性分类；材质、积雪、装饰许可与植被必须消费同一结果。
final class MountainDetailModel {
    static final double HIGH_CAVE_PROBABILITY = 0.06D;
    static final double SNOW_TRANSITION_WIDTH = 0.22D;

    private static final double LOW_FOREST_END = 0.42D;
    private static final double TREE_MAX_SLOPE = 0.95D;
    private static final int STABLE_ROOF_DEPTH = 6;
    private static final int LOW_TREE_GRID = 30;
    private static final int MONTANE_TREE_GRID = 32;
    private static final int SNOW_TREE_GRID = 34;
    private static final int FALLBACK_LOW_TREE_GRID = 28;
    private static final int FALLBACK_MONTANE_TREE_GRID = 30;
    private static final int LOW_TREE_CLUSTER_SIZE = 16;
    private static final int MONTANE_TREE_CLUSTER_SIZE = 14;
    private static final int SNOW_TREE_CLUSTER_SIZE = 16;
    private static final int LOW_TREE_CLUSTER_RADIUS = 12;
    private static final int MONTANE_TREE_CLUSTER_RADIUS = 11;
    private static final int SNOW_TREE_CLUSTER_RADIUS = 13;
    private static final double ABSOLUTE_CLIFF_SLOPE = 1.82D;
    private static final double OUTCROP_MIN_SLOPE = 1.45D;
    private static final double OUTCROP_MIN_RIDGE = 0.55D;
    private static final int SNOW_FIELD_SCALE = 96;
    private static final int SNOW_COVERAGE_MACRO_SCALE = 48;
    private static final int SNOW_COVERAGE_DETAIL_SCALE = 13;
    private static final int ROCK_FIELD_SCALE = 48;
    private static final int FOOTPRINT_MACRO_SCALE = 72;
    private static final int FOOTPRINT_DETAIL_SCALE = 19;

    private static final int SNOW_FIELD_CHANNEL = 0x45424F41;
    private static final int SNOW_COVERAGE_MACRO_CHANNEL = 0x45424F43;
    private static final int SNOW_COVERAGE_DETAIL_CHANNEL = 0x45424F44;
    private static final int ROCK_FIELD_CHANNEL = 0x45424F42;
    private static final int VEGETATION_POSITION_CHANNEL = 0x45424F51;
    private static final int VEGETATION_ACCEPT_CHANNEL = 0x45424F52;
    private static final int VEGETATION_KIND_CHANNEL = 0x45424F53;
    private static final int VEGETATION_VARIANT_CHANNEL = 0x45424F57;
    private static final int CAVE_CHANNEL = 0x45424F54;
    private static final int FOOTPRINT_MACRO_CHANNEL = 0x45424F55;
    private static final int FOOTPRINT_DETAIL_CHANNEL = 0x45424F56;

    private MountainDetailModel() {
    }

    static SurfaceProfile classifySurface(
            long mountainSeed,
            int worldX,
            int worldZ,
            double relativeHeight,
            double slope,
            double ridgeMap,
            double temperature,
            double downfall
    ) {
        double height = clamp(relativeHeight, 0.0D, 1.0D);
        double snowLine = localSnowLine(mountainSeed, worldX, worldZ, temperature, ridgeMap);
        if (hasSnowCover(mountainSeed, worldX, worldZ, height, snowLine)) {
            double accumulation = clamp((height - snowLine) / 0.14D, 0.0D, 1.0D);
            int snowDepth = 1 + Math.min(2, (int) (accumulation * 3.0D));
            return new SurfaceProfile(Surface.SNOW, VegetationZone.NONE, snowDepth, snowLine);
        }

        if (isExposedRock(mountainSeed, worldX, worldZ, slope, ridgeMap)) {
            return new SurfaceProfile(Surface.ROCK, VegetationZone.NONE, 0, snowLine);
        }

        double treeLine = treeLine(temperature, downfall);
        VegetationZone vegetation = height < LOW_FOREST_END
                ? VegetationZone.LOW_FOREST
                : height < treeLine ? VegetationZone.MONTANE_FOREST : VegetationZone.ALPINE_MEADOW;
        int soilDepth = vegetation == VegetationZone.LOW_FOREST ? 4
                : vegetation == VegetationZone.MONTANE_FOREST ? 3 : 2;
        if (ridgeMap <= -0.35D) {
            soilDepth++;
        }
        return new SurfaceProfile(Surface.SOIL, vegetation, soilDepth, snowLine);
    }

    static double relativeHeight(int baseY, int summitY, int surfaceY) {
        int verticalRange = summitY - baseY;
        if (verticalRange <= 0) {
            return surfaceY >= summitY ? 1.0D : 0.0D;
        }
        return clamp((surfaceY - baseY) / (double) verticalRange, 0.0D, 1.0D);
    }

    static double snowLine(double temperature) {
        return clamp(0.78D + (temperature - 0.5D) * 0.06D, 0.74D, 0.82D);
    }

    static double localSnowLine(
            long mountainSeed,
            int worldX,
            int worldZ,
            double temperature,
            double ridgeMap
    ) {
        long seed = WorldgenMath.channelSeed(mountainSeed, SNOW_FIELD_CHANNEL);
        double field = coherentField(worldX, worldZ, SNOW_FIELD_SCALE, seed) - 0.5D;
        return clamp(snowLine(temperature) + field * 0.05D + clamp(ridgeMap, -1.0D, 1.0D) * 0.015D,
                0.715D, 0.845D);
    }

    // 雪线下方使用长过渡带；大尺度场形成雪舌，小尺度场扰动边缘
    static boolean hasSnowCover(long mountainSeed, int worldX, int worldZ, double height, double snowLine) {
        if (height >= snowLine) {
            return true;
        }

        double coverage = snowCoverageProbability(height, snowLine);
        if (coverage <= 0.0D) {
            return false;
        }

        long macroSeed = WorldgenMath.channelSeed(mountainSeed, SNOW_COVERAGE_MACRO_CHANNEL);
        long detailSeed = WorldgenMath.channelSeed(mountainSeed, SNOW_COVERAGE_DETAIL_CHANNEL);
        double macro = coherentField(worldX, worldZ, SNOW_COVERAGE_MACRO_SCALE, macroSeed);
        double detail = coherentField(worldX, worldZ, SNOW_COVERAGE_DETAIL_SCALE, detailSeed);
        double patchMask = macro * 0.72D + detail * 0.28D;
        return patchMask >= 1.0D - coverage;
    }

    static double snowCoverageProbability(double height, double snowLine) {
        double transitionStart = snowLine - SNOW_TRANSITION_WIDTH;
        double progress = clamp((height - transitionStart) / SNOW_TRANSITION_WIDTH, 0.0D, 1.0D);
        return smootherstep(progress);
    }

    // 山脚以连续斑块接管原表层，大尺度形成舌状区域，小尺度打散边缘。
    static boolean usesEbottSurface(long mountainSeed, int worldX, int worldZ, double influence) {
        double clampedInfluence = clamp(influence, 0.0D, 1.0D);
        if (clampedInfluence <= 0.0D) {
            return false;
        }
        if (clampedInfluence >= 1.0D) {
            return true;
        }

        long macroSeed = WorldgenMath.channelSeed(mountainSeed, FOOTPRINT_MACRO_CHANNEL);
        long detailSeed = WorldgenMath.channelSeed(mountainSeed, FOOTPRINT_DETAIL_CHANNEL);
        double macro = coherentField(worldX, worldZ, FOOTPRINT_MACRO_SCALE, macroSeed);
        double detail = coherentField(worldX, worldZ, FOOTPRINT_DETAIL_SCALE, detailSeed);
        double patchMask = clamp((macro * 0.78D + detail * 0.22D - 0.20D) / 0.60D, 0.0D, 1.0D);
        return patchMask < clampedInfluence;
    }

    static double treeLine(double temperature, double downfall) {
        return clamp(0.62D + (downfall - 0.5D) * 0.08D - Math.max(0.0D, temperature - 1.0D) * 0.03D,
                0.56D, 0.68D);
    }

    static Climate effectiveClimate(double temperature, double downfall, boolean fallbackBiome) {
        return fallbackBiome ? new Climate(0.5D, 0.5D) : new Climate(temperature, downfall);
    }

    static int vegetationGrid(VegetationZone zone, boolean fallbackBiome) {
        if (zone == VegetationZone.LOW_FOREST) {
            return fallbackBiome ? FALLBACK_LOW_TREE_GRID : LOW_TREE_GRID;
        }
        if (zone == VegetationZone.MONTANE_FOREST) {
            return fallbackBiome ? FALLBACK_MONTANE_TREE_GRID : MONTANE_TREE_GRID;
        }
        throw new IllegalArgumentException("zone has no vegetation grid: " + zone);
    }

    static int treeGrid(SurfaceProfile profile, boolean fallbackBiome) {
        return profile.surface() == Surface.SNOW
                ? SNOW_TREE_GRID
                : vegetationGrid(profile.vegetation(), fallbackBiome);
    }

    static int treeClusterSize(SurfaceProfile profile) {
        if (profile.surface() == Surface.SNOW) {
            return SNOW_TREE_CLUSTER_SIZE;
        }
        return profile.vegetation() == VegetationZone.LOW_FOREST
                ? LOW_TREE_CLUSTER_SIZE : MONTANE_TREE_CLUSTER_SIZE;
    }

    static int treeClusterRadius(SurfaceProfile profile) {
        if (profile.surface() == Surface.SNOW) {
            return SNOW_TREE_CLUSTER_RADIUS;
        }
        return profile.vegetation() == VegetationZone.LOW_FOREST
                ? LOW_TREE_CLUSTER_RADIUS : MONTANE_TREE_CLUSTER_RADIUS;
    }

    static boolean allowsBiomeVegetation(SurfaceProfile profile) {
        // 中高山必须由海拔演替接管，防止暖地树种越过林线。
        return profile.surface() == Surface.SOIL && profile.vegetation() == VegetationZone.LOW_FOREST;
    }

    static boolean mayPlaceTree(SurfaceProfile profile, double slope, boolean soilSurface) {
        return soilSurface
                && (profile.surface() == Surface.SNOW || profile.vegetation().isForest())
                && slope < TREE_MAX_SLOPE;
    }

    static double treeDensity(
            SurfaceProfile profile,
            double relativeHeight,
            double temperature,
            double downfall
    ) {
        double height = clamp(relativeHeight, 0.0D, 1.0D);
        if (profile.surface() == Surface.SNOW) {
            double snowProgress = clamp(
                    (height - profile.snowLine()) / Math.max(0.01D, 1.0D - profile.snowLine()),
                    0.0D,
                    1.0D
            );
            return lerp(0.78D, 0.56D, smootherstep(snowProgress));
        }
        if (!profile.vegetation().isForest()) {
            return 0.0D;
        }

        double moisture = clamp(0.78D + downfall * 0.30D, 0.78D, 1.08D);
        if (profile.vegetation() == VegetationZone.LOW_FOREST) {
            double progress = smootherstep(clamp(height / LOW_FOREST_END, 0.0D, 1.0D));
            return clamp(lerp(0.98D, 0.82D, progress) * moisture, 0.0D, 1.0D);
        }

        double localTreeLine = treeLine(temperature, downfall);
        double progress = smootherstep(clamp(
                (height - LOW_FOREST_END) / Math.max(0.01D, localTreeLine - LOW_FOREST_END),
                0.0D,
                1.0D
        ));
        return clamp(lerp(0.82D, 0.48D, progress) * moisture, 0.0D, 1.0D);
    }

    static boolean acceptsTree(
            Candidate candidate,
            SurfaceProfile profile,
            double relativeHeight,
            double temperature,
            double downfall
    ) {
        return candidate.acceptanceRoll() < treeDensity(
                profile,
                relativeHeight,
                temperature,
                downfall
        );
    }

    static double coldTreeShare(double relativeHeight, double temperature, double downfall) {
        double transitionStart = clamp(0.22D + (temperature - 0.5D) * 0.04D - downfall * 0.02D,
                0.16D, 0.28D);
        double progress = clamp(
                (relativeHeight - transitionStart) / Math.max(0.01D, LOW_FOREST_END - transitionStart),
                0.0D,
                1.0D
        );
        return smootherstep(progress);
    }

    static boolean mayPlaceWatercourse(
            SurfaceProfile profile,
            double relativeHeight,
            double slope,
            double ridgeMap,
            double downfall,
            boolean solidSurface,
            boolean clearAbove
    ) {
        double wetGullyThreshold = -0.58D + clamp(downfall, 0.0D, 1.0D) * 0.16D;
        return solidSurface
                && clearAbove
                && profile.surface() != Surface.SNOW
                && relativeHeight >= 0.28D
                && relativeHeight <= 0.76D
                && slope >= 0.08D
                && slope <= 0.90D
                && ridgeMap <= wetGullyThreshold;
    }

    static int caveMinY(int baseY) {
        return Math.max(160, baseY + 32);
    }

    static int caveMaxY(int summitY) {
        return summitY - 12;
    }

    static boolean caveMayWrite(int originalSurfaceY, int targetSurfaceY, int y) {
        return y > originalSurfaceY
                && y <= targetSurfaceY - STABLE_ROOF_DEPTH;
    }

    static boolean runHighCavePass(long mountainSeed, int chunkX, int chunkZ) {
        WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(0L));
        random.setLargeFeatureSeed(highCaveSeed(mountainSeed), chunkX, chunkZ);
        return random.nextFloat() <= (float) HIGH_CAVE_PROBABILITY;
    }

    static long highCaveSeed(long mountainSeed) {
        return WorldgenMath.channelSeed(mountainSeed, CAVE_CHANNEL);
    }

    static Candidate vegetationCandidate(long mountainSeed, int cellX, int cellZ, int gridSize) {
        if (gridSize < 1) {
            throw new IllegalArgumentException("gridSize must be positive");
        }
        long positionSeed = WorldgenMath.channelSeed(mountainSeed, VEGETATION_POSITION_CHANNEL ^ gridSize);
        long acceptSeed = WorldgenMath.channelSeed(mountainSeed, VEGETATION_ACCEPT_CHANNEL ^ gridSize);
        long kindSeed = WorldgenMath.channelSeed(mountainSeed, VEGETATION_KIND_CHANNEL ^ gridSize);
        long variantSeed = WorldgenMath.channelSeed(mountainSeed, VEGETATION_VARIANT_CHANNEL ^ gridSize);
        int originX = Math.multiplyExact(cellX, gridSize);
        int originZ = Math.multiplyExact(cellZ, gridSize);
        int offsetX = Math.min(gridSize - 1,
                (int) (WorldgenMath.hashToUnit(cellX, gridSize, cellZ, positionSeed) * gridSize));
        int offsetZ = Math.min(gridSize - 1,
                (int) (WorldgenMath.hashToUnit(cellX, -gridSize, cellZ, positionSeed) * gridSize));
        return new Candidate(
                originX + offsetX,
                originZ + offsetZ,
                WorldgenMath.hashToUnit(cellX, 0, cellZ, acceptSeed),
                WorldgenMath.hashToUnit(cellX, 1, cellZ, kindSeed),
                WorldgenMath.hashToUnit(cellX, 2, cellZ, variantSeed)
        );
    }

    static Candidate treeClusterCandidate(
            long mountainSeed,
            int cellX,
            int cellZ,
            int gridSize,
            int member,
            int radius
    ) {
        if (member < 0 || radius < 1) {
            throw new IllegalArgumentException("member must be non-negative and radius must be positive");
        }
        Candidate center = vegetationCandidate(mountainSeed, cellX, cellZ, gridSize);
        long positionSeed = WorldgenMath.channelSeed(mountainSeed,
                VEGETATION_POSITION_CHANNEL ^ gridSize ^ 0x13579BDF);
        long kindSeed = WorldgenMath.channelSeed(mountainSeed,
                VEGETATION_KIND_CHANNEL ^ gridSize ^ 0x2468ACE0);
        long variantSeed = WorldgenMath.channelSeed(mountainSeed,
                VEGETATION_VARIANT_CHANNEL ^ gridSize ^ 0x10203040);
        double angle = WorldgenMath.hashToUnit(cellX, member * 2 + 1, cellZ, positionSeed) * Math.PI * 2.0D;
        double distance = member == 0 ? 0.0D : radius * Math.sqrt(
                WorldgenMath.hashToUnit(cellX, member * 2 + 2, cellZ, positionSeed)
        );
        return new Candidate(
                center.worldX() + (int) Math.round(Math.cos(angle) * distance),
                center.worldZ() + (int) Math.round(Math.sin(angle) * distance),
                center.acceptanceRoll(),
                WorldgenMath.hashToUnit(cellX, member, cellZ, kindSeed),
                WorldgenMath.hashToUnit(cellX, member, cellZ, variantSeed)
        );
    }

    private static boolean isExposedRock(long mountainSeed, int worldX, int worldZ, double slope, double ridgeMap) {
        if (slope >= ABSOLUTE_CLIFF_SLOPE) {
            return true;
        }
        if (slope < OUTCROP_MIN_SLOPE || ridgeMap < OUTCROP_MIN_RIDGE) {
            return false;
        }
        long seed = WorldgenMath.channelSeed(mountainSeed, ROCK_FIELD_CHANNEL);
        return coherentField(worldX, worldZ, ROCK_FIELD_SCALE, seed) >= 0.58D;
    }

    private static double coherentField(int worldX, int worldZ, int scale, long seed) {
        int cellX = Math.floorDiv(worldX, scale);
        int cellZ = Math.floorDiv(worldZ, scale);
        double fractionX = Math.floorMod(worldX, scale) / (double) scale;
        double fractionZ = Math.floorMod(worldZ, scale) / (double) scale;
        double blendX = smootherstep(fractionX);
        double blendZ = smootherstep(fractionZ);
        double lower = lerp(
                WorldgenMath.hashToUnit(cellX, 0, cellZ, seed),
                WorldgenMath.hashToUnit(cellX + 1, 0, cellZ, seed),
                blendX
        );
        double upper = lerp(
                WorldgenMath.hashToUnit(cellX, 0, cellZ + 1, seed),
                WorldgenMath.hashToUnit(cellX + 1, 0, cellZ + 1, seed),
                blendX
        );
        return lerp(lower, upper, blendZ);
    }

    private static double smootherstep(double value) {
        double t = clamp(value, 0.0D, 1.0D);
        return t * t * t * (t * (t * 6.0D - 15.0D) + 10.0D);
    }

    private static double lerp(double from, double to, double amount) {
        return from + (to - from) * amount;
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    enum Surface {
        SOIL,
        ROCK,
        SNOW
    }

    enum VegetationZone {
        LOW_FOREST,
        MONTANE_FOREST,
        ALPINE_MEADOW,
        NONE;

        boolean isForest() {
            return this == LOW_FOREST || this == MONTANE_FOREST;
        }
    }

    record SurfaceProfile(Surface surface, VegetationZone vegetation, int coverDepth, double snowLine) {
    }

    record Candidate(int worldX, int worldZ, double acceptanceRoll, double kindRoll, double variantRoll) {
    }

    record Climate(double temperature, double downfall) {
    }
}
