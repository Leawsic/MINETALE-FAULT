package cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.entity.monsternpc.client.gpu;

import cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.entity.monsternpc.network.SnowtownCrowdPopulationPayload;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.Arrays;
import java.util.Optional;

// 从已加载方块一次性捕获不可变的 2.5D 连通可走场。
final class SnowtownGpuCrowdStaticField {
    // 固定扇区以一像素对应一方块
    static final int SIZE = 128;
    static final int CELL_COUNT = SIZE * SIZE;
    // 目标纹理只保存屏幕外出生点
    static final int SPAWN_POINT_COUNT = 128;
    static final int FLOW_WIDTH = SIZE;
    static final int FLOW_HEIGHT = SIZE;
    static final float HALF_SIZE = SIZE * 0.5F;
    static final float HEIGHT_RANGE = 16.0F;
    static final float MAX_CLEARANCE = 8.0F;
    // 15 格覆盖方块光传播上限，同时作为遮挡变化影响天光的局部失效范围。
    static final int LIGHT_PROPAGATION_RADIUS = 15;
    private static final int PLANNING_CELL_SIZE =
            SIZE / SnowtownCrowdPopulationPayload.PLANNING_MASK_EDGE;

    // 允许完整一格台阶连通，并用小余量吸收碰撞面浮点误差。
    static final float MAX_STEP_HEIGHT = 1.01F;
    private static final float SUPPORT_MIN_WIDTH = 0.45F;
    private static final float AGENT_RADIUS = 0.24F;
    private static final float AGENT_HEIGHT = 1.82F;
    private static final float INTERACTION_MODEL_MAX_HEIGHT = 5.15F;
    private static final double INTERACTION_RAY_STEP = 0.25D;
    private static final double RENDER_HEIGHT_TRANSITION_BAND = 0.14D;
    private static final double COLLISION_EPSILON = 0.002D;
    private static final int[] CARDINAL_X = {1, -1, 0, 0};
    private static final int[] CARDINAL_Z = {0, 0, 1, -1};

    private final int originBlockX;
    private final int originBlockZ;
    private final float baseHeight;
    private final long[] planningMaskWords;
    private final boolean[] walkable;
    private final float[] surfaceHeights;
    private final float[] clearance;
    private final int[] fieldPixels;
    private final int[] lightPixels;
    private final int[] flowPixels;
    private final int[] destinationPixels;
    private final int walkableCount;
    private final int unloadedCellCount;
    private final float minimumHeight;
    private final float maximumHeight;
    private final int minimumWalkableX;
    private final int maximumWalkableX;
    private final int mainStreamCellCount;
    private final int tributaryCellCount;
    private final int circulationCount;
    private final long captureNanos;

    SnowtownGpuCrowdStaticField(
            int originBlockX,
            int originBlockZ,
            float baseHeight,
            long[] planningMaskWords,
            boolean[] walkable,
            float[] surfaceHeights,
            float[] clearance,
            int[] fieldPixels,
            int[] lightPixels,
            int[] flowPixels,
            int[] destinationPixels,
            int walkableCount,
            int unloadedCellCount,
            float minimumHeight,
            float maximumHeight,
            int minimumWalkableX,
            int maximumWalkableX,
            int mainStreamCellCount,
            int tributaryCellCount,
            int circulationCount,
            long captureNanos
    ) {
        this.originBlockX = originBlockX;
        this.originBlockZ = originBlockZ;
        this.baseHeight = baseHeight;
        this.planningMaskWords = planningMaskWords.clone();
        this.walkable = walkable;
        this.surfaceHeights = surfaceHeights;
        this.clearance = clearance;
        this.fieldPixels = fieldPixels;
        this.lightPixels = lightPixels;
        this.flowPixels = flowPixels;
        this.destinationPixels = destinationPixels;
        this.walkableCount = walkableCount;
        this.unloadedCellCount = unloadedCellCount;
        this.minimumHeight = minimumHeight;
        this.maximumHeight = maximumHeight;
        this.minimumWalkableX = minimumWalkableX;
        this.maximumWalkableX = maximumWalkableX;
        this.mainStreamCellCount = mainStreamCellCount;
        this.tributaryCellCount = tributaryCellCount;
        this.circulationCount = circulationCount;
        this.captureNanos = captureNanos;
    }

