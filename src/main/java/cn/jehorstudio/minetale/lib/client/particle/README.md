# Client Particle

该子模块提供由多个功能域共享的 GPU 粒子生命周期和渲染管线。

## 使用边界

- 领域实现 `ParticleAction`，提供稳定 `ParticleDefinition`、启停状态和每帧不可变 `ParticleFrame`。
- 客户端初始化阶段通过 `GpuParticleSystem.register(...)` 注册；重复 ID 会失败。
- 运行方只按 ID 调用 `start`、`stop`、`toggle` 和 `isRunning`。
- 未注册 ID 会抛出 `IllegalArgumentException`。

## 生命周期

`GpuParticleSystem` 接管 NeoForge 客户端事件：注册每个动作的 pipeline、在 Client Tick 更新动作、在 `ExtractLevelRenderStateEvent` 提取帧，并在 `AfterEntities` 阶段绘制。退出世界时停止全部动作、关闭共享 Renderer 并清除投影状态。

同帧动作先写入共享 Mask 与 Depth，随后只提交一次 Cutout 材质。Iris 材质身份、PBR 分量、合批成本与限制见 [GPU 粒子材质](docs/gpu-material.md)。

`ParticleDefinition` 固定动作 ID、粒子数量、Sprite、Vertex Shader、Alpha Cutout 和可选中心模型。粒子数量必须大于零，每个粒子固定提交六个顶点。Alpha 只控制 Cutout 覆盖，保留像素的 RGB 独立决定亮度。

当前注册发生在 `MineTaleClient`，不要在 Render Pipeline 注册完成后动态追加动作，否则新 pipeline 没有注册机会。
