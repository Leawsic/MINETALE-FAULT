# EBOTT 上下文

## 单一世界事实

`EbottData` 只存放在 Overworld。首次启动时，它通过 `PlaceManager` 解析山体位置，通过 `CaveEntranceGenerator.resolve` 固定入口 Placement，并创建 `ShaftData`；后续启动恢复相同数据。服务端运行期间只向生成线程发布不可变 `EbottData.Snapshot`。

`EbottData.Snapshot` 是下列系统共享的唯一事实：

- 山体覆盖范围、基底、峰顶和 Seed；
- 洞窟模板 Origin、朝向、山侧 Mouth 和 Shaft Opening；
- 两端竖井共用的 Profile 与 Seed。

## 生成顺序

Ebott 替换原版生成过程。对相交 Chunk，`MountainGenerator` 在 Carver 后冻结原始 `WORLD_SURFACE_WG`，并在 Feature 阶段执行：

1. `MountainDetailing` 基于快照添加山体体积、材质、雪、水道、洞穴和植被；
2. 调用原版 Biome Decoration；
3. `CaveEntranceGenerator` 写入当前 Chunk 相交的入口模板和连接通道；
4. `ShaftGenerator.writeSourceChunk` 写 Overworld 竖井。

Underground World 的相交 Chunk 由同一入口调用 `writeTargetChunk`。所有写入都限制在当前 Chunk；不得提前修改邻居 Chunk。

## 目标边界

`EbottDestination.ENABLE_SNOWDIN_TARGET` 当前为 true。目标水平位置是 Snowdin 区域中点，预览半径至少 128 格；目标接缝位于 Underground World 最高方块的上表面。每列竖井下界通过 Snowdin 自然地形 Kernel 搜索穹顶空气带，以防继续向洞腔内部延长井壁。

关闭开关时才使用 `OriginSettings` 和 Shaft Profile 中保存的旧目标接缝。EBOTT 只消费 Worldgen 的目标事实。

## 管理入口

- `/locate structure minetale:ebott_mountain`：在 Overworld 返回山体附近安全位置。
- `/locate structure minetale:origin`：在 Underground World 返回 Origin。
- `/minetale ebott probe`：完整检查当前维度一端的 Shaft。
- `/minetale ebott repair`：按冻结 Snapshot 幂等重写入口与两端 Shaft。
- `/minetale ebott debug`：输出当前持久化和解析后的坐标/Profile。

`minetale:ebott_mountain` 是自定义 Locate Literal，不是注册到 Minecraft Structure Registry 的 Structure。
