package cn.jehorstudio.minetale.contentpack;

import cn.jehorstudio.minetale.MineTale;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.FilePackResources;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.event.AddPackFindersEvent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;

// 将安装库中通过验证的 .mtpack 暴露为服务器数据包。
public final class ContentPackRepository {
    public static final String PACK_ID_PREFIX = "minetale/contentpack/";
    private static final PackSelectionConfig SELECTION = new PackSelectionConfig(false, Pack.Position.TOP, false);
    private static final PackSource CONTENT_PACK_SOURCE = PackSource.create(value -> value, false);

    private ContentPackRepository() {}

    public static Path libraryPath() {
        return FMLPaths.GAMEDIR.get().resolve("minetale").resolve("contentpack").toAbsolutePath().normalize();
    }

    public static void register(AddPackFindersEvent event) {
        if (event.getPackType() != PackType.SERVER_DATA) return;
        event.addRepositorySource(ContentPackRepository::loadPacks);
    }

    public static List<InstalledPack> scanInstalled() {
        return inspectLibrary().installed();
    }

    // 扫描结果必须保留拒绝原因，供管理界面解释无效归档。
    public static LibraryInspection inspectLibrary() {
        Path library = libraryPath();
        try { Files.createDirectories(library); }
        catch (IOException exception) {
            MineTale.LOGGER.error("Could not create MineTale Content Pack library {}", library, exception);
            return new LibraryInspection(List.of(), List.of(new RejectedPack(library, exception.getMessage())));
        }
        List<ContentPackArchiveValidator.ValidatedArchive> validated = new ArrayList<>();
        List<RejectedPack> rejected = new ArrayList<>();
        try (Stream<Path> paths = Files.list(library)) {
            paths.filter(path -> path.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".mtpack"))
                    .sorted().forEach(path -> {
                        try { validated.add(ContentPackArchiveValidator.validate(path)); }
                        catch (IOException | RuntimeException exception) {
                            MineTale.LOGGER.error("Rejected MineTale Content Pack {}", path, exception);
                            rejected.add(new RejectedPack(path, readableMessage(exception)));
                        }
                    });
        } catch (IOException exception) {
            MineTale.LOGGER.error("Could not scan MineTale Content Pack library {}", library, exception);
            rejected.add(new RejectedPack(library, readableMessage(exception)));
        }

        Map<String, ContentPackArchiveValidator.ValidatedArchive> unique = new HashMap<>();
        Map<String, Boolean> conflicted = new HashMap<>();
        for (ContentPackArchiveValidator.ValidatedArchive archive : validated) {
            String identity = archive.manifest().development() == null
                    ? archive.manifest().contentPackId() + "@" + archive.manifest().version()
                    : "dev:" + archive.manifest().development().workspaceUuid();
            ContentPackArchiveValidator.ValidatedArchive previous = unique.putIfAbsent(identity, archive);
            if (previous != null && !previous.manifest().contentDigest().equals(archive.manifest().contentDigest())) {
                conflicted.put(identity, true);
                String reason = "同一 Content Pack ID/version 存在不同摘要：" + identity;
                rejected.add(new RejectedPack(previous.path(), reason));
                rejected.add(new RejectedPack(archive.path(), reason));
                MineTale.LOGGER.error("Rejected duplicate Content Pack identity {} with different digests: {} and {}", identity, previous.path(), archive.path());
            }
        }
        List<InstalledPack> installed = unique.entrySet().stream().filter(entry -> !conflicted.containsKey(entry.getKey()))
                .map(entry -> new InstalledPack(entry.getValue().path(), entry.getValue().manifest(), repositoryPackId(entry.getValue().manifest())))
                .sorted(Comparator.comparing((InstalledPack pack) -> pack.manifest().contentPackId()).thenComparing(pack -> pack.manifest().version()))
                .toList();
        return new LibraryInspection(installed, List.copyOf(rejected));
    }

    private static String readableMessage(Throwable exception) {
        Throwable current = exception;
        while (current.getCause() != null && (current.getMessage() == null || current.getMessage().isBlank())) current = current.getCause();
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    private static void loadPacks(Consumer<Pack> acceptor) {
        for (InstalledPack installed : scanInstalled()) {
            ContentPackManifest manifest = installed.manifest();
            PackLocationInfo location = new PackLocationInfo(
                    installed.repositoryId(),
                    packTitle(manifest),
                    CONTENT_PACK_SOURCE,
                    Optional.empty()
            );
            Pack pack = Pack.readMetaAndCreate(location, new FilePackResources.FileResourcesSupplier(installed.path()), PackType.SERVER_DATA, SELECTION);
            if (pack == null) MineTale.LOGGER.error("Rejected Content Pack {} because pack.mcmeta is missing or incompatible", installed.path());
            else acceptor.accept(pack);
        }
    }

    static PackSource packSource() {
        return CONTENT_PACK_SOURCE;
    }

    public static Component packTitle(ContentPackManifest manifest) {
        String title = manifest.displayName() + " " + manifest.version();
        if (manifest.development() == null) return Component.literal(title);
        String workspaceUuid = manifest.development().workspaceUuid();
        String shortUuid = workspaceUuid.substring(0, Math.min(8, workspaceUuid.length()));
        return Component.literal(title + " [DEV " + shortUuid + "]");
    }

    public static String repositoryPackId(ContentPackManifest manifest) {
        if (manifest.development() != null) return PACK_ID_PREFIX + "dev/" + manifest.development().workspaceUuid();
        // 世界会持久化该 Pack ID，因此 ID 同时固定版本与完整摘要。
        return PACK_ID_PREFIX + manifest.contentPackId() + "/" + manifest.version() + "/" + manifest.contentDigest();
    }

    public record InstalledPack(Path path, ContentPackManifest manifest, String repositoryId) {}
    public record RejectedPack(Path path, String reason) {}
    public record LibraryInspection(List<InstalledPack> installed, List<RejectedPack> rejected) {
        public LibraryInspection {
            installed = List.copyOf(installed);
            rejected = List.copyOf(rejected);
        }
    }
}