    // 仅在覆盖 Chunk 全部就绪后捕获扇区
    static Optional<Geometry> captureSectorGeometry(
            ClientLevel level,
            int sectorX,
            int sectorZ,
            int preferredWorldX,
            float preferredHeight,
            int preferredWorldZ,
            long[] planningMaskWords
    ) {
        requirePlanningMask(planningMaskWords);
        int originBlockX = Math.multiplyExact(sectorX, SIZE);
        int originBlockZ = Math.multiplyExact(sectorZ, SIZE);
        if (!hasAllSectorChunks(level, originBlockX, originBlockZ)) {
            return Optional.empty();
        }
        Optional<SurfaceSeed> seed = findSectorSeed(
                level,
                originBlockX,
                originBlockZ,
                preferredWorldX,
                preferredWorldZ,
                preferredHeight,
                planningMaskWords
        );
        return seed.map(surfaceSeed -> captureGeometry(
                level,
                originBlockX,
                originBlockZ,
                surfaceSeed.height(),
                surfaceSeed,
                planningMaskWords.clone()
        ));
    }

    Optional<Geometry> recaptureGeometry(ClientLevel level) {
        Optional<SurfaceSeed> seed = findRecaptureSeed(level);
        return seed.map(surfaceSeed -> captureGeometry(
                level,
                this.originBlockX,
                this.originBlockZ,
                this.baseHeight,
                surfaceSeed,
                this.planningMaskWords.clone()));
    }

    boolean hasAllChunks(ClientLevel level) {
        return hasAllSectorChunks(level, this.originBlockX, this.originBlockZ);
    }

    SnowtownGpuCrowdStaticField withGeometry(Geometry geometry) {
        requireSameLayout(geometry);
        return new SnowtownGpuCrowdStaticField(
                geometry.originBlockX(),
                geometry.originBlockZ(),
                geometry.baseHeight(),
                geometry.planningMaskWords(),
                geometry.walkable(),
                geometry.surfaceHeights(),
                geometry.clearance(),
                geometry.fieldPixels(),
                geometry.lightPixels(),
                this.flowPixels,
                this.destinationPixels,
                geometry.walkableCount(),
                geometry.unloadedCellCount(),
                geometry.minimumHeight(),
                geometry.maximumHeight(),
                geometry.minimumWalkableX(),
                geometry.maximumWalkableX(),
                this.mainStreamCellCount,
                this.tributaryCellCount,
                this.circulationCount,
                geometry.captureNanos());
    }

    boolean mayBeAffectedBy(BlockPos position) {
        return position.getX() >= this.originBlockX
                && position.getX() < this.originBlockX + SIZE
                && position.getZ() >= this.originBlockZ
                && position.getZ() < this.originBlockZ + SIZE
                && position.getY() >= this.baseHeight - HEIGHT_RANGE - 2.0F
                && position.getY() <= this.baseHeight + HEIGHT_RANGE + AGENT_HEIGHT + 2.0F;
    }

    Optional<LightRegion> lightRegionAffectedBy(BlockPos position) {
        int minimumX = Math.max(
                0,
                position.getX() - LIGHT_PROPAGATION_RADIUS - this.originBlockX);
        int maximumX = Math.min(
                SIZE - 1,
                position.getX() + LIGHT_PROPAGATION_RADIUS - this.originBlockX);
        int minimumZ = Math.max(
                0,
                position.getZ() - LIGHT_PROPAGATION_RADIUS - this.originBlockZ);
        int maximumZ = Math.min(
                SIZE - 1,
                position.getZ() + LIGHT_PROPAGATION_RADIUS - this.originBlockZ);
        if (minimumX > maximumX || minimumZ > maximumZ) {
            return Optional.empty();
        }
        return Optional.of(new LightRegion(minimumX, minimumZ, maximumX, maximumZ));
    }

