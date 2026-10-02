package cn.jehorstudio.minetale.contentpack;

import cn.jehorstudio.minetale.MineTale;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

// 保存世界选择的精确 Pack ID、版本与摘要，在 Minecraft 静默移除缺失数据包前先显式报错。
public final class ContentPackActiveProfile {
    static final String FORMAT = "minetale-active-content-pack-profile";
    static final int FORMAT_VERSION = 1;
    private static final String FILE_NAME = "contentpack-profile.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private ContentPackActiveProfile() {}

    public static void verifyOnAboutToStart(ServerAboutToStartEvent event) {
        Path path = profilePath(event.getServer());
        if (!Files.isRegularFile(path)) return;
        try {
            List<ProfileEntry> expected = read(path);
            List<ProfileEntry> actual = snapshot(event.getServer());
            if (!expected.equals(actual)) throw new IllegalStateException(describeMismatch(expected, actual, path));
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("MineTale Content Pack Active Profile 无法恢复；请恢复缺失归档或显式修复世界配置：" + path, exception);
        }
    }

    public static void saveOnStarted(ServerStartedEvent event) {
        try {
            save(event.getServer());
        } catch (IOException exception) {
            throw new IllegalStateException("无法保存 MineTale Content Pack Active Profile。", exception);
        }
    }

    public static void saveAfterReload(OnDatapackSyncEvent event) {
        if (event.getPlayer() != null) return;
        try {
            save(event.getPlayerList().getServer());
        } catch (IOException exception) {
            MineTale.LOGGER.error("Could not save MineTale Content Pack Active Profile after reload", exception);
        }
    }

    public static void save(MinecraftServer server) throws IOException {
        Path path = profilePath(server);
        Files.createDirectories(path.getParent());
        JsonObject root = new JsonObject();
        root.addProperty("format", FORMAT);
        root.addProperty("formatVersion", FORMAT_VERSION);
        JsonArray packs = new JsonArray();
        for (ProfileEntry entry : snapshot(server)) {
            JsonObject pack = new JsonObject();
            pack.addProperty("repositoryId", entry.repositoryId());
            pack.addProperty("contentPackId", entry.contentPackId());
            pack.addProperty("version", entry.version());
            pack.addProperty("contentDigest", entry.contentDigest());
            if (entry.developmentWorkspaceUuid() != null) pack.addProperty("developmentWorkspaceUuid", entry.developmentWorkspaceUuid());
            packs.add(pack);
        }
        root.add("packs", packs);

        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
            GSON.toJson(root, writer);
        }
        try {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    static List<ProfileEntry> read(Path path) throws IOException {
        JsonObject root;
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonElement value = JsonParser.parseReader(reader);
            ContentPackManifest.require(value.isJsonObject(), "Active Profile 根必须是对象。");
            root = value.getAsJsonObject();
        }
        for (String key : root.keySet()) ContentPackManifest.require(Set.of("format", "formatVersion", "packs").contains(key), "Active Profile 包含未知字段 " + key + "。");
        ContentPackManifest.require(root.has("format") && FORMAT.equals(root.get("format").getAsString()), "Active Profile format 无效。");
        ContentPackManifest.require(root.has("formatVersion") && root.get("formatVersion").getAsInt() == FORMAT_VERSION, "Active Profile formatVersion 无效。");
        ContentPackManifest.require(root.has("packs") && root.get("packs").isJsonArray(), "Active Profile packs 必须是数组。");
        List<ProfileEntry> result = new ArrayList<>();
        for (JsonElement element : root.getAsJsonArray("packs")) {
            ContentPackManifest.require(element.isJsonObject(), "Active Profile pack 必须是对象。");
            JsonObject pack = element.getAsJsonObject();
            for (String key : pack.keySet()) ContentPackManifest.require(Set.of("repositoryId", "contentPackId", "version", "contentDigest", "developmentWorkspaceUuid").contains(key), "Active Profile pack 包含未知字段 " + key + "。");
            ProfileEntry entry = new ProfileEntry(
                    requiredString(pack, "repositoryId"),
                    requiredString(pack, "contentPackId"),
                    requiredString(pack, "version"),
                    requiredString(pack, "contentDigest"),
                    pack.has("developmentWorkspaceUuid") ? requiredString(pack, "developmentWorkspaceUuid") : null);
            ContentPackSemVer.parse(entry.version());
            ContentPackManifest.require(entry.contentDigest().matches("[0-9a-f]{64}"), "Active Profile contentDigest 无效。");
            String expectedRepositoryId = entry.developmentWorkspaceUuid() == null
                    ? ContentPackRepository.PACK_ID_PREFIX + entry.contentPackId() + "/" + entry.version() + "/" + entry.contentDigest()
                    : ContentPackRepository.PACK_ID_PREFIX + "dev/" + entry.developmentWorkspaceUuid();
            if (entry.developmentWorkspaceUuid() != null) {
                try { java.util.UUID.fromString(entry.developmentWorkspaceUuid()); }
                catch (IllegalArgumentException exception) { throw new IllegalArgumentException("Active Profile developmentWorkspaceUuid 无效。", exception); }
            }
            ContentPackManifest.require(entry.repositoryId().equals(expectedRepositoryId), "Active Profile repositoryId 与身份字段不一致。");
            ContentPackManifest.require(result.stream().noneMatch(previous -> previous.contentPackId().equals(entry.contentPackId())), "Active Profile 不能包含同 ID 多个版本。");
            result.add(entry);
        }
        return List.copyOf(result);
    }

    static List<ProfileEntry> snapshot(MinecraftServer server) {
        List<ContentPackRepository.InstalledPack> installedPacks = ContentPackRepository.scanInstalled();
        List<ProfileEntry> result = new ArrayList<>();
        for (Pack pack : server.getPackRepository().getSelectedPacks()) {
            if (!pack.getId().startsWith(ContentPackRepository.PACK_ID_PREFIX)) continue;
            ContentPackRepository.InstalledPack installed = installedPacks.stream()
                    .filter(candidate -> candidate.repositoryId().equals(pack.getId()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("活动 Content Pack 不在已验证安装库中：" + pack.getId()));
            ContentPackManifest manifest = installed.manifest();
            result.add(new ProfileEntry(pack.getId(), manifest.contentPackId(), manifest.version().toString(), manifest.contentDigest(),
                    manifest.development() == null ? null : manifest.development().workspaceUuid()));
        }
        return List.copyOf(result);
    }

    private static String requiredString(JsonObject object, String key) {
        ContentPackManifest.require(object.has(key) && object.get(key).isJsonPrimitive() && object.get(key).getAsJsonPrimitive().isString(), "Active Profile " + key + " 必须是字符串。");
        String value = object.get(key).getAsString();
        ContentPackManifest.require(!value.isBlank(), "Active Profile " + key + " 不能为空。");
        return value;
    }

    private static String describeMismatch(List<ProfileEntry> expected, List<ProfileEntry> actual, Path path) {
        return "世界固定的 Content Pack 与当前可用活动栈不同。expected=" + expected.stream().map(ProfileEntry::identity).toList()
                + ", actual=" + actual.stream().map(ProfileEntry::identity).toList() + ", profile=" + path;
    }

    private static Path profilePath(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("minetale").resolve(FILE_NAME).toAbsolutePath().normalize();
    }

    record ProfileEntry(String repositoryId, String contentPackId, String version, String contentDigest, String developmentWorkspaceUuid) {
        String identity() { return contentPackId + "@" + version + "#" + contentDigest + (developmentWorkspaceUuid == null ? "" : " (dev " + developmentWorkspaceUuid + ")"); }
    }
}
