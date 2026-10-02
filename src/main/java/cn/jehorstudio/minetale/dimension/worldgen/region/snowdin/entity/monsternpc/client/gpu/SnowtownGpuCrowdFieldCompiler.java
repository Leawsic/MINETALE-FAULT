package cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.entity.monsternpc.client.gpu;

import cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.entity.monsternpc.client.gpu.SnowtownGpuCrowdStaticField.Geometry;
import cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.entity.monsternpc.client.gpu.SnowtownGpuDirectedFlowCompiler.DirectedFlow;
import net.minecraft.util.Mth;

import java.util.Arrays;

import static cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.entity.monsternpc.client.gpu.SnowtownGpuCrowdStaticField.CELL_COUNT;
import static cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.entity.monsternpc.client.gpu.SnowtownGpuCrowdStaticField.HALF_SIZE;
import static cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.entity.monsternpc.client.gpu.SnowtownGpuCrowdStaticField.HEIGHT_RANGE;
import static cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.entity.monsternpc.client.gpu.SnowtownGpuCrowdStaticField.MAX_CLEARANCE;
import static cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.entity.monsternpc.client.gpu.SnowtownGpuCrowdStaticField.SIZE;
import static cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.entity.monsternpc.client.gpu.SnowtownGpuCrowdStaticField.SPAWN_POINT_COUNT;

// 只将已捕获几何确定性编译为 GPU 静态场
final class SnowtownGpuCrowdFieldCompiler {
    private static final float MIN_DESTINATION_CLEARANCE = 0.65F;

    private SnowtownGpuCrowdFieldCompiler() {
    }

    static SnowtownGpuCrowdStaticField compile(Geometry geometry) {
        DirectedFlow flow = SnowtownGpuDirectedFlowCompiler.compile(
                geometry.walkable(),
                geometry.surfaceHeights(),
                geometry.clearance());
        return compileWithFlow(
                geometry,
                flow,
                selectDestinations(
                        geometry.walkable(),
                        geometry.clearance(),
                        flow.kind()));
    }

    static SnowtownGpuCrowdStaticField recompile(
            Geometry geometry,
            int[] previousDestinationPixels
    ) {
        DirectedFlow flow = SnowtownGpuDirectedFlowCompiler.compile(
                geometry.walkable(),
                geometry.surfaceHeights(),
                geometry.clearance());
        return compileWithFlow(
                geometry,
                flow,
                stabilizeDestinations(
                        geometry,
                        previousDestinationPixels,
                        flow.kind()));
    }

    private static SnowtownGpuCrowdStaticField compileWithFlow(
            Geometry geometry,
            DirectedFlow flow,
            int[] destinations
    ) {
        long started = System.nanoTime();
        boolean[] walkable = geometry.walkable();
        float[] heights = geometry.surfaceHeights();
        float[] clearance = geometry.clearance();
        int[] flowPixels = new int[CELL_COUNT];
        for (int cell = 0; cell < CELL_COUNT; cell++) {
            flowPixels[cell] = encodeFlowPixel(
                    flow.x()[cell],
                    flow.z()[cell],
                    flow.kind()[cell]);
        }
        int[] destinationPixels = new int[destinations.length];
        for (int destination = 0; destination < destinations.length; destination++) {
            int destinationCell = destinations[destination];
            destinationPixels[destination] = encodeDestinationPixel(
                    destinationCell % SIZE,
                    destinationCell / SIZE);
        }

        return new SnowtownGpuCrowdStaticField(
                geometry.originBlockX(),
                geometry.originBlockZ(),
                geometry.baseHeight(),
                geometry.planningMaskWords(),
                walkable,
                heights,
                clearance,
                geometry.fieldPixels(),
                geometry.lightPixels(),
                flowPixels,
                destinationPixels,
                geometry.walkableCount(),
                geometry.unloadedCellCount(),
                geometry.minimumHeight(),
                geometry.maximumHeight(),
                geometry.minimumWalkableX(),
                geometry.maximumWalkableX(),
                flow.mainStreamCellCount(),
                flow.tributaryCellCount(),
                flow.circulationCount(),
                geometry.captureNanos() + System.nanoTime() - started);
    }

