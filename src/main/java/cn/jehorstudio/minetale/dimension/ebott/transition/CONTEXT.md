# Dimension Transition 上下文

Transition 是按玩家、服务端权威的接缝穿越状态机。它只读取 `EbottData.Snapshot`、`EbottDestination` 和 `DimensionSeam`。

## 状态

```text
PREWARMING -> PASSABLE -> CROSSING -> COMPLETE
     \-------------------------------> FAILED
```

- `PREWARMING`：源端和目标端 Chunk Lease、目标 Chunk 发包及客户端 Prepared World 尚未全部就绪；结界保持碰撞。
- `PASSABLE`：客户端已确认目标数据安装完成；只有此状态允许穿过结界。
- `CROSSING`：服务端检测到玩家在圆形 Aperture 内跨过接缝平面，并冻结 `CrossingSnapshot`。
- `COMPLETE`：`DimensionCommitter` 完成换维和客户端 Visual Commit。
- `FAILED`：准备或提交失败；释放租约并保持/恢复不可穿越。

状态只向前推进。渲染成功与否不参与服务端通行判定。

## 准备与资源所有权

`CacheManager` 按目标 Footprint 分批添加 Chunk Ticket，并允许玩家共享同一 Lease；最后一个引用释放时移除 Ticket。`TransitionManager` 同样按 Tick 预算向客户端发送目标 Chunk。Footprint 的 Render Columns 由圆形预览范围决定，Data Columns 额外包含一圈邻居以支持 Section 编译。

客户端 `TransitionClient` 拦截预热阶段的目标 Respawn/Chunk 数据，构建独立 `ClientLevel + LevelRenderer`，并由 `PreparedTargetSectionPrecompiler` 编译目标 Section。正式换维时复用 Prepared Renderer，完成后由 `RetiredRendererCleanup` 关闭旧资源。

## 结界与表现

`TransitionBarrier` 和 `DimensionSeam` 提供服务端/客户端共用的水平碰撞与圆形 Aperture。Mixin 只把该形状并入普通碰撞查询，不去主动改写玩家位置、速度、重力。

`TargetDimensionRender` 在源世界绘制 Prepared Target Section；`BarrierRender` 绘制结界；`BarrierVortexParticleAction` 通过共享 `GpuParticleSystem` 启停。它们只消费状态。

玩家离开影响范围、退出、死亡、服务端停止或任一准备步骤失败时，Session、Chunk Lease、客户端 Prepared World 和渲染资源都必须被释放。GameTest 覆盖接缝几何、准备状态、不同移动方式、多人隔离、退出和死亡。