    LightRefresh refreshLight(ClientLevel level, LightRegion region) {
        long started = System.nanoTime();
        int[] refreshedPixels = this.lightPixels.clone();
        BlockPos.MutableBlockPos lightPosition = new BlockPos.MutableBlockPos();
        int sampledCells = 0;
        int changedCells = 0;
        for (int localZ = region.minimumZ(); localZ <= region.maximumZ(); localZ++) {
            for (int localX = region.minimumX(); localX <= region.maximumX(); localX++) {
                int cell = index(localX, localZ);
                if (!this.walkable[cell]) {
                    continue;
                }
                sampledCells++;
                lightPosition.set(
                        this.originBlockX + localX,
                        Mth.floor(this.surfaceHeights[cell] + 0.01F),
                        this.originBlockZ + localZ);
                int refreshed = SnowtownGpuCrowdFieldCompiler.encodeLightPixel(
                        level.getBrightness(LightLayer.BLOCK, lightPosition),
                        level.getBrightness(LightLayer.SKY, lightPosition));
                if (refreshedPixels[cell] != refreshed) {
                    refreshedPixels[cell] = refreshed;
                    changedCells++;
                }
            }
        }
        SnowtownGpuCrowdStaticField refreshedField = changedCells == 0
                ? this
                : withLightPixels(refreshedPixels);
        return new LightRefresh(
                refreshedField,
                sampledCells,
                changedCells,
                System.nanoTime() - started);
    }

    SnowtownGpuCrowdStaticField withLightPixels(int[] refreshedPixels) {
        if (refreshedPixels.length != CELL_COUNT) {
            throw new IllegalArgumentException("Snowtown GPU 光照场尺寸不匹配");
        }
        return new SnowtownGpuCrowdStaticField(
                this.originBlockX,
                this.originBlockZ,
                this.baseHeight,
                this.planningMaskWords,
                this.walkable,
                this.surfaceHeights,
                this.clearance,
                this.fieldPixels,
                refreshedPixels,
                this.flowPixels,
                this.destinationPixels,
                this.walkableCount,
                this.unloadedCellCount,
                this.minimumHeight,
                this.maximumHeight,
                this.minimumWalkableX,
                this.maximumWalkableX,
                this.mainStreamCellCount,
                this.tributaryCellCount,
                this.circulationCount,
                this.captureNanos);
    }

