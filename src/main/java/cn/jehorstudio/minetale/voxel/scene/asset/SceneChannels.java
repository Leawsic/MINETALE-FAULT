package cn.jehorstudio.minetale.voxel.scene.asset;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/** 场景材质声明；在资产边界校验并展开默认值，生成线程共享不可变结果。 */
public final class SceneChannels {
    public static final int COLOR = 1, NORMAL = 2, ROUGHNESS = 4, METALNESS = 8, EMISSION = 16;
    public enum Mode { VARIABLE, CONSTANT, DISABLED }
    public record Channel(Mode mode, float x, float y, float z) {
        public boolean variable() { return mode == Mode.VARIABLE; }
    }
    public record LodProfile(float color, float normal, float roughness, float metalness) {
        public static final LodProfile DEFAULT = new LodProfile(4, 4, 8, 16);
        public LodProfile strictest(LodProfile other) {
            return new LodProfile(Math.min(color, other.color), Math.min(normal, other.normal),
                    Math.min(roughness, other.roughness), Math.min(metalness, other.metalness));
        }
    }
    public record Material(Channel normal, Channel roughness, Channel metalness, Channel emission, LodProfile lod) {
        public int variableMask(boolean shaderPack) {
            return COLOR | (emission.variable() ? EMISSION : 0) | (shaderPack
                    ? (normal.variable() ? NORMAL : 0) | (roughness.variable() ? ROUGHNESS : 0)
                    | (metalness.variable() ? METALNESS : 0) : 0);
        }
    }
    private static final Channel VARIABLE = new Channel(Mode.VARIABLE, 0, 0, 0);
    public static final SceneChannels LEGACY = new SceneChannels(
            new Material(VARIABLE, VARIABLE, VARIABLE, VARIABLE, LodProfile.DEFAULT), Map.of());
    private final Material defaults;
    private final Map<Integer, Material> materials;

    private SceneChannels(Material defaults, Map<Integer, Material> materials) {
        this.defaults = defaults; this.materials = Map.copyOf(materials);
    }

    public Material material(int id) { return materials.getOrDefault(id, defaults); }
    public Material defaults() { return defaults; }
    public Map<Integer, Material> overrides() { return materials; }

    static SceneChannels read(String version, JsonObject channels, JsonObject profiles) throws IOException {
        if ("1.0".equals(version)) {
            if (channels != null || profiles != null) throw new IOException("1.0 资产不能携带 1.1 材质声明");
            return LEGACY;
        }
        try {
            JsonObject declarations = object(channels, "defaults"), lodDefaults = object(profiles, "defaults");
            Material defaults = merge(LEGACY.defaults, declarations, lodDefaults);
            Map<Integer, Material> overrides = new HashMap<>();
            JsonObject byMaterial = object(channels, "by_material"), byProfile = object(profiles, "by_material");
            var keys = new java.util.HashSet<String>();
            if (byMaterial != null) keys.addAll(byMaterial.keySet());
            if (byProfile != null) keys.addAll(byProfile.keySet());
            for (String key : keys) {
                if (!key.matches("0|[1-9][0-9]*")) throw new IOException("材质 ID 必须为非负整数");
                int id = Integer.parseInt(key);
                overrides.put(id, merge(defaults, object(byMaterial, key), object(byProfile, key)));
            }
            return new SceneChannels(defaults, overrides);
        } catch (IllegalArgumentException | IllegalStateException | UnsupportedOperationException malformed) {
            throw new IOException("材质通道声明无效", malformed);
        }
    }

    private static JsonObject object(JsonObject owner, String key) {
        return owner == null || !owner.has(key) ? null : owner.get(key).getAsJsonObject();
    }

    private static Material merge(Material previous, JsonObject declaration, JsonObject profile) throws IOException {
        LodProfile p = previous.lod;
        return new Material(channel(declaration, "normal", previous.normal, 0, 0, 1),
                channel(declaration, "roughness", previous.roughness, 1, 1, 1),
                channel(declaration, "metalness", previous.metalness, 0, 0, 0),
                channel(declaration, "emission", previous.emission, 0, 0, 0),
                new LodProfile(pixels(profile, "color", p.color), pixels(profile, "normal", p.normal),
                        pixels(profile, "roughness", p.roughness), pixels(profile, "metalness", p.metalness)));
    }

