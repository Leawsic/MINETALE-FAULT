package cn.jehorstudio.minetale.contentpack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public record ContentPackManifest(
        String contentPackId,
        String displayName,
        ContentPackSemVer version,
        ContentPackSemVer minMineTaleVersion,
        String contentDigest,
        List<Dependency> dependencies,
        List<Export> exports,
        List<Override> overrides,
        Development development,
        List<Resource> resources
) {
    public static final String FORMAT = "minetale-content-pack";
    public static final int FORMAT_VERSION = 1;
    private static final Set<String> ROOT_KEYS = Set.of("format", "formatVersion", "contentPackId", "displayName", "version", "minMineTaleVersion", "contentDigest", "dependencies", "exports", "overrides", "development", "resources");

    public ContentPackManifest {
        dependencies = List.copyOf(dependencies); exports = List.copyOf(exports); overrides = List.copyOf(overrides); resources = List.copyOf(resources);
    }

    public static ContentPackManifest parse(JsonObject root) {
        rejectUnknown(root, ROOT_KEYS, "contentpack.json");
        require(FORMAT.equals(string(root, "format", "contentpack.json")), "contentpack.json.format 无效。");
        require(integer(root, "formatVersion", "contentpack.json") == FORMAT_VERSION, "contentpack.json.formatVersion 无效。");
        String contentPackId = contentPackId(string(root, "contentPackId", "contentpack.json"), false);
        String displayName = string(root, "displayName", "contentpack.json");
        ContentPackSemVer version = ContentPackSemVer.parse(string(root, "version", "contentpack.json"));
        ContentPackSemVer minimum = ContentPackSemVer.parse(string(root, "minMineTaleVersion", "contentpack.json"));
        String digest = sha(string(root, "contentDigest", "contentpack.json"), "contentpack.json.contentDigest");
        List<Dependency> dependencies = parseDependencies(array(root, "dependencies", "contentpack.json"));
        List<Export> exports = parseExports(array(root, "exports", "contentpack.json"));
        List<Override> overrides = parseOverrides(array(root, "overrides", "contentpack.json"));
        Development development = parseDevelopment(root);
        List<Resource> resources = parseResources(array(root, "resources", "contentpack.json"));
        require(new HashSet<>(dependencies.stream().map(Dependency::contentPackId).toList()).size() == dependencies.size(), "Content Pack 依赖不能重复。");
        require(new HashSet<>(exports.stream().map(value -> value.type + "\0" + value.resourceId).toList()).size() == exports.size(), "Content Pack export 不能重复。");
        require(new HashSet<>(resources.stream().map(Resource::path).toList()).size() == resources.size(), "Content Pack 资源路径不能重复。");
        return new ContentPackManifest(contentPackId, displayName, version, minimum, digest, dependencies, exports, overrides, development, resources);
    }

    private static Development parseDevelopment(JsonObject root) {
        if (!root.has("development")) return null;
        JsonObject value = object(root.get("development"), "contentpack.json.development");
        rejectUnknown(value, Set.of("workspaceUuid"), "contentpack.json.development");
        String workspaceUuid = string(value, "workspaceUuid", "contentpack.json.development");
        try { java.util.UUID.fromString(workspaceUuid); }
        catch (IllegalArgumentException exception) { throw new IllegalArgumentException("contentpack.json.development.workspaceUuid 不是有效 UUID。", exception); }
        return new Development(workspaceUuid);
    }

    public Export export(String type, ResourceLocation id) {
        return exports.stream().filter(value -> value.type.equals(type) && value.resourceId.equals(id)).findFirst().orElse(null);
    }

    private static List<Dependency> parseDependencies(JsonArray array) {
        List<Dependency> result = new ArrayList<>();
        for (int index = 0; index < array.size(); index++) {
            String path = "contentpack.json.dependencies[" + index + "]"; JsonObject item = object(array.get(index), path);
            rejectUnknown(item, Set.of("contentPackId", "versionRange"), path);
            String id = contentPackId(string(item, "contentPackId", path), false); String range = string(item, "versionRange", path);
            ContentPackSemVer.satisfies(new ContentPackSemVer(0, 0, 0, ""), range);
            result.add(new Dependency(id, range));
        }
        return result;
    }

    private static List<Export> parseExports(JsonArray array) {
        List<Export> result = new ArrayList<>();
        for (int index = 0; index < array.size(); index++) {
            String path = "contentpack.json.exports[" + index + "]"; JsonObject item = object(array.get(index), path);
            rejectUnknown(item, Set.of("resourceId", "type", "displayName", "replacement", "stateAccess"), path);
            String type = resourceType(string(item, "type", path));
            String replacement = oneOf(string(item, "replacement", path), Set.of("sealed", "replaceable"), path + ".replacement");
            String access = item.has("stateAccess") ? oneOf(string(item, "stateAccess", path), Set.of("read_only", "read_write"), path + ".stateAccess") : null;
            require(type.equals("story_state") || access == null, path + ".stateAccess 只适用于 story_state。");
            result.add(new Export(resourceLocation(string(item, "resourceId", path), path), type, string(item, "displayName", path), replacement, access));
        }
        return result;
    }

    private static List<Override> parseOverrides(JsonArray array) {
        List<Override> result = new ArrayList<>();
        for (int index = 0; index < array.size(); index++) {
            String path = "contentpack.json.overrides[" + index + "]"; JsonObject item = object(array.get(index), path);
            rejectUnknown(item, Set.of("providerContentPackId", "resourceId", "type", "compatibleVersionRange"), path);
            String range = string(item, "compatibleVersionRange", path); ContentPackSemVer.satisfies(new ContentPackSemVer(0, 0, 0, ""), range);
            result.add(new Override(contentPackId(string(item, "providerContentPackId", path), true), resourceLocation(string(item, "resourceId", path), path), resourceType(string(item, "type", path)), range));
        }
        return result;
    }

    private static List<Resource> parseResources(JsonArray array) {
        List<Resource> result = new ArrayList<>(); Set<String> caseFolded = new LinkedHashSet<>();
        for (int index = 0; index < array.size(); index++) {
            String path = "contentpack.json.resources[" + index + "]"; JsonObject item = object(array.get(index), path);
            rejectUnknown(item, Set.of("path", "sha256", "kind", "resourceId", "locale"), path);
            String archivePath = safePath(string(item, "path", path));
            require(caseFolded.add(archivePath.toLowerCase(Locale.ROOT)), "Content Pack 资源路径发生大小写碰撞：" + archivePath);
            String kind = oneOf(string(item, "kind", path), Set.of("battle", "dialogue", "story_state", "asset", "data", "metadata"), path + ".kind");
            ResourceLocation resourceId = item.has("resourceId") ? resourceLocation(string(item, "resourceId", path), path) : null;
            String locale = item.has("locale") ? string(item, "locale", path) : null;
            require(kind.equals("dialogue") || locale == null, path + ".locale 只适用于 dialogue。");
            require(!Set.of("battle", "dialogue", "story_state").contains(kind) || resourceId != null, path + " 缺少 resourceId。");
            result.add(new Resource(archivePath, sha(string(item, "sha256", path), path + ".sha256"), kind, resourceId, locale));
        }
        return result;
    }

    static String safePath(String path) {
        require(!path.isBlank() && !path.startsWith("/") && !path.contains("\\") && !path.matches("^[A-Za-z]:.*"), "归档路径无效：" + path);
        for (String segment : path.split("/", -1)) require(!segment.isBlank() && !segment.equals(".") && !segment.equals(".."), "归档路径无效：" + path);
        return path;
    }

    private static String contentPackId(String text, boolean mainAllowed) { if (mainAllowed && text.equals("main")) return text; resourceLocation(text, "Content Pack ID"); require(!text.equals("main"), "外部 Content Pack ID 不能是 main。"); return text; }
    private static ResourceLocation resourceLocation(String text, String path) { ResourceLocation id = ResourceLocation.tryParse(text); require(id != null, path + " 不是有效 ResourceLocation：" + text); return id; }
    private static String resourceType(String text) { return oneOf(text, Set.of("battle", "actor_prefab", "pattern", "dialogue", "story_state"), "resource type"); }
    private static String sha(String text, String path) { require(text.matches("[0-9a-f]{64}"), path + " 必须是小写 SHA-256。"); return text; }
    private static String oneOf(String value, Set<String> allowed, String path) { require(allowed.contains(value), path + " 无效：" + value); return value; }
    private static JsonObject object(JsonElement value, String path) { require(value != null && value.isJsonObject(), path + " 必须是对象。"); return value.getAsJsonObject(); }
    private static JsonArray array(JsonObject root, String member, String path) { require(root.has(member) && root.get(member).isJsonArray(), path + "." + member + " 必须是数组。"); return root.getAsJsonArray(member); }
    private static String string(JsonObject root, String member, String path) { require(root.has(member) && root.get(member).isJsonPrimitive() && root.get(member).getAsJsonPrimitive().isString(), path + "." + member + " 必须是字符串。"); String value = root.get(member).getAsString(); require(!value.isBlank(), path + "." + member + " 不能为空。"); return value; }
    private static int integer(JsonObject root, String member, String path) { require(root.has(member) && root.get(member).isJsonPrimitive() && root.get(member).getAsJsonPrimitive().isNumber(), path + "." + member + " 必须是整数。"); return root.get(member).getAsInt(); }
    private static void rejectUnknown(JsonObject root, Set<String> allowed, String path) { for (String key : root.keySet()) require(allowed.contains(key), path + " 包含未知字段 " + key + "。"); }
    static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }

    public record Dependency(String contentPackId, String versionRange) { public Dependency { Objects.requireNonNull(contentPackId); Objects.requireNonNull(versionRange); } }
    public record Export(ResourceLocation resourceId, String type, String displayName, String replacement, String stateAccess) {}
    public record Override(String providerContentPackId, ResourceLocation resourceId, String type, String compatibleVersionRange) {}
    public record Development(String workspaceUuid) {}
    public record Resource(String path, String sha256, String kind, ResourceLocation resourceId, String locale) {}
}
