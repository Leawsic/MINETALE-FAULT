package cn.jehorstudio.minetale.dimension.worldgen.asset.matching;

import cn.jehorstudio.minetale.dimension.worldgen.asset.catalog.StructureAssetDefinition;
import cn.jehorstudio.minetale.dimension.worldgen.asset.placement.OccupancyMap;
import cn.jehorstudio.minetale.dimension.worldgen.asset.transform.TransformedAssetPlacement;
import cn.jehorstudio.minetale.dimension.worldgen.asset.transform.TransformedTemplateMarker;
import java.util.Collection;
import java.util.List;
import org.jetbrains.annotations.Nullable;

public record ConnectorMatchRequest(
        TransformedAssetPlacement parentPlacement,
        TransformedTemplateMarker parentMarker,
        Collection<StructureAssetDefinition> candidates,
        @Nullable OccupancyMap occupancyMap,
        int maxResults
) {
    public ConnectorMatchRequest {
        candidates = List.copyOf(candidates == null ? List.of() : candidates);
        if (maxResults <= 0) {
            maxResults = 10;
        }
    }
}