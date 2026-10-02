package cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.element.pillar;

import cn.jehorstudio.minetale.dimension.worldgen.WorldgenSamplingContext;
import cn.jehorstudio.minetale.dimension.worldgen.math.WorldgenMath;
import cn.jehorstudio.minetale.dimension.worldgen.network.settings.UndergroundSamplingSettings;
import cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.element.pillar.settings.SnowdinPillarSettings;
import net.minecraft.util.Mth;

public final class SnowdinPillarMask {
    public static final SnowdinPillarMask INSTANCE = new SnowdinPillarMask();

    private static final int CELL_PROFILE_CACHE_SIZE = 256;
    private static final int CELL_PROFILE_CACHE_MASK = CELL_PROFILE_CACHE_SIZE - 1;
    // 工作线程独占固定缓存槽
    private static final ThreadLocal<PillarSeeds> SEED_CACHE = new ThreadLocal<>();
    private static final ThreadLocal<CellProfileCache> CELL_PROFILE_CACHE =
            ThreadLocal.withInitial(CellProfileCache::new);

    private SnowdinPillarMask() {}

    public double sample(
            double x,
            double y,
            double z,
            double horizontalPathDistance,
            double floorY,
            double ceilingY
    ) {
        return sample(
                new WorldgenSamplingContext(0L, UndergroundSamplingSettings.defaults()),
                x,
                y,
                z,
                horizontalPathDistance,
                floorY,
                ceilingY
        );
    }

    public double sample(
            WorldgenSamplingContext context,
            double x,
            double y,
            double z,
            double horizontalPathDistance,
            double floorY,
            double ceilingY
    ) {
        return sampleReference(context, x, y, z, horizontalPathDistance, floorY, ceilingY, 1.0);
    }

    public double sampleTerraceTop(
            double x,
            double y,
            double z,
            double horizontalPathDistance,
            double floorY,
            double ceilingY
    ) {
        return sampleTerraceTop(new WorldgenSamplingContext(0L, UndergroundSamplingSettings.defaults()), x, y, z, horizontalPathDistance, floorY, ceilingY);
    }

    public double sampleTerraceTop(
            WorldgenSamplingContext context,
            double x,
            double y,
            double z,
            double horizontalPathDistance,
            double floorY,
            double ceilingY
    ) {
        return sampleReference(
                context,
                x,
                y,
                z,
                horizontalPathDistance,
                floorY,
                ceilingY,
                SnowdinPillarSettings.TERRACE_TOP_RADIUS_SCALE
        );
    }

    // 每列只推导一次与 Y 无关的候选
    public PreparedColumn prepare(
            WorldgenSamplingContext context,
            double x,
            double z,
            double horizontalPathDistance
    ) {
        double cellSize = SnowdinPillarSettings.CELL_SIZE;
        int cellX = Mth.floor(x / cellSize);
        int cellZ = Mth.floor(z / cellSize);
        PillarSeeds seeds = seeds(context.worldgenSeed());
        CellProfileCache cellProfileCache = CELL_PROFILE_CACHE.get();

        int neighborRadius = SnowdinPillarSettings.NEIGHBOR_CELL_RADIUS;
        int neighborDiameter = neighborRadius * 2 + 1;
        int capacity = neighborDiameter * neighborDiameter;
        CellProfile[] candidates = new CellProfile[capacity];
        int candidateCount = 0;

        for (int dx = -neighborRadius; dx <= neighborRadius; dx++) {
            for (int dz = -neighborRadius; dz <= neighborRadius; dz++) {
                int cx = cellX + dx;
                int cz = cellZ + dz;
                CellProfile candidate = cellProfileCache.get(seeds, cx, cz, cellSize);
                if (candidate != null) {
                    candidates[candidateCount++] = candidate;
                }
            }
        }

        double roadClear = WorldgenMath.smoothstep(
                (Math.abs(horizontalPathDistance) - SnowdinPillarSettings.ROAD_CLEAR_START_DISTANCE)
                        / SnowdinPillarSettings.ROAD_CLEAR_FADE_DISTANCE
        );
        double edgeDetailNoise = WorldgenMath.signedValueNoise2d(
                x * SnowdinPillarSettings.EDGE_DETAIL_NOISE_SCALE,
                z * SnowdinPillarSettings.EDGE_DETAIL_NOISE_SCALE,
                seeds.edgeDetailNoiseSeed
        ) * SnowdinPillarSettings.EDGE_DETAIL_NOISE_STRENGTH;
        moveNearestCandidateFirst(x, z, candidates, candidateCount);
        double farCandidateUpperBound = WorldgenMath.smooth(Mth.clamp(
                Math.nextUp(Math.nextUp(SnowdinPillarSettings.EDGE_NOISE_STRENGTH) + edgeDetailNoise),
                0.0,
                1.0
        ));

        return new PreparedColumn(
                x,
                z,
                roadClear,
                edgeDetailNoise,
                farCandidateUpperBound,
                x * SnowdinPillarSettings.CRACK_XZ_SCALE,
                z * SnowdinPillarSettings.CRACK_XZ_SCALE,
                seeds,
                candidates,
                candidateCount
        );
    }