    private static Geometry captureGeometry(
            ClientLevel level,
            int originBlockX,
            int originBlockZ,
            float baseHeight,
            SurfaceSeed seed,
            long[] planningMaskWords
    ) {
        requirePlanningMask(planningMaskWords);
        if (!isPlanningCellAllowed(
                planningMaskWords,
                seed.localX(),
                seed.localZ())) {
            throw new IllegalArgumentException("Snowtown GPU 场种子不在规划可走面内");
        }
        long started = System.nanoTime();
        boolean[] walkable = new boolean[CELL_COUNT];
        float[] heights = new float[CELL_COUNT];
        Arrays.fill(heights, Float.NaN);
        int[] queue = new int[CELL_COUNT];
        int queueHead = 0;
        int queueTail = 0;
        int seedIndex = index(seed.localX(), seed.localZ());
        walkable[seedIndex] = true;
        heights[seedIndex] = seed.height();
        queue[queueTail++] = seedIndex;

        int unloadedCells = 0;
        boolean[] unloadedSeen = new boolean[CELL_COUNT];
        float minimumHeight = seed.height();
        float maximumHeight = seed.height();
        while (queueHead < queueTail) {
            int currentIndex = queue[queueHead++];
            int localX = currentIndex % SIZE;
            int localZ = currentIndex / SIZE;
            float currentHeight = heights[currentIndex];
            for (int direction = 0; direction < CARDINAL_X.length; direction++) {
                int nextX = localX + CARDINAL_X[direction];
                int nextZ = localZ + CARDINAL_Z[direction];
                if (!inside(nextX, nextZ)
                        || !isPlanningCellAllowed(planningMaskWords, nextX, nextZ)) {
                    continue;
                }
                int nextIndex = index(nextX, nextZ);
                if (walkable[nextIndex]) {
                    continue;
                }
                int worldX = originBlockX + nextX;
                int worldZ = originBlockZ + nextZ;
                if (!level.getChunkSource().hasChunk(
                        SectionPos.blockToSectionCoord(worldX),
                        SectionPos.blockToSectionCoord(worldZ))) {
                    if (!unloadedSeen[nextIndex]) {
                        unloadedSeen[nextIndex] = true;
                        unloadedCells++;
                    }
                    continue;
                }
                Optional<Float> nextHeight = findSurfaceNear(level, worldX, worldZ, currentHeight);
                if (nextHeight.isEmpty()
                        || Math.abs(nextHeight.get() - baseHeight) >= HEIGHT_RANGE - 0.5F) {
                    continue;
                }
                float height = nextHeight.get();
                walkable[nextIndex] = true;
                heights[nextIndex] = height;
                queue[queueTail++] = nextIndex;
                minimumHeight = Math.min(minimumHeight, height);
                maximumHeight = Math.max(maximumHeight, height);
            }
        }

        float[] clearance = SnowtownGpuCrowdFieldCompiler.buildClearance(walkable);
        int[] fieldPixels = new int[CELL_COUNT];
        int[] lightPixels = new int[CELL_COUNT];
        BlockPos.MutableBlockPos lightPosition = new BlockPos.MutableBlockPos();
        int minimumWalkableX = SIZE;
        int maximumWalkableX = -1;
        for (int localZ = 0; localZ < SIZE; localZ++) {
            for (int localX = 0; localX < SIZE; localX++) {
                int cell = index(localX, localZ);
                if (walkable[cell]) {
                    minimumWalkableX = Math.min(minimumWalkableX, localX);
                    maximumWalkableX = Math.max(maximumWalkableX, localX);
                }
                fieldPixels[cell] = SnowtownGpuCrowdFieldCompiler.encodeFieldPixel(
                        walkable[cell], heights[cell], baseHeight, clearance[cell]);
                if (walkable[cell]) {
                    lightPosition.set(
                            originBlockX + localX,
                            Mth.floor(heights[cell] + 0.01F),
                            originBlockZ + localZ);
                    lightPixels[cell] = SnowtownGpuCrowdFieldCompiler.encodeLightPixel(
                            level.getBrightness(LightLayer.BLOCK, lightPosition),
                            level.getBrightness(LightLayer.SKY, lightPosition));
                }
            }
        }
        return new Geometry(
                originBlockX,
                originBlockZ,
                baseHeight,
                planningMaskWords,
                walkable,
                heights,
                clearance,
                fieldPixels,
                lightPixels,
                queueTail,
                unloadedCells,
                minimumHeight,
                maximumHeight,
                minimumWalkableX,
                maximumWalkableX,
                System.nanoTime() - started
        );
    }

    private Optional<SurfaceSeed> findRecaptureSeed(ClientLevel level) {
        int previousSeed = SnowtownGpuCrowdFieldCompiler.firstWalkableCell(this.walkable);
        if (previousSeed < 0) {
            return Optional.empty();
        }
        int previousSeedX = previousSeed % SIZE;
        int previousSeedZ = previousSeed / SIZE;
        for (int radius = 0; radius <= 8; radius++) {
            for (int offsetZ = -radius; offsetZ <= radius; offsetZ++) {
                for (int offsetX = -radius; offsetX <= radius; offsetX++) {
                    if (radius > 0 && Math.abs(offsetX) != radius && Math.abs(offsetZ) != radius) {
                        continue;
                    }
                    int localX = previousSeedX + offsetX;
                    int localZ = previousSeedZ + offsetZ;
                    if (!inside(localX, localZ)
                            || !isPlanningCellAllowed(
                                    this.planningMaskWords,
                                    localX,
                                    localZ)) {
                        continue;
                    }
                    Optional<Float> height = findSurfaceNear(
                            level,
                            this.originBlockX + localX,
                            this.originBlockZ + localZ,
                            this.baseHeight);
                    if (height.isPresent()
                            && Math.abs(height.get() - this.baseHeight) < HEIGHT_RANGE - 0.5F) {
                        return Optional.of(new SurfaceSeed(localX, localZ, height.get()));
                    }
                }
            }
        }
        return Optional.empty();
    }

