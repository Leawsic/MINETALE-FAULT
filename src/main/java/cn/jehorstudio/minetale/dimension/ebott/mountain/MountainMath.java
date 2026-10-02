package cn.jehorstudio.minetale.dimension.ebott.mountain;

import cn.jehorstudio.minetale.dimension.ebott.PlaceManager;

// 纯数学模型，不读取世界、区块或注册表；冻结选址与坐标相同则结果相同，可供异步 worldgen 使用。
public final class MountainMath {
    private static final int CHUNK_SIDE = 16;
    private static final int CHUNK_COLUMNS = CHUNK_SIDE * CHUNK_SIDE;

    private static final double PRIMARY_BUTTRESS_FRACTION = 0.165;
    private static final double SECONDARY_BUTTRESS_FRACTION = 0.060;
    private static final double FLANK_ASYMMETRY_FRACTION = 0.035;

    private static final ErosionProfile EROSION = new ErosionProfile(
            4,
            192.0,
            2.0,
            0.62,
            0.72,
            0.58,
            0.075,
            0.22,
            0.22,
            32.0,
            14.0
    );

    private static final double TAU = StrictMath.PI * 2.0;
    private static final long X_SALT = 0x9E3779B97F4A7C15L;
    private static final long Z_SALT = 0xC2B2AE3D27D4EB4FL;
    private static final long OCTAVE_SALT = 0x165667B19E3779F9L;

    private MountainMath() {
    }

    public static TerrainSample sample(
            PlaceManager.Place place,
            int worldX,
            int worldZ,
            int originalSurfaceY,
            int maxBuildY
    ) {
        MutableErosion erosion = new MutableErosion();
        MutableTerrain terrain = new MutableTerrain();
        sampleInto(place, worldX, worldZ, originalSurfaceY, maxBuildY, erosion, terrain);
        return terrain.snapshot();
    }

    // 区块缓存保证每列只执行一次侵蚀计算。
    public static ChunkPlan planChunk(
            PlaceManager.Place place,
            int minWorldX,
            int minWorldZ,
            int maxBuildY,
            OriginalSurfaceSampler originalSurface
    ) {
        ChunkPlan plan = new ChunkPlan();
        MutableErosion erosion = new MutableErosion();
        MutableTerrain terrain = new MutableTerrain();
        for (int localZ = 0; localZ < CHUNK_SIDE; localZ++) {
            int worldZ = minWorldZ + localZ;
            for (int localX = 0; localX < CHUNK_SIDE; localX++) {
                int worldX = minWorldX + localX;
                int index = columnIndex(localX, localZ);
                sampleInto(
                        place,
                        worldX,
                        worldZ,
                        originalSurface.sample(worldX, worldZ),
                        maxBuildY,
                        erosion,
                        terrain
                );
                plan.targetSurfaceY[index] = terrain.targetSurfaceY;
                plan.ridgeMap[index] = (float) terrain.ridgeMap;
                plan.slope[index] = (float) terrain.slope;
                plan.protectionMask[index] = (float) terrain.protectionMask;
                plan.footprintInfluence[index] = (float) terrain.footprintInfluence;
            }
        }
        return plan;
    }

    public static RoutePoint ascentRoutePoint(PlaceManager.Place place, double radius) {
        double clampedRadius = StrictMath.max(0.0, StrictMath.min(place.mountainOuterRadius(), radius));
        double angle = ascentRouteAngle(place, clampedRadius);
        return new RoutePoint(
                place.centerX() + (int) StrictMath.round(StrictMath.cos(angle) * clampedRadius),
                place.centerZ() + (int) StrictMath.round(StrictMath.sin(angle) * clampedRadius)
        );
    }

