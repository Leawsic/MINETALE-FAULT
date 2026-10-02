package cn.jehorstudio.minetale.dimension.worldgen.asset.catalog;

import cn.jehorstudio.minetale.MineTale;
import cn.jehorstudio.minetale.dimension.worldgen.asset.scanner.StructureAssetScanner;
import cn.jehorstudio.minetale.dimension.worldgen.asset.scanner.TemplateScanResult;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.neoforged.neoforge.event.AddServerReloadListenersEvent;
import org.jetbrains.annotations.Nullable;

public final class StructureAssetReloadListener extends SimpleJsonResourceReloadListener<StructureAssetDefinitionData> {
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(MineTale.MODID, "structure_asset_catalog");
    private static final FileToIdConverter STRUCTURE_ASSET_LISTER = FileToIdConverter.json("structure_assets");
    private static final FileToIdConverter STRUCTURE_TEMPLATE_LISTER = new FileToIdConverter("structure", ".nbt");

    private final RegistryAccess registryAccess;

    private StructureAssetReloadListener(RegistryAccess registryAccess) {
        super(StructureAssetDefinitionData.CODEC, STRUCTURE_ASSET_LISTER);
        this.registryAccess = registryAccess;
    }

    public static void register(AddServerReloadListenersEvent event) {
        event.addListener(ID, new StructureAssetReloadListener(event.getRegistryAccess()));
    }

    @Override
    protected void apply(Map<ResourceLocation, StructureAssetDefinitionData> loadedData, ResourceManager resourceManager, ProfilerFiller profiler) {
        Map<ResourceLocation, StructureAssetDefinition> loadedDefinitions = new HashMap<>();
        int scanFailures = 0;
        for (Map.Entry<ResourceLocation, StructureAssetDefinitionData> entry : loadedData.entrySet()) {
            ResourceLocation id = entry.getKey();
            StructureAssetDefinitionData data = entry.getValue();
            ScanOutcome scanOutcome = scanMarkersIfRequested(data, resourceManager);
            if (!scanOutcome.errors().isEmpty()) {
                scanFailures++;
            }
            loadedDefinitions.put(id, new StructureAssetDefinition(id, data, scanOutcome.scanResult(), scanOutcome.errors()));
        }

        StructureAssetCatalog.replace(loadedDefinitions);
        long invalidCount = StructureAssetCatalog.list().stream().filter(definition -> !definition.isValid()).count();
        MineTale.LOGGER.info(
                "Loaded {} structure asset definitions ({} invalid, {} scan failures)",
                StructureAssetCatalog.size(),
                invalidCount,
                scanFailures
        );
    }

    private ScanOutcome scanMarkersIfRequested(StructureAssetDefinitionData data, ResourceManager resourceManager) {
        if (!data.scanMarkers()) {
            return new ScanOutcome(null, List.of());
        }

        List<String> errors = new ArrayList<>();
        ResourceLocation templateId = data.template();
        ResourceLocation templateFile = STRUCTURE_TEMPLATE_LISTER.idToFile(templateId);
        Resource resource = resourceManager.getResource(templateFile).orElse(null);
        if (resource == null) {
            errors.add("template missing: " + templateId);
            return new ScanOutcome(null, errors);
        }

        try (InputStream inputStream = resource.open()) {
            StructureTemplate template = readTemplate(inputStream);
            TemplateScanResult scanResult = StructureAssetScanner.scanTemplate(this.registryAccess, templateId, template);
            return new ScanOutcome(scanResult, errors);
        } catch (RuntimeException | IOException ex) {
            MineTale.LOGGER.error("Could not scan structure asset template {}", templateId, ex);
            errors.add("template could not be read: " + templateId + " (" + ex.getMessage() + ")");
            return new ScanOutcome(null, errors);
        }
    }

    private StructureTemplate readTemplate(InputStream inputStream) throws IOException {
        CompoundTag rawTag = NbtIo.readCompressed(inputStream, NbtAccounter.unlimitedHeap());
        int dataVersion = NbtUtils.getDataVersion(rawTag, 500);
        CompoundTag updatedTag = DataFixTypes.STRUCTURE.updateToCurrentVersion(DataFixers.getDataFixer(), rawTag, dataVersion);
        StructureTemplate template = new StructureTemplate();
        template.load(this.registryAccess.lookupOrThrow(Registries.BLOCK), updatedTag);
        return template;
    }

    private record ScanOutcome(@Nullable TemplateScanResult scanResult, List<String> errors) {
        private ScanOutcome {
            errors = List.copyOf(errors == null ? List.of() : errors);
        }
    }
}