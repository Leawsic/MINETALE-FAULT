package cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.element.stalactite;

import cn.jehorstudio.minetale.dimension.worldgen.WorldgenSamplingContext;
import cn.jehorstudio.minetale.dimension.worldgen.math.WorldgenMath;
import cn.jehorstudio.minetale.dimension.worldgen.network.settings.UndergroundSamplingSettings;
import cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.settings.SnowdinTerrainSettings;
import net.minecraft.util.Mth;

public final class SnowdinStalactiteMask {
    public static final SnowdinStalactiteMask INSTANCE = new SnowdinStalactiteMask();

    private static final int LENGTH = 0;
    private static final int VISIBLE_LENGTH = 1;
    private static final int CENTER_X = 2;
    private static final int CENTER_Z = 3;
    private static final int LEAN_X_NOISE = 4;
    private static final int LEAN_Z_NOISE = 5;
    private static final int ROOT_RADIUS = 6;
    private static final int CANDIDATE_STRIDE = 7;

    private SnowdinStalactiteMask() {}

    public double sample(
            double x,
            double y,
            double z,
            double floorY,
            double ceilingY
    ) {
        return sample(new WorldgenSamplingContext(0L, UndergroundSamplingSettings.defaults()), x, y, z, floorY, ceilingY);
    }

    public double sample(
            WorldgenSamplingContext context,
            double x,
            double y,
            double z,
            double floorY,
            double ceilingY
    ) {
        double cellSize = SnowdinTerrainSettings.STALACTITE_CELL_SIZE;
        int cellX = Mth.floor(x / cellSize);
        int cellZ = Mth.floor(z / cellSize);

        double best = 0.0;

        for (int dx = -SnowdinTerrainSettings.STALACTITE_NEIGHBOR_CELL_RADIUS;
             dx <= SnowdinTerrainSettings.STALACTITE_NEIGHBOR_CELL_RADIUS;
             dx++) {
            for (int dz = -SnowdinTerrainSettings.STALACTITE_NEIGHBOR_CELL_RADIUS;
                 dz <= SnowdinTerrainSettings.STALACTITE_NEIGHBOR_CELL_RADIUS;
                 dz++) {
                int cx = cellX + dx;
                int cz = cellZ + dz;

                double score = WorldgenMath.hashToUnit(cx, 0, cz, context.channelSeed(6341));
                if (score < SnowdinTerrainSettings.STALACTITE_GENERATION_THRESHOLD) {
                    continue;
                }

                best = Math.max(best, sampleAtCell(
                        x,
                        y,
                        z,
                        floorY,
                        ceilingY,
                        cx,
                        cz,
                        cellSize,
                        context
                ));
            }
        }

        return Mth.clamp(best, 0.0, 1.0);
    }