    public double sample(PreparedColumn column, double y, double floorY, double ceilingY) {
        return samplePrepared(column, y, floorY, ceilingY, 1.0);
    }

    public double sampleTerraceTop(PreparedColumn column, double y, double floorY, double ceilingY) {
        return samplePrepared(
                column,
                y,
                floorY,
                ceilingY,
                SnowdinPillarSettings.TERRACE_TOP_RADIUS_SCALE
        );
    }

    private double samplePrepared(
            PreparedColumn column,
            double y,
            double floorY,
            double ceilingY,
            double radiusScale
    ) {
        if (column.roadClear == 0.0) {
            return 0.0;
        }

        double height = Math.max(ceilingY - floorY, 1.0);
        double vertical = Mth.clamp((y - floorY) / height, 0.0, 1.0);
        double best = 0.0;

        for (int candidate = 0; candidate < column.candidateCount; candidate++) {
            best = bestWithCandidate(column, candidate, y, vertical, radiusScale, best);
        }

        return Mth.clamp(best * column.roadClear, 0.0, 1.0);
    }

    private static void moveNearestCandidateFirst(
            double x,
            double z,
            CellProfile[] candidates,
            int candidateCount
    ) {
        if (candidateCount < 2) {
            return;
        }

        int nearest = 0;
        double nearestDistanceSquared = Double.POSITIVE_INFINITY;
        for (int candidate = 0; candidate < candidateCount; candidate++) {
            CellProfile profile = candidates[candidate];
            double dx = x - profile.centerX;
            double dz = z - profile.centerZ;
            double distanceSquared = dx * dx + dz * dz;
            if (distanceSquared < nearestDistanceSquared) {
                nearestDistanceSquared = distanceSquared;
                nearest = candidate;
            }
        }
        if (nearest == 0) {
            return;
        }

        CellProfile swap = candidates[0];
        candidates[0] = candidates[nearest];
        candidates[nearest] = swap;
    }

    double sampleReference(
            WorldgenSamplingContext context,
            double x,
            double y,
            double z,
            double horizontalPathDistance,
            double floorY,
            double ceilingY,
            double radiusScale
    ) {
        double cellSize = SnowdinPillarSettings.CELL_SIZE;

        int cellX = Mth.floor(x / cellSize);
        int cellZ = Mth.floor(z / cellSize);

        double best = 0.0;

        for (int dx = -SnowdinPillarSettings.NEIGHBOR_CELL_RADIUS;
             dx <= SnowdinPillarSettings.NEIGHBOR_CELL_RADIUS;
             dx++) {
            for (int dz = -SnowdinPillarSettings.NEIGHBOR_CELL_RADIUS;
                 dz <= SnowdinPillarSettings.NEIGHBOR_CELL_RADIUS;
                 dz++) {
                int cx = cellX + dx;
                int cz = cellZ + dz;

                if (!hasPillar(context, cx, cz)) {
                    continue;
                }

                best = Math.max(best, pillarShapeAtCellReference(
                        x,
                        y,
                        z,
                        floorY,
                        ceilingY,
                        cx,
                        cz,
                        cellSize,
                        radiusScale,
                        context
                ));
            }
        }

        double roadClear = WorldgenMath.smoothstep(
                (Math.abs(horizontalPathDistance) - SnowdinPillarSettings.ROAD_CLEAR_START_DISTANCE)
                        / SnowdinPillarSettings.ROAD_CLEAR_FADE_DISTANCE
        );

        return Mth.clamp(best * roadClear, 0.0, 1.0);
    }

    private static boolean hasPillar(WorldgenSamplingContext context, int cellX, int cellZ) {
        return hasPillar(cellX, cellZ, context.channelSeed(7001));
    }

