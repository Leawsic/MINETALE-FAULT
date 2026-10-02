# 场景几何

本包从来源曲面生成可接管的空间砖，并维护与这些几何一致的 Low、索引和截面。输入是资产和空间范围，输出是 CPU 几何、采样数据与空间目录。

| 职责 | 入口及内部实现 |
|---|---|
| 来源查询 | `SceneSurface`；`SceneSubdivision`、`SceneSourceMaterials` 处理细分与原材质 |
| 空间覆盖 | `SceneLayout`、`SceneIndex`、`SceneSeams`；`SceneBvh`、`SceneLowVolume`、`SceneVisibility` 支撑空间查询与离线占用构建 |
| 砖与基础模型生成 | `SceneVoxels.geometry` / `material`、`SceneBase.generate` / `write` |

曲面准备数据、来源关联、索引和生成器共享同一组几何约束，因此集中在本包内。包内类型和成员保持最小可见性。

深入阅读：[静态空间索引](docs/static-spatial-index.md)、[多级精度](docs/multiresolution.md)、[原材质](docs/source-materials.md)、[已知问题](docs/known-issues.md)、[模块设计](../docs/architecture.md)。
