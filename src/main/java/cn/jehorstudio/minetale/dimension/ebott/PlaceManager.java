package cn.jehorstudio.minetale.dimension.ebott;

import cn.jehorstudio.minetale.dimension.worldgen.math.WorldgenMath;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.Objects;

// 伊伯特山唯一选址入口：主线程解析并持久化一次，异步 worldgen 只读冻结 Place。
public final class PlaceManager {
    private static final int MOUNTAIN_SEED_CHANNEL = 0x4D54;
    private static final int CANDIDATE_CHANNEL = 0xCA7D;

    private static final int GENERATION_VERSION = 1;
    private static final int PROFILE_VERSION = 1;
    private static final int ANCHOR_MIN_DISTANCE = 1536;
    private static final int ANCHOR_MAX_DISTANCE = 3072;
    private static final int CANDIDATE_COUNT = 24;
    private static final int CANDIDATE_SAMPLE_RADIUS = 96;
    private static final int MOUNTAIN_OUTER_RADIUS = 640;
    private static final int OUTER_BLEND_WIDTH = 96;
    private static final int PREFERRED_SUMMIT_Y = 304;
    private static final int BUILD_HEIGHT_SAFETY_MARGIN = 12;

    private PlaceManager() {
    }

    // 使用主世界原始高度函数
    public static Place select(ServerLevel overworld) {
        int minY = overworld.getMinY();
        int maxY = minY + overworld.getHeight() - 1;
        var generator = overworld.getChunkSource().getGenerator();
        var randomState = overworld.getChunkSource().randomState();
        return select(
                overworld.getSeed(),
                overworld.getRespawnData().pos().getX(),
                overworld.getRespawnData().pos().getZ(),
                minY,
                maxY,
                overworld.getSeaLevel(),
                (x, z) -> generator.getBaseHeight(
                        x,
                        z,
                        Heightmap.Types.WORLD_SURFACE_WG,
                        overworld,
                        randomState
                ) - 1
        );
    }

    public static Place select(
            long worldSeed,
            int spawnX,
            int spawnZ,
            int minY,
            int maxY,
            int seaLevel,
            SurfaceSampler surfaceSampler
    ) {
        Objects.requireNonNull(surfaceSampler, "surfaceSampler");
        if (maxY < minY) {
            throw new IllegalArgumentException("maxY must not be below minY");
        }

        Candidate best = null;
        long candidateSeed = WorldgenMath.channelSeed(worldSeed, CANDIDATE_CHANNEL);
        double angularOffset = WorldgenMath.hashToUnit(0, 0, 0, candidateSeed);
        for (int index = 0; index < CANDIDATE_COUNT; index++) {
            double radiusUnit = WorldgenMath.hashToUnit(index, 1, 0, candidateSeed);
            double radius = ANCHOR_MIN_DISTANCE
                    + (ANCHOR_MAX_DISTANCE - ANCHOR_MIN_DISTANCE) * radiusUnit;
            double angle = StrictMath.PI * 2.0 * (index + angularOffset) / CANDIDATE_COUNT;
            int x = spawnX + (int) StrictMath.round(StrictMath.cos(angle) * radius);
            int z = spawnZ + (int) StrictMath.round(StrictMath.sin(angle) * radius);
            Candidate candidate = evaluateCandidate(
                    index,
                    x,
                    z,
                    spawnX,
                    spawnZ,
                    minY,
                    maxY,
                    seaLevel,
                    surfaceSampler
            );
            if (best == null || candidate.score() > best.score()) {
                best = candidate;
            }
        }

        Candidate chosen = Objects.requireNonNull(best, "candidate plan");
        int safeTopY = Math.max(minY, maxY - BUILD_HEIGHT_SAFETY_MARGIN);
        int baseY = Math.min(chosen.baseY(), safeTopY);
        int summitY = Math.max(baseY, Math.min(PREFERRED_SUMMIT_Y, safeTopY));
        return new Place(
                chosen.x(),
                chosen.z(),
                baseY,
                summitY,
                WorldgenMath.channelSeed(worldSeed, MOUNTAIN_SEED_CHANNEL),
                PROFILE_VERSION,
                GENERATION_VERSION,
                MOUNTAIN_OUTER_RADIUS,
                OUTER_BLEND_WIDTH
        );
    }