    private void requireSameLayout(Geometry geometry) {
        if (geometry.originBlockX() != this.originBlockX
                || geometry.originBlockZ() != this.originBlockZ
                || geometry.baseHeight() != this.baseHeight
                || !Arrays.equals(
                        geometry.planningMaskWords(),
                        this.planningMaskWords)) {
            throw new IllegalArgumentException("动态导航几何必须保持原有纹理布局");
        }
    }

    Vec3 worldAnchor() {
        return new Vec3(
                this.originBlockX + HALF_SIZE,
                this.baseHeight,
                this.originBlockZ + HALF_SIZE
        );
    }

    int walkableCount() {
        return this.walkableCount;
    }

    String diagnostics() {
        return "field=" + SIZE + "x" + SIZE
                + ", walkable=" + this.walkableCount
                + ", unloaded=" + this.unloadedCellCount
                + ", height=" + formatHeight(this.minimumHeight) + ".." + formatHeight(this.maximumHeight)
                + ", flowX=" + this.minimumWalkableX + ".." + this.maximumWalkableX
                + ", cycles=" + this.circulationCount
                + ", main=" + this.mainStreamCellCount
                + ", tributaries=" + this.tributaryCellCount
                + ", spawnPoints=" + this.destinationPixels.length
                + ", capture=" + Math.round(this.captureNanos / 1_000_000.0D) + "ms";
    }

    boolean isWalkable(int localX, int localZ) {
        return this.walkable[index(localX, localZ)];
    }

    float surfaceHeight(int localX, int localZ) {
        return this.surfaceHeights[index(localX, localZ)];
    }

    // CPU 交互复现 draw shader 的脚底混合与 baseHeight 回退，保证命中高度对应画面。
    float renderedSurfaceHeight(double centeredX, double centeredZ) {
        double gridX = centeredX + HALF_SIZE;
        double gridZ = centeredZ + HALF_SIZE;
        int centerX = (int)Math.floor(gridX);
        int centerZ = (int)Math.floor(gridZ);
        double localX = gridX - Math.floor(gridX);
        double localZ = gridZ - Math.floor(gridZ);

        int lowX = centerX;
        int highX = centerX;
        int lowZ = centerZ;
        int highZ = centerZ;
        double blendX = 0.0D;
        double blendZ = 0.0D;
        if (localX < RENDER_HEIGHT_TRANSITION_BAND) {
            lowX--;
            blendX = 0.5D + 0.5D * smoothStep(
                    0.0D,
                    RENDER_HEIGHT_TRANSITION_BAND,
                    localX
            );
        } else if (localX > 1.0D - RENDER_HEIGHT_TRANSITION_BAND) {
            highX++;
            blendX = 0.5D * smoothStep(
                    1.0D - RENDER_HEIGHT_TRANSITION_BAND,
                    1.0D,
                    localX
            );
        }
        if (localZ < RENDER_HEIGHT_TRANSITION_BAND) {
            lowZ--;
            blendZ = 0.5D + 0.5D * smoothStep(
                    0.0D,
                    RENDER_HEIGHT_TRANSITION_BAND,
                    localZ
            );
        } else if (localZ > 1.0D - RENDER_HEIGHT_TRANSITION_BAND) {
            highZ++;
            blendZ = 0.5D * smoothStep(
                    1.0D - RENDER_HEIGHT_TRANSITION_BAND,
                    1.0D,
                    localZ
            );
        }

        int x0 = Math.clamp(lowX, 0, SIZE - 1);
        int x1 = Math.clamp(highX, 0, SIZE - 1);
        int z0 = Math.clamp(lowZ, 0, SIZE - 1);
        int z1 = Math.clamp(highZ, 0, SIZE - 1);
        double weight00 = (1.0D - blendX) * (1.0D - blendZ);
        double weight10 = blendX * (1.0D - blendZ);
        double weight01 = (1.0D - blendX) * blendZ;
        double weight11 = blendX * blendZ;
        double weightedHeight = 0.0D;
        double totalWeight = 0.0D;

        int cell00 = index(x0, z0);
        if (this.walkable[cell00]) {
            weightedHeight += this.surfaceHeights[cell00] * weight00;
            totalWeight += weight00;
        }
        int cell10 = index(x1, z0);
        if (this.walkable[cell10]) {
            weightedHeight += this.surfaceHeights[cell10] * weight10;
            totalWeight += weight10;
        }
        int cell01 = index(x0, z1);
        if (this.walkable[cell01]) {
            weightedHeight += this.surfaceHeights[cell01] * weight01;
            totalWeight += weight01;
        }
        int cell11 = index(x1, z1);
        if (this.walkable[cell11]) {
            weightedHeight += this.surfaceHeights[cell11] * weight11;
            totalWeight += weight11;
        }
        return totalWeight > 0.0001D
                ? (float)(weightedHeight / totalWeight)
                : this.baseHeight;
    }

