package cn.jehorstudio.minetale.dimension.worldgen.asset.plan;

import cn.jehorstudio.minetale.dimension.worldgen.asset.transform.TransformedTemplateMarker;

public record PlanFrontierEntry(
        int pieceIndex,
        int markerIndex,
        TransformedTemplateMarker marker
) {
}
