package cn.jehorstudio.minetale.dimension.worldgen.asset.catalog;

import cn.jehorstudio.minetale.dimension.worldgen.asset.marker.MarkerKind;
import cn.jehorstudio.minetale.dimension.worldgen.asset.marker.TemplateMarkerData;
import cn.jehorstudio.minetale.dimension.worldgen.asset.marker.TemplateMarkerValidation;
import cn.jehorstudio.minetale.dimension.worldgen.asset.scanner.ScannedTemplateMarker;
import cn.jehorstudio.minetale.dimension.worldgen.asset.scanner.TemplateScanResult;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

public record StructureAssetDefinition(
        ResourceLocation id,
        StructureAssetDefinitionData data,
        @Nullable TemplateScanResult scanResult,
        List<String> loadErrors
) {
    public StructureAssetDefinition {
        loadErrors = List.copyOf(loadErrors == null ? List.of() : loadErrors);
    }

    public ResourceLocation template() {
        return this.data.template();
    }

    public String role() {
        return this.data.role();
    }

    public ResourceLocation category() {
        return this.data.category();
    }

    public int weight() {
        return this.data.weight();
    }

    public Set<ResourceLocation> tags() {
        return new LinkedHashSet<>(this.data.tags());
    }

    public StructureAssetFootprint footprint() {
        return this.data.footprint();
    }

    public StructureAssetPlacement placement() {
        return this.data.placement();
    }

    public boolean scanMarkers() {
        return this.data.scanMarkers();
    }

    public List<ScannedTemplateMarker> markers() {
        if (this.scanResult == null) {
            return List.of();
        }
        return this.scanResult.markers();
    }

    public List<String> validate() {
        List<String> errors = new ArrayList<>(this.loadErrors);
        errors.addAll(this.data.validate());
        if (this.scanMarkers() && this.scanResult == null) {
            errors.add("template missing or could not be read: " + this.template());
        }
        for (ScannedTemplateMarker marker : this.markers()) {
            TemplateMarkerData markerData = marker.data();
            for (String markerError : TemplateMarkerValidation.validateForSave(markerData)) {
                errors.add("marker " + formatPos(marker.localPos()) + ": " + markerError);
            }
            if (markerData.kind() == MarkerKind.CONNECTOR && markerData.id() == null) {
                errors.add("marker " + formatPos(marker.localPos()) + ": connector id is required");
            }
        }
        return errors;
    }

    public boolean isValid() {
        return this.validate().isEmpty();
    }

    private static String formatPos(net.minecraft.core.Vec3i pos) {
        return "[" + pos.getX() + "," + pos.getY() + "," + pos.getZ() + "]";
    }
}
