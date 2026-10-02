package cn.jehorstudio.minetale.dimension.worldgen.asset.plan;

import cn.jehorstudio.minetale.dimension.worldgen.asset.catalog.StructureAssetDefinition;
import cn.jehorstudio.minetale.dimension.worldgen.asset.transform.TransformedAssetPlacement;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

public record PlannedPiece(
        String roleInPlan,
        StructureAssetDefinition asset,
        ResourceLocation assetId,
        ResourceLocation templateId,
        BlockPos origin,
        Rotation rotation,
        Mirror mirror,
        BoundingBox bounds,
        TransformedAssetPlacement placement
) {
    public static PlannedPiece from(String roleInPlan, StructureAssetDefinition asset, TransformedAssetPlacement placement) {
        return new PlannedPiece(
                roleInPlan,
                asset,
                asset.id(),
                asset.template(),
                placement.origin(),
                placement.rotation(),
                placement.mirror(),
                placement.bounds(),
                placement
        );
    }
}