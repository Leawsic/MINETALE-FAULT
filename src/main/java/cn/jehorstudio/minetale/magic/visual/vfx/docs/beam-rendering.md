# 激光射出与遮挡

原版使用独立核心与 bloom，受支持的 Iris 光影使用自发光表面。两条路径共用发射时间轴、传播规则和热扭曲；材质接口见 [光影兼容](beam-compatibility.md)。

## 发射与后退

`AnimationFrame.extension()` 从张嘴起点计时，以固定 4 Tick 线性达到 1。嘴部开度控制束径和炮口球核；收口时，已达到的射出进度及各横截面的接触距离同时按下颌开度回缩，近墙束尾也立即收回。客户端和服务端共用时间轴，伤害按 Tick 中点取样。

`AnimationFrame.recoilDistance()` 从张嘴发射起以 0.036 格/Tick² 加速后退，进入 close 后保持末速度。服务端实体保留固定射击基准，客户端从同步时间轴推导模型、球核与束身的位置。

设基准为 `base`、后退距离为 `recoil`：

- 表现炮口：`base - axis * recoil`。
- 可见长度：`range * extension + recoil`。
- 墙面终点距离：`stop * retraction + recoil`。

收口围绕固定基准回缩，射程和地形接触点保持固定；`visualBounds` 覆盖后退扫过的模型空间。

## 光照与模型

GB 的光照表现只使用激光、球核和眼睛的自发光材质。

