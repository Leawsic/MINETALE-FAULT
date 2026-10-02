package cn.jehorstudio.minetale.dimension.worldgen.asset.transform;

import cn.jehorstudio.minetale.dimension.worldgen.asset.marker.MarkerVisual;
import cn.jehorstudio.minetale.dimension.worldgen.asset.marker.TemplateMarkerData;
import cn.jehorstudio.minetale.dimension.worldgen.asset.scanner.ScannedTemplateMarker;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

public record TransformedTemplateMarker(
        ScannedTemplateMarker source,
        BlockPos localPos,
        Direction localFacing,
        BlockPos worldPos,
        Direction worldFacing,
        MarkerVisual visual,
        TemplateMarkerData data
) {
    public TransformedTemplateMarker {
        data = data == null ? TemplateMarkerData.defaults() : data.copy();
    }
}
