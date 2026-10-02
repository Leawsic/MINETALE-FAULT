package cn.jehorstudio.minetale.dimension.worldgen.asset;

import cn.jehorstudio.minetale.dimension.worldgen.asset.catalog.StructureAssetDefinition;
import cn.jehorstudio.minetale.dimension.worldgen.asset.placement.PlacementBlockerCleanupProcessor;
import cn.jehorstudio.minetale.dimension.worldgen.asset.placement.TemplateMarkerCleanupProcessor;
import cn.jehorstudio.minetale.dimension.worldgen.asset.scanner.ScannedTemplateMarker;
import cn.jehorstudio.minetale.dimension.worldgen.asset.scanner.StructureAssetMarkerQueries;
import cn.jehorstudio.minetale.dimension.worldgen.asset.scanner.TemplateScanResult;
import cn.jehorstudio.minetale.dimension.worldgen.asset.transform.StructureAssetTransform;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockIgnoreProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

public final class AssetPlacer {
    private AssetPlacer() {
    }

    public static Optional<ScannedTemplateMarker> firstAnchor(StructureAssetDefinition asset) {
        TemplateScanResult scanResult = asset.scanResult();
        if (scanResult == null) {
            return Optional.empty();
        }
        return StructureAssetMarkerQueries.anchors(scanResult).stream().findFirst();
    }

    public static Optional<AssetPlacementPlan> anchorAt(
            StructureAssetDefinition asset,
            ScannedTemplateMarker anchor,
            BlockPos desiredAnchor,
            Direction desiredFacing
    ) {
        return anchorAt(asset, anchor, desiredAnchor, desiredFacing, Mirror.NONE);
    }

    public static Optional<AssetPlacementPlan> anchorAt(
            StructureAssetDefinition asset,
            ScannedTemplateMarker anchor,
            BlockPos desiredAnchor,
            Direction desiredFacing,
            Mirror mirror
    ) {
        TemplateScanResult scanResult = asset.scanResult();
        if (scanResult == null) {
            return Optional.empty();
        }
        if (mirror != Mirror.NONE && !asset.placement().canMirror()) {
            return Optional.empty();
        }
        Rotation rotation = asset.placement().canRotate()
                ? rotationFromTo(horizontal(anchor.facing()), horizontal(desiredFacing))
                : Rotation.NONE;
        BlockPos transformedAnchorOffset = StructureTemplate.transform(anchor.localPos(), mirror, rotation, BlockPos.ZERO);
        BlockPos origin = desiredAnchor.offset(
                -transformedAnchorOffset.getX(),
                -transformedAnchorOffset.getY(),
                -transformedAnchorOffset.getZ()
        );
        BoundingBox bounds = StructureAssetTransform.computeBounds(scanResult.size(), origin, rotation, mirror);
        return Optional.of(new AssetPlacementPlan(asset, origin, rotation, mirror, bounds));
    }

    public static boolean place(WorldGenLevel level, AssetPlacementPlan plan, long seed) {
        return place(level, plan, seed, null, false);
    }

    // 跨区块 asset 在每个生成区块内独立裁切，避免写入尚未处于当前阶段的邻区块。
    public static boolean placeInChunk(
            WorldGenLevel level,
            AssetPlacementPlan plan,
            ChunkAccess chunk,
            long seed
    ) {
        return placeInChunk(level, plan, chunk, seed, false);
    }

    // 忽略模板 air，防止分片放置清除已有世界方块。
    public static boolean placeInChunkIgnoringAir(
            WorldGenLevel level,
            AssetPlacementPlan plan,
            ChunkAccess chunk,
            long seed
    ) {
        return placeInChunk(level, plan, chunk, seed, true);
    }

    private static boolean placeInChunk(
            WorldGenLevel level,
            AssetPlacementPlan plan,
            ChunkAccess chunk,
            long seed,
            boolean ignoreAir
    ) {
        int minX = chunk.getPos().getMinBlockX();
        int minY = chunk.getMinY();
        int minZ = chunk.getPos().getMinBlockZ();
        BoundingBox chunkBounds = new BoundingBox(
                minX,
                minY,
                minZ,
                minX + 15,
                minY + chunk.getHeight() - 1,
                minZ + 15
        );
        return place(level, plan, seed, chunkBounds, ignoreAir);
    }

    private static boolean place(
            WorldGenLevel level,
            AssetPlacementPlan plan,
            long seed,
            BoundingBox writableBounds,
            boolean ignoreAir
    ) {
        ServerLevel serverLevel = level.getLevel();
        StructureTemplate template = serverLevel.getStructureManager().get(plan.asset().template()).orElse(null);
        if (template == null) {
            return false;
        }

        StructurePlaceSettings settings = new StructurePlaceSettings()
                .setMirror(plan.mirror())
                .setRotation(plan.rotation())
                .setKnownShape(false);
        if (writableBounds != null) {
            settings.setBoundingBox(writableBounds);
        }
        if (ignoreAir) {
            settings.addProcessor(BlockIgnoreProcessor.AIR);
        }
        settings.addProcessor(PlacementBlockerCleanupProcessor.INSTANCE);
        settings.addProcessor(TemplateMarkerCleanupProcessor.INSTANCE);

        return template.placeInWorld(
                level,
                plan.origin(),
                plan.origin(),
                settings,
                RandomSource.create(seed),
                2
        );
    }

    public static boolean intersectsChunk(BoundingBox bounds, ChunkAccess chunk) {
        int minX = chunk.getPos().getMinBlockX();
        int minZ = chunk.getPos().getMinBlockZ();
        return bounds.intersects(minX, minZ, minX + 15, minZ + 15);
    }

    public static boolean blockInChunk(BlockPos pos, ChunkAccess chunk) {
        int minX = chunk.getPos().getMinBlockX();
        int minZ = chunk.getPos().getMinBlockZ();
        return pos.getX() >= minX
                && pos.getX() < minX + 16
                && pos.getZ() >= minZ
                && pos.getZ() < minZ + 16;
    }

    public static Direction horizontal(Direction direction) {
        return direction.getAxis().isHorizontal() ? direction : Direction.SOUTH;
    }

    public static Rotation rotationFromTo(Direction from, Direction to) {
        for (Rotation rotation : Rotation.values()) {
            if (rotation.rotate(from) == to) {
                return rotation;
            }
        }
        return Rotation.NONE;
    }

    public record AssetPlacementPlan(
            StructureAssetDefinition asset,
            BlockPos origin,
            Rotation rotation,
            Mirror mirror,
            BoundingBox bounds
    ) {
    }
}