独立核心按真实表面的相机相对坐标计算环境雾和渲染距离雾。接口参见 [Minecraft Shader Fog Changes](https://www.minecraft.net/de-de/article/minecraft-java-edition-1-21-6)，字段与函数按项目 1.21.10 资源核对。

`left_eye`、`right_eye` 使用 `MagicScreenDoor` 自发光管线，眼窝保留 Cutout 和背面剔除。GeckoLib 5.3-alpha-3 的 `CustomBoneTextureGeoLayer` 会因姿态获取时机和子→父变换造成瞳孔错位，因此在执行阶段从根骨骼采样并筛选主体／眼睛 Cube。渐隐与批次路径见 [模型渐隐](model-fading.md)。

## 传播裁切与伤害

`MagicBeamOcclusion` 沿横截面查询 `MagicCollision.beamBlockShape`，生成覆盖外围光晕的 16×16 至 64×64 距离图。小炮采样更密，大炮最大间隔约 0.275 格。RGB 编码 24 位距离，步长 1/512 格；Alpha 编码命中面的法线轴。

只有同时满足 `isSolidRender`、阻光值 15 和完整碰撞立方体的方块阻挡传播。树叶、水、玻璃、冰、半砖、楼梯、栅栏等可穿透。未加载区域、构建高度外和世界边界外视为阻挡，查询只读取已加载区块。

独立核心与热扭曲先用法线将邻近纹素距离校正到当前横截面位置，再分别比较遮挡并混合可见性，避免窄柱两侧的距离插值产生假斜面。观察方向的遮挡另行处理：独立核心使用透明地形前的深度，Iris 表面使用 terrain 程序的深度测试。

距离图是有限分辨率近似：细于采样间隔的障碍可能漏采，轮廓存在约一个纹素的过渡带。取样依据见 [NVIDIA GPU Gems](https://developer.nvidia.com/gpugems/gpugems/part-ii-lighting-and-shadows/chapter-11-shadow-map-antialiasing)；API 按项目 1.21.10 的 `BlockGetter.traverseBlocks`、`VoxelShape.clip` 和 `DynamicTexture` 源码核对。

服务端先以当前束径、长度做圆柱相交，再沿发射轴检查目标的受光路径：先检查轴线到目标盒的最近点，再尝试目标盒的 27 个固定角点、边中点、面中点和中心点。采样点保持固定，增大半径时保留已有路径；每条路径按自身障碍距离和收口比例回缩。客户端距离图和外围光晕只用于表现。固定采样可能漏掉极窄露出区域。

## 独立核心与 bloom

核心采用不透明三角形表面，保留横截面渐变和低频轴向色带：

1. `beam.vsh` 用 `gl_VertexID` 生成 32 边圆柱、端盖和 16×8 炮口球，共 1152 顶点／束。CPU 只提交 224 字节参数。
2. `beam.fsh` 计算表面颜色、传播遮挡和雾透射率；共享 DEPTH32 目标通过硬件深度保留最近核心。端盖从射程内侧比较可见性，避免最大射程处被裁掉。
3. RGBA8 核心目标以 RGB 保存未混雾颜色，A 保存雾透射率，深度决定覆盖。bloom 源取 `RGB * A`，FogColor 只在最终合成时加入，完全入雾的核心保持不透明。
4. 所有核心统一按 4×4 平均降到四分之一宽高，再横／纵各五次线性采样模糊。权重归一化，步距倍率 1.8，辉光系数 0.12。源与目标分离，重合核心只贡献一次源颜色。
5. 最终全屏 pass 输出混雾核心，只在核心外叠加 bloom。

核心由纯白过渡到 `(0.96, 0.976, 1.0)` 肩部和 `(0.88, 0.93, 1.0)` 边缘，球核附近抑制接缝。退场只收缩束径、球核和长度，RGB 保持材质颜色。

轴向纹理坐标乘以 `sqrt(0.25 / 完整半径)`：半径 0.25 格保持基准，半径 4 格的波长放大 4 倍。外围主波空间频率倍率为 0.4，时间频率倍率为 0.2。缩放使用完整口径，开合期间纹理比例保持固定。

bloom 只从可见核心生成，以屏幕像素为尺度在亮边周围扩散。分离模糊参考 [Sascha Willems bloom 示例](https://github.com/SaschaWillems/Vulkan/blob/master/examples/bloom/bloom.cpp)。

N 束的核心与 bloom 共 `N + 4` 次绘制，开启折射后为 `2N + 6`。使用一个全尺寸颜色／深度目标、两个四分之一宽高颜色目标；三个 bloom pass 统一处理所有可见核心。

## 资源与缓存

`MagicBeamRenderer` 为每束可见光束持有距离图、原生像素、GPU 贴图与形状依赖，并独立管理这些资源。不可见、资源重载及退出世界时释放，按需重建。

每 Tick 检查涉及的 chunk section 身份和 block palette 写入版本，仅 `hasDynamicShape()` 方块持续复查形状。Mixin 监听 `PalettedContainer` 写入口，覆盖单方块、直接 palette 写入及整区块替换；版本随容器销毁。

重建时先按 16 格短段检查 section palette。所有 section 均已加载、位于边界内且无潜在阻挡状态时，直接填入无遮挡距离；否则逐格 DDA。`DebugLevel` 按实际显示状态逐格追踪并复查形状。实际加载状态通过 `ChunkSource.hasChunk()` 查询。

方块、区块加载状态、世界边界、传播基准、方向、半径或射程变化使缓存失效。后退期间传播基准保持固定，距离图复用。Iris 裁切网格的缓存规则见 [光影兼容](beam-compatibility.md)。

## 世界空间折射

`beam.fsh` 的折射变体在四分之一宽高目标上计算视线到有限光束线段的距离，以统一包络覆盖侧面、端点和近处。`ViewProjection` 及逆矩阵转换世界位置与屏幕方向，Uniform 为 `2 mat4 + 6 vec4`，共 224 字节。投影定义见 [Khronos OpenGL 3.3](https://registry.khronos.org/OpenGL/specs/gl/glspec33.core.pdf#page=106)。

`GameRenderer.bobView` 的平移包含在投影矩阵中，视线必须由近裁剪面和中间深度的两个反投影点构建。中间深度可减少远处齐次除法误差。原理见 [Newcastle University：Raycasting](https://research.ncl.ac.uk/game/mastersdegree/gametechnologies/physicstutorials/1raycasting/Physics%20-%20Raycasting.pdf#page=3)。

每束检查传播图、场景和透明手部深度；每个低分辨率像素平均对应 4×4 区域的可见性，再统一平滑。折射只采样当前画面，轮廓附近会有少量扩散。

位移累加与合成流程：

1. RGBA8 分别保存非负 `x+ / y+ / x- / y-`，加法混合保证提交顺序无关；通道饱和限制幅值，反向强场可能保守抵消。
2. 横／纵各 13 次相邻采样平滑。横向输出采用每轴 16 bit 有符号位移，纵向平滑与最终合成在线性解码后采样，保持低速位移连续。
3. 连续限幅并在屏幕边缘渐隐，沿位移场分步采样背景，避免大位移翻折。720p 使用 8 步，更高分辨率随高度增加。

位移累加只用于背景折射。背景取 `AfterLevel` 的最终颜色，向外采样使特征向内凹陷。无光束或强度为零时跳过折射与颜色复制；窗口变化时重建资源，重载和退出时释放。

热浪相位锚定固定传播原点和静态 `extent / 2.2` 口径，避免开合与后退引起扫频。速度只推进相位增量，0 冻结相位；相机、实体、尺寸或光影深度变化会改变空间折射。外部深度限制见 [光影兼容](beam-compatibility.md)。

### 内部参数

参数由 [`MagicConfig`](../../../MagicConfig.java) 中的内部常量定义。

| 参数 | 默认值 | 含义 |
| --- | --- | --- |
| `BEAM_DISTORTION_RADIUS` | 2 | 半径 1 格时的折射外扩范围（格） |
| `BEAM_DISTORTION_STRENGTH` | 1 | 半径 1 格时的基础位移（格）；0 关闭 |
| `BEAM_DISTORTION_FALLOFF` | 2 | 衰减指数 |
| `BEAM_DISTORTION_WAVE` | 1 | 热浪振幅占基础位移的比例 |
| `BEAM_DISTORTION_SPEED` | 1.5707 | 相位速度（弧度／秒）；0 静止 |

范围和基础位移乘当前可见半径，热浪波长乘静态尺寸；振幅比例、衰减指数和时间速度保持原值。默认最大空间位移为 `当前半径 × (1 + 1)` 格，再受衰减与雾影响；最终屏幕位移连续限制在屏幕高度的 1/60 内。
