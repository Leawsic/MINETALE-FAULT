# GPU 粒子材质

## 帧内绘制

`GpuParticleRenderer` 先计算所有可见动作的屏幕包围范围，再按联合范围清理一次共享 RGBA8 Mask 与 DEPTH32。每个动作保留自己的程序化 Vertex Shader、Sprite 和局部 Scissor，但写入同一组附件，因此不同动作之间也由深度保留最近粒子。最后只进行一次材质代理提交：联合矩形覆盖面积较小时使用一个 Quad，动作相距较远时在同一 Draw 内使用多个局部 Quad，避免栅格化中间的空白区域。

Mask Fragment Shader 在 Sprite Alpha 与动作 `alphaCutout` 通过后将输出 Alpha 固定为 1。Alpha 只决定像素是否存在，RGB 不再经过最终透明混合的二次衰减。淡入、淡出和密度变化表现为 Cutout 覆盖率变化；粒子 Shader 可独立设置保留像素的 RGB 亮度。

对于同帧 `N` 个可见动作，材质路径使用一次联合范围清理、`N` 次粒子 Mask 绘制和一次代理 Draw；代理像素覆盖取“联合矩形面积”与“各动作矩形面积之和”的较小值。PBR 只使用两个 1×1 纹理，不增加全尺寸附件、每粒子顶点属性或第二次粒子绘制。

## Iris 材质路径

启用受支持的 Iris 光影时，`GpuParticleMaterial` 将代理提交为 `TERRAIN_CUTOUT`：

- 读取当前 `WorldRenderingSettings.getBlockStateIds()` 中 `minecraft:sea_lantern` 的材质 ID；缺省值为 `-1`。
- 使用与 GB 表面相同的 64 字节 terrain 顶点布局，将 `mc_Entity` 与 `at_midBlock` 作为浮点属性提交。
- 为动态 Mask 纹理登记专用 PBR loader，注入 1×1 flat normal `(127, 127, 255, 255)` 与 specular `(0, 0, 255, 254)`。Specular 同时为 oldPBR Blue emission 和 LabPBR Alpha emission 提供满强度值。
- PBR 分量首次创建发生在 RenderPass 之前。重载时由 Iris 释放，下一次材质提交按需重建。

Iris 未启用或内部接口不兼容时，使用无混合、无深度写入的原版 emissive Cutout 管线。代理位于近裁剪面；世界不透明遮挡已在 Mask 阶段按场景深度完成，因此代理不能写入该近裁剪深度。后续透明地形的前后关系沿用 GPU 粒子原有边界。

最终 emission、曝光、雾与 bloom 由光影包决定。材质与 PBR 入口参考 [Iris PBR Standards](https://shaders.properties/current/how-to/pbr_standards/)、[LabPBR Material Standard](https://shaderlabs.org/wiki/LabPBR_Material_Standard)、[IrisApi](https://github.com/IrisShaders/Iris/blob/11e691d94343a6e372affc1a80a0a1f06d6378ff/common/src/api/java/net/irisshaders/iris/api/v0/IrisApi.java)、[PBRTextureLoaderRegistry](https://github.com/IrisShaders/Iris/blob/11e691d94343a6e372affc1a80a0a1f06d6378ff/common/src/main/java/net/irisshaders/iris/pbr/loader/PBRTextureLoaderRegistry.java) 与 [WorldRenderingSettings](https://github.com/IrisShaders/Iris/blob/11e691d94343a6e372affc1a80a0a1f06d6378ff/common/src/main/java/net/irisshaders/iris/shaderpack/materialmap/WorldRenderingSettings.java)。该桥接依赖 Iris 内部 PBR loader 与材质映射接口，接口变化会触发一次告警并回退。
