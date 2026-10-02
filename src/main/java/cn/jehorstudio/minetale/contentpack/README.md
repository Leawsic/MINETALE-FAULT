# Content Pack

该模块把安装目录中的 `.mtpack` 归档接入 Minecraft 服务端 Data Pack，并为每个世界保存精确的启用版本。

## 主要入口

- `ContentPackArchiveValidator`：校验 ZIP 路径、大小、摘要和 `contentpack.json`。
- `ContentPackInstaller`：验证正式归档后写入安装库。
- `ContentPackRepository`：扫描 `minetale/contentpack/`，把有效归档注册为服务端 Pack。
- `ContentPackActiveStackReloadListener`：Data Pack 重载时验证依赖、覆盖和跨包引用。
- `ContentPackActiveProfile`：在世界目录中保存并恢复精确活动栈。
- `ContentPackManagementScreen`：展示安装结果，并为当前单人世界打开原版 Pack 选择界面。

清单格式和实际加载规则见[运行时契约](docs/content-pack-runtime-contract.md)，跨文件不变量见[模块上下文](CONTEXT.md)。