    private static void sampleInto(
            PlaceManager.Place place,
            int worldX,
            int worldZ,
            int originalSurfaceY,
            int maxBuildY,
            MutableErosion erosion,
            MutableTerrain output
    ) {
        double localX = (double) worldX - place.centerX();
        double localZ = (double) worldZ - place.centerZ();
        double radius = StrictMath.hypot(localX, localZ);
        if (radius >= place.mountainOuterRadius()) {
            output.set(originalSurfaceY, originalSurfaceY, 0.0, 0.0, 0.0, 0.0, 0.0);
            return;
        }

        double macro = continuousBaseHeight(place, worldX, worldZ, originalSurfaceY, maxBuildY);
        double macroXMinus = continuousBaseHeight(place, worldX - 1.0, worldZ, originalSurfaceY, maxBuildY);
        double macroXPlus = continuousBaseHeight(place, worldX + 1.0, worldZ, originalSurfaceY, maxBuildY);
        double macroZMinus = continuousBaseHeight(place, worldX, worldZ - 1.0, originalSurfaceY, maxBuildY);
        double macroZPlus = continuousBaseHeight(place, worldX, worldZ + 1.0, originalSurfaceY, maxBuildY);
        double gradientX = (macroXPlus - macroXMinus) * 0.5;
        double gradientZ = (macroZPlus - macroZMinus) * 0.5;

        double totalRelief = StrictMath.max(0.0, place.summitY() - place.baseY());
        double erosionStrength = erosionAmplitudeBudget(place, macro, originalSurfaceY, maxBuildY);
        double fadeTarget = totalRelief <= 1.0E-9
                ? 0.0
                : clamp((macro - place.baseY()) / totalRelief * 2.0 - 1.0, -1.0, 1.0);

        sampleErosion(
                place.mountainSeed(),
                worldX,
                worldZ,
                gradientX,
                gradientZ,
                fadeTarget,
                erosionStrength,
                erosion
        );

        double edgeDistance = place.mountainOuterRadius() - radius;
        double outerEdgeProtection = smootherstep(edgeDistance / place.outerBlendWidth());
        double summitProtection = smootherstep(
                (radius - EROSION.summitProtectionRadius()) / EROSION.summitProtectionRadius());
        double protection = outerEdgeProtection
                * summitProtection
                * ascentErosionMultiplier(place, worldX, worldZ);

        double appliedOffset = erosion.heightOffset * protection;
        double continuous = StrictMath.max(originalSurfaceY, macro + appliedOffset);
        double finalSlopeX = gradientX + (erosion.slopeX - gradientX) * protection;
        double finalSlopeZ = gradientZ + (erosion.slopeZ - gradientZ) * protection;
        output.set(
                (int) StrictMath.round(continuous),
                continuous,
                appliedOffset,
                StrictMath.hypot(finalSlopeX, finalSlopeZ),
                erosion.ridgeMap * protection,
                protection,
                footprintInfluence(place, worldX, worldZ)
        );
    }

    static double footprintInfluence(PlaceManager.Place place, double worldX, double worldZ) {
        double localX = worldX - place.centerX();
        double localZ = worldZ - place.centerZ();
        double radialDistance = StrictMath.hypot(localX, localZ);
        if (radialDistance >= place.mountainOuterRadius()) {
            return 0.0;
        }
        double angle = radialDistance < 1.0E-9 ? 0.0 : StrictMath.atan2(localZ, localX);
        double edgeDistance = outlineRadius(place, angle) - radialDistance;
        return edgeDistance >= place.outerBlendWidth()
                ? 1.0
                : smootherstep(edgeDistance / place.outerBlendWidth());
    }

    private static double continuousBaseHeight(
            PlaceManager.Place place,
            double worldX,
            double worldZ,
            double originalSurfaceY,
            int maxBuildY
    ) {
        double localX = worldX - place.centerX();
        double localZ = worldZ - place.centerZ();
        double radialDistance = StrictMath.hypot(localX, localZ);
        if (radialDistance >= place.mountainOuterRadius()) {
            return originalSurfaceY;
        }

        double angle = radialDistance < 1.0E-9 ? 0.0 : StrictMath.atan2(localZ, localX);
        double phase = ((place.mountainSeed() >>> 11) * 0x1.0p-53) * TAU;
        double outlineRadius = outlineRadius(place, angle);
        double normalizedDistance = StrictMath.min(1.0, radialDistance / outlineRadius);
        double distanceScale = 1.0
                + StrictMath.cos(angle * 3.0 + phase) * PRIMARY_BUTTRESS_FRACTION
                + StrictMath.cos(angle * 6.0 - phase * 0.45) * SECONDARY_BUTTRESS_FRACTION
                + StrictMath.cos(angle - phase * 0.30) * FLANK_ASYMMETRY_FRACTION;
        double peak = 1.0 - smootherstep(normalizedDistance * distanceScale);
        double canonicalY = lerp(
                place.baseY(),
                StrictMath.min(place.summitY(), maxBuildY),
                peak
        );
        double edgeDistance = outlineRadius - radialDistance;
        double footprint = edgeDistance >= place.outerBlendWidth()
                ? 1.0
                : smootherstep(edgeDistance / place.outerBlendWidth());
        return StrictMath.max(originalSurfaceY, lerp(originalSurfaceY, canonicalY, footprint));
    }