    private static boolean hasPillar(int cellX, int cellZ, long presenceSeed) {
        double score = WorldgenMath.hashToUnit(cellX, 0, cellZ, presenceSeed);

        return score >= SnowdinPillarSettings.GENERATION_THRESHOLD;
    }

    private static double pillarShapeAtCellReference(
            double x,
            double y,
            double z,
            double floorY,
            double ceilingY,
            int cellX,
            int cellZ,
            double cellSize,
            double radiusScale,
            WorldgenSamplingContext context
    ) {
        double height = Math.max(ceilingY - floorY, 1.0);
        double vertical = Mth.clamp((y - floorY) / height, 0.0, 1.0);

        int style = Math.floorMod(
                WorldgenMath.hash(cellX + 43, 7601, cellZ - 19, context.channelSeed(7601)),
                SnowdinPillarSettings.STYLE_COUNT
        );
        double cellJitterX = WorldgenMath.signedNoise(cellX, 0, cellZ, context.channelSeed(7002));
        double cellJitterZ = WorldgenMath.signedNoise(cellX, 0, cellZ, context.channelSeed(7003));
        double leanX = WorldgenMath.signedNoise(cellX, 1, cellZ, context.channelSeed(7602));
        double leanZ = WorldgenMath.signedNoise(cellX, 1, cellZ, context.channelSeed(7603));

        double centerX = (cellX + 0.5) * cellSize
                + cellJitterX * cellSize * SnowdinPillarSettings.CENTER_JITTER_CELL_SCALE;
        double centerZ = (cellZ + 0.5) * cellSize
                + cellJitterZ * cellSize * SnowdinPillarSettings.CENTER_JITTER_CELL_SCALE;

        double leanCurve = (vertical - SnowdinPillarSettings.NOISE_CENTER)
                * SnowdinPillarSettings.NORMALIZED_HEIGHT_RANGE_SCALE;
        centerX += leanX * leanCurve * SnowdinPillarSettings.LEAN_STRENGTH;
        centerZ += leanZ * leanCurve * SnowdinPillarSettings.LEAN_STRENGTH;

        double angle = WorldgenMath.valueNoise2d(
                cellX * SnowdinPillarSettings.ANGLE_CELL_X_SCALE,
                cellZ * SnowdinPillarSettings.ANGLE_CELL_Z_SCALE,
                context.channelSeed(7004)
        ) * Math.PI;
        angle += WorldgenMath.signedValueNoise1d(
                vertical * SnowdinPillarSettings.ANGLE_TWIST_SCALE
                        + cellX * SnowdinPillarSettings.ANGLE_TWIST_CELL_X_OFFSET_SCALE
                        + cellZ * SnowdinPillarSettings.ANGLE_TWIST_CELL_Z_OFFSET_SCALE,
                context.channelSeed(7604)
        ) * SnowdinPillarSettings.ANGLE_TWIST_STRENGTH;

        double cos = Math.cos(angle);
        double sin = Math.sin(angle);

        double dx = x - centerX;
        double dz = z - centerZ;

        double radiusNoiseX = WorldgenMath.valueNoise2d(
                cellX * SnowdinPillarSettings.RADIUS_X_NOISE_CELL_X_SCALE,
                cellZ * SnowdinPillarSettings.RADIUS_X_NOISE_CELL_Z_SCALE,
                context.channelSeed(7005)
        );
        double radiusNoiseZ = WorldgenMath.valueNoise2d(
                cellX * SnowdinPillarSettings.RADIUS_Z_NOISE_CELL_X_SCALE,
                cellZ * SnowdinPillarSettings.RADIUS_Z_NOISE_CELL_Z_SCALE,
                context.channelSeed(7006)
        );
        double radiusNoise = (radiusNoiseX + radiusNoiseZ) * 0.5;
        double rx = SnowdinPillarSettings.RADIUS_X_BASE
                + radiusNoise * SnowdinPillarSettings.RADIUS_X_RANGE;
        double rz = SnowdinPillarSettings.RADIUS_Z_BASE
                + radiusNoise * SnowdinPillarSettings.RADIUS_Z_RANGE;

        int wallChance = Math.floorMod(
                WorldgenMath.hash(cellX + 19, 7101, cellZ - 31, context.channelSeed(7101)),
                SnowdinPillarSettings.WALL_STYLE_HASH_MODULO
        );
        if (wallChance < SnowdinPillarSettings.WALL_STYLE_CHANCE_PERCENT) {
            rz *= SnowdinPillarSettings.WALL_STYLE_Z_RADIUS_SCALE;
            rx *= SnowdinPillarSettings.WALL_STYLE_X_RADIUS_SCALE;
        }

        double waist = 1.0 - Math.sin(vertical * Math.PI) * SnowdinPillarSettings.WAIST_STRENGTH;
        double footFlare = 1.0
                + Math.pow(1.0 - vertical, SnowdinPillarSettings.FOOT_FLARE_POWER)
                * SnowdinPillarSettings.FOOT_FLARE_STRENGTH;
        double capFlare = 1.0
                + Math.pow(vertical, SnowdinPillarSettings.CAP_FLARE_POWER)
                * SnowdinPillarSettings.CAP_FLARE_STRENGTH;
        double tierNoise = WorldgenMath.valueNoise1d(
                vertical * SnowdinPillarSettings.TIER_NOISE_VERTICAL_SCALE
                        + cellX * SnowdinPillarSettings.TIER_NOISE_CELL_X_OFFSET_SCALE
                        - cellZ * SnowdinPillarSettings.TIER_NOISE_CELL_Z_OFFSET_SCALE,
                context.channelSeed(7605)
        );
        double tier = SnowdinPillarSettings.TIER_RADIUS_BASE
                + (tierNoise - SnowdinPillarSettings.NOISE_CENTER)
                * SnowdinPillarSettings.TIER_RADIUS_STRENGTH;
        double crack = 1.0 + WorldgenMath.signedValueNoise3d(
                x * SnowdinPillarSettings.CRACK_XZ_SCALE + cellX,
                y * SnowdinPillarSettings.CRACK_Y_SCALE,
                z * SnowdinPillarSettings.CRACK_XZ_SCALE - cellZ,
                context.channelSeed(7606)
        ) * SnowdinPillarSettings.CRACK_STRENGTH;

        double profile = waist * footFlare * capFlare * tier * crack;

        if (style == SnowdinPillarSettings.STYLE_WAIST) {
            profile *= SnowdinPillarSettings.STYLE_WAIST_BASE
                    + Math.pow(
                    Math.abs(vertical - SnowdinPillarSettings.STYLE_WAIST_CENTER)
                            * SnowdinPillarSettings.STYLE_WAIST_DISTANCE_SCALE,
                    SnowdinPillarSettings.STYLE_WAIST_POWER
            )
                    * SnowdinPillarSettings.STYLE_WAIST_STRENGTH;
        } else if (style == SnowdinPillarSettings.STYLE_TOP_HEAVY) {
            profile *= SnowdinPillarSettings.STYLE_TOP_HEAVY_BASE
                    + WorldgenMath.smoothstep(
                    (vertical - SnowdinPillarSettings.STYLE_TOP_HEAVY_START)
                            / SnowdinPillarSettings.STYLE_TOP_HEAVY_FADE
            )
                    * SnowdinPillarSettings.STYLE_TOP_HEAVY_STRENGTH;
        } else if (style == SnowdinPillarSettings.STYLE_WAVE) {
            profile *= 1.0
                    + Math.sin(
                    (vertical * SnowdinPillarSettings.STYLE_WAVE_VERTICAL_SCALE
                            + WorldgenMath.valueNoise2d(cellX, cellZ, context.channelSeed(7607))) * Math.PI
            ) * SnowdinPillarSettings.STYLE_WAVE_STRENGTH;
        }

        rx *= radiusScale;
        rz *= radiusScale;

        rx *= Mth.clamp(
                profile,
                SnowdinPillarSettings.PROFILE_MIN,
                SnowdinPillarSettings.PROFILE_X_MAX
        );
        rz *= Mth.clamp(
                profile * (
                        SnowdinPillarSettings.PROFILE_Z_TIER_BASE
                                + tierNoise * SnowdinPillarSettings.PROFILE_Z_TIER_STRENGTH
                ),
                SnowdinPillarSettings.PROFILE_MIN,
                SnowdinPillarSettings.PROFILE_Z_MAX
        );

        double localX = dx * cos - dz * sin;
        double localZ = dx * sin + dz * cos;

        double ellipse = Math.sqrt((localX * localX) / (rx * rx) + (localZ * localZ) / (rz * rz));
        double shape = 1.0 - ellipse;

        double best = shape > 0.0 ? WorldgenMath.smooth(shape) : 0.0;

        double edgeNoise =
                WorldgenMath.signedValueNoise3d(
                        localX * SnowdinPillarSettings.EDGE_NOISE_XZ_SCALE,
                        y * SnowdinPillarSettings.EDGE_NOISE_Y_SCALE,
                        localZ * SnowdinPillarSettings.EDGE_NOISE_XZ_SCALE,
                        context.channelSeed(7401)
                ) * SnowdinPillarSettings.EDGE_NOISE_STRENGTH
                        + WorldgenMath.signedValueNoise2d(
                        x * SnowdinPillarSettings.EDGE_DETAIL_NOISE_SCALE,
                        z * SnowdinPillarSettings.EDGE_DETAIL_NOISE_SCALE,
                        context.channelSeed(7402)
                ) * SnowdinPillarSettings.EDGE_DETAIL_NOISE_STRENGTH;

        best = Mth.clamp(best + edgeNoise, 0.0, 1.0);

        return WorldgenMath.smooth(best);
    }

