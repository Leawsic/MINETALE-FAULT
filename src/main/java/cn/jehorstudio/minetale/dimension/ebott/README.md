# EBOTT

该模块在 Overworld 生成 Mount Ebott、洞窟入口和跨维度竖井，并在接缝附近完成目标维度预热、结界控制和玩家换维。

## 主要入口

- `EbottData`：把山体位置、洞窟 Placement 和 Shaft Profile 持久化在 Overworld Saved Data 中。
- `PlaceManager`：仅在世界首次解析时选择山体位置。
- `mountain.MountainGenerator`、`MountainDetailing`：接管 Ebott 覆盖区的 Feature 阶段。
- `entrance.CaveEntranceGenerator`：放置入口模板、挖掘山侧通道并添加 Mysterious Campfire。
- `shaft.ShaftGenerator`：在 Overworld 和 Underground World 两端写入同一 Shaft Profile。
- `EbottDestination`：选择 Underground 目标坐标、接缝高度和预览范围。
- [`transition`](transition/CONTEXT.md)：按玩家管理预热、结界和 Dimension Commit。
- `EbottCommands`：只读 Locate 以及管理员 Probe、Repair、Debug。

生成所有权与存档约束见[模块上下文](CONTEXT.md)。
