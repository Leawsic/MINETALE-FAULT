# Structure Asset

该子模块把 Minecraft Structure Template 转换为可筛选、可变换、可连接并可安全放置的 Worldgen 资产。

## 组成

- `catalog`：从 `data/<namespace>/structure_assets/*.json` 加载定义，并按需扫描模板 Marker。
- `marker`：Marker Block、Block Entity、编辑界面、命令和网络更新。
- `scanner`、`transform`、`matching`：提取 Marker，计算旋转/镜像后的坐标，并匹配 Connector。
- `plan`：构建成对、链式或增量 Structure Plan，主要用于调试和生成器复用。
- `blocker`、`placement`：校验目标位置并在放置后清理 Marker/Blocker。
- `importer`：开发期开关启用后，从世界 `generated/.../structures` 导入模板和 JSON 草稿。
- `AssetPlacer`：按 Anchor 对齐并把最终模板写入世界或当前 Chunk。

## 文档

- [Structure Asset Catalog](docs/structure-asset-catalog.md)
- [Template Marker](docs/template-marker.md)
- [Placement Blocker](docs/placement-blocker.md)

命令入口是 `/minetale marker ...` 和 `/minetale asset ...`。导入写文件能力默认关闭，并且要求显式配置 `assetResourceRoot`。
