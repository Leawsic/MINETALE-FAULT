# 激光的光影兼容

## 渲染入口

启用受支持的 Iris 光影时，`MagicBeamRenderer` 在 `AfterEntities` 调用 `MagicBeamSurface`，将真实圆柱和炮口球面提交为 `TERRAIN_SOLID`。表面按传播图裁切，核心的观察遮挡与颜色合成由光影处理。

最终颜色与光晕由光影的 emission、曝光、雾、透明合成和 bloom 决定。运行时依据 Iris 桥接能力选择渲染路径。

Iris 光影关闭或桥接不受支持时，回退到独立核心与 bloom。API、顶点格式或材质映射不兼容时告警一次；回退路径受外部深度限制。

## 材质与顶点契约

- `beam_surface.png` 为不透明白色，法线附图为平面法线。
- `beam_surface_s.png` 的 RGBA 为 `(0, 0, 255, 254)`，同时提供 oldPBR Blue emission 和 labPBR Alpha emission；接受 labPBR Blue 对应的 SSS 含义。仅用于光束材质。
- 方块身份从当前 `WorldRenderingSettings.getBlockStateIds()` 读取 `minecraft:sea_lantern` 的映射。缺省映射为 `-1`，材质附图独立提供 PBR 属性。编号以浮点属性完整传递；实心材质的 `mc_Entity.y` 为 `-1`。
- 本管线使用 64 字节顶点：保留 Iris terrain 属性顺序，将 `mc_Entity` 与 `at_midBlock` 改为浮点属性，避免 Blaze3D 将 GENERIC 整数属性绑定成整数输入。光束使用独立顶点格式与 VAO。
- Iris terrain 的法线矩阵只包含全局视图。Normal 与 tangent 因此预先旋转为世界方向；单位网格按实际束轴共享，Position 通过每次绘制的 `DynamicTransforms` 定位。球面与端盖按各自 UV 方向提供 tangent 和 handedness。
- 基础贴图及 PBR 附图的首次加载必须在 RenderPass 外完成。所有变换每帧批量上传一次，材质 pass 在 `AfterEntities` 执行。

材质Reference：[Iris PBR 说明](https://shaders.properties/current/how-to/pbr_standards/)、[labPBR 标准](https://shaderlabs.org/wiki/LabPBR_Material_Standard)。接口及布局Reference：[IrisApi](https://github.com/IrisShaders/Iris/blob/11e691d94343a6e372affc1a80a0a1f06d6378ff/common/src/api/java/net/irisshaders/iris/api/v0/IrisApi.java)、[IrisVertexFormats](https://github.com/IrisShaders/Iris/blob/11e691d94343a6e372affc1a80a0a1f06d6378ff/common/src/main/java/net/irisshaders/iris/vertices/IrisVertexFormats.java)、[WorldRenderingSettings](https://github.com/IrisShaders/Iris/blob/11e691d94343a6e372affc1a80a0a1f06d6378ff/common/src/main/java/net/irisshaders/iris/shaderpack/materialmap/WorldRenderingSettings.java)。适配版本为 Minecraft 1.21.10、NeoForge 21.10.64、Iris 1.9.6、Sodium 0.7.3；`WorldRenderingSettings` 属于内部接口，版本变化可能使桥接失效。

## 传播裁切与缓存

`MagicBeamOcclusion` 负责发射方向的传播阻挡。`MagicBeamSurface` 只裁切圆柱侧面、首尾端盖与球面。

每个表面四边形只访问投影到束横截面后覆盖的格子。先裁到该格范围，再根据各格命中面法线将距离校正为局部平面。材质路径采用最近格划分，独立核心及热扭曲混合四邻域可见性。两者在遮挡轮廓附近存在一个采样格尺度内的差异；采样精度与穿透规则见 [激光射出与遮挡](beam-rendering.md)。

后坐期间传播基准保持固定。基准前方的裁切网格按传播图 revision、口径、可见前端和收口比例缓存；前端抵消后坐补偿时，采用 1/512 格容差避免浮点舍入反复触发上传。基准后方使用未裁切的单位束身。炮口球跨越传播基准时，前半球同样按图裁切。重载、材质映射变化、方向变化和实体退出均使相应派生网格失效。

## 热扭曲与独立核心的限制

`BeamRenderStageMixin` 在透明地形真正提交前只复制深度。`order = 900` 保证先于 Sodium 默认 order 1000 的可取消 HEAD 执行。该快照覆盖透明地形提交前的不透明场景，包括实体及 Iris 提前绘制的实心手部。可选 `BeamHandDepthIrisMixin` 在透明手部 pass 前另存一次深度，用最终深度差识别新增手部覆盖。

在 `AfterLevel` 中，独立核心模式绘制核心和统一 bloom；Iris 材质模式只生成热扭曲所用的核心保护 mask，跳过独立 bloom。热扭曲读取最终场景颜色，最终合成在 mask 内输出零覆盖，保留光影已完成的核心颜色。圆柱与 mask 都采用 32 边，炮口球采用 16×8 网格。

热扭曲按原版投影、窗口坐标和深度编码解释场景／手部快照。改变深度布局、`gl_FragDepth` 或最终画面几何映射的光影可能造成折射遮挡或保护范围错位；核心材质使用 terrain 程序的深度测试。[Iris depthtex 文档](https://shaders.properties/current/reference/buffers/depthtex/)定义相关深度缓冲的用途，实际坐标布局取决于光影程序。

材质路径通过共同的 terrain 程序处理常规场景深度，只提交主场景表面。表面呈现受光影的方块顶点变形、自定义几何过滤及 terrain 程序替换影响；场景照明取决于光影对主场景自发光表面的处理。
