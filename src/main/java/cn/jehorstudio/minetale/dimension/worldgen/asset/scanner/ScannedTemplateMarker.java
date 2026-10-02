package cn.jehorstudio.minetale.dimension.worldgen.asset.scanner;

import cn.jehorstudio.minetale.dimension.worldgen.asset.marker.MarkerVisual;
import cn.jehorstudio.minetale.dimension.worldgen.asset.marker.TemplateMarkerData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

public record ScannedTemplateMarker(
        BlockPos localPos,
        Direction facing,
        MarkerVisual visual,
        TemplateMarkerData data
) {
    public ScannedTemplateMarker {
        data = data == null ? TemplateMarkerData.defaults() : data.copy();
    }
}
