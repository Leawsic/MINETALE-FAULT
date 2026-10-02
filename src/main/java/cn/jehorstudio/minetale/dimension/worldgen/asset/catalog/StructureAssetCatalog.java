package cn.jehorstudio.minetale.dimension.worldgen.asset.catalog;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;

public final class StructureAssetCatalog {
    private static volatile Map<ResourceLocation, StructureAssetDefinition> definitions = Map.of();
    private static volatile long revision = 0L;

    private StructureAssetCatalog() {
    }

    static void replace(Map<ResourceLocation, StructureAssetDefinition> nextDefinitions) {
        LinkedHashMap<ResourceLocation, StructureAssetDefinition> sorted = new LinkedHashMap<>();
        nextDefinitions.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> sorted.put(entry.getKey(), entry.getValue()));
        definitions = Map.copyOf(sorted);
        revision++;
    }

    public static Optional<StructureAssetDefinition> get(ResourceLocation id) {
        return Optional.ofNullable(definitions.get(id));
    }

    public static List<StructureAssetDefinition> list() {
        return definitions.values().stream()
                .sorted(Comparator.comparing(definition -> definition.id().toString()))
                .toList();
    }

    public static Collection<ResourceLocation> ids() {
        return definitions.keySet();
    }

    public static int size() {
        return definitions.size();
    }

    public static long revision() {
        return revision;
    }
}
