package cn.jehorstudio.minetale.dimension.worldgen.asset.scanner;

import cn.jehorstudio.minetale.dimension.worldgen.asset.marker.MarkerKind;
import java.util.List;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;

public final class StructureAssetMarkerQueries {
    private StructureAssetMarkerQueries() {
    }

    public static List<ScannedTemplateMarker> connectors(TemplateScanResult result) {
        return byKind(result, MarkerKind.CONNECTOR);
    }

    public static List<ScannedTemplateMarker> anchors(TemplateScanResult result) {
        return byKind(result, MarkerKind.ANCHOR);
    }

    public static List<ScannedTemplateMarker> anchorsById(TemplateScanResult result, ResourceLocation id) {
        return anchors(result).stream()
                .filter(marker -> marker.data().id().equals(id))
                .toList();
    }

    public static Optional<ScannedTemplateMarker> firstAnchor(TemplateScanResult result, ResourceLocation id) {
        return anchorsById(result, id).stream().findFirst();
    }

    private static List<ScannedTemplateMarker> byKind(TemplateScanResult result, MarkerKind kind) {
        return result.markers().stream()
                .filter(marker -> marker.data().kind() == kind)
                .toList();
    }
}