    private static int[] stabilizeDestinations(
            Geometry geometry,
            int[] previousDestinationPixels,
            byte[] streamKind
    ) {
        int[] fresh = selectDestinations(
                geometry.walkable(),
                geometry.clearance(),
                streamKind);
        boolean hasStream = hasStream(streamKind);
        int[] stable = new int[SPAWN_POINT_COUNT];
        boolean[] used = new boolean[CELL_COUNT];
        for (int destination = 0; destination < stable.length; destination++) {
            int encoded = previousDestinationPixels[
                    destination % previousDestinationPixels.length];
            int previousX = encoded & 255;
            int previousZ = encoded >>> 8 & 255;
            boolean previousInside = previousX >= 0
                    && previousX < SIZE
                    && previousZ >= 0
                    && previousZ < SIZE;
            int previous = previousInside ? index(previousX, previousZ) : -1;
            if (previousInside
                    && geometry.walkable()[previous]
                    && (!hasStream || streamKind[previous] != 0)
                    && geometry.clearance()[previous] >= MIN_DESTINATION_CLEARANCE
                    && !used[previous]) {
                stable[destination] = previous;
                used[previous] = true;
                continue;
            }
            int best = -1;
            float bestDistanceSquared = Float.POSITIVE_INFINITY;
            for (int candidate : fresh) {
                if (used[candidate]) {
                    continue;
                }
                float distanceSquared = square(candidate % SIZE - previousX)
                        + square(candidate / SIZE - previousZ);
                if (distanceSquared < bestDistanceSquared) {
                    best = candidate;
                    bestDistanceSquared = distanceSquared;
                }
            }
            if (best < 0) {
                best = fresh[destination % fresh.length];
            }
            stable[destination] = best;
            used[best] = true;
        }
        return stable;
    }

    static float[] buildClearance(boolean[] walkable) {
        // 两次一维平方欧氏距离变换将全障碍逐格扫描降为线性处理。
        final double infinity = 1.0E9D;
        double[] source = new double[SIZE];
        double[] transformed = new double[SIZE];
        double[] horizontal = new double[CELL_COUNT];
        for (int z = 0; z < SIZE; z++) {
            for (int x = 0; x < SIZE; x++) {
                source[x] = walkable[index(x, z)] ? infinity : 0.0D;
            }
            squaredDistanceTransform(source, transformed);
            System.arraycopy(transformed, 0, horizontal, z * SIZE, SIZE);
        }

        double[] squaredDistance = new double[CELL_COUNT];
        for (int x = 0; x < SIZE; x++) {
            for (int z = 0; z < SIZE; z++) {
                source[z] = horizontal[index(x, z)];
            }
            squaredDistanceTransform(source, transformed);
            for (int z = 0; z < SIZE; z++) {
                squaredDistance[index(x, z)] = transformed[z];
            }
        }

        float[] result = new float[CELL_COUNT];
        for (int cell = 0; cell < CELL_COUNT; cell++) {
            if (!walkable[cell]) {
                continue;
            }
            int x = cell % SIZE;
            int z = cell / SIZE;
            float boundaryDistance = Math.min(
                    Math.min(x + 0.5F, SIZE - x - 0.5F),
                    Math.min(z + 0.5F, SIZE - z - 0.5F));
            double minimumSquared = Math.min(
                    boundaryDistance * boundaryDistance,
                    squaredDistance[cell]);
            result[cell] = Math.min((float) Math.sqrt(minimumSquared), MAX_CLEARANCE);
        }
        return result;
    }

    // 一维抛物线下包络，作为二维平方欧氏距离变换的单轴步骤。
    private static void squaredDistanceTransform(double[] source, double[] result) {
        int[] sites = new int[SIZE];
        double[] boundaries = new double[SIZE + 1];
        int envelope = 0;
        sites[0] = 0;
        boundaries[0] = Double.NEGATIVE_INFINITY;
        boundaries[1] = Double.POSITIVE_INFINITY;
        for (int point = 1; point < SIZE; point++) {
            double intersection;
            do {
                int site = sites[envelope];
                intersection = ((source[point] + square(point))
                        - (source[site] + square(site)))
                        / (2.0D * (point - site));
                if (intersection <= boundaries[envelope]) {
                    envelope--;
                } else {
                    break;
                }
            } while (envelope >= 0);
            envelope++;
            sites[envelope] = point;
            boundaries[envelope] = intersection;
            boundaries[envelope + 1] = Double.POSITIVE_INFINITY;
        }

        envelope = 0;
        for (int point = 0; point < SIZE; point++) {
            while (boundaries[envelope + 1] < point) {
                envelope++;
            }
            int site = sites[envelope];
            result[point] = square(point - site) + source[site];
        }
    }

