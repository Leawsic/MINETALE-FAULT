package cn.jehorstudio.minetale.contentpack;

import cn.jehorstudio.minetale.MineTale;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.event.AddServerReloadListenersEvent;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

// 必须在 Battle/Narrative 发布快照前验证当前世界 Pack 拓扑与公共契约。
public final class ContentPackActiveStackReloadListener implements ResourceManagerReloadListener {
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(MineTale.MODID, "content_pack_stack");

    public static void register(AddServerReloadListenersEvent event) {
        event.addListener(ID, new ContentPackActiveStackReloadListener());
    }

    @Override
    public void onResourceManagerReload(ResourceManager resourceManager) {
        try {
            List<ActivePack> active = readActivePacks(resourceManager);
            validate(active);
            MineTale.LOGGER.info("Validated MineTale Content Pack stack: {} external pack(s)", active.size());
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("MineTale Content Pack 活动栈验证失败；拒绝发布新的服务器 Runtime 快照。", exception);
        }
    }

    public static void validate(List<ActivePack> active) throws IOException {
        validate(active, currentMineTaleVersion());
    }

    static void validate(List<ActivePack> active, ContentPackSemVer mineTaleVersion) throws IOException {
        Map<String, ActivePack> byId = new LinkedHashMap<>();
        for (ActivePack pack : active) {
            if (byId.putIfAbsent(pack.manifest.contentPackId(), pack) != null) throw error("同一世界不能同时启用 Content Pack 的多个版本：" + pack.manifest.contentPackId());
        }
        for (ActivePack pack : active) {
            if (mineTaleVersion.compareTo(pack.manifest.minMineTaleVersion()) < 0) throw error(pack.identity() + " 需要 MineTale >= " + pack.manifest.minMineTaleVersion());
            for (ContentPackManifest.Dependency dependency : pack.manifest.dependencies()) {
                ActivePack provider = byId.get(dependency.contentPackId());
                if (provider == null) throw error(pack.identity() + " 缺少依赖 " + dependency.contentPackId());
                if (provider.manifest.development() != null) throw error(pack.identity() + " 不能依赖开发部署 " + provider.identity());
                if (!ContentPackSemVer.satisfies(provider.manifest.version(), dependency.versionRange())) throw error(pack.identity() + " 的依赖 " + provider.identity() + " 不满足 " + dependency.versionRange());
                if (provider.index >= pack.index) throw error("依赖包必须位于依赖者下方：" + provider.identity() + " -> " + pack.identity());
            }
        }
        rejectDependencyCycles(active, byId);

        Map<ResourceKey, ActivePack> effective = new HashMap<>();
        for (ActivePack pack : active) for (ContentPackManifest.Resource resource : pack.manifest.resources()) {
            String type = runtimeType(resource.kind());
            if (type == null || resource.resourceId() == null) continue;
            ResourceKey key = new ResourceKey(type, resource.resourceId());
            MainContentPackCatalog.Export mainContract = MainContentPackCatalog.export(type, resource.resourceId());
            if (mainContract != null) requireLegalMainOverride(pack, key, mainContract);
            ActivePack lower = effective.put(key, pack);
            if (lower != null && lower != pack) requireLegalOverride(lower, pack, key);
        }
        validateExports(active);
        validateRuntimeReferences(active, effective);
    }

    private static List<ActivePack> readActivePacks(ResourceManager manager) throws IOException {
        List<ActivePack> result = new ArrayList<>(); List<PackResources> packs = manager.listPacks().toList();
        for (int index = 0; index < packs.size(); index++) {
            PackResources resources = packs.get(index);
            if (!resources.packId().startsWith(ContentPackRepository.PACK_ID_PREFIX)) continue;
            IoSupplier<InputStream> supplier = resources.getRootResource("contentpack.json");
            if (supplier == null) throw error(resources.packId() + " 缺少 contentpack.json。");
            try (InputStream stream = supplier.get(); InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                result.add(new ActivePack(index, resources, ContentPackManifest.parse(JsonParser.parseReader(reader).getAsJsonObject())));
            }
        }
        return List.copyOf(result);
    }

    private static void validateExports(List<ActivePack> active) {
        for (ActivePack pack : active) for (ContentPackManifest.Export export : pack.manifest.exports()) {
            if (export.type().equals("actor_prefab") || export.type().equals("pattern")) continue;
            boolean present = pack.manifest.resources().stream().anyMatch(resource -> export.type().equals(runtimeType(resource.kind())) && export.resourceId().equals(resource.resourceId()));
            if (!present) throw error(pack.identity() + " export 未对应实际 Runtime 资源：" + export.type() + " " + export.resourceId());
        }
    }