    // 每列只预计算一次与 Y 无关的钟乳石候选。
    public PreparedColumn prepare(
            WorldgenSamplingContext context,
            double x,
            double z,
            double floorY,
            double ceilingY
    ) {
        double cellSize = SnowdinTerrainSettings.STALACTITE_CELL_SIZE;
        int cellX = Mth.floor(x / cellSize);
        int cellZ = Mth.floor(z / cellSize);
        int neighborRadius = SnowdinTerrainSettings.STALACTITE_NEIGHBOR_CELL_RADIUS;
        int neighborDiameter = neighborRadius * 2 + 1;
        double[] candidates = new double[neighborDiameter * neighborDiameter * CANDIDATE_STRIDE];
        int candidateCount = 0;
        double caveHeight = Math.max(ceilingY - floorY, 1.0);

        long generationSeed = context.channelSeed(6341);
        long lengthSeed = context.channelSeed(6342);
        long centerXSeed = context.channelSeed(6343);
        long centerZSeed = context.channelSeed(6344);
        long leanXSeed = context.channelSeed(6345);
        long leanZSeed = context.channelSeed(6346);
        long radiusSeed = context.channelSeed(6347);

        for (int dx = -neighborRadius; dx <= neighborRadius; dx++) {
            for (int dz = -neighborRadius; dz <= neighborRadius; dz++) {
                int cx = cellX + dx;
                int cz = cellZ + dz;
                double score = WorldgenMath.hashToUnit(cx, 0, cz, generationSeed);
                if (score < SnowdinTerrainSettings.STALACTITE_GENERATION_THRESHOLD) {
                    continue;
                }

                int offset = candidateCount * CANDIDATE_STRIDE;
                double length = SnowdinTerrainSettings.STALACTITE_LENGTH_BASE
                        + WorldgenMath.hashToUnit(cx, 1, cz, lengthSeed)
                        * SnowdinTerrainSettings.STALACTITE_LENGTH_RANGE;
                length = Math.min(length, caveHeight * SnowdinTerrainSettings.STALACTITE_MAX_HEIGHT_FRACTION);
                candidates[offset + LENGTH] = length;
                candidates[offset + VISIBLE_LENGTH] = length * SnowdinTerrainSettings.STALACTITE_VISIBLE_LENGTH_FRACTION;
                candidates[offset + CENTER_X] = (cx + 0.5) * cellSize
                        + WorldgenMath.signedNoise(cx, 0, cz, centerXSeed)
                        * cellSize
                        * SnowdinTerrainSettings.STALACTITE_CENTER_JITTER_CELL_SCALE;
                candidates[offset + CENTER_Z] = (cz + 0.5) * cellSize
                        + WorldgenMath.signedNoise(cx, 0, cz, centerZSeed)
                        * cellSize
                        * SnowdinTerrainSettings.STALACTITE_CENTER_JITTER_CELL_SCALE;
                candidates[offset + LEAN_X_NOISE] = WorldgenMath.signedNoise(cx, 2, cz, leanXSeed);
                candidates[offset + LEAN_Z_NOISE] = WorldgenMath.signedNoise(cx, 2, cz, leanZSeed);
                candidates[offset + ROOT_RADIUS] = SnowdinTerrainSettings.STALACTITE_RADIUS_BASE
                        + WorldgenMath.hashToUnit(cx, 3, cz, radiusSeed)
                        * SnowdinTerrainSettings.STALACTITE_RADIUS_RANGE;
                candidateCount++;
            }
        }

        return new PreparedColumn(
                x,
                z,
                ceilingY,
                context.channelSeed(6348),
                candidates,
                candidateCount
        );
    }

    // 按 Y 读取列快照，结果必须与逐点重新推导候选一致。
    public double sample(PreparedColumn column, double y) {
        double distanceBelowCeiling = column.ceilingY - y;
        double edgeNoise = Double.NaN;
        double best = 0.0;

        for (int candidate = 0; candidate < column.candidateCount; candidate++) {
            int offset = candidate * CANDIDATE_STRIDE;
            double visibleLength = column.candidates[offset + VISIBLE_LENGTH];
            if (distanceBelowCeiling < 0.0 || distanceBelowCeiling > visibleLength) {
                continue;
            }

            double length = column.candidates[offset + LENGTH];
            double vertical = distanceBelowCeiling / length;
            double centerX = column.candidates[offset + CENTER_X]
                    + column.candidates[offset + LEAN_X_NOISE]
                    * vertical
                    * SnowdinTerrainSettings.STALACTITE_LEAN_STRENGTH;
            double centerZ = column.candidates[offset + CENTER_Z]
                    + column.candidates[offset + LEAN_Z_NOISE]
                    * vertical
                    * SnowdinTerrainSettings.STALACTITE_LEAN_STRENGTH;

            double radius = column.candidates[offset + ROOT_RADIUS] * Math.pow(
                    1.0 - vertical,
                    SnowdinTerrainSettings.STALACTITE_RADIUS_TAPER_POWER
            );
            double rootBulge = 1.0 - WorldgenMath.smoothstep(
                    vertical / SnowdinTerrainSettings.STALACTITE_ROOT_BULGE_FRACTION
            );
            radius *= 1.0 + rootBulge * SnowdinTerrainSettings.STALACTITE_ROOT_BULGE_STRENGTH;

            if (Double.isNaN(edgeNoise)) {
                edgeNoise = WorldgenMath.signedValueNoise3d(
                        column.x * SnowdinTerrainSettings.STALACTITE_EDGE_NOISE_SCALE,
                        y * SnowdinTerrainSettings.STALACTITE_EDGE_NOISE_SCALE,
                        column.z * SnowdinTerrainSettings.STALACTITE_EDGE_NOISE_SCALE,
                        column.edgeNoiseSeed
                );
            }
            radius *= 1.0 + edgeNoise * SnowdinTerrainSettings.STALACTITE_EDGE_NOISE_STRENGTH;

            double px = column.x - centerX;
            double pz = column.z - centerZ;
            double distance = Math.sqrt(px * px + pz * pz);
            radius = Math.max(radius, SnowdinTerrainSettings.STALACTITE_MIN_RADIUS);
            double shape = 1.0 - distance / radius;
            best = Math.max(best, shape > 0.0 ? WorldgenMath.smooth(shape) : 0.0);
        }

        return Mth.clamp(best, 0.0, 1.0);
    }

