package cn.jehorstudio.minetale.dimension.worldgen.asset.matching;

import cn.jehorstudio.minetale.dimension.worldgen.asset.catalog.StructureAssetDefinition;
import cn.jehorstudio.minetale.dimension.worldgen.asset.transform.TransformedAssetPlacement;
import cn.jehorstudio.minetale.dimension.worldgen.asset.transform.TransformedTemplateMarker;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

public record ConnectorMatch(
        StructureAssetDefinition childAsset,
        TransformedAssetPlacement childPlacement,
        TransformedTemplateMarker parentMarker,
        TransformedTemplateMarker childMarker,
        Rotation rotation,
        Mirror mirror,
        BlockPos origin,
        BoundingBox bounds,
        boolean collides,
        List<String> notes
) {
    public ConnectorMatch {
        notes = List.copyOf(notes == null ? List.of() : notes);
    }
}