    private static double outlineRadius(PlaceManager.Place place, double angle) {
        double phase = ((place.mountainSeed() >>> 11) * 0x1.0p-53) * TAU;
        return place.mountainOuterRadius() * (0.90
                + StrictMath.sin(angle * 2.0 + phase) * 0.060
                + StrictMath.sin(angle * 3.0 - phase * 0.7) * 0.025
                + StrictMath.sin(angle * 5.0 + phase * 0.4) * 0.010);
    }

    private static double erosionAmplitudeBudget(
            PlaceManager.Place place,
            double macroSurfaceY,
            double originalSurfaceY,
            int maxBuildY
    ) {
        double totalRelief = StrictMath.max(0.0, place.summitY() - place.baseY());
        double localRelief = StrictMath.max(0.0, macroSurfaceY - originalSurfaceY);
        double summitHeadroom = StrictMath.max(
                0.0,
                StrictMath.min(place.summitY(), maxBuildY) - macroSurfaceY
        );
        return StrictMath.min(
                StrictMath.min(
                        totalRelief * EROSION.totalReliefFraction(),
                        localRelief * EROSION.localReliefFraction()
                ),
                summitHeadroom
        );
    }

    private static double ascentErosionMultiplier(
            PlaceManager.Place place,
            double worldX,
            double worldZ
    ) {
        double localX = worldX - place.centerX();
        double localZ = worldZ - place.centerZ();
        double radius = StrictMath.hypot(localX, localZ);
        if (radius < 1.0E-9) {
            return 0.0;
        }
        double actualAngle = StrictMath.atan2(localZ, localX);
        double routeAngle = ascentRouteAngle(place, radius);
        double angularDelta = StrictMath.atan2(
                StrictMath.sin(actualAngle - routeAngle),
                StrictMath.cos(actualAngle - routeAngle)
        );
        double lateralDistance = StrictMath.abs(angularDelta) * StrictMath.max(radius, 1.0);
        double halfWidth = EROSION.ascentHalfWidth();
        return smootherstep((lateralDistance - halfWidth) / halfWidth);
    }

    private static double ascentRouteAngle(PlaceManager.Place place, double radius) {
        double seedAngle = ((place.mountainSeed() >>> 11) * 0x1.0p-53) * TAU;
        double progress = 1.0 - clamp01(radius / place.mountainOuterRadius());
        return seedAngle + progress * 0.72 + StrictMath.sin(progress * StrictMath.PI) * 0.18;
    }

