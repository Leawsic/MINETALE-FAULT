package cn.jehorstudio.minetale.dimension.worldgen.asset.transform;

import cn.jehorstudio.minetale.dimension.worldgen.asset.catalog.StructureAssetDefinition;
import cn.jehorstudio.minetale.dimension.worldgen.asset.scanner.ScannedTemplateMarker;
import cn.jehorstudio.minetale.dimension.worldgen.asset.scanner.TemplateScanResult;
import java.util.List;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

public final class StructureAssetTransform {
    private StructureAssetTransform() {
    }

    public static TransformedAssetPlacement transform(
            StructureAssetDefinition asset,
            BlockPos origin,
            Rotation rotation,
            Mirror mirror
    ) {
        TemplateScanResult scanResult = Objects.requireNonNull(asset.scanResult(), "asset scanResult");
        return transform(asset, scanResult, origin, rotation, mirror);
    }

    public static TransformedAssetPlacement transform(
            StructureAssetDefinition asset,
            TemplateScanResult scanResult,
            BlockPos origin,
            Rotation rotation,
            Mirror mirror
    ) {
        Vec3i templateSize = scanResult.size();
        List<TransformedTemplateMarker> markers = scanResult.markers().stream()
                .map(marker -> transformMarker(marker, templateSize, origin, rotation, mirror))
                .toList();
        return new TransformedAssetPlacement(
                asset.id(),
                scanResult.templateId(),
                origin,
                rotation,
                mirror,
                templateSize,
                computeBounds(templateSize, origin, rotation, mirror),
                markers
        );
    }

    public static BlockPos transformMarkerPos(
            BlockPos localPos,
            Vec3i templateSize,
            BlockPos origin,
            Rotation rotation,
            Mirror mirror
    ) {
        Objects.requireNonNull(templateSize, "templateSize");
        // 标记位置必须复用 StructureTemplate 放置方块时的局部坐标变换。
        return StructureTemplate.transform(localPos, mirror, rotation, BlockPos.ZERO).offset(origin);
    }

    public static Direction transformFacing(Direction facing, Rotation rotation, Mirror mirror) {
        return rotation.rotate(mirror.mirror(facing));
    }

    public static BoundingBox computeBounds(Vec3i templateSize, BlockPos origin, Rotation rotation, Mirror mirror) {
        // 边界与 pivot 为 BlockPos.ZERO 的 StructureTemplate 放置规则保持一致。
        Vec3i lastLocalPos = templateSize.offset(-1, -1, -1);
        BlockPos firstCorner = StructureTemplate.transform(BlockPos.ZERO, mirror, rotation, BlockPos.ZERO);
        BlockPos secondCorner = StructureTemplate.transform(BlockPos.ZERO.offset(lastLocalPos), mirror, rotation, BlockPos.ZERO);
        return BoundingBox.fromCorners(firstCorner, secondCorner).move(origin);
    }

    private static TransformedTemplateMarker transformMarker(
            ScannedTemplateMarker source,
            Vec3i templateSize,
            BlockPos origin,
            Rotation rotation,
            Mirror mirror
    ) {
        BlockPos localPos = source.localPos();
        Direction localFacing = source.facing();
        return new TransformedTemplateMarker(
                source,
                localPos,
                localFacing,
                transformMarkerPos(localPos, templateSize, origin, rotation, mirror),
                transformFacing(localFacing, rotation, mirror),
                source.visual(),
                source.data()
        );
    }
}
