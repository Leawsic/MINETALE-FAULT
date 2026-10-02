package cn.jehorstudio.minetale.dimension.worldgen.asset.importer;

import cn.jehorstudio.minetale.dimension.worldgen.asset.blocker.PlacementBlockerScanner;
import cn.jehorstudio.minetale.dimension.worldgen.asset.scanner.StructureAssetScanner;
import cn.jehorstudio.minetale.dimension.worldgen.asset.scanner.TemplateScanResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

public final class StructureAssetImportTool {
    private static final Path DRAFT_RESOURCE_ROOT = Path.of("src/main/resources");

    private StructureAssetImportTool() {
    }

    public static boolean enabled() {
        return StructureAssetImportConfig.enabled();
    }

    public static boolean hasResourceRoot() {
        return !StructureAssetImportConfig.resourceRoot().isBlank();
    }

    public static StructureAssetImportResult draft(ServerLevel level, ResourceLocation templateId, String role, ResourceLocation category) throws IOException {
        Path source = SavedStructureLocator.locate(level.getServer(), templateId)
                .orElseThrow(() -> new MissingSavedStructureException(templateId));
        TemplateData templateData = readTemplateData(level, templateId, source);
        StructureAssetJsonDraft draft = new StructureAssetJsonDraft(templateId, role, category, templateData.size());
        return new StructureAssetImportResult(
                templateId,
                source,
                targetStructurePath(DRAFT_RESOURCE_ROOT, templateId),
                targetAssetPath(DRAFT_RESOURCE_ROOT, templateId),
                templateData.size(),
                templateData.markerCount(),
                templateData.blockerCount(),
                false,
                false,
                false,
                draft.toJson()
        );
    }

    public static StructureAssetImportResult importSaved(ServerLevel level, ResourceLocation templateId, String role, ResourceLocation category) throws IOException {
        Path source = SavedStructureLocator.locate(level.getServer(), templateId)
                .orElseThrow(() -> new MissingSavedStructureException(templateId));
        TemplateData templateData = readTemplateData(level, templateId, source);
        StructureAssetJsonDraft draft = new StructureAssetJsonDraft(templateId, role, category, templateData.size());

        Path root = Path.of(StructureAssetImportConfig.resourceRoot()).toAbsolutePath().normalize();
        Path targetNbt = targetStructurePath(root, templateId);
        Path targetJson = targetAssetPath(root, templateId);
        boolean structureOverwritten = Files.exists(targetNbt);
        boolean assetOverwritten = Files.exists(targetJson);

        Files.createDirectories(targetNbt.getParent());
        Files.createDirectories(targetJson.getParent());
        Files.copy(source, targetNbt, StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(targetJson, draft.toJson());

        return new StructureAssetImportResult(
                templateId,
                source,
                targetNbt,
                targetJson,
                templateData.size(),
                templateData.markerCount(),
                templateData.blockerCount(),
                structureOverwritten,
                assetOverwritten,
                true,
                draft.toJson()
        );
    }

    private static Path targetStructurePath(Path root, ResourceLocation templateId) {
        return root.resolve("data")
                .resolve(templateId.getNamespace())
                .resolve("structure")
                .resolve(templateId.getPath() + ".nbt")
                .normalize();
    }

    private static Path targetAssetPath(Path root, ResourceLocation templateId) {
        return root.resolve("data")
                .resolve(templateId.getNamespace())
                .resolve("structure_assets")
                .resolve(templateId.getPath() + ".json")
                .normalize();
    }

    private static TemplateData readTemplateData(ServerLevel level, ResourceLocation templateId, Path source) throws IOException {
        CompoundTag raw = NbtIo.readCompressed(source, NbtAccounter.unlimitedHeap());
        Vec3i size = readSize(raw);
        int dataVersion = NbtUtils.getDataVersion(raw, 500);
        CompoundTag updated = DataFixTypes.STRUCTURE.updateToCurrentVersion(level.getServer().getFixerUpper(), raw, dataVersion);
        StructureTemplate template = new StructureTemplate();
        template.load(level.registryAccess().lookupOrThrow(Registries.BLOCK), updated);
        TemplateScanResult scanResult = StructureAssetScanner.scanTemplate(level.registryAccess(), templateId, template);
        int blockerCount = PlacementBlockerScanner.scanTemplate(template).size();
        return new TemplateData(size, scanResult.markers().size(), blockerCount);
    }

    private static Vec3i readSize(CompoundTag tag) {
        ListTag size = tag.getListOrEmpty("size");
        if (size.size() < 3) {
            return Vec3i.ZERO;
        }
        return new Vec3i(size.getIntOr(0, 0), size.getIntOr(1, 0), size.getIntOr(2, 0));
    }

    public static final class MissingSavedStructureException extends IOException {
        public MissingSavedStructureException(ResourceLocation templateId) {
            super(templateId.toString());
        }
    }

    private record TemplateData(Vec3i size, int markerCount, int blockerCount) {
    }
}
