# 激光、模型与镜头视效

本模块只负责通用客户端视效。

## 激光

[`MagicBeamRenderer.submit(event, frames)`](MagicBeamRenderer.java) 在 `ExtractLevelRenderStateEvent` 的 NORMAL 或更低优先级中追加不可变 `BeamFrame`，HIGHEST 阶段清空上一帧。调用方提供 Level 内唯一且存续期间稳定的实体 ID、当前形状、固定传播基准及可见包络；渲染器管理 GPU 资源。

[`MagicBeamOcclusion`](MagicBeamOcclusion.java) 管理客户端绘制所用的传播距离图及世界依赖。受支持的 Iris 光影通过 [`MagicBeamSurface`](MagicBeamSurface.java) 绘制自发光表面；原版使用独立核心与 bloom。

详见 [射出、裁切与缓存](docs/beam-rendering.md)、[光影材质与兼容边界](docs/beam-compatibility.md)。

## 模型

[`MagicScreenDoor`](MagicScreenDoor.java) 通过 `renderType(texture, emissive)` 提供普通／自发光管线，通过 `color(argb, opacity)` 编码屏门覆盖率。详见 [模型渐隐](docs/model-fading.md)。

## 镜头震动

[`MagicCameraShake.submit(position, strength, range)`](MagicCameraShake.java) 在 `RenderFrameEvent.Pre` 的 NORMAL 或更低优先级中提交当前帧震源，下一帧自动清空。调用方提供时序与强度包络；VFX 按实际镜头位置计算距离衰减，叠加强度并钳制到 0.12 方块，再乘用户倍率。

[`CameraShakeConfig`](CameraShakeConfig.java) 保存 `minetale-vfx-client.toml` 中的倍率，辅助功能页面提供「MineTale 镜头震动」0%–100% 滑块，0% 关闭震动。相机 Mixin 在 `Camera.setup` 结束后平移主相机并裁剪方块碰撞，保留旋转角度。

GB 在张嘴发射时提交峰值，随后按时间衰减并随闭口归零；模型尺度决定强度与范围，震源在视锥内外均提交。
