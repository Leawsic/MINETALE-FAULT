package cn.jehorstudio.minetale.dimension.worldgen.asset.importer;

import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;

// 冻结一次结构资产导入的输入路径、命名空间与覆盖策略。
public final class StructureAssetImportConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    private static final ModConfigSpec.BooleanValue ENABLED = BUILDER
            .translation("minetale.configuration.asset_tools_enabled")
            .comment("开发期结构资产导入工具开关。生产环境建议保持 false。")
            .define("assetToolsEnabled", false);

    private static final ModConfigSpec.ConfigValue<String> RESOURCE_ROOT = BUILDER
            .translation("minetale.configuration.asset_resource_root")
            .comment("结构资产导入目标资源根目录，例如 D:/Project/src/main/resources。为空时只输出 JSON 草稿，不写文件。")
            .define("assetResourceRoot", "");

    private static final ModConfigSpec SPEC = BUILDER.build();

    public static void register(ModContainer container) {
        container.registerConfig(ModConfig.Type.COMMON, SPEC, "minetale-common.toml");
    }

    public static boolean enabled() {
        return ENABLED.getAsBoolean();
    }

    public static String resourceRoot() {
        return RESOURCE_ROOT.get();
    }

    private StructureAssetImportConfig() {}
}