    private static float pixels(JsonObject profile, String key, float fallback) throws IOException {
        if (profile == null || !profile.has(key)) return fallback;
        float value = profile.get(key).getAsFloat();
        if (!Float.isFinite(value) || value < .25F || value > 64) throw new IOException("LOD 像素尺度必须在 [0.25,64]");
        return value;
    }

    private static Channel channel(JsonObject owner, String name, Channel previous, float dx, float dy, float dz) throws IOException {
        JsonObject declaration = object(owner, name);
        if (declaration == null) return previous;
        if (!declaration.has("mode")) throw new IOException("材质通道缺少 mode: " + name);
        Mode mode = switch (declaration.get("mode").getAsString()) {
            case "variable" -> Mode.VARIABLE;
            case "constant" -> Mode.CONSTANT;
            case "disabled" -> Mode.DISABLED;
            default -> throw new IOException("未知的材质通道模式: " + name);
        };
        if (mode == Mode.VARIABLE) return VARIABLE;
        if (mode == Mode.DISABLED) return new Channel(mode, dx, dy, dz);
        JsonElement value = declaration.get("value");
        if (value == null) throw new IOException("常量通道缺少 value: " + name);
        float x, y, z;
        if (value.isJsonArray() && (name.equals("normal") || name.equals("emission"))) {
            if (value.getAsJsonArray().size() != 3) throw new IOException("向量常量必须含三个分量");
            x = value.getAsJsonArray().get(0).getAsFloat();
            y = value.getAsJsonArray().get(1).getAsFloat();
            z = value.getAsJsonArray().get(2).getAsFloat();
        } else {
            if (name.equals("normal")) throw new IOException("normal 常量必须为切线空间单位向量");
            x = y = z = value.getAsFloat();
        }
        boolean normal = name.equals("normal");
        for (float component : new float[]{x,y,z}) if (!Float.isFinite(component) || component < (normal ? -1 : 0) || component > 1)
            throw new IOException("材质通道常量越界: " + name);
        if (normal && (Math.abs(x*x+y*y+z*z-1) > .002 || z < 0)) throw new IOException("normal 常量必须为正半球单位向量");
        return new Channel(mode, x, y, z);
    }

    public JsonObject declarations() {
        JsonObject result = new JsonObject(); result.add("defaults", declarations(defaults));
        JsonObject overrides = new JsonObject();
        materials.forEach((id, material) -> overrides.add(Integer.toString(id), declarations(material)));
        if (!overrides.isEmpty()) result.add("by_material", overrides);
        return result;
    }
    public JsonObject profiles() {
        JsonObject result = new JsonObject(); result.add("defaults", profile(defaults.lod));
        JsonObject overrides = new JsonObject();
        materials.forEach((id, material) -> overrides.add(Integer.toString(id), profile(material.lod)));
        if (!overrides.isEmpty()) result.add("by_material", overrides);
        return result;
    }
    private static JsonObject profile(LodProfile p) {
        JsonObject result = new JsonObject(); result.addProperty("color", p.color); result.addProperty("normal", p.normal);
        result.addProperty("roughness", p.roughness); result.addProperty("metalness", p.metalness); return result;
    }
    private static JsonObject declarations(Material m) {
        JsonObject result = new JsonObject();
        String[] names = {"normal", "roughness", "metalness", "emission"};
        Channel[] channels = {m.normal, m.roughness, m.metalness, m.emission};
        for (int i = 0; i < names.length; i++) {
            Channel c = channels[i]; JsonObject declaration = new JsonObject();
            declaration.addProperty("mode", c.mode.name().toLowerCase(java.util.Locale.ROOT));
            if (c.mode == Mode.CONSTANT) {
                if (i == 0 || i == 3) { var array = new com.google.gson.JsonArray(); array.add(c.x); array.add(c.y); array.add(c.z); declaration.add("value", array); }
                else declaration.addProperty("value", c.x);
            }
            result.add(names[i], declaration);
        }
        return result;
    }
}