    // 四个 Octave 可独立按坐标求值；后一层使用前一层修正后的坡向形成稳定分叉。
    private static void sampleErosion(
            long seed,
            double worldX,
            double worldZ,
            double inputGradientX,
            double inputGradientZ,
            double fadeTarget,
            double heightStrength,
            MutableErosion output
    ) {
        double gradientX = inputGradientX;
        double gradientZ = inputGradientZ;
        double outputSlopeX = gradientX;
        double outputSlopeZ = gradientZ;
        double scale = EROSION.largestScale();
        double amplitude = 1.0;
        double amplitudeSum = 0.0;
        double offset = 0.0;
        double ridge = 0.0;
        double ridgeWeight = 0.0;
        double detailMask = 1.0;

        for (int octave = 0; octave < EROSION.octaves(); octave++) {
            double slope = StrictMath.hypot(gradientX, gradientZ);
            double directionX;
            double directionZ;
            if (slope > 1.0E-9) {
                directionX = gradientX / slope;
                directionZ = gradientZ / slope;
            } else {
                double fallback = hashUnit(seed, octave, 0L) * StrictMath.PI;
                directionX = StrictMath.cos(fallback);
                directionZ = StrictMath.sin(fallback);
            }

            long octaveSeed = seed + OCTAVE_SALT * (octave + 1L);
            double cellSize = scale * EROSION.cellScale();
            double pointX = worldX / cellSize;
            double pointZ = worldZ / cellSize;
            long cellX = fastFloor(pointX);
            long cellZ = fastFloor(pointZ);
            double fractionX = pointX - cellX;
            double fractionZ = pointZ - cellZ;
            double acrossSlopeX = -directionZ;
            double acrossSlopeZ = directionX;
            double sideX = acrossSlopeX * EROSION.cellScale() * TAU;
            double sideZ = acrossSlopeZ * EROSION.cellScale() * TAU;
            double cosineSum = 0.0;
            double sineSum = 0.0;
            double weightSum = 0.0;

            for (int offsetZ = -1; offsetZ <= 2; offsetZ++) {
                for (int offsetX = -1; offsetX <= 2; offsetX++) {
                    long gridX = cellX + offsetX;
                    long gridZ = cellZ + offsetZ;
                    double pivotX = hashUnit(octaveSeed, gridX, gridZ) * 0.45;
                    double pivotZ = hashUnit(octaveSeed ^ Z_SALT, gridX, gridZ) * 0.45;
                    double fromPivotX = fractionX - offsetX - pivotX;
                    double fromPivotZ = fractionZ - offsetZ - pivotZ;
                    double squaredDistance = fromPivotX * fromPivotX + fromPivotZ * fromPivotZ;
                    double weight = StrictMath.max(
                            0.0,
                            StrictMath.exp(-squaredDistance * 2.0) - 0.01111
                    );
                    double phase = fromPivotX * sideX + fromPivotZ * sideZ + TAU * 0.25;
                    cosineSum += StrictMath.cos(phase) * weight;
                    sineSum += StrictMath.sin(phase) * weight;
                    weightSum += weight;
                }
            }

            double blendedCosine = cosineSum / weightSum;
            double blendedSine = sineSum / weightSum;
            double magnitude = StrictMath.hypot(blendedCosine, blendedSine);
            double divisor = StrictMath.max(1.0 - EROSION.normalization(), magnitude);
            double cosine = blendedCosine / divisor;
            double sine = blendedSine / divisor;

            double slopeRatio = clamp01(slope / EROSION.referenceSlope());
            double slopeMask = 1.0 - (1.0 - slopeRatio) * (1.0 - slopeRatio);
            double octaveMask = slopeMask * detailMask;
            double gully = lerp(fadeTarget, cosine, octaveMask);

            offset += gully * amplitude;
            amplitudeSum += amplitude;
            ridge += gully * amplitude;
            ridgeWeight += amplitude;

            double derivative = -sine * TAU / scale * amplitude * heightStrength;
            outputSlopeX += derivative * acrossSlopeX * slopeMask;
            outputSlopeZ += derivative * acrossSlopeZ * slopeMask;
            gradientX += derivative * acrossSlopeX;
            gradientZ += derivative * acrossSlopeZ;

            double ridgeOrGullySlope = clamp01(StrictMath.abs(sine) * 1.6);
            detailMask *= 1.0 - (1.0 - ridgeOrGullySlope) * (1.0 - ridgeOrGullySlope);
            fadeTarget = gully;
            scale /= EROSION.lacunarity();
            amplitude *= EROSION.persistence();
        }

        output.heightOffset = (amplitudeSum == 0.0 ? 0.0 : offset / amplitudeSum) * heightStrength;
        output.slopeX = outputSlopeX;
        output.slopeZ = outputSlopeZ;
        output.ridgeMap = ridgeWeight == 0.0 ? 0.0 : clamp(ridge / ridgeWeight, -1.0, 1.0);
    }

    private static long fastFloor(double value) {
        long integer = (long) value;
        return value < integer ? integer - 1L : integer;
    }

    private static double hashUnit(long seed, long x, long z) {
        long value = seed ^ x * X_SALT ^ z * Z_SALT;
        value ^= value >>> 30;
        value *= 0xBF58476D1CE4E5B9L;
        value ^= value >>> 27;
        value *= 0x94D049BB133111EBL;
        value ^= value >>> 31;
        return ((value >>> 11) * 0x1.0p-53) * 2.0 - 1.0;
    }

    private static int columnIndex(int localX, int localZ) {
        if ((localX | localZ) < 0 || localX >= CHUNK_SIDE || localZ >= CHUNK_SIDE) {
            throw new IndexOutOfBoundsException("local column is outside 16x16 chunk");
        }
        return localZ * CHUNK_SIDE + localX;
    }

    private static double smootherstep(double value) {
        double t = clamp01(value);
        return t * t * t * (t * (t * 6.0 - 15.0) + 10.0);
    }

    private static double clamp01(double value) {
        return clamp(value, 0.0, 1.0);
    }

    private static double clamp(double value, double min, double max) {
        return StrictMath.max(min, StrictMath.min(max, value));
    }

    private static double lerp(double from, double to, double amount) {
        return from + (to - from) * amount;
    }

    public record TerrainSample(
            int targetSurfaceY,
            double continuousSurfaceY,
            double erosionOffset,
            double slope,
            double ridgeMap,
            double protectionMask,
            double footprintInfluence
    ) {
    }

