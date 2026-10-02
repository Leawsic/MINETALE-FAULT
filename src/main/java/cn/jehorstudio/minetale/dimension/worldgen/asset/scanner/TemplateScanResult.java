package cn.jehorstudio.minetale.dimension.worldgen.asset.scanner;

import java.util.List;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.ResourceLocation;

public record TemplateScanResult(
        ResourceLocation templateId,
        Vec3i size,
        List<ScannedTemplateMarker> markers
) {
    public TemplateScanResult {
        markers = List.copyOf(markers == null ? List.of() : markers);
    }
}