    private static double sampleAtCell(
            double x,
            double y,
            double z,
            double floorY,
            double ceilingY,
            int cellX,
            int cellZ,
            double cellSize,
            WorldgenSamplingContext context
    ) {
        double caveHeight = Math.max(ceilingY - floorY, 1.0);
        double length = SnowdinTerrainSettings.STALACTITE_LENGTH_BASE
                + WorldgenMath.hashToUnit(cellX, 1, cellZ, context.channelSeed(6342))
                * SnowdinTerrainSettings.STALACTITE_LENGTH_RANGE;
        length = Math.min(length, caveHeight * SnowdinTerrainSettings.STALACTITE_MAX_HEIGHT_FRACTION);

        double distanceBelowCeiling = ceilingY - y;
        double visibleLength = length * SnowdinTerrainSettings.STALACTITE_VISIBLE_LENGTH_FRACTION;

        if (distanceBelowCeiling < 0.0 || distanceBelowCeiling > visibleLength) {
            return 0.0;
        }

        double vertical = distanceBelowCeiling / length;
        double centerX = (cellX + 0.5) * cellSize
                + WorldgenMath.signedNoise(cellX, 0, cellZ, context.channelSeed(6343))
                * cellSize
                * SnowdinTerrainSettings.STALACTITE_CENTER_JITTER_CELL_SCALE;
        double centerZ = (cellZ + 0.5) * cellSize
                + WorldgenMath.signedNoise(cellX, 0, cellZ, context.channelSeed(6344))
                * cellSize
                * SnowdinTerrainSettings.STALACTITE_CENTER_JITTER_CELL_SCALE;

        centerX += WorldgenMath.signedNoise(cellX, 2, cellZ, context.channelSeed(6345))
                * vertical
                * SnowdinTerrainSettings.STALACTITE_LEAN_STRENGTH;
        centerZ += WorldgenMath.signedNoise(cellX, 2, cellZ, context.channelSeed(6346))
                * vertical
                * SnowdinTerrainSettings.STALACTITE_LEAN_STRENGTH;

        double rootRadius = SnowdinTerrainSettings.STALACTITE_RADIUS_BASE
                + WorldgenMath.hashToUnit(cellX, 3, cellZ, context.channelSeed(6347))
                * SnowdinTerrainSettings.STALACTITE_RADIUS_RANGE;
        double radius = rootRadius * Math.pow(
                1.0 - vertical,
                SnowdinTerrainSettings.STALACTITE_RADIUS_TAPER_POWER
        );
        double rootBulge = 1.0 - WorldgenMath.smoothstep(
                vertical / SnowdinTerrainSettings.STALACTITE_ROOT_BULGE_FRACTION
        );
        radius *= 1.0 + rootBulge * SnowdinTerrainSettings.STALACTITE_ROOT_BULGE_STRENGTH;

        double edgeNoise = WorldgenMath.signedValueNoise3d(
                x * SnowdinTerrainSettings.STALACTITE_EDGE_NOISE_SCALE,
                y * SnowdinTerrainSettings.STALACTITE_EDGE_NOISE_SCALE,
                z * SnowdinTerrainSettings.STALACTITE_EDGE_NOISE_SCALE,
                context.channelSeed(6348)
        );
        radius *= 1.0 + edgeNoise * SnowdinTerrainSettings.STALACTITE_EDGE_NOISE_STRENGTH;

        double px = x - centerX;
        double pz = z - centerZ;
        double distance = Math.sqrt(px * px + pz * pz);
        radius = Math.max(radius, SnowdinTerrainSettings.STALACTITE_MIN_RADIUS);
        double shape = 1.0 - distance / radius;

        return shape > 0.0 ? WorldgenMath.smooth(shape) : 0.0;
    }

    // 单个水平坐标拥有的不可变候选集合。
    public static final class PreparedColumn {
        private final double x;
        private final double z;
        private final double ceilingY;
        private final long edgeNoiseSeed;
        private final double[] candidates;
        private final int candidateCount;

        private PreparedColumn(
                double x,
                double z,
                double ceilingY,
                long edgeNoiseSeed,
                double[] candidates,
                int candidateCount
        ) {
            this.x = x;
            this.z = z;
            this.ceilingY = ceilingY;
            this.edgeNoiseSeed = edgeNoiseSeed;
            this.candidates = candidates;
            this.candidateCount = candidateCount;
        }
    }
}
