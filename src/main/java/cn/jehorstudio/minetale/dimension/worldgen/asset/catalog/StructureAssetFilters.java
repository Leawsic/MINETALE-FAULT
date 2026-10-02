package cn.jehorstudio.minetale.dimension.worldgen.asset.catalog;

import java.util.List;
import net.minecraft.resources.ResourceLocation;

public final class StructureAssetFilters {
    private StructureAssetFilters() {
    }

    public static List<StructureAssetDefinition> all() {
        return StructureAssetCatalog.list();
    }

    public static List<StructureAssetDefinition> byTag(ResourceLocation tag) {
        if (tag == null) {
            return all();
        }
        return StructureAssetCatalog.list().stream()
                .filter(asset -> asset.tags().contains(tag))
                .toList();
    }
}
