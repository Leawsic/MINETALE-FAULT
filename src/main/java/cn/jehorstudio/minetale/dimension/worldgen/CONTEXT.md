# Worldgen 上下文

## 世界模型

`UndergroundNoiseGenerator` 继承 `NoiseBasedChunkGenerator`，保留原版基础生成，并把世界 Seed 绑定到 `WorldgenSamplingContext`。`RainbowCakeModel` 使用扭曲后的纵向进度依次划分：

```text
Origin -> Transition Tunnel -> Ruins -> Transition Tunnel -> Snowdin
       -> Transition Tunnel -> Waterfall -> Transition Tunnel -> Hot Land
```

序列之外是 `EDGE_REGION`。Origin 和 Snowdin 有独立 Pipeline；Edge 当前整列填充 Bedrock；Ruins、Waterfall、Hot Land 仍使用空 Placeholder Pipeline，Transition Tunnel 目前只负责 Origin Exit Connector 的空气切割。
**但仅有Snowdin有相对完整的实现。**

## 五阶段生命周期

每个区域 Pipeline 必须提供固定五个阶段：

| 阶段 | 当前 Minecraft 挂点 | 责任 |
| --- | --- | --- |
| Stage 1、2 | `fillFromNoise` 完成后 | 地形白板、大型元素 |
| Stage 3 | `buildSurface` 完成后 | 材质替换 |
| Stage 4、5 | 原版 `applyBiomeDecoration` 完成后 | 自定义结构、自定义 Feature |

`applyCarvers` 只执行原版 Carver。每个 Chunk 的 `ColumnCache` 从 Stage 1 保留到 Stage 5 结束，随后移除；它不是长期或跨 Chunk 缓存。

## 确定性

- 同一 Seed、设置和资源目录必须产生同一生成结果。
- 随机通道必须由世界 Seed 与稳定 Salt/坐标组合得到，不能只使用固定 Salt。
- Preview 与正式生成应调用相同的采样函数；`@PreviewSetting` 只标记允许预览工具发现和修改的源码常量。
- 区域生成只写当前任务允许的列或 Chunk。分析 Halo 可以读取纯采样事实，但不能写方块或触发邻居 Chunk 生成。

## 模块边界

- `RainbowCakeModel` 只决定区域和进度，不直接写地形。
- `RegionGeneration` 只做按列分发和阶段调用，不拥有区域内部规则。
- `RegionContext` 保存区域跨阶段数据；通用列缓存属于 `WorldgenContext`。
- Structure Asset 模块只提供模板解析、变换和放置。
- EBOTT 可以读取 Underground 目标坐标和 Snowdin 地形事实，且仅限于读取。
