package cn.jehorstudio.minetale.contentpack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

// 内嵌 main Pack 已发布资源的机器可读目录。
public final class MainContentPackCatalog {
    private static final Catalog CATALOG = load();

    private MainContentPackCatalog() {}

    public static ContentPackSemVer version() { return CATALOG.version(); }

    public static Export export(String type, ResourceLocation resourceId) {
        return CATALOG.exports().get(new Key(type, resourceId));
    }

    public static Map<Key, Export> exports() { return CATALOG.exports(); }

    private static Catalog load() {
        try (InputStream stream = MainContentPackCatalog.class.getResourceAsStream("/minetale-main-contentpack.json")) {
            if (stream == null) throw new IllegalStateException("缺少 /minetale-main-contentpack.json。");
            JsonObject root = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            rejectUnknown(root, Set.of("format", "formatVersion", "version", "exports"), "main catalog");
            ContentPackManifest.require(root.has("format") && root.get("format").getAsString().equals("minetale-main-export-catalog"), "main catalog format 无效。");
            ContentPackManifest.require(root.has("formatVersion") && root.get("formatVersion").getAsInt() == 1, "main catalog formatVersion 无效。");
            ContentPackSemVer version = ContentPackSemVer.parse(requiredString(root, "version", "main catalog"));
            ContentPackManifest.require(root.has("exports") && root.get("exports").isJsonArray(), "main catalog exports 必须是数组。");
            Map<Key, Export> exports = new HashMap<>(); JsonArray values = root.getAsJsonArray("exports");
            for (int index = 0; index < values.size(); index++) {
                String path = "main catalog exports[" + index + "]"; JsonElement element = values.get(index);
                ContentPackManifest.require(element.isJsonObject(), path + " 必须是对象。"); JsonObject value = element.getAsJsonObject();
                rejectUnknown(value, Set.of("resourceId", "type", "displayName", "replacement", "stateAccess"), path);
                ResourceLocation id = ResourceLocation.tryParse(requiredString(value, "resourceId", path));
                ContentPackManifest.require(id != null, path + ".resourceId 无效。");
                String type = requiredString(value, "type", path);
                ContentPackManifest.require(Set.of("battle", "actor_prefab", "pattern", "dialogue", "story_state").contains(type), path + ".type 无效。");
                String replacement = requiredString(value, "replacement", path);
                ContentPackManifest.require(replacement.equals("sealed") || replacement.equals("replaceable"), path + ".replacement 无效。");
                String stateAccess = value.has("stateAccess") ? requiredString(value, "stateAccess", path) : null;
                ContentPackManifest.require(type.equals("story_state") || stateAccess == null, path + ".stateAccess 只适用于 story_state。");
                if (stateAccess != null) ContentPackManifest.require(stateAccess.equals("read_only") || stateAccess.equals("read_write"), path + ".stateAccess 无效。");
                Key key = new Key(type, id);
                ContentPackManifest.require(exports.putIfAbsent(key, new Export(id, type, requiredString(value, "displayName", path), replacement, stateAccess)) == null, "main catalog export 重复：" + key);
            }
            return new Catalog(version, Map.copyOf(exports));
        } catch (Exception exception) {
            if (exception instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("无法加载 main Content Pack Catalog。", exception);
        }
    }

    private static String requiredString(JsonObject root, String member, String path) {
        ContentPackManifest.require(root.has(member) && root.get(member).isJsonPrimitive() && root.get(member).getAsJsonPrimitive().isString(), path + "." + member + " 必须是字符串。");
        String value = root.get(member).getAsString(); ContentPackManifest.require(!value.isBlank(), path + "." + member + " 不能为空。"); return value;
    }

    private static void rejectUnknown(JsonObject root, Set<String> allowed, String path) {
        for (String key : root.keySet()) ContentPackManifest.require(allowed.contains(key), path + " 包含未知字段 " + key + "。");
    }

    public record Key(String type, ResourceLocation resourceId) {}
    public record Export(ResourceLocation resourceId, String type, String displayName, String replacement, String stateAccess) {}
    private record Catalog(ContentPackSemVer version, Map<Key, Export> exports) {}
}