    private static int[] selectDestinations(
            boolean[] walkable,
            float[] clearance,
            byte[] streamKind
    ) {
        int[] destinations = new int[SPAWN_POINT_COUNT];
        boolean[] selected = new boolean[CELL_COUNT];
        float[] nearestSquared = new float[CELL_COUNT];
        Arrays.fill(nearestSquared, Float.POSITIVE_INFINITY);
        boolean requireStream = hasStream(streamKind);
        int candidateCount = 0;
        int destinationCandidateCount = 0;
        for (int cell = 0; cell < CELL_COUNT; cell++) {
            if (isSpawnCandidate(walkable, streamKind, requireStream, cell)) {
                candidateCount++;
                if (clearance[cell] >= MIN_DESTINATION_CLEARANCE) {
                    destinationCandidateCount++;
                }
            }
        }

        int uniqueDestinationCount = Math.min(destinations.length, candidateCount);
        for (int destination = 0; destination < uniqueDestinationCount; destination++) {
            int bestCell = -1;
            float bestScore = Float.NEGATIVE_INFINITY;
            boolean requireViableClearance = destination
                    < Math.min(destinationCandidateCount, uniqueDestinationCount);
            for (int cell = 0; cell < CELL_COUNT; cell++) {
                if (!isSpawnCandidate(walkable, streamKind, requireStream, cell)
                        || selected[cell]
                        || requireViableClearance && clearance[cell] < MIN_DESTINATION_CLEARANCE) {
                    continue;
                }
                int x = cell % SIZE;
                int z = cell / SIZE;
                float score;
                if (destination == 0) {
                    float centerDistance = Mth.sqrt(
                            square(x + 0.5F - HALF_SIZE) + square(z + 0.5F - HALF_SIZE));
                    score = clearance[cell] * 5.0F - centerDistance * 0.15F;
                } else {
                    float openness = 0.65F + Math.min(clearance[cell] / 4.0F, 1.0F) * 0.35F;
                    score = nearestSquared[cell] * openness + clearance[cell] * 0.5F;
                }
                if (score > bestScore) {
                    bestScore = score;
                    bestCell = cell;
                }
            }
            bestCell = bestCell >= 0
                    ? bestCell
                    : firstSpawnCandidate(walkable, streamKind, requireStream);
            destinations[destination] = bestCell;
            selected[bestCell] = true;
            int bestX = bestCell % SIZE;
            int bestZ = bestCell / SIZE;
            for (int cell = 0; cell < CELL_COUNT; cell++) {
                if (!isSpawnCandidate(walkable, streamKind, requireStream, cell)
                        || selected[cell]) {
                    continue;
                }
                nearestSquared[cell] = Math.min(
                        nearestSquared[cell],
                        square(cell % SIZE - bestX) + square(cell / SIZE - bestZ));
            }
        }
        for (int destination = uniqueDestinationCount; destination < destinations.length; destination++) {
            destinations[destination] = destinations[destination % uniqueDestinationCount];
        }
        return destinations;
    }

    private static boolean hasStream(byte[] streamKind) {
        for (byte kind : streamKind) {
            if (kind != 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSpawnCandidate(
            boolean[] walkable,
            byte[] streamKind,
            boolean requireStream,
            int cell
    ) {
        return walkable[cell] && (!requireStream || streamKind[cell] != 0);
    }

    private static int firstSpawnCandidate(
            boolean[] walkable,
            byte[] streamKind,
            boolean requireStream
    ) {
        for (int cell = 0; cell < CELL_COUNT; cell++) {
            if (isSpawnCandidate(walkable, streamKind, requireStream, cell)) {
                return cell;
            }
        }
        return firstWalkableCell(walkable);
    }

    static int firstWalkableCell(boolean[] walkable) {
        for (int cell = 0; cell < CELL_COUNT; cell++) {
            if (walkable[cell]) {
                return cell;
            }
        }
        throw new IllegalStateException("可走面至少应包含起始格");
    }


    static int encodeFieldPixel(boolean walkable, float height, float baseHeight, float clearance) {
        int walkableByte = walkable ? 255 : 0;
        float encodedHeight = walkable
                ? Math.clamp((height - baseHeight) / (HEIGHT_RANGE * 2.0F) + 0.5F, 0.0F, 1.0F)
                : 0.5F;
        int height16 = Math.clamp(Math.round(encodedHeight * 65535.0F), 0, 65535);
        int heightHigh = height16 >>> 8;
        int heightLow = height16 & 0xFF;
        int clearanceByte = Math.clamp(Math.round(clearance / MAX_CLEARANCE * 255.0F), 0, 255);
        return walkableByte | heightHigh << 8 | heightLow << 16 | clearanceByte << 24;
    }

    static int encodeLightPixel(int blockLight, int skyLight) {
        int blockByte = Math.clamp(blockLight, 0, 15) * 17;
        int skyByte = Math.clamp(skyLight, 0, 15) * 17;
        return blockByte | skyByte << 8 | 255 << 24;
    }

    private static int encodeFlowPixel(float flowX, float flowZ, byte streamKind) {
        int streamByte = streamKind == 2 ? 255 : streamKind == 1 ? 128 : 0;
        return encodeSignedUnit(flowX)
                | encodeSignedUnit(flowZ) << 8
                | streamByte << 16
                | 255 << 24;
    }

    private static int encodeDestinationPixel(int localX, int localZ) {
        return localX | localZ << 8 | 255 << 24;
    }

    private static float square(float value) {
        return value * value;
    }

    private static double square(double value) {
        return value * value;
    }

    private static int encodeSignedUnit(float value) {
        return Math.clamp(Math.round((Math.clamp(value, -1.0F, 1.0F) * 0.5F + 0.5F) * 255.0F), 0, 255);
    }

    private static int index(int localX, int localZ) {
        return localZ * SIZE + localX;
    }

}
