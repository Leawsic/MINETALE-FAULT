package cn.jehorstudio.minetale.dimension.ebott.shaft;

import cn.jehorstudio.minetale.dimension.ebott.EbottData;
import cn.jehorstudio.minetale.dimension.ebott.EbottDestination;
import cn.jehorstudio.minetale.dimension.ebott.PlaceManager;
import cn.jehorstudio.minetale.dimension.worldgen.math.WorldgenMath;
import cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.settings.SnowdinTerrainSettings;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;

import java.util.function.IntBinaryOperator;

// 两端只提供中心与 Y 映射，竖井几何和材质必须统一从 ShaftData 采样。
public final class ShaftGenerator {
    private static final int SOURCE_SEAM_OPENING_HEIGHT = 5;
    private static final int CENTER_X_CHANNEL = 0x51A1;
    private static final int CENTER_Z_CHANNEL = 0x51A2;
    private static final int RADIUS_CHANNEL = 0x51A3;
    private static final int SNOWDIN_STALACTITE_CLEANUP_DEPTH = (int) Math.ceil(
            (SnowdinTerrainSettings.STALACTITE_LENGTH_BASE + SnowdinTerrainSettings.STALACTITE_LENGTH_RANGE)
                    * SnowdinTerrainSettings.STALACTITE_VISIBLE_LENGTH_FRACTION
    );

    private ShaftGenerator() {
    }

