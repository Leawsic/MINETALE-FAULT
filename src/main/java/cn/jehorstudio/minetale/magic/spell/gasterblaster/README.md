# Gaster Blaster 法术

GB 是可直接召唤的世界装置，拥有生成、前摇、发射、收尾和销毁。调用者无需创建技能会话，也不需要赋予技能或魔力；是否允许使用与如何付费属于调用技能。

## 调用与所有权

`GasterBlaster.summon(caster, target, muzzle, parameters)` 在服务端线程调用。`Target` 指定初始世界目标点及可选跟踪实体；`Parameters` 明确模型倍率、光束半径和射程、每 Tick 接触伤害、Karma 积累倍率与三个阶段时长。参数构造验证当前支持范围。世界拒绝生成时返回 null。

`occupiedBounds` 与 `bodyBounds` 提供完整出场和稳定炮体包络，技能用它们选址；`visualBounds` 额外覆盖后退动画，供客户端视锥裁剪。它们不是受击盒。`ownedBy` 和 `aimsAt` 供围攻技能判断已有炮组，不授予修改状态的权限。

## 生命周期

1. 生成时冻结参数与归属，服务端同步形状、方向、射程和起始时间。
2. 嘴开始张开前追踪目标；目标失效时保留最后有效点，开火后固定方向。
3. 前端自张嘴起独立用 4 Tick 射出，嘴部曲线控制粗细与球核。服务端从固定射击基准按 Tick 中点判定逐目标伤害。
4. 进入 close 时从已经到达的位置收束，客户端后退动画保留末速度；结束时移除实体。归属实体离开当前 Level、死亡或成为旁观者时提前销毁。

实体不保存到区块。已生成的法术不依赖施放它的技能会话；同一施法者可以同时拥有多门不同参数的 GB。

## 实现入口

- [GasterBlaster](GasterBlaster.java)：实体、固定参数、时间轴、占用范围、命中历史与销毁。
- [GasterAnimation](GasterAnimation.java)：从模组原始资源采样下颌曲线，不维护第二套缓动。
- [client/GasterBlasterRenderer](client/GasterBlasterRenderer.java)：模型与瞳孔绘制、口心校准、动画到通用 `BeamFrame` 的适配。
- [client/GasterGpuModel](client/GasterGpuModel.java)：共享模型几何、每实例骨骼姿态、GPU 顶点生成与实体材质批绘。标准 36 字节布局自动启用；Iris 扩展布局保留原 GeckoLib 绘制和阴影。

模型口心为应用 shoot 后的 `(0,18,-8)/16`，瞄准只组合 yaw 与 pitch，不叠加 root 旋转。客户端使用同一单调年龄供模型与光束采样。后退按现有 `0.036 * powered * (powered * 0.5 + coast)` 曲线生成表现炮口，不移动服务端实体。光束补上表现炮口到固定基准的距离，前端与地形接触点仍按逻辑射程收束。

核心改为不透明后，激光退场由口径、球核半径和长度收束完成。视效帧已移除独立亮度／透明度参数，不能继续用下颌开度压低不透明 RGB；炮体模型仍沿自身的屏门覆盖率渐隐。

激光遮挡、球核、距离雾及光影边界见 [激光视效](../../visual/vfx/docs/beam-rendering.md)；模型渐隐与眼睛处理见 [模型渐隐](../../visual/vfx/docs/model-fading.md)。

模型 GPU 路径保留完整几何与原动画采样，服务端不参与表现计算。GPU 资源由 `GasterGpuModel` 拥有，在资源重载和退出世界时释放。
