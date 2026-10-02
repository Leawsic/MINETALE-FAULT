# Worldgen

该模块实现 `minetale:underground_world` 的 Chunk Generator、区域分布、区域生成 Pipeline、结构资产工具和采样预览元数据。

## 主要入口

- `generator.UndergroundNoiseGenerator`：在原版 Noise Generator 生命周期中执行 MineTale 五阶段生成。
- `RainbowCakeModel`：按世界坐标和 Seed 计算 Underground Region 与区域内进度。
- `region.RegionGeneration`：把每个方块列分发给对应区域 Pipeline。
- `network.WorldgenSamplingContext`：绑定 Seed 与采样设置，供生成和预览共用。
- [`asset`](asset/README.md)：结构模板目录、Marker、连接、校验和开发期导入。
- [`region.snowdin`](region/snowdin/README.md)：当前最完整的区域实现，包括 Snowtown。

生命周期、不变量和未实现边界见[模块上下文](CONTEXT.md)。