    private static double bestWithCandidate(
            PreparedColumn column,
            int candidate,
            double y,
            double vertical,
            double radiusScale,
            double currentBest
    ) {
        CellProfile profile = column.candidates[candidate];

        double leanCurve = (vertical - SnowdinPillarSettings.NOISE_CENTER)
                * SnowdinPillarSettings.NORMALIZED_HEIGHT_RANGE_SCALE;
        double centerX = profile.centerX;
        double centerZ = profile.centerZ;
        centerX += profile.leanX * leanCurve * SnowdinPillarSettings.LEAN_STRENGTH;
        centerZ += profile.leanZ * leanCurve * SnowdinPillarSettings.LEAN_STRENGTH;

        double dx = column.x - centerX;
        double dz = column.z - centerZ;
        double maxRadius = Math.max(
                profile.baseRadiusX * radiusScale * SnowdinPillarSettings.PROFILE_X_MAX,
                profile.baseRadiusZ * radiusScale * SnowdinPillarSettings.PROFILE_Z_MAX
        );
        double safeMaxRadius = Math.nextUp(maxRadius + 1.0e-9);
        boolean outsideMaximumRadius = dx * dx + dz * dz > safeMaxRadius * safeMaxRadius;
        if (outsideMaximumRadius && currentBest >= column.farCandidateUpperBound) {
            return currentBest;
        }

        double angle = profile.baseAngle;
        angle += WorldgenMath.signedValueNoise1d(
                vertical * SnowdinPillarSettings.ANGLE_TWIST_SCALE
                        + profile.twistCellX
                        + profile.twistCellZ,
                column.seeds.angleTwistSeed
        ) * SnowdinPillarSettings.ANGLE_TWIST_STRENGTH;

        double cos = Math.cos(angle);
        double sin = Math.sin(angle);
        double localX = dx * cos - dz * sin;
        double localZ = dx * sin + dz * cos;

        if (outsideMaximumRadius) {
            double edgeNoise = WorldgenMath.signedValueNoise3d(
                    localX * SnowdinPillarSettings.EDGE_NOISE_XZ_SCALE,
                    y * SnowdinPillarSettings.EDGE_NOISE_Y_SCALE,
                    localZ * SnowdinPillarSettings.EDGE_NOISE_XZ_SCALE,
                    column.seeds.edgeNoiseSeed
            ) * SnowdinPillarSettings.EDGE_NOISE_STRENGTH
                    + column.edgeDetailNoise;
            double candidateBest = Mth.clamp(edgeNoise, 0.0, 1.0);
            return Math.max(currentBest, WorldgenMath.smooth(candidateBest));
        }

        double rx = profile.baseRadiusX;
        double rz = profile.baseRadiusZ;

        double waist = 1.0 - Math.sin(vertical * Math.PI) * SnowdinPillarSettings.WAIST_STRENGTH;
        double footFlare = 1.0
                + Math.pow(1.0 - vertical, SnowdinPillarSettings.FOOT_FLARE_POWER)
                * SnowdinPillarSettings.FOOT_FLARE_STRENGTH;
        double capFlare = 1.0
                + Math.pow(vertical, SnowdinPillarSettings.CAP_FLARE_POWER)
                * SnowdinPillarSettings.CAP_FLARE_STRENGTH;
        double tierNoise = WorldgenMath.valueNoise1d(
                vertical * SnowdinPillarSettings.TIER_NOISE_VERTICAL_SCALE
                        + profile.tierCellX
                        - profile.tierCellZ,
                column.seeds.tierNoiseSeed
        );
        double tier = SnowdinPillarSettings.TIER_RADIUS_BASE
                + (tierNoise - SnowdinPillarSettings.NOISE_CENTER)
                * SnowdinPillarSettings.TIER_RADIUS_STRENGTH;
        double crack = 1.0 + WorldgenMath.signedValueNoise3d(
                column.crackBaseX + profile.cellX,
                y * SnowdinPillarSettings.CRACK_Y_SCALE,
                column.crackBaseZ - profile.cellZ,
                column.seeds.crackNoiseSeed
        ) * SnowdinPillarSettings.CRACK_STRENGTH;

        double radialProfile = waist * footFlare * capFlare * tier * crack;
        int style = profile.style;
        if (style == SnowdinPillarSettings.STYLE_WAIST) {
            radialProfile *= SnowdinPillarSettings.STYLE_WAIST_BASE
                    + Math.pow(
                    Math.abs(vertical - SnowdinPillarSettings.STYLE_WAIST_CENTER)
                            * SnowdinPillarSettings.STYLE_WAIST_DISTANCE_SCALE,
                    SnowdinPillarSettings.STYLE_WAIST_POWER
            )
                    * SnowdinPillarSettings.STYLE_WAIST_STRENGTH;
        } else if (style == SnowdinPillarSettings.STYLE_TOP_HEAVY) {
            radialProfile *= SnowdinPillarSettings.STYLE_TOP_HEAVY_BASE
                    + WorldgenMath.smoothstep(
                    (vertical - SnowdinPillarSettings.STYLE_TOP_HEAVY_START)
                            / SnowdinPillarSettings.STYLE_TOP_HEAVY_FADE
            )
                    * SnowdinPillarSettings.STYLE_TOP_HEAVY_STRENGTH;
        } else if (style == SnowdinPillarSettings.STYLE_WAVE) {
            radialProfile *= 1.0
                    + Math.sin(
                    (vertical * SnowdinPillarSettings.STYLE_WAVE_VERTICAL_SCALE
                            + profile.waveNoise) * Math.PI
            ) * SnowdinPillarSettings.STYLE_WAVE_STRENGTH;
        }

        rx *= radiusScale;
        rz *= radiusScale;
        rx *= Mth.clamp(
                radialProfile,
                SnowdinPillarSettings.PROFILE_MIN,
                SnowdinPillarSettings.PROFILE_X_MAX
        );
        rz *= Mth.clamp(
                radialProfile * (
                        SnowdinPillarSettings.PROFILE_Z_TIER_BASE
                                + tierNoise * SnowdinPillarSettings.PROFILE_Z_TIER_STRENGTH
                ),
                SnowdinPillarSettings.PROFILE_MIN,
                SnowdinPillarSettings.PROFILE_Z_MAX
        );

        double ellipse = Math.sqrt((localX * localX) / (rx * rx) + (localZ * localZ) / (rz * rz));
        double shape = 1.0 - ellipse;
        double best = shape > 0.0 ? WorldgenMath.smooth(shape) : 0.0;

        double edgeNoise = WorldgenMath.signedValueNoise3d(
                localX * SnowdinPillarSettings.EDGE_NOISE_XZ_SCALE,
                y * SnowdinPillarSettings.EDGE_NOISE_Y_SCALE,
                localZ * SnowdinPillarSettings.EDGE_NOISE_XZ_SCALE,
                column.seeds.edgeNoiseSeed
        ) * SnowdinPillarSettings.EDGE_NOISE_STRENGTH
                + column.edgeDetailNoise;
        best = Mth.clamp(best + edgeNoise, 0.0, 1.0);

        return Math.max(currentBest, WorldgenMath.smooth(best));
    }