    // Origin Stage1 先保留空气体积，井壁由后续阶段统一覆盖。
    public static void carveTargetColumn(
            ChunkAccess chunk,
            ShaftData shaft,
            int targetCenterX,
            int targetCenterZ,
            int worldX,
            int worldZ,
            int minY,
            int maxY
    ) {
        ShaftData.Profile profile = shaft.profile();
        int localX = worldX - targetCenterX;
        int localZ = worldZ - targetCenterZ;
        if (!insideEnvelope(profile, localX, localZ)) {
            return;
        }

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = minY; y <= maxY; y++) {
            Classification classification = classifyTarget(profile, localX, y, localZ);
            if (classification == Classification.SHAFT_AIR
                    || classification == Classification.BARRIER) {
                chunk.setBlockState(cursor.set(worldX, y, worldZ), Blocks.AIR.defaultBlockState());
            }
        }
    }

    // Origin Stage3/Stage5 可重复恢复该阶段应有的空气、井壁和阻挡。
    public static void restoreTargetColumn(
            ChunkAccess chunk,
            ShaftData shaft,
            int targetCenterX,
            int targetCenterZ,
            int worldX,
            int worldZ,
            int minY,
            int maxY
    ) {
        ShaftData.Profile profile = shaft.profile();
        int localX = worldX - targetCenterX;
        int localZ = worldZ - targetCenterZ;
        if (!insideEnvelope(profile, localX, localZ)) {
            return;
        }

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = minY; y <= maxY; y++) {
            Classification classification = classifyTarget(profile, localX, y, localZ);
            BlockState generatedState = chunk.getBlockState(cursor.set(worldX, y, worldZ));
            BlockState desired = targetState(profile, classification, generatedState, y);
            if (desired != null) {
                chunk.setBlockState(cursor, desired);
            }
        }
    }

    // 源端竖井必须晚于山体和其他 Feature 写入。
    public static void writeSourceChunk(
            ChunkAccess chunk,
            EbottData.Snapshot snapshot,
            int minY,
            int maxY
    ) {
        PlaceManager.Place place = snapshot.place();
        BlockPos opening = snapshot.caveEntrance().shaftOpening(place);
        ShaftData.Profile profile = snapshot.shaft().profile();
        if (!intersectsEnvelope(
                chunk,
                opening.getX(),
                opening.getZ(),
                profile.shaftEnvelopeRadius())) {
            return;
        }

        int boundedMaxY = Math.min(maxY, opening.getY());
        if (boundedMaxY < minY) {
            return;
        }
        LayerSample[] layers = sourceLayers(profile, minY, boundedMaxY);
        writeSourceGeometry(
                chunk,
                opening.getX(),
                opening.getZ(),
                opening.getY(),
                profile,
                minY,
                boundedMaxY,
                layers,
                chunk::setBlockState
        );
    }

    // 目标端竖井必须晚于地下世界的其他 Feature 写入。
    public static void writeTargetChunk(
            ChunkAccess chunk,
            ShaftData shaft,
            int targetCenterX,
            int targetCenterZ,
            int minY,
            int maxY,
            IntBinaryOperator targetMinY,
            int targetSeamY
    ) {
        ShaftData.Profile profile = shaft.profile();
        if (!intersectsEnvelope(
                chunk,
                targetCenterX,
                targetCenterZ,
                profile.shaftEnvelopeRadius())) {
            return;
        }

        LayerSample[] layers = targetLayers(profile, minY, maxY, targetSeamY);
        writeTargetGeometry(
                chunk,
                profile,
                targetCenterX,
                targetCenterZ,
                minY,
                maxY,
                layers,
                targetMinY,
                targetSeamY,
                chunk::setBlockState
        );
    }

    public static boolean intersectsTargetChunk(
            ChunkAccess chunk,
            ShaftData shaft,
            int targetCenterX,
            int targetCenterZ
    ) {
        return intersectsEnvelope(
                chunk,
                targetCenterX,
                targetCenterZ,
                shaft.profile().shaftEnvelopeRadius()
        );
    }

    // 已生成区块只能在主线程按同一几何规则幂等修复。
    public static int repairGeneratedSource(ServerLevel level, EbottData.Snapshot snapshot) {
        PlaceManager.Place place = snapshot.place();
        BlockPos opening = snapshot.caveEntrance().shaftOpening(place);
        return writeBoundedSource(
                level,
                snapshot.shaft(),
                opening.getX(),
                opening.getZ(),
                opening.getY()
        );
    }

    // 洞口以上不参与读取或覆盖，避免修复波及地表结构。
    public static int writeBoundedSource(
            ServerLevel level,
            ShaftData shaft,
            int centerX,
            int centerZ,
            int openingY
    ) {
        int minY = level.getMinY();
        if (openingY < minY || openingY > level.getMaxY()) {
            throw new IllegalArgumentException("openingY is outside the level build height");
        }

        ShaftData.Profile profile = shaft.profile();
        LayerSample[] layers = sourceLayers(profile, minY, openingY);
        return repairGeneratedArea(
                level,
                centerX,
                centerZ,
                profile.shaftEnvelopeRadius(),
                chunk -> writeSourceGeometry(
                        chunk,
                        centerX,
                        centerZ,
                        openingY,
                        profile,
                        minY,
                        openingY,
                        layers,
                        serverWriter(level)
                )
        );
    }

    // 目标端已生成区块同样由主线程执行幂等修复。
    public static int repairGeneratedTarget(
            ServerLevel level,
            EbottData.Snapshot snapshot,
            int targetCenterX,
            int targetCenterZ,
            IntBinaryOperator targetMinY,
            int targetSeamY
    ) {
        ShaftData.Profile profile = snapshot.shaft().profile();
        int minY = level.getMinY();
        int maxY = level.getMaxY();
        LayerSample[] layers = targetLayers(profile, minY, maxY, targetSeamY);
        return repairGeneratedArea(
                level,
                targetCenterX,
                targetCenterZ,
                profile.shaftEnvelopeRadius(),
                chunk -> writeTargetGeometry(
                        chunk,
                        profile,
                        targetCenterX,
                        targetCenterZ,
                        minY,
                        maxY,
                        layers,
                        targetMinY,
                        targetSeamY,
                        serverWriter(level)
                )
        );
    }

    public static VerificationResult verifySource(ServerLevel level, EbottData.Snapshot snapshot) {
        PlaceManager.Place place = snapshot.place();
        BlockPos opening = snapshot.caveEntrance().shaftOpening(place);
        ShaftData.Profile profile = snapshot.shaft().profile();
        VerificationResult loaded = requireLoaded(
                level,
                opening.getX(),
                opening.getZ(),
                profile.shaftEnvelopeRadius()
        );
        if (!loaded.success()) {
            return loaded;
        }

        int checked = 0;
        int radius = profile.shaftEnvelopeRadius();
        int minY = level.getMinY();
        int maxY = opening.getY();
        LayerSample[] layers = sourceLayers(profile, minY, maxY);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int localZ = -radius; localZ <= radius; localZ++) {
            for (int localX = -radius; localX <= radius; localX++) {
                for (int y = minY; y <= maxY; y++) {
                    Classification classification = classify(layers[y - minY], localX, localZ);
                    cursor.set(opening.getX() + localX, y, opening.getZ() + localZ);
                    BlockState actual = level.getBlockState(cursor);
                    BlockState expected = sourceState(
                            profile,
                            classification,
                            actual,
                            y,
                            opening.getY()
                    );
                    if (expected == null) {
                        continue;
                    }
                    checked++;
                    if (!actual.equals(expected)) {
                        return VerificationResult.failure(
                                checked,
                                "方块不匹配 pos=%s expected=%s actual=%s"
                                        .formatted(cursor.immutable(), expected, actual)
                        );
                    }
                }
            }
        }
        return VerificationResult.success(checked);
    }

    public static VerificationResult verifyTarget(
            ServerLevel level,
            EbottData.Snapshot snapshot,
            int targetCenterX,
            int targetCenterZ,
            IntBinaryOperator targetMinY,
            int targetSeamY
    ) {
        ShaftData.Profile profile = snapshot.shaft().profile();
        VerificationResult loaded = requireLoaded(
                level,
                targetCenterX,
                targetCenterZ,
                profile.shaftEnvelopeRadius()
        );
        if (!loaded.success()) {
            return loaded;
        }

        int checked = 0;
        int radius = profile.shaftEnvelopeRadius();
        int minY = level.getMinY();
        int maxY = level.getMaxY();
        LayerSample[] layers = targetLayers(profile, minY, maxY, targetSeamY);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int localZ = -radius; localZ <= radius; localZ++) {
            for (int localX = -radius; localX <= radius; localX++) {
                int worldX = targetCenterX + localX;
                int worldZ = targetCenterZ + localZ;
                int columnMinY = boundedTargetMinY(targetMinY, worldX, worldZ, minY, maxY);
                int cleanupMinY = targetCleanupMinY(columnMinY, minY);
                for (int y = cleanupMinY; y < columnMinY; y++) {
                    Classification classification = classifyTarget(
                            profile,
                            layers[y - minY],
                            localX,
                            y,
                            localZ,
                            targetSeamY
                    );
                    if (classification != Classification.SHAFT_AIR) {
                        continue;
                    }
                    cursor.set(worldX, y, worldZ);
                    BlockState actual = level.getBlockState(cursor);
                    checked++;
                    if (isSnowdinStalactite(actual)) {
                        return VerificationResult.failure(
                                checked,
                                "竖井空气体积内残留钟乳石 pos=%s actual=%s"
                                        .formatted(cursor.immutable(), actual)
                        );
                    }
                }
                for (int y = columnMinY; y <= maxY; y++) {
                    cursor.set(worldX, y, worldZ);
                    Classification classification = classifyTarget(
                            profile,
                            layers[y - minY],
                            localX,
                            y,
                            localZ,
                            targetSeamY
                    );
                    BlockState actual = level.getBlockState(cursor);
                    BlockState expected = targetState(
                            profile,
                            classification,
                            actual,
                            y,
                            targetSeamY
                    );
                    if (expected == null) {
                        continue;
                    }
                    checked++;
                    if (!actual.equals(expected)) {
                        return VerificationResult.failure(
                                checked,
                                "方块不匹配 pos=%s expected=%s actual=%s"
                                        .formatted(cursor.immutable(), expected, actual)
                        );
                    }
                }
            }
        }
        return VerificationResult.success(checked);
    }

    public static Classification classifySource(
            ShaftData.Profile profile,
            int localX,
            int sourceY,
            int localZ
    ) {
        return classify(sampleSourceLayer(profile, sourceY), localX, localZ);
    }

    public static Classification classifyTarget(
            ShaftData.Profile profile,
            int localX,
            int targetY,
            int localZ
    ) {
        return classifyTarget(profile, localX, targetY, localZ, profile.targetSeamY());
    }

    public static Classification classifyTarget(
            ShaftData.Profile profile,
            int localX,
            int targetY,
            int localZ,
            int targetSeamY
    ) {
        return classifyTarget(
                profile,
                sampleTargetLayer(profile, targetY, targetSeamY),
                localX,
                targetY,
                localZ,
                targetSeamY
        );
    }

    public static LayerSample sampleSourceLayer(ShaftData.Profile profile, int sourceY) {
        return usesCanonicalSourceSeamLayer(profile, sourceY)
                ? seamLayer(profile)
                : sampleLayer(profile, profile.sourceLogicalY(sourceY));
    }

    public static LayerSample sampleTargetLayer(ShaftData.Profile profile, int targetY) {
        return sampleTargetLayer(profile, targetY, profile.targetSeamY());
    }

    public static LayerSample sampleTargetLayer(
            ShaftData.Profile profile,
            int targetY,
            int targetSeamY
    ) {
        return usesCanonicalTargetSeamLayer(profile, targetY, targetSeamY)
                ? seamLayer(profile)
                : sampleLayer(profile, profile.targetLogicalY(targetY));
    }

    public static BlockState sourceState(
            ShaftData.Profile profile,
            Classification classification,
            BlockState generatedState,
            int sourceY,
            int sourceSummitY
    ) {
        if (classification == Classification.SHAFT_AIR) {
            return Blocks.AIR.defaultBlockState();
        }
        if (sourceY > sourceSummitY) {
            return null;
        }
        return switch (classification) {
            case SHAFT_WALL -> sourceY == profile.sourceSeamY()
                    ? Blocks.BEDROCK.defaultBlockState()
                    : generatedWallMaterial(generatedState);
            case BARRIER -> Blocks.BEDROCK.defaultBlockState();
            case OUTSIDE, SHAFT_AIR -> null;
        };
    }

    public static BlockState targetState(
            ShaftData.Profile profile,
            Classification classification,
            BlockState generatedState,
            int targetY
    ) {
        return targetState(profile, classification, generatedState, targetY, profile.targetSeamY());
    }

    public static BlockState targetState(
            ShaftData.Profile profile,
            Classification classification,
            BlockState generatedState,
            int targetY,
            int targetSeamY
    ) {
        return switch (classification) {
            case SHAFT_AIR -> Blocks.AIR.defaultBlockState();
            case SHAFT_WALL -> targetY == targetSeamY
                    ? Blocks.BEDROCK.defaultBlockState()
                    : generatedWallMaterial(generatedState);
            // 结界仅是视觉效果；物理阻挡会封住目标接缝下方的换维落点。
            case BARRIER -> Blocks.AIR.defaultBlockState();
            case OUTSIDE -> null;
        };
    }

    private static Classification classifyTarget(
            ShaftData.Profile profile,
            LayerSample layer,
            int localX,
            int targetY,
            int localZ,
            int targetSeamY
    ) {
        Classification classification = classify(layer, localX, localZ);
        if (classification == Classification.SHAFT_AIR
                && targetY >= targetBarrierMinY(profile, targetSeamY)
                && targetY < targetSeamY) {
            return Classification.BARRIER;
        }
        return classification;
    }

    private static Classification classify(LayerSample layer, int localX, int localZ) {
        double dx = localX - layer.centerX();
        double dz = localZ - layer.centerZ();
        double distanceSquared = dx * dx + dz * dz;
        if (distanceSquared <= layer.airBoundary() * layer.airBoundary()) {
            return Classification.SHAFT_AIR;
        }
        double wallBoundary = layer.airBoundary() + layer.wallThickness();
        if (distanceSquared <= wallBoundary * wallBoundary) {
            return Classification.SHAFT_WALL;
        }
        return Classification.OUTSIDE;
    }

    private static LayerSample sampleLayer(ShaftData.Profile profile, int logicalY) {
        double sampleY = logicalY * profile.verticalNoiseScale();
        double centerX = WorldgenMath.signedValueNoise1d(
                sampleY,
                WorldgenMath.channelSeed(profile.shaftSeed(), CENTER_X_CHANNEL)
        ) * profile.centerlineWander();
        double centerZ = WorldgenMath.signedValueNoise1d(
                sampleY,
                WorldgenMath.channelSeed(profile.shaftSeed(), CENTER_Z_CHANNEL)
        ) * profile.centerlineWander();
        double radiusOffset = WorldgenMath.signedValueNoise1d(
                sampleY,
                WorldgenMath.channelSeed(profile.shaftSeed(), RADIUS_CHANNEL)
        ) * profile.wallPerturbation();
        return new LayerSample(
                centerX,
                centerZ,
                profile.airRadius() + radiusOffset,
                profile.wallThickness()
        );
    }

    private static LayerSample seamLayer(ShaftData.Profile profile) {
        return sampleLayer(profile, profile.sourceLogicalY(profile.sourceSeamY()));
    }

    private static boolean usesCanonicalSourceSeamLayer(ShaftData.Profile profile, int sourceY) {
        return sourceY >= profile.sourceSeamY()
                && sourceY < profile.sourceSeamY() + SOURCE_SEAM_OPENING_HEIGHT;
    }

    private static boolean usesCanonicalTargetSeamLayer(
            ShaftData.Profile profile,
            int targetY,
            int targetSeamY
    ) {
        return targetY >= targetBarrierMinY(profile, targetSeamY) && targetY <= targetSeamY;
    }

    private static int targetBarrierMinY(ShaftData.Profile profile, int targetSeamY) {
        return targetSeamY - (profile.targetSeamY() - profile.targetBarrierMinY());
    }

    private static BlockState generatedWallMaterial(BlockState generatedState) {
        return generatedState.isAir() || !generatedState.getFluidState().isEmpty()
                ? Blocks.STONE.defaultBlockState()
                : generatedState;
    }

    private static void writeSourceGeometry(
            ChunkAccess chunk,
            int centerX,
            int centerZ,
            int sourceSummitY,
            ShaftData.Profile profile,
            int minY,
            int maxY,
            LayerSample[] layers,
            BlockWriter writer
    ) {
        int radius = profile.shaftEnvelopeRadius();
        int minX = Math.max(chunk.getPos().getMinBlockX(), centerX - radius);
        int maxX = Math.min(chunk.getPos().getMaxBlockX(), centerX + radius);
        int minZ = Math.max(chunk.getPos().getMinBlockZ(), centerZ - radius);
        int maxZ = Math.min(chunk.getPos().getMaxBlockZ(), centerZ + radius);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int z = minZ; z <= maxZ; z++) {
            int localZ = z - centerZ;
            for (int x = minX; x <= maxX; x++) {
                int localX = x - centerX;
                for (int y = minY; y <= maxY; y++) {
                    BlockState generatedState = chunk.getBlockState(cursor.set(x, y, z));
                    Classification classification = classify(layers[y - minY], localX, localZ);
                    BlockState desired = sourceState(
                            profile,
                            classification,
                            generatedState,
                            y,
                            sourceSummitY
                    );
                    if (desired != null) {
                        writer.set(cursor, desired);
                    }
                }
            }
        }
    }

    private static void writeTargetGeometry(
            ChunkAccess chunk,
            ShaftData.Profile profile,
            int targetCenterX,
            int targetCenterZ,
            int minY,
            int maxY,
            LayerSample[] layers,
            IntBinaryOperator targetMinY,
            int targetSeamY,
            BlockWriter writer
    ) {
        int radius = profile.shaftEnvelopeRadius();
        int minX = Math.max(chunk.getPos().getMinBlockX(), targetCenterX - radius);
        int maxX = Math.min(chunk.getPos().getMaxBlockX(), targetCenterX + radius);
        int minZ = Math.max(chunk.getPos().getMinBlockZ(), targetCenterZ - radius);
        int maxZ = Math.min(chunk.getPos().getMaxBlockZ(), targetCenterZ + radius);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int z = minZ; z <= maxZ; z++) {
            int localZ = z - targetCenterZ;
            for (int x = minX; x <= maxX; x++) {
                int localX = x - targetCenterX;
                int columnMinY = boundedTargetMinY(targetMinY, x, z, minY, maxY);
                int cleanupMinY = targetCleanupMinY(columnMinY, minY);
                for (int y = cleanupMinY; y < columnMinY; y++) {
                    Classification classification = classifyTarget(
                            profile,
                            layers[y - minY],
                            localX,
                            y,
                            localZ,
                            targetSeamY
                    );
                    if (classification != Classification.SHAFT_AIR) {
                        continue;
                    }
                    BlockState generatedState = chunk.getBlockState(cursor.set(x, y, z));
                    if (isSnowdinStalactite(generatedState)) {
                        writer.set(cursor, Blocks.AIR.defaultBlockState());
                    }
                }
                for (int y = columnMinY; y <= maxY; y++) {
                    BlockState generatedState = chunk.getBlockState(cursor.set(x, y, z));
                    Classification classification = classifyTarget(
                            profile,
                            layers[y - minY],
                            localX,
                            y,
                            localZ,
                            targetSeamY
                    );
                    BlockState desired = targetState(
                            profile,
                            classification,
                            generatedState,
                            y,
                            targetSeamY
                    );
                    if (desired != null) {
                        writer.set(cursor, desired);
                    }
                }
            }
        }
    }

    private static int boundedTargetMinY(
            IntBinaryOperator targetMinY,
            int worldX,
            int worldZ,
            int minY,
            int maxY
    ) {
        return Math.max(minY, Math.min(maxY, targetMinY.applyAsInt(worldX, worldZ)));
    }

    // 仅 Snowdin 需要向下清除侵入竖井空腔的钟乳石；Origin 保留原写入下界。
    static int targetCleanupMinY(int columnMinY, int levelMinY) {
        return EbottDestination.ENABLE_SNOWDIN_TARGET
                ? Math.max(levelMinY, columnMinY - SNOWDIN_STALACTITE_CLEANUP_DEPTH)
                : columnMinY;
    }

    private static boolean isSnowdinStalactite(BlockState state) {
        return state.is(Blocks.DRIPSTONE_BLOCK) || state.is(Blocks.POINTED_DRIPSTONE);
    }

    private static int repairGeneratedArea(
            ServerLevel level,
            int centerX,
            int centerZ,
            int radius,
            ChunkRestorer restorer
    ) {
        int minChunkX = Math.floorDiv(centerX - radius, 16);
        int maxChunkX = Math.floorDiv(centerX + radius, 16);
        int minChunkZ = Math.floorDiv(centerZ - radius, 16);
        int maxChunkZ = Math.floorDiv(centerZ + radius, 16);
        int repaired = 0;
        for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                restorer.restore(level.getChunk(chunkX, chunkZ));
                repaired++;
            }
        }
        return repaired;
    }

    private static VerificationResult requireLoaded(
            ServerLevel level,
            int centerX,
            int centerZ,
            int radius
    ) {
        int minChunkX = Math.floorDiv(centerX - radius, 16);
        int maxChunkX = Math.floorDiv(centerX + radius, 16);
        int minChunkZ = Math.floorDiv(centerZ - radius, 16);
        int maxChunkZ = Math.floorDiv(centerZ + radius, 16);
        for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                if (!level.hasChunk(chunkX, chunkZ)) {
                    return VerificationResult.failure(
                            0,
                            "竖井 Chunk 尚未全部加载，缺少 [%d,%d]".formatted(chunkX, chunkZ)
                    );
                }
            }
        }
        return VerificationResult.success(0);
    }

    private static LayerSample[] sourceLayers(ShaftData.Profile profile, int minY, int maxY) {
        LayerSample[] layers = new LayerSample[maxY - minY + 1];
        for (int y = minY; y <= maxY; y++) {
            layers[y - minY] = sampleSourceLayer(profile, y);
        }
        return layers;
    }

    private static LayerSample[] targetLayers(
            ShaftData.Profile profile,
            int minY,
            int maxY,
            int targetSeamY
    ) {
        LayerSample[] layers = new LayerSample[maxY - minY + 1];
        for (int y = minY; y <= maxY; y++) {
            layers[y - minY] = sampleTargetLayer(profile, y, targetSeamY);
        }
        return layers;
    }

    private static boolean insideEnvelope(ShaftData.Profile profile, int localX, int localZ) {
        int radius = profile.shaftEnvelopeRadius();
        return Math.abs(localX) <= radius && Math.abs(localZ) <= radius;
    }

    private static boolean intersectsEnvelope(
            ChunkAccess chunk,
            int centerX,
            int centerZ,
            int radius
    ) {
        return chunk.getPos().getMaxBlockX() >= centerX - radius
                && chunk.getPos().getMinBlockX() <= centerX + radius
                && chunk.getPos().getMaxBlockZ() >= centerZ - radius
                && chunk.getPos().getMinBlockZ() <= centerZ + radius;
    }

    private static BlockWriter serverWriter(ServerLevel level) {
        return (pos, state) -> {
            if (!level.getBlockState(pos).equals(state)) {
                level.setBlock(pos, state, Block.UPDATE_CLIENTS);
            }
        };
    }

    public enum Classification {
        OUTSIDE,
        SHAFT_WALL,
        SHAFT_AIR,
        BARRIER
    }

    public record LayerSample(
            double centerX,
            double centerZ,
            double airBoundary,
            double wallThickness
    ) {
    }

    public record VerificationResult(boolean success, int checkedBlocks, String detail) {
        private static VerificationResult success(int checkedBlocks) {
            return new VerificationResult(true, checkedBlocks, "PASS");
        }

        private static VerificationResult failure(int checkedBlocks, String detail) {
            return new VerificationResult(false, checkedBlocks, detail);
        }
    }

    @FunctionalInterface
    private interface ChunkRestorer {
        void restore(ChunkAccess chunk);
    }

    @FunctionalInterface
    private interface BlockWriter {
        void set(BlockPos pos, BlockState state);
    }
}
