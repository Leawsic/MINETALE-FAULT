# Configuration

该模块提供 MineTale 的游戏内配置总入口，不拥有各功能模块的配置值。

`MineTaleConfigurationScreen` 会：

- 打开 Content Pack 管理界面；
- 枚举当前已注册的 MineTale `ModConfig`；
- 将 `SoulRecall.SPEC`、`VisualConfig.SPEC`、`SceneConfig.SPEC` 和其他配置分别显示为“交互”“战斗”“体素”和“通用”；
- 对 Soul Recall 使用专用界面，其余配置复用 NeoForge 的 `ConfigurationSectionScreen`。

配置定义、默认值和应用时机仍由所属模块负责。目前主要入口是：

- `battle.presentation.VisualConfig`：战斗客户端视觉配置；
- `voxel.scene.runtime.SceneConfig`：体素“大场景”分类中的 Low 接缝磁盘缓存；
- `content.player.soul.SoulRecall`：灵魂召回按键时长；
- `dimension.worldgen.asset.importer.StructureAssetImportConfig`：结构素材导入配置。