    private static PillarSeeds seeds(long worldgenSeed) {
        PillarSeeds cached = SEED_CACHE.get();
        if (cached == null || cached.worldgenSeed != worldgenSeed) {
            cached = new PillarSeeds(worldgenSeed);
            SEED_CACHE.set(cached);
        }
        return cached;
    }

    private static CellProfile createCellProfile(
            PillarSeeds seeds,
            int cellX,
            int cellZ,
            double cellSize
    ) {
        int style = Math.floorMod(
                WorldgenMath.hash(cellX + 43, 7601, cellZ - 19, seeds.styleSeed),
                SnowdinPillarSettings.STYLE_COUNT
        );
        double cellJitterX = WorldgenMath.signedNoise(cellX, 0, cellZ, seeds.centerJitterXSeed);
        double cellJitterZ = WorldgenMath.signedNoise(cellX, 0, cellZ, seeds.centerJitterZSeed);
        double centerX = (cellX + 0.5) * cellSize
                + cellJitterX * cellSize * SnowdinPillarSettings.CENTER_JITTER_CELL_SCALE;
        double centerZ = (cellZ + 0.5) * cellSize
                + cellJitterZ * cellSize * SnowdinPillarSettings.CENTER_JITTER_CELL_SCALE;
        double leanX = WorldgenMath.signedNoise(cellX, 1, cellZ, seeds.leanXSeed);
        double leanZ = WorldgenMath.signedNoise(cellX, 1, cellZ, seeds.leanZSeed);
        double baseAngle = WorldgenMath.valueNoise2d(
                cellX * SnowdinPillarSettings.ANGLE_CELL_X_SCALE,
                cellZ * SnowdinPillarSettings.ANGLE_CELL_Z_SCALE,
                seeds.baseAngleSeed
        ) * Math.PI;
        double twistCellX = cellX * SnowdinPillarSettings.ANGLE_TWIST_CELL_X_OFFSET_SCALE;
        double twistCellZ = cellZ * SnowdinPillarSettings.ANGLE_TWIST_CELL_Z_OFFSET_SCALE;

        double radiusNoiseX = WorldgenMath.valueNoise2d(
                cellX * SnowdinPillarSettings.RADIUS_X_NOISE_CELL_X_SCALE,
                cellZ * SnowdinPillarSettings.RADIUS_X_NOISE_CELL_Z_SCALE,
                seeds.radiusXSeed
        );
        double radiusNoiseZ = WorldgenMath.valueNoise2d(
                cellX * SnowdinPillarSettings.RADIUS_Z_NOISE_CELL_X_SCALE,
                cellZ * SnowdinPillarSettings.RADIUS_Z_NOISE_CELL_Z_SCALE,
                seeds.radiusZSeed
        );
        double radiusNoise = (radiusNoiseX + radiusNoiseZ) * 0.5;
        double radiusX = SnowdinPillarSettings.RADIUS_X_BASE
                + radiusNoise * SnowdinPillarSettings.RADIUS_X_RANGE;
        double radiusZ = SnowdinPillarSettings.RADIUS_Z_BASE
                + radiusNoise * SnowdinPillarSettings.RADIUS_Z_RANGE;
        int wallChance = Math.floorMod(
                WorldgenMath.hash(cellX + 19, 7101, cellZ - 31, seeds.wallStyleSeed),
                SnowdinPillarSettings.WALL_STYLE_HASH_MODULO
        );
        if (wallChance < SnowdinPillarSettings.WALL_STYLE_CHANCE_PERCENT) {
            radiusZ *= SnowdinPillarSettings.WALL_STYLE_Z_RADIUS_SCALE;
            radiusX *= SnowdinPillarSettings.WALL_STYLE_X_RADIUS_SCALE;
        }

        return new CellProfile(
                cellX,
                cellZ,
                style,
                centerX,
                centerZ,
                leanX,
                leanZ,
                baseAngle,
                twistCellX,
                twistCellZ,
                radiusX,
                radiusZ,
                cellX * SnowdinPillarSettings.TIER_NOISE_CELL_X_OFFSET_SCALE,
                cellZ * SnowdinPillarSettings.TIER_NOISE_CELL_Z_OFFSET_SCALE,
                WorldgenMath.valueNoise2d(cellX, cellZ, seeds.waveNoiseSeed)
        );
    }

