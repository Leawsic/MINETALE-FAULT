# 魔法表现

本模块只负责客户端表现；选址、消耗和命中由服务端决定。

| 模块 | 职责与入口 |
| --- | --- |
| [vfx](vfx/README.md) | `MagicBeamRenderer` 绘制光束，`MagicScreenDoor` 提供模型渐隐，`MagicCameraShake` 处理镜头震动 |
| [TargetLockParticles](particle/TargetLockParticles.java) | `track(target)` 更新 GPU 锁定粒子的跟随目标，传入 null 停止效果 |

注册入口为 `MagicClient`。GB 客户端负责嘴部、模型尺寸和球核换算，光束渲染器只接收表现帧；锁定粒子以实体为输入。跨模块约束见 [Magic CONTEXT](../CONTEXT.md)。