    public record RoutePoint(int worldX, int worldZ) {
    }

    public static final class ChunkPlan {
        private final int[] targetSurfaceY = new int[CHUNK_COLUMNS];
        private final float[] ridgeMap = new float[CHUNK_COLUMNS];
        private final float[] slope = new float[CHUNK_COLUMNS];
        private final float[] protectionMask = new float[CHUNK_COLUMNS];
        private final float[] footprintInfluence = new float[CHUNK_COLUMNS];

        private ChunkPlan() {
        }

        public int targetSurfaceY(int localX, int localZ) {
            return targetSurfaceY[columnIndex(localX, localZ)];
        }

        public float ridgeMap(int localX, int localZ) {
            return ridgeMap[columnIndex(localX, localZ)];
        }

        public float slope(int localX, int localZ) {
            return slope[columnIndex(localX, localZ)];
        }

        public float protectionMask(int localX, int localZ) {
            return protectionMask[columnIndex(localX, localZ)];
        }

        public float footprintInfluence(int localX, int localZ) {
            return footprintInfluence[columnIndex(localX, localZ)];
        }

        public long fingerprint() {
            long hash = 0xCBF29CE484222325L;
            for (int index = 0; index < CHUNK_COLUMNS; index++) {
                hash = mix(hash, targetSurfaceY[index]);
                hash = mix(hash, Float.floatToRawIntBits(ridgeMap[index]));
                hash = mix(hash, Float.floatToRawIntBits(slope[index]));
                hash = mix(hash, Float.floatToRawIntBits(protectionMask[index]));
                hash = mix(hash, Float.floatToRawIntBits(footprintInfluence[index]));
            }
            return hash;
        }

        private static long mix(long hash, int value) {
            hash ^= value & 0xFFFF_FFFFL;
            return hash * 0x100000001B3L;
        }
    }

    @FunctionalInterface
    public interface OriginalSurfaceSampler {
        int sample(int worldX, int worldZ);
    }

    private record ErosionProfile(
            int octaves,
            double largestScale,
            double lacunarity,
            double persistence,
            double cellScale,
            double normalization,
            double totalReliefFraction,
            double localReliefFraction,
            double referenceSlope,
            double summitProtectionRadius,
            double ascentHalfWidth
    ) {
        private ErosionProfile {
            if (octaves < 1 || octaves > 8) {
                throw new IllegalArgumentException("octaves must be in [1, 8]");
            }
            if (!(largestScale > 0.0) || !(lacunarity > 1.0)) {
                throw new IllegalArgumentException("invalid erosion scale parameters");
            }
            if (!(persistence > 0.0 && persistence < 1.0)
                    || !(cellScale > 0.0)
                    || !(normalization > 0.0 && normalization <= 1.0)) {
                throw new IllegalArgumentException("invalid erosion octave parameters");
            }
            if (!(totalReliefFraction >= 0.0 && totalReliefFraction <= 1.0)
                    || !(localReliefFraction >= 0.0 && localReliefFraction <= 1.0)
                    || !(referenceSlope > 0.0)) {
                throw new IllegalArgumentException("invalid erosion strength parameters");
            }
            if (!(summitProtectionRadius > 0.0) || !(ascentHalfWidth > 0.0)) {
                throw new IllegalArgumentException("invalid Ebott erosion protection parameters");
            }
        }
    }

    private static final class MutableErosion {
        private double heightOffset;
        private double slopeX;
        private double slopeZ;
        private double ridgeMap;
    }

    private static final class MutableTerrain {
        private int targetSurfaceY;
        private double continuousSurfaceY;
        private double erosionOffset;
        private double slope;
        private double ridgeMap;
        private double protectionMask;
        private double footprintInfluence;

        private void set(
                int targetSurfaceY,
                double continuousSurfaceY,
                double erosionOffset,
                double slope,
                double ridgeMap,
                double protectionMask,
                double footprintInfluence
        ) {
            this.targetSurfaceY = targetSurfaceY;
            this.continuousSurfaceY = continuousSurfaceY;
            this.erosionOffset = erosionOffset;
            this.slope = slope;
            this.ridgeMap = ridgeMap;
            this.protectionMask = protectionMask;
            this.footprintInfluence = footprintInfluence;
        }

        private TerrainSample snapshot() {
            return new TerrainSample(
                    targetSurfaceY,
                    continuousSurfaceY,
                    erosionOffset,
                    slope,
                    ridgeMap,
                    protectionMask,
                    footprintInfluence
            );
        }
    }
}