    private static void requireLegalOverride(ActivePack lower, ActivePack upper, ResourceKey key) {
        boolean directDependency = upper.manifest.dependencies().stream().anyMatch(value -> value.contentPackId().equals(lower.manifest.contentPackId()));
        if (!directDependency) throw error(upper.identity() + " 覆盖了非直接依赖 " + lower.identity() + " 的 " + key);
        ContentPackManifest.Export contract = lower.manifest.export(key.type, key.id);
        if (contract == null || !contract.replacement().equals("replaceable")) throw error(lower.identity() + " 未把 " + key + " 导出为 replaceable。");
        ContentPackManifest.Override declaration = upper.manifest.overrides().stream()
                .filter(value -> value.providerContentPackId().equals(lower.manifest.contentPackId()) && value.resourceId().equals(key.id) && value.type().equals(key.type)).findFirst().orElse(null);
        if (declaration == null) throw error(upper.identity() + " 对 " + key + " 的同 ID 实现缺少显式 Override。");
        if (!ContentPackSemVer.satisfies(lower.manifest.version(), declaration.compatibleVersionRange())) throw error(upper.identity() + " 的 Override 版本范围不接受 " + lower.identity());
    }

    private static void requireLegalMainOverride(ActivePack upper, ResourceKey key, MainContentPackCatalog.Export contract) {
        if (!contract.replacement().equals("replaceable")) throw error(upper.identity() + " 试图覆盖 main 的 sealed export " + key);
        ContentPackManifest.Override declaration = upper.manifest.overrides().stream()
                .filter(value -> value.providerContentPackId().equals("main") && value.resourceId().equals(key.id) && value.type().equals(key.type)).findFirst().orElse(null);
        if (declaration == null) throw error(upper.identity() + " 对 main " + key + " 的同 ID 实现缺少显式 Override。");
        if (!ContentPackSemVer.satisfies(MainContentPackCatalog.version(), declaration.compatibleVersionRange())) throw error(upper.identity() + " 的 Override 版本范围不接受 main@" + MainContentPackCatalog.version());
    }

    private static void validateRuntimeReferences(List<ActivePack> active, Map<ResourceKey, ActivePack> effective) throws IOException {
        Map<ResourceKey, ActivePack> globalBattleExports = new HashMap<>();
        for (ActivePack pack : active) {
            for (JsonObject battle : readJsonResources(pack.resources, "battles")) collectBattleGlobals(pack, battle, globalBattleExports);
        }
        for (ActivePack pack : active) {
            for (JsonObject dialogue : readJsonResources(pack.resources, "dialogues")) scanDialogue(pack, dialogue, effective);
            for (JsonObject battle : readJsonResources(pack.resources, "battles")) scanBattleImports(pack, battle, globalBattleExports);
        }
    }

    private static List<JsonObject> readJsonResources(PackResources pack, String folder) throws IOException {
        List<JsonObject> result = new ArrayList<>(); List<IOException> failures = new ArrayList<>();
        for (String namespace : pack.getNamespaces(PackType.SERVER_DATA)) {
            pack.listResources(PackType.SERVER_DATA, namespace, folder, (id, supplier) -> {
                if (!id.getPath().endsWith(".json")) return;
                try (InputStream stream = supplier.get(); InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) { result.add(JsonParser.parseReader(reader).getAsJsonObject()); }
                catch (IOException exception) { failures.add(exception); }
            });
        }
        if (!failures.isEmpty()) throw failures.getFirst();
        return result;
    }

    private static void collectBattleGlobals(ActivePack owner, JsonObject battle, Map<ResourceKey, ActivePack> effective) {
        collectGlobalDefinitions(owner, battle.getAsJsonObject("actors"), "actor_prefab", effective);
        collectGlobalDefinitions(owner, battle.getAsJsonObject("patterns"), "pattern", effective);
    }

    private static void collectGlobalDefinitions(ActivePack owner, JsonObject definitions, String type, Map<ResourceKey, ActivePack> effective) {
        if (definitions == null) return;
        for (Map.Entry<String, JsonElement> entry : definitions.entrySet()) if (entry.getValue().isJsonObject()) {
            JsonObject definition = entry.getValue().getAsJsonObject();
            if (!definition.has("id") || !definition.get("id").isJsonPrimitive()) continue;
            ResourceLocation id = ResourceLocation.tryParse(definition.get("id").getAsString());
            if (id == null) continue;
            ResourceKey key = new ResourceKey(type, id); ActivePack lower = effective.put(key, owner);
            MainContentPackCatalog.Export mainContract = MainContentPackCatalog.export(type, id);
            if (mainContract != null) requireLegalMainOverride(owner, key, mainContract);
            if (lower != null && lower != owner) requireLegalOverride(lower, owner, key);
        }
    }

    private static void scanBattleImports(ActivePack caller, JsonObject battle, Map<ResourceKey, ActivePack> effective) {
        JsonObject imports = battle.getAsJsonObject("imports"); if (imports == null) return;
        requireImportedList(caller, imports.getAsJsonArray("actorPrefabs"), "actor_prefab", effective);
        requireImportedList(caller, imports.getAsJsonArray("patterns"), "pattern", effective);
    }

    private static void requireImportedList(ActivePack caller, JsonArray values, String type, Map<ResourceKey, ActivePack> effective) {
        if (values == null) return;
        for (JsonElement value : values) { ResourceLocation id = ResourceLocation.tryParse(value.getAsString()); if (id != null) requirePublic(caller, effective.get(new ResourceKey(type, id)), type, id, false); }
    }

