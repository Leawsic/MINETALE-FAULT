package cn.jehorstudio.minetale.dimension.worldgen.asset.importer;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

public final class SavedStructureLocator {
    private SavedStructureLocator() {
    }

    public static Optional<Path> locate(MinecraftServer server, ResourceLocation templateId) {
        Path generatedRoot = server.getWorldPath(LevelResource.GENERATED_DIR);
        for (Path candidate : candidates(generatedRoot, templateId)) {
            if (java.nio.file.Files.isRegularFile(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    public static List<Path> candidates(Path generatedRoot, ResourceLocation templateId) {
        String path = templateId.getPath() + ".nbt";
        return List.of(
                generatedRoot.resolve(templateId.getNamespace()).resolve("structures").resolve(path).normalize(),
                generatedRoot.resolve(templateId.getNamespace()).resolve("structure").resolve(path).normalize()
        );
    }
}