    private static final class CellProfileCache {
        private final CacheEntry[] entries = new CacheEntry[CELL_PROFILE_CACHE_SIZE];

        private CellProfile get(PillarSeeds seeds, int cellX, int cellZ, double cellSize) {
            int presenceHash = WorldgenMath.hash(cellX, 0, cellZ, seeds.presenceSeed);
            int index = presenceHash & CELL_PROFILE_CACHE_MASK;
            CacheEntry cached = entries[index];
            if (cached != null
                    && cached.worldgenSeed == seeds.worldgenSeed
                    && cached.cellX == cellX
                    && cached.cellZ == cellZ) {
                return cached.profile;
            }

            double score = (presenceHash & 0xFFFF) / 65535.0;
            CellProfile profile = score >= SnowdinPillarSettings.GENERATION_THRESHOLD
                    ? createCellProfile(seeds, cellX, cellZ, cellSize)
                    : null;
            entries[index] = new CacheEntry(seeds.worldgenSeed, cellX, cellZ, profile);
            return profile;
        }
    }

    private record CacheEntry(long worldgenSeed, int cellX, int cellZ, CellProfile profile) {}

    private record CellProfile(
            int cellX,
            int cellZ,
            int style,
            double centerX,
            double centerZ,
            double leanX,
            double leanZ,
            double baseAngle,
            double twistCellX,
            double twistCellZ,
            double baseRadiusX,
            double baseRadiusZ,
            double tierCellX,
            double tierCellZ,
            double waveNoise
    ) {}