    private static void scanDialogue(ActivePack caller, JsonElement element, Map<ResourceKey, ActivePack> effective) {
        if (element == null || element.isJsonNull()) return;
        if (element.isJsonArray()) { for (JsonElement child : element.getAsJsonArray()) scanDialogue(caller, child, effective); return; }
        if (!element.isJsonObject()) return;
        JsonObject object = element.getAsJsonObject(); String type = object.has("type") && object.get("type").isJsonPrimitive() ? object.get("type").getAsString() : "";
        if (type.equals("set") || type.equals("add")) requireStoryState(caller, object.get("state"), true, effective);
        else if (object.has("state")) requireStoryState(caller, object.get("state"), false, effective);
        if (type.equals("run")) requireResource(caller, object.get("package"), "dialogue", effective);
        if (type.equals("start_battle")) requireResource(caller, object.get("battle"), "battle", effective);
        if (object.has("run")) requireResource(caller, object.get("run"), "dialogue", effective);
        if ((type.equals("random") || type.equals("shuffle_cycle")) && object.has("packages") && object.get("packages").isJsonArray()) for (JsonElement id : object.getAsJsonArray("packages")) requireResource(caller, id, "dialogue", effective);
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) scanDialogue(caller, entry.getValue(), effective);
    }

    private static void requireStoryState(ActivePack caller, JsonElement value, boolean write, Map<ResourceKey, ActivePack> effective) { requireResource(caller, value, "story_state", effective, write); }
    private static void requireResource(ActivePack caller, JsonElement value, String type, Map<ResourceKey, ActivePack> effective) { requireResource(caller, value, type, effective, false); }
    private static void requireResource(ActivePack caller, JsonElement value, String type, Map<ResourceKey, ActivePack> effective, boolean write) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) return;
        ResourceLocation id = ResourceLocation.tryParse(value.getAsString()); if (id != null) requirePublic(caller, effective.get(new ResourceKey(type, id)), type, id, write);
    }

    private static void requirePublic(ActivePack caller, ActivePack provider, String type, ResourceLocation id, boolean write) {
        if (provider == null) {
            MainContentPackCatalog.Export main = MainContentPackCatalog.export(type, id);
            if (main != null && write && type.equals("story_state") && !"read_write".equals(main.stateAccess())) throw error(caller.identity() + " 无权写入 main 的 Story State " + id);
            return; // 非 Content Pack 引用由各 Runtime compiler 验证存在性。
        }
        if (provider == caller) return;
        boolean directDependency = caller.manifest.dependencies().stream().anyMatch(value -> value.contentPackId().equals(provider.manifest.contentPackId()));
        if (!directDependency) throw error(caller.identity() + " 引用了非直接依赖 " + provider.identity() + " 的 " + type + " " + id);
        ContentPackManifest.Export contract = provider.manifest.export(type, id);
        if (contract == null) throw error(provider.identity() + " 未公开导出 " + type + " " + id);
        if (write && type.equals("story_state") && !"read_write".equals(contract.stateAccess())) throw error(caller.identity() + " 无权写入 " + provider.identity() + " 的 Story State " + id);
    }

    private static void rejectDependencyCycles(List<ActivePack> active, Map<String, ActivePack> byId) {
        Set<String> finished = new HashSet<>(); Set<String> visiting = new HashSet<>(); ArrayDeque<String> path = new ArrayDeque<>();
        for (ActivePack pack : active) visit(pack, byId, visiting, finished, path);
    }

    private static void visit(ActivePack pack, Map<String, ActivePack> byId, Set<String> visiting, Set<String> finished, ArrayDeque<String> path) {
        String id = pack.manifest.contentPackId(); if (finished.contains(id)) return;
        if (!visiting.add(id)) throw error("Content Pack 依赖循环：" + String.join(" -> ", path) + " -> " + id);
        path.addLast(id);
        for (ContentPackManifest.Dependency dependency : pack.manifest.dependencies()) visit(byId.get(dependency.contentPackId()), byId, visiting, finished, path);
        path.removeLast(); visiting.remove(id); finished.add(id);
    }

    private static String runtimeType(String kind) { return switch (kind) { case "battle" -> "battle"; case "dialogue" -> "dialogue"; case "story_state" -> "story_state"; default -> null; }; }
    private static ContentPackSemVer currentMineTaleVersion() { String text = ModList.get().getModContainerById(MineTale.MODID).map(container -> container.getModInfo().getVersion().toString()).orElse("0.0.0"); try { return ContentPackSemVer.parse(text); } catch (IllegalArgumentException ignored) { MineTale.LOGGER.warn("MineTale mod version {} is not SemVer; using 0.0.0 for Content Pack compatibility", text); return new ContentPackSemVer(0, 0, 0, ""); } }
    private static IllegalArgumentException error(String message) { return new IllegalArgumentException(message); }

    public record ActivePack(int index, PackResources resources, ContentPackManifest manifest) { String identity() { return manifest.contentPackId() + "@" + manifest.version(); } }
    private record ResourceKey(String type, ResourceLocation id) { @Override public String toString() { return type + " " + id; } }
}
