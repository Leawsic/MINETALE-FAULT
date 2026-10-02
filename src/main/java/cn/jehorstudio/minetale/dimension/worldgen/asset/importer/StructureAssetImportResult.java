package cn.jehorstudio.minetale.dimension.worldgen.asset.importer;

import java.nio.file.Path;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.ResourceLocation;

public record StructureAssetImportResult(
        ResourceLocation templateId,
        Path sourceNbt,
        Path targetNbt,
        Path targetAssetJson,
        Vec3i size,
        int markerCount,
        int blockerCount,
        boolean structureOverwritten,
        boolean assetOverwritten,
        boolean wroteFiles,
        String draftJson
) {
    public boolean overwritten() {
        return this.structureOverwritten || this.assetOverwritten;
    }
}