    private static Candidate evaluateCandidate(
            int index,
            int x,
            int z,
            int spawnX,
            int spawnZ,
            int minY,
            int maxY,
            int seaLevel,
            SurfaceSampler sampler
    ) {
        int centerY = clampedSurface(sampler, x, z, minY, maxY);
        double sum = centerY;
        double sumSquares = (double) centerY * centerY;
        int samples = 1;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                int y = clampedSurface(
                        sampler,
                        x + dx * CANDIDATE_SAMPLE_RADIUS,
                        z + dz * CANDIDATE_SAMPLE_RADIUS,
                        minY,
                        maxY
                );
                sum += y;
                sumSquares += (double) y * y;
                samples++;
            }
        }

        double mean = sum / samples;
        double variance = Math.max(0.0, sumSquares / samples - mean * mean);
        double spawnDistance = StrictMath.hypot((double) x - spawnX, (double) z - spawnZ);
        double desiredDistance = (ANCHOR_MIN_DISTANCE + ANCHOR_MAX_DISTANCE) * 0.5;
        double waterPenalty = centerY <= seaLevel + 2 ? 400.0 : 0.0;
        double heightPenalty = Math.max(
                0,
                centerY - (maxY - BUILD_HEIGHT_SAFETY_MARGIN - 32)
        ) * 20.0;
        double score = -variance * 6.0
                - waterPenalty
                - heightPenalty
                - Math.abs(spawnDistance - desiredDistance) * 0.002
                - index * 1.0E-6;
        return new Candidate(x, z, centerY, score);
    }

    private static int clampedSurface(
            SurfaceSampler sampler,
            int x,
            int z,
            int minY,
            int maxY
    ) {
        return Math.max(minY, Math.min(maxY, sampler.surfaceY(x, z)));
    }

    // 每个世界唯一且可持久化的选址事实。
    public record Place(
            int centerX,
            int centerZ,
            int baseY,
            int summitY,
            long mountainSeed,
            int profileVersion,
            int generationVersion,
            int mountainOuterRadius,
            int outerBlendWidth
    ) {
        public Place {
            if (summitY < baseY) {
                throw new IllegalArgumentException("summit must not be below mountain base");
            }
            if (mountainOuterRadius < 1
                    || outerBlendWidth < 1
                    || outerBlendWidth > mountainOuterRadius) {
                throw new IllegalArgumentException("invalid frozen mountain footprint");
            }
        }

        public boolean intersectsChunk(ChunkPos chunkPos) {
            return intersectsChunk(chunkPos.x, chunkPos.z);
        }

        public boolean intersectsChunk(int chunkX, int chunkZ) {
            long minX = (long) chunkX * 16L;
            long minZ = (long) chunkZ * 16L;
            long maxX = minX + 15L;
            long maxZ = minZ + 15L;
            return maxX >= (long) centerX - mountainOuterRadius
                    && minX <= (long) centerX + mountainOuterRadius
                    && maxZ >= (long) centerZ - mountainOuterRadius
                    && minZ <= (long) centerZ + mountainOuterRadius;
        }

        public Bounds bounds() {
            return new Bounds(
                    centerX - mountainOuterRadius,
                    centerZ - mountainOuterRadius,
                    centerX + mountainOuterRadius,
                    centerZ + mountainOuterRadius
            );
        }
    }

    public record Bounds(int minX, int minZ, int maxX, int maxZ) {
        public boolean contains(int x, int z) {
            return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }
    }

    @FunctionalInterface
    public interface SurfaceSampler {
        int surfaceY(int x, int z);
    }

    private record Candidate(int x, int z, int baseY, double score) {
    }
}
