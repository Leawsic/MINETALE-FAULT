# Core 放置（dimension.region.core）

Core 是由 `.mtscene` 场景资产表达的超大型结构；本包负责把资产真实放置进服务端世界，
并为其提供方块级碰撞与光标选择。场景的解码、LOD 与渲染在 `voxel.scene`（见其 README）。

## 职责

- 放置指令：`/mt_scene load <scene> <position>` 与 `/mt_scene unload` 是正式入口（客户端指令注册在
  `client.CoreRegionClient`，与 `SceneClient` 的 mt_scene 调试子命令共用根节点）——客户端发送
  `CorePlacementRequest`，服务端校验权限（等级 2）后放置并广播；`/minetale core load [scene]
  <position>` 与 `/minetale core clear` 是同一操作的服务端/控制台入口（缺省场景 `minetale:core`，
  坐标向下取整到方块）。放置的固定坐标与生成统一收敛在
  `CoreRegionManager.generateCore`/`removeCore`：写档（`CoreRegionData`）、构建碰撞、
  永久写入地图数据、广播，重放置先卸载原 Core。
- 碰撞即地图数据：生成的碰撞栅格序列化进维度 data 目录
  （主世界 `data/`、其它维度 `dimensions/<ns>/<path>/data/` 下的 `minetale_core_collision.bin`），
  维度加载时同步还原——玩家进入世界即有完整碰撞，没有从零生成的真空期；
  卸载或重放置时删除/重写。磁盘读写机制（原子写、还原、客户端缓存布局）统一在
  `collision.CoreCollisionStore`；存档异常时回退为后台重建。
- 客户端预测：本地缓存 `cache/minetale/core-collision/col-<digest>-<x>-<y>-<z>.bin`
  （单条目），命中时预测碰撞亚秒就绪；未命中回退完整重建（期间由服务端权威碰撞兜底回弹）。
- 碰撞（`CoreCollisionGrid` 两类表达，服务端与客户端各自从同一资产确定性重建）：
  - 主体固定网格（`SceneFixed` 全量渲染的未体素化 mesh，`raw/fixed/geometry.bin`）：
    轴对齐面沿实体侧挤出 0.1m 薄壳，与可见面完全齐平；非轴对齐三角形落回 0.5m 占据格。
  - 部件：最低一档 LOD 网格按表面 SAT 标记进 0.5m 占据格（无楔形填充）；触点为种子在
    搜索半径内到固定网格表面的最近点（与 `SceneAttachments` 同一裁决）。
  - 体素层（raw 页）：按渲染光栅化同一约定（SAT + spread）标记 0.5m 占据格。
  - `ServerCoreCollisionMixin` / `ClientCoreCollisionMixin` 把碰撞盒并入
    `Entity.collectColliders`，走完整原版移动解算（含台阶）。
- 选择框：客户端视线取最近命中（占据格 DDA + 面壳 slab），命中且比原版目标更近时在
  `RenderLevelStageEvent.AfterEntities` 画黑色线框；固定网格面经贪婪合并可能横跨数百米，
  选择框取命中点处的 1×1m 局部格块而非整个合并面。世界中无对应方块状态，
  因此不可破坏、不可交互。

## 入口

- 指令注册与生命周期：`CoreEvents`（维度加载恢复放置，玩家登录、重生、换维同步 `CoreStatePayload`）。
- 权威状态与构建：`CoreRegionManager`（每 `ServerLevel` 一个，后台单线程构建，令牌失效旧构建）。
- 碰撞构建与查询：`collision.CoreCollisionBuilder`（资产 → 栅格）与 `collision.CoreCollisionGrid`
  （占据格/面壳双表达、碰撞收集、射线；`appendShapes` 是服务端/客户端实体碰撞注入的共用收口）。
- 客户端状态：`client.CoreRegionClient`（客户端放置指令、驱动 `SceneClient.serve` 渲染并重建本地栅格）。

## 依赖方向

- 本包依赖 `voxel.scene`（`SceneAsset`/`SceneParts` 读资产、`SceneClient.serve` 驱动渲染）；
  `voxel.scene` 不依赖本包，调用只能从本包发起。

## 边界

- 部件碰撞使用最细一档 LOD：贴合近景视觉，代价仅一次性构建耗时（约 +4s，异步）。
- 体素层（raw 页）基础视觉为 2m 壳，可高出 raw 表面至多 2m；High 就位前踩上会有
  短暂下沉窗口。
- 固定网格烘焙统一产出颜色+法线+PBR 全通道（不随光影开关变化，渲染端各自取用）；
  结果按资产摘要 128 位落盘于 `cache/minetale/core-fixed/`，命中时直写纹素跳过烘焙，
  受客户端配置 `scene.fixed_bake_disk_cache`（固定烘焙磁盘缓存）控制。
  非光影模式因此新增 normal/specular 图集显存（约 +180 MiB，渲染端不采样）。
- 兜底（烘焙就绪前）主体为红棕铜色（主体烘焙反照率均值 #402121），不再是纯白。
- 光标选择接入原版客户端 `BlockGetter.clip`（`ClientCoreClipMixin`）：Core 命中比原版结果更近时
  以空气位置的假想方块命中返回——可以此为落点放置方块、右键使用；破坏因位置为空气而无效。
  服务端以 `EntityPlaceEvent` 裁决：放置的方块碰撞与 Core 相交即取消（多人同理）；投射物与
  活塞/流体不受该裁决约束。
- 调试：`/mt_scene core` 报告视线命中、脚下占据与栅格统计。
