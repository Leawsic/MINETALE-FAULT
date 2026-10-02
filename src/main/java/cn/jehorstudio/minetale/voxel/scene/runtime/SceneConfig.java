package cn.jehorstudio.minetale.voxel.scene.runtime;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.function.BiConsumer;

public final class SceneConfig {
    public static final ModConfigSpec SPEC;
    private static final ModConfigSpec.BooleanValue SEAM_DISK_CACHE;
    private static final ModConfigSpec.ConfigValue<String> SEAM_CACHE_PATH;
    private static final ModConfigSpec.BooleanValue FIXED_BAKE_DISK_CACHE;

    static {
        var builder = new ModConfigSpec.Builder();
        builder.translation("minetale.configuration.voxel.scene").push("scene");
        SEAM_DISK_CACHE = builder.translation("minetale.configuration.voxel.scene.seam_disk_cache")
                .comment("启用 Low 接缝磁盘缓存；下次加载场景时生效。")
                .define("seam_disk_cache", false);
        SEAM_CACHE_PATH = builder.translation("minetale.configuration.voxel.scene.seam_cache_path")
                .comment("缓存目录。留空使用游戏目录下 cache/minetale/core-seams；下次加载场景时生效。")
                .define("seam_cache_path", "", SceneConfig::validPath);
        FIXED_BAKE_DISK_CACHE = builder.translation("minetale.configuration.voxel.scene.fixed_bake_disk_cache")
                .comment("固定网格烘焙结果落盘（cache/minetale/core-fixed）；关闭后每次加载重新烘焙。")
                .define("fixed_bake_disk_cache", true);
        builder.pop();
        SPEC = builder.build();
    }

    private SceneConfig() {}

    private static boolean validPath(Object value) {
        if (!(value instanceof String path)) return false;
        try { Path.of(path.strip()); return true; }
        catch (InvalidPathException invalid) { return false; }
    }

    static boolean fixedBakeDiskCache() {
        return SPEC.isLoaded() && FIXED_BAKE_DISK_CACHE.getAsBoolean();
    }

    // 场景持有配置快照；已有工作线程完成后释放，路径切换在下一次场景加载生效。
    static Path seamCacheDirectory(Path gameDirectory) {
        if (!SPEC.isLoaded() || !SEAM_DISK_CACHE.getAsBoolean()) return null;
        String configured = SEAM_CACHE_PATH.get().strip();
        return configured.isEmpty() ? gameDirectory.resolve("cache/minetale/core-seams")
                : gameDirectory.resolve(configured).normalize();
    }

    public static void addZhCn(BiConsumer<String, String> add) {
        add.accept("minetale.configuration.voxel", "体素");
        add.accept("minetale.configuration.voxel.scene", "大场景");
        add.accept("minetale.configuration.voxel.scene.seam_disk_cache", "Low 磁盘缓存");
        add.accept("minetale.configuration.voxel.scene.seam_disk_cache.tooltip", "下次加载场景时生效。");
        add.accept("minetale.configuration.voxel.scene.seam_cache_path", "Low 缓存路径");
        add.accept("minetale.configuration.voxel.scene.seam_cache_path.tooltip", "留空使用游戏目录下 cache/minetale/core-seams；下次加载场景时生效。");
        add.accept("minetale.configuration.voxel.scene.fixed_bake_disk_cache", "固定烘焙磁盘缓存");
        add.accept("minetale.configuration.voxel.scene.fixed_bake_disk_cache.tooltip", "固定网格烘焙结果落盘（cache/minetale/core-fixed）；关闭后每次加载重新烘焙。");
    }

    public static void addEnUs(BiConsumer<String, String> add) {
        add.accept("minetale.configuration.voxel", "Voxel");
        add.accept("minetale.configuration.voxel.scene", "Large Scenes");
        add.accept("minetale.configuration.voxel.scene.seam_disk_cache", "Disk Cache");
        add.accept("minetale.configuration.voxel.scene.seam_disk_cache.tooltip", "Applies when a scene is loaded.");
        add.accept("minetale.configuration.voxel.scene.seam_cache_path", "Low Seam Cache Path");
        add.accept("minetale.configuration.voxel.scene.seam_cache_path.tooltip", "Empty uses cache/minetale/core-seams in the game directory. Applies when a scene is loaded.");
        add.accept("minetale.configuration.voxel.scene.fixed_bake_disk_cache", "Fixed Bake Disk Cache");
        add.accept("minetale.configuration.voxel.scene.fixed_bake_disk_cache.tooltip", "Persists fixed mesh bake results under cache/minetale/core-fixed; disable to rebake every load.");
    }
}