    private static final class PillarSeeds {
        private final long worldgenSeed;
        private final long presenceSeed;
        private final long centerJitterXSeed;
        private final long centerJitterZSeed;
        private final long styleSeed;
        private final long leanXSeed;
        private final long leanZSeed;
        private final long baseAngleSeed;
        private final long angleTwistSeed;
        private final long radiusXSeed;
        private final long radiusZSeed;
        private final long wallStyleSeed;
        private final long tierNoiseSeed;
        private final long crackNoiseSeed;
        private final long waveNoiseSeed;
        private final long edgeNoiseSeed;
        private final long edgeDetailNoiseSeed;

        private PillarSeeds(long worldgenSeed) {
            this.worldgenSeed = worldgenSeed;
            this.presenceSeed = WorldgenMath.channelSeed(worldgenSeed, 7001);
            this.centerJitterXSeed = WorldgenMath.channelSeed(worldgenSeed, 7002);
            this.centerJitterZSeed = WorldgenMath.channelSeed(worldgenSeed, 7003);
            this.styleSeed = WorldgenMath.channelSeed(worldgenSeed, 7601);
            this.leanXSeed = WorldgenMath.channelSeed(worldgenSeed, 7602);
            this.leanZSeed = WorldgenMath.channelSeed(worldgenSeed, 7603);
            this.baseAngleSeed = WorldgenMath.channelSeed(worldgenSeed, 7004);
            this.angleTwistSeed = WorldgenMath.channelSeed(worldgenSeed, 7604);
            this.radiusXSeed = WorldgenMath.channelSeed(worldgenSeed, 7005);
            this.radiusZSeed = WorldgenMath.channelSeed(worldgenSeed, 7006);
            this.wallStyleSeed = WorldgenMath.channelSeed(worldgenSeed, 7101);
            this.tierNoiseSeed = WorldgenMath.channelSeed(worldgenSeed, 7605);
            this.crackNoiseSeed = WorldgenMath.channelSeed(worldgenSeed, 7606);
            this.waveNoiseSeed = WorldgenMath.channelSeed(worldgenSeed, 7607);
            this.edgeNoiseSeed = WorldgenMath.channelSeed(worldgenSeed, 7401);
            this.edgeDetailNoiseSeed = WorldgenMath.channelSeed(worldgenSeed, 7402);
        }
    }

    // 候选数组保持私有，使列快照在各 Y 采样间不可变。
    public static final class PreparedColumn {
        private final double x;
        private final double z;
        private final double roadClear;
        private final double edgeDetailNoise;
        private final double farCandidateUpperBound;
        private final double crackBaseX;
        private final double crackBaseZ;
        private final PillarSeeds seeds;
        private final CellProfile[] candidates;
        private final int candidateCount;

        private PreparedColumn(
                double x,
                double z,
                double roadClear,
                double edgeDetailNoise,
                double farCandidateUpperBound,
                double crackBaseX,
                double crackBaseZ,
                PillarSeeds seeds,
                CellProfile[] candidates,
                int candidateCount
        ) {
            this.x = x;
            this.z = z;
            this.roadClear = roadClear;
            this.edgeDetailNoise = edgeDetailNoise;
            this.farCandidateUpperBound = farCandidateUpperBound;
            this.crackBaseX = crackBaseX;
            this.crackBaseZ = crackBaseZ;
            this.seeds = seeds;
            this.candidates = candidates;
            this.candidateCount = candidateCount;
        }
    }
}
