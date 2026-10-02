package cn.jehorstudio.minetale.dimension.worldgen.asset.transform;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

public record TransformedAssetPlacement(
        ResourceLocation assetId,
        ResourceLocation templateId,
        BlockPos origin,
        Rotation rotation,
        Mirror mirror,
        Vec3i templateSize,
        BoundingBox bounds,
        List<TransformedTemplateMarker> markers
) {
    public TransformedAssetPlacement {
        markers = List.copyOf(markers == null ? List.of() : markers);
    }
}
