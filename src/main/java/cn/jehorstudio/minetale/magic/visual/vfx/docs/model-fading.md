# 模型渐显与渐隐

`MagicScreenDoor` 为 Magic 模型提供屏幕像素点阵裁切

## 接入

使用 `MagicScreenDoor.renderType(texture, emissive)` 获取模型 RenderType，并用 `MagicScreenDoor.color(argb, opacity)` 设置顶点颜色。覆盖率为 0..1，通过顶点 Alpha 传递；RGB 保持原值。0 覆盖率时可直接跳过提交。接口使用 `NEW_ENTITY` 格式、背面剔除、深度测试与深度写入，发光模式忽略环境光与方向光。

`shaders/include/screen_door.glsl` 是原版与 Iris 共用的 8×8 Bayer 裁切规则，以 `gl_FragCoord.xy` 为坐标，保留像素完全不透明。原贴图 Alpha 小于 0.1 的孔洞始终保留。主体、眼睛及前后表面使用相同覆盖率时，同一屏幕像素共用保留／丢弃结果，主体与眼睛同步消隐。

点阵密度独立于模型 UV 比例、贴图分辨率、口径与距离，按当前渲染目标像素定义；抗锯齿、动态分辨率及光影包后处理影响最终画面。覆盖率通过 8 位顶点颜色传递，点阵有 64 个阈值。

## Iris 接入

原版使用普通光照／自发光两种核心管线。Iris 1.9.7 使用实体／眼睛着色，通过 `IrisPipelines.copyPipeline` 同时复制主画面与阴影映射。

两个客户端 Mixin 在 Iris 程序链接前传递独立的覆盖率，在片段入口执行共用裁切规则，并将光影包接收的顶点 Alpha 恢复为 1；每次绘制按当前管线设置开关，只为接入的模型启用裁切。Uniform 位置按 `GlProgram` 对象弱引用缓存，避免资源重载后 GL id 复用导致错误。原版路径由核心 Shader 直接执行相同规则。

已验证组合为 Iris 1.9.7 与 Complementary Reimagined r5.6。接口只接入由 Vertex 和 Fragment 阶段组成的 Iris 程序。兼容背景见 Iris 官方[核心着色器兼容说明](https://github.com/IrisShaders/Iris/blob/26.1/docs/development/compatibility/core-shaders.md)。

## GB 时间线

覆盖率只从客户端当前 `AnimationFrame` 派生：

- 出场从零渐显，在张嘴起始时间的 83% 处完全可见，略早于入场移动稳定。
- 发射与收束期间保持可见；光束和球核由嘴部开度控制。
- 正常关闭后半段渐隐至零；服务器移除或强制移除时立即结束绘制。

眼睛与主体共用屏幕裁切和覆盖率，分别使用自发光／普通光照管线并写入深度，避免瞳孔因 UV 过小而整面消失。标准实体布局中，GB 只采样一次完整骨骼，再由 GPU 生成本体／眼睛两个顶点批次；Iris 扩展布局由 GeckoLib 分两遍遍历。覆盖率与材质规则相同。