    // CPU 粗筛可以有假阳性但不能漏掉大型外观
    boolean mayIntersectInteractionRay(Vec3 origin, Vec3 direction, double reach) {
        double directionLength = direction.length();
        if (reach <= 0.0D || directionLength < 1.0E-6D) {
            return false;
        }
        Vec3 stepDirection = direction.scale(1.0D / directionLength);
        int samples = Math.max(1, (int)Math.ceil(reach / INTERACTION_RAY_STEP));
        for (int sample = 0; sample <= samples; sample++) {
            double distance = Math.min(reach, sample * INTERACTION_RAY_STEP);
            Vec3 point = origin.add(stepDirection.scale(distance));
            int centerX = Mth.floor(point.x) - this.originBlockX;
            int centerZ = Mth.floor(point.z) - this.originBlockZ;
            for (int offsetZ = -1; offsetZ <= 1; offsetZ++) {
                for (int offsetX = -1; offsetX <= 1; offsetX++) {
                    int localX = centerX + offsetX;
                    int localZ = centerZ + offsetZ;
                    if (!inside(localX, localZ) || !isWalkable(localX, localZ)) {
                        continue;
                    }
                    float ground = surfaceHeight(localX, localZ);
                    if (point.y >= ground - 0.35D
                            && point.y <= ground + INTERACTION_MODEL_MAX_HEIGHT) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    float clearance(int localX, int localZ) {
        return this.clearance[index(localX, localZ)];
    }

    int[] fieldPixels() {
        return this.fieldPixels;
    }

    int[] lightPixels() {
        return this.lightPixels;
    }

    int[] flowPixels() {
        return this.flowPixels;
    }

    int[] destinationPixels() {
        return this.destinationPixels;
    }

    float baseHeight() {
        return this.baseHeight;
    }

    private static double smoothStep(double edge0, double edge1, double value) {
        double normalized = Math.clamp((value - edge0) / (edge1 - edge0), 0.0D, 1.0D);
        return normalized * normalized * (3.0D - 2.0D * normalized);
    }

    private static boolean hasAllSectorChunks(
            ClientLevel level,
            int originBlockX,
            int originBlockZ
    ) {
        int minimumChunkX = SectionPos.blockToSectionCoord(originBlockX);
        int maximumChunkX = SectionPos.blockToSectionCoord(originBlockX + SIZE - 1);
        int minimumChunkZ = SectionPos.blockToSectionCoord(originBlockZ);
        int maximumChunkZ = SectionPos.blockToSectionCoord(originBlockZ + SIZE - 1);
        for (int chunkZ = minimumChunkZ; chunkZ <= maximumChunkZ; chunkZ++) {
            for (int chunkX = minimumChunkX; chunkX <= maximumChunkX; chunkX++) {
                if (!level.getChunkSource().hasChunk(chunkX, chunkZ)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static Optional<SurfaceSeed> findSectorSeed(
            ClientLevel level,
            int originBlockX,
            int originBlockZ,
            int preferredWorldX,
            int preferredWorldZ,
            float preferredHeight,
            long[] planningMaskWords
    ) {
        int preferredLocalX = Math.clamp(preferredWorldX - originBlockX, 0, SIZE - 1);
        int preferredLocalZ = Math.clamp(preferredWorldZ - originBlockZ, 0, SIZE - 1);
        for (int radius = 0; radius <= SIZE; radius += 4) {
            for (int localZ = preferredLocalZ - radius;
                    localZ <= preferredLocalZ + radius;
                    localZ += 4) {
                for (int localX = preferredLocalX - radius;
                        localX <= preferredLocalX + radius;
                        localX += 4) {
                    if (radius > 0
                            && localX != preferredLocalX - radius
                            && localX != preferredLocalX + radius
                            && localZ != preferredLocalZ - radius
                            && localZ != preferredLocalZ + radius) {
                        continue;
                    }
                    if (!inside(localX, localZ)
                            || !isPlanningCellAllowed(
                                    planningMaskWords,
                                    localX,
                                    localZ)) {
                        continue;
                    }
                    Optional<Float> height = findSurface(
                            level,
                            originBlockX + localX,
                            originBlockZ + localZ,
                            preferredHeight + HEIGHT_RANGE * 0.5F,
                            preferredHeight - HEIGHT_RANGE * 0.5F,
                            false
                    );
                    if (height.isPresent()) {
                        return Optional.of(new SurfaceSeed(localX, localZ, height.get()));
                    }
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<Float> findSurfaceNear(
            ClientLevel level,
            int worldX,
            int worldZ,
            float targetHeight
    ) {
        return findSurface(
                level,
                worldX,
                worldZ,
                targetHeight + MAX_STEP_HEIGHT,
                targetHeight - MAX_STEP_HEIGHT,
                false
        );
    }

    private static Optional<Float> findSurface(
            ClientLevel level,
            int worldX,
            int worldZ,
            float maximumSurface,
            float minimumSurface,
            boolean preferHighest
    ) {
        int maximumBlockY = Math.min(level.getMaxY() - 1, Mth.floor(maximumSurface));
        int minimumBlockY = Math.max(level.getMinY(), Mth.floor(minimumSurface) - 1);
        float bestHeight = Float.NaN;
        float targetHeight = (maximumSurface + minimumSurface) * 0.5F;
        BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
        for (int blockY = maximumBlockY; blockY >= minimumBlockY; blockY--) {
            position.set(worldX, blockY, worldZ);
            var supportState = level.getBlockState(position);
            if (!isPedestrianSupport(level, position, supportState.is(Blocks.SNOW))) {
                continue;
            }
            VoxelShape shape = supportState.getCollisionShape(level, position);
            if (shape.isEmpty()) {
                continue;
            }
            for (AABB support : shape.toAabbs()) {
                if (support.minX > 0.5D || support.maxX < 0.5D
                        || support.minZ > 0.5D || support.maxZ < 0.5D
                        || support.getXsize() < SUPPORT_MIN_WIDTH
                        || support.getZsize() < SUPPORT_MIN_WIDTH) {
                    continue;
                }
                float surface = (float) (blockY + support.maxY);
                if (surface < minimumSurface || surface > maximumSurface
                        || !hasHeadroom(level, worldX, worldZ, surface)) {
                    continue;
                }
                if (preferHighest) {
                    return Optional.of(surface);
                }
                if (Float.isNaN(bestHeight)
                        || Math.abs(surface - targetHeight) < Math.abs(bestHeight - targetHeight)) {
                    bestHeight = surface;
                }
            }
        }
        return Float.isNaN(bestHeight) ? Optional.empty() : Optional.of(bestHeight);
    }

    private static boolean isPedestrianSupport(
            ClientLevel level,
            BlockPos position,
            boolean snowLayer
    ) {
        var supportState = level.getBlockState(position);
        if (supportState.is(BlockTags.LEAVES)
                || supportState.is(BlockTags.FENCES)
                || supportState.is(BlockTags.WALLS)
                || supportState.is(BlockTags.TRAPDOORS)) {
            return false;
        }
        if (!snowLayer) {
            return true;
        }
        var below = level.getBlockState(position.below());
        return !below.is(BlockTags.LEAVES)
                && !below.is(BlockTags.FENCES)
                && !below.is(BlockTags.WALLS)
                && !below.is(BlockTags.TRAPDOORS);
    }

    private static boolean hasHeadroom(ClientLevel level, int worldX, int worldZ, float surface) {
        double centerX = worldX + 0.5D;
        double centerZ = worldZ + 0.5D;
        AABB body = new AABB(
                centerX - AGENT_RADIUS,
                surface + COLLISION_EPSILON,
                centerZ - AGENT_RADIUS,
                centerX + AGENT_RADIUS,
                surface + AGENT_HEIGHT,
                centerZ + AGENT_RADIUS
        );
        return level.noBlockCollision(null, body);
    }

    private static int index(int localX, int localZ) {
        return localZ * SIZE + localX;
    }

    private static void requirePlanningMask(long[] planningMaskWords) {
        if (planningMaskWords == null
                || planningMaskWords.length
                != SnowtownCrowdPopulationPayload.PLANNING_MASK_WORDS
                || Arrays.stream(planningMaskWords).allMatch(word -> word == 0L)) {
            throw new IllegalArgumentException("Snowtown GPU 规划可走面掩码无效");
        }
    }

    private static boolean isPlanningCellAllowed(
            long[] planningMaskWords,
            int localX,
            int localZ
    ) {
        if (!inside(localX, localZ)) {
            return false;
        }
        int planningX = localX / PLANNING_CELL_SIZE;
        int planningZ = localZ / PLANNING_CELL_SIZE;
        int bit = planningZ * SnowtownCrowdPopulationPayload.PLANNING_MASK_EDGE
                + planningX;
        return (planningMaskWords[bit / Long.SIZE]
                & 1L << (bit % Long.SIZE)) != 0L;
    }

    private static boolean inside(int localX, int localZ) {
        return localX >= 0 && localX < SIZE && localZ >= 0 && localZ < SIZE;
    }

    private static String formatHeight(float height) {
        return String.format(java.util.Locale.ROOT, "%.2f", height);
    }

    record LightRegion(int minimumX, int minimumZ, int maximumX, int maximumZ) {
        LightRegion union(LightRegion other) {
            return new LightRegion(
                    Math.min(this.minimumX, other.minimumX),
                    Math.min(this.minimumZ, other.minimumZ),
                    Math.max(this.maximumX, other.maximumX),
                    Math.max(this.maximumZ, other.maximumZ));
        }
    }

    record LightRefresh(
            SnowtownGpuCrowdStaticField field,
            int sampledCells,
            int changedCells,
            long captureNanos
    ) {
    }

    record Geometry(
            int originBlockX,
            int originBlockZ,
            float baseHeight,
            long[] planningMaskWords,
            boolean[] walkable,
            float[] surfaceHeights,
            float[] clearance,
            int[] fieldPixels,
            int[] lightPixels,
            int walkableCount,
            int unloadedCellCount,
            float minimumHeight,
            float maximumHeight,
            int minimumWalkableX,
            int maximumWalkableX,
            long captureNanos
    ) {
    }

    private record SurfaceSeed(int localX, int localZ, float height) {
    }
}
