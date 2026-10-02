# .mtscene 文件契约

`.mtscene 1.0/1.1` 是完整的客户端场景资产。Low（LOD）和 Raw 共用页目录、坐标系与生命周期；编码和压缩保留源场景的 Raw、Low 及页元数据。Raw 随场景分发，用于运行时接管、调试和来源查询。

## 资源位置与加载

项目资源位于 `src/main/resources/assets/<namespace>/scene/<path>.mtscene`。资源包内的路径为 `assets/<namespace>/scene/<path>.mtscene`；例如 `assets/minetale/scene/core.mtscene` 对应场景 ID `minetale:core`。

客户端命令为 `/mt_scene load minetale:core 0 64 0`。参数是场景 ID，省略 `scene/` 和 `.mtscene`。Minecraft ResourceManager 选择最高优先级的完整资源；覆盖一次替换整个耦合场景，Low、Raw 和贴图来自同一个资源包。资源重载保留场景 ID 与摆放位置，并重新读取当前资源。

读取线程将资源流复制到临时读取专用 ZIP 快照，以支持目录、ZIP 资源包和模组 JAR 中的嵌套容器。`SceneAsset` 拥有快照，并在关闭或加载失败时删除它。快照职责限定为读取；关闭外层资源包后，已加载快照继续提供 Raw 随机读取。

## 容器与清单

外层采用标准 ZIP，压缩方法限定为 STORED 和 DEFLATED。条目名唯一且区分大小写，路径使用 `/`。JSON 的 `version` 接受字符串 `"1.0"` 和 `"1.1"`。1.0 沿用全部通道可变的兼容语义，1.1 支持下面的材质声明。读取端验证格式标识和编码字段，校验失败时终止读取。导出先写完整临时包，再替换目标；失败时保留原目标。

```text
manifest.json
low/0.bin.xz        # 有 Low 的页
raw/0.bin           # 有 Raw 的页
...
color.webp
normal.webp
specular.webp
```

几何条目在三角形数为零时省略。`low/*.bin.xz` 使用 ZIP STORED，其余条目可以使用 DEFLATED。包内只保存基础贴图，第二级 mip 按下述算法生成。

清单使用 UTF-8 JSON：

```json
{
  "format": "minetale:scene",
  "version": "1.0",
  "low_step": 2,
  "page_size": 32,
  "low_vertex_floats": 14,
  "raw_vertex_floats": 8,
  "raw_available": true,
  "low_encoding": "rectangle80-xz",
  "raw_encoding": "float32",
  "texture_encoding": "webp-lossless",
  "atlas": {
    "width": 4096,
    "height": 4096,
    "mip_levels": 2,
    "mip_filter": "labpbr",
    "regions": 245805
  },
  "pages": [
    {"id": 0, "cell": [0, 0, 0], "low_triangles": 2, "raw_triangles": 1}
  ]
}
```

示例只展示字段形状；页数和 atlas 区域数由实际资产决定。来源和转换统计可以作为清单元数据保留。

- `low_step`：必需的米制采样间距，范围为 0.0001–256。
- `page_size`：1–256 的整数，单位为米；Blender `(x,z,-y)` 转换后 1 米对应 1 方块。
- `pages`：至少包含一页；ID 按数组顺序从 0 连续编号，`cell` 为唯一的三维整数，绝对值上限为 1,000,000。
- 分发模型的每页至少包含一种非空表示；压缩矩形 Low 的三角形数为偶数。运行时基础 LOD 来源和生成缓存允许空页；携带 Low 的场景的 Low 总数大于零。
- atlas 两边均为至少 16 的 2 的幂，三个通道共享尺寸和布局。`mip_levels=2` 表示基础级加一级 mip。
- `raw_available` 保留源输入的可用性。Raw 来源缺失时声明 `false`，Raw 总数为零；压缩器保持 Raw 数据和该标记原样。为 `true` 时，单页 Raw 数量为零表示真实空表面，并允许该页接管 Low。
- 几何解码后检查有限值、非零法线、页内位置（容差 0.01 米）、有效 UV 和逐顶点正交帧。XZ 几何解码检查已知输出长度；资源大小和解码内存受表示范围及可用内存约束。

## 1.1 材质声明

`material_channels` 包含 `defaults` 和可选的 `by_material`；后者以非负材质 ID 的十进制字符串为键，逐字段覆盖默认值。可声明 `normal`、`roughness`、`metalness`、`emission`，各项包含 `mode`：

- `variable`：由来源／程序材质求值。
- `constant`：使用 `value`，不进行该通道的逐样本求值。
- `disabled`：normal 使用 `[0,0,1]`，roughness 使用 1，metalness 和 emission 使用 0。

normal 常量是正半球单位三维向量；emission 接受三维向量或标量；roughness／metalness 接受标量。颜色继续按采样求值。未声明字段继承默认值，最终缺省为 `variable`。标量及 emission 分量范围为 `[0,1]`。

`lod_profiles` 使用同样的 `defaults`／`by_material` 结构，保存 color、normal、roughness、metalness 的像素尺度，范围 `[0.25,64]`；当前显式精度调度不消费这些档位提示。1.0 容器携带这两类 1.1 字段时拒绝读取。

## Low：rectangle80-xz

Low 向渲染器提供每顶点 14 个 float32：位置 XYZ、UV、法线 XYZ、切线 XYZ、handedness 和 UV 区域中心。每个矩形的六个顶点按 `0,1,2,0,2,3` 排列；两个三角形共享四角、帧和区域中心。

每个磁盘矩形记录为 20 个 little-endian uint32，共 80 bytes。所有浮点属性保留原始 float32 位模式，量化和法线计算沿用源值。A/B 比较采用 unsigned 位模式顺序：

| word 索引 | 内容 |
|---|---|
| 0–4 | XYZUV 每分量四角位模式的最小值 A |
| 5–9 | XYZUV 每分量四角位模式的最大值 B |
| 10–12 | 共享法线 XYZ |
| 13–15 | 共享切线 XYZ |
| 16 | handedness（float32 的 +1 或 -1） |
| 17–18 | 共享 UV 区域中心 |
| 19 | 角点选择掩码，低 20 bit 有效，其余为零 |

每个 XYZUV 分量最多允许两种位模式，四角必须精确取 A 或 B。第 `corner*5+component` 位为 0 时取 A，为 1 时取 B；corner 顺序为 0、1、2、3，component 顺序为 X、Y、Z、U、V。A=B 时编码器写入 1。浮点正零和负零作为各自位模式保留；超出该表示范围的输入明确失败，输出保持原始位模式语义。

一页含 N=`low_triangles/2` 个矩形。编码器先按记录字节位置重排：原字节 `record[r*80+b]` 写入 `planes[b*N+r]`；再将完整 planes 编码为 XZ 流（LZMA2、CRC64，当前编码器 preset 3）。解码长度必须恰为 `N*80`；截断、超长或校验失败均拒绝。页之间独立压缩，支持随机访问。

解码恢复原始六顶点后执行法线归一化及区域验证；GPU 表示将确认的矩形转换为四顶点加索引，任意三角形保持独立索引表示。空间分页、绕序、切线、UV 及 [terrain 区域契约](../../runtime/docs/shader-uv-contract.md)保持一致。

## Raw：float32

`raw/<id>.bin` 每三个顶点组成一个三角形，每个顶点按位置 XYZ、UV、法线 XYZ 保存 8 个 little-endian float32。长度严格为 `raw_triangles*3*8*4`。ZIP DEFLATE 压缩字节，源几何、三角形顺序和页归属保持原样。

场景可以包含 Raw-only 页、Low-only 页以及同时包含两种表示的页。Raw 页集与 Low 页集独立保存，空 Raw 页继续表达真实空表面。

## 贴图：webp-lossless

三个通道使用单帧 VP8L WebP，尺寸由 manifest 验证，解码为 RGBA8。编码开启 `lossless=true` 和 `exact=true`，包括 A=0 时的 RGB 在内逐字节保留；LabPBR specular 的 A 表达材质数据，因此透明像素的 RGB 也要保留。

编码过程采用源颜色数值、原始分辨率、原始调色板值和 lossless WebP 编码。Java 通过显式 TwelveMonkeys WebP reader 固定解码路径，独立于其他模组的 ImageIO 注册顺序；生产 JAR 内附解码依赖。

发布文件为 `build/libs/minetale-<version>.jar`，包含 XZ、TwelveMonkeys 及其依赖。Gradle `jar`、`jarJar`、`build` 和 `publish` 使用同一完整包；Maven 发布与 Gradle variant 元数据引用同一产物。内部类归档位于 `build/intermediates/thinJar/`，使用 `.zip` 扩展名。安装环境只保留一个 MineTale JAR。

## mip：labpbr

基础级是唯一持久贴图来源。每个输出纹素读取对应的 2×2 输入，顺序固定为左上、右上、左下、右下。最终各通道限制到 0–255，使用最近整数舍入，平票取偶数。

1. **color**：RGB 在近似线性空间平均，使用 `pow(mean(pow(value/255,2.2)),1/2.2)*255`；中间颜色值使用 float32，A 使用四值算术平均。
2. **normal**：RG 解码为 `value/127.5-1`，源 RG=(0,0) 表示平坦法线；XY 长度大于 1 时先缩至单位圆，Z=`sqrt(max(0,1-dot(XY,XY)))`。四个三维法线在 float64 中平均并归一化，RG 重新编码为 `(XY*0.5+0.5)*255` 后转换为 float32；BA 使用算术平均。
3. **specular**：R 使用算术平均；G、B、A 先按类别投票，再只平均获胜类别内的原始值。G<230 为一类，230–255 各自为一类；B 按 `<65`/`>=65` 分为两类；A 按 `<255`/`=255` 分为两类。平票选择输入顺序中最先出现的类别，平均值使用 float32 舍入。

后台线程完成首次解码和 mip 构建；首次上传消费已准备的像素，长期只保留小体积编码源。Iris 再次请求 PBR 时可以重新生成。该格式减少磁盘体积，三通道两级 RGBA8 的 GPU 容量保持原值。

## 来源曲面

`raw/source.json` 声明 `version="1.0"`、`material_mode`、`subdivision_input`、`materials` 和 `parts`。`manifest.raw_source` 可以记录同一格式标识、条目名、SHA-256 和部件数。来源数据由本地可信导出器生成；加载时验证数组容量、索引、有限坐标、材质以及受支持的法线/折痕范围。

`material_mode` 表达材质求值方式：

- `procedural`：Core 程序材质使用逐面法线、0/1 硬折痕和固定材质 ID；材质由 GLSL 求值。包含 smooth 面、逐角 UV 或逐角法线的输入会被拒绝。
- `textured`：保存已求值三角形、原材质纹理、逐角法线和 UV；部件声明 `subdivision=false`。完整布局见[通用 Raw 材质](../../geometry/docs/source-materials.md)。

`subdivision_input=control` 表示 Catmull-Clark 控制点，`surface_samples` 表示曲面应通过的采样点。两种语义都显式声明。采样点按三级细分和 limit 规则求解控制笼；静态索引保存拟合点，静态索引缺失时在装载阶段求解。Core 来源包含 62 个部件、21,379 个控制面和 11 个细分部件。

## 基础 LOD 来源与生成缓存

场景使用同一 1.0 清单契约，由 `base_lod` 指定用途：

| 用途 | `base_lod` | Low 编码 | 贴图编码 |
|---|---|---|---|
| 分发模型 | 省略 | `rectangle80-xz` | `webp-lossless` |
| 基础 LOD 来源 | `runtime-0.5` | `rectangle80-xz`，Low 数量为零 | `webp-lossless`，贴图编码为空 |
| 生成缓存 | `generated-0.5` | `float32` | `png` |

来源包要求 32 米页、完整来源和静态索引缺失状态。生成缓存要求 32 米页、`low_step=2`，保存完整来源和静态索引缺失状态。`raw_encoding` 均为 `float32`。

浮点 Low 页为 `low/<id>.bin`，每顶点 14 个 little-endian float32；长度为 `low_triangles*3*14*4`，属性顺序与矩形解码输出相同。缓存的三个通道 PNG 位于根目录及 `mip/1/`，直接保存 GPU 输出和显式 mip；atlas 同样声明 `mip_filter="labpbr"`。两种编码分别承担磁盘压缩和生成结果持久化。

## 静态索引与截面

清单中的目录为 `static_index: {"version":"1.0","entry":"static/index.bin.xz","sha256":"<64位小写十六进制>","caps_group_size":64}`。索引与截面组使用 XZ preset 6 和 ZIP STORED；SHA-256 校验解压后的内容。内部数字使用 Java DataOutput 的 big-endian。

索引头为 `int32 MAGIC=0x4d545349`、`int32 VERSION=1`（1.0 的二进制标识）、`int32 pageSize`、`int32 brickSize`，随后保存 32-byte 来源 SHA-256 和 32-byte Low 几何 SHA-256。来源指纹覆盖 `raw/source.json`；Low 指纹按页依次覆盖 id、cell、Low 三角形数和压缩页字节。

头部后依次保存带长度的曲面准备数据、float64 Low 采样间距及占用数据、页目录、砖目录、页 BVH 和连通信息。砖包含空间坐标、父页、Low 范围、来源面关联、截面字节数和摘要；数组长度及索引在读入时验证。

`static/caps/groups/<group>.bin.xz` 按连续 64 个砖保存连接截面内容；空组省略，分组长度与各砖声明长度之和一致。砖内容为六个 int32 三角形数，随后按方向保存每个三角形的 42 个 float32。运行时按组解压并验证单砖摘要，只缓存当前组。内部布局以 `SceneIndex.encode/read`、`SceneSurface.writePrepared/read` 及对应结构的读写方法为准。

生成缓存的 low/<id>.bin 使用完整的 14-float 顶点记录，允许同一三角形的法线和切线随顶点变化以表示平滑曲面。区域验证要求三个顶点共享 UV 中心，各顶点切线保持单位长度、与自身法线正交，handedness 为 ±1。上述 80-byte 压缩矩形仍保存共享帧。

## Core 固定 Mesh 扩展

可选目录 `raw/fixed/manifest.json` 使用整数 `version: 2`，`batches` 按顺序引用 `raw/fixed/<id>.bin`。固定 Mesh 的面不出现在 `raw/source.json` 和空间页中；生成基础缓存原样复制此目录，完整资产摘要覆盖固定来源。程序材质 ID 为 Core 的 0–7。

所有固定二进制字段使用 little-endian。发布包保存固定几何、原始材质来源三角形和每面的采样参数。逐样本坐标、法线、材质 ID 和完整图集索引在加载线程生成，不能作为版本 2 发布数据写入。

`geometry.bin` 的头为 `uint32 0x4d544647`、`uint32 batchCount`，随后保存各批次的 `int32 vertexCount` 和 8-float 三角形顶点（位置、UV、法线）。这份紧凑白板表示在程序材质就绪前显示完整几何。

`source.bin` 保存原始材质归属。头为 `uint32 0x4d544653`、`uint32 triangleCount`，每个三角形为 9 个 float32 场景坐标和一个 int32 材质 ID。加载线程建立 BVH，按真实三角形距离选择材质；严格等距时选目录中索引较小的三角形。距离比较不使用包围盒距离替代三角形距离。

每个批次文件的头为 `uint32 0x4d544650`、`uint32 atlasSize`、`uint32 vertexFloatCount`、`uint32 sampleCount`、`float32 resolution`；版本 2 的 `resolution=2`。随后保存：

1. `vertexFloatCount` 个 float32：每顶点 14 个数，按四顶点组织，三角形重复最后一个顶点；属性顺序与浮点 Low 相同。
2. 每面 84 bytes 的重建参数：7 个 int32，依次为 atlas 起点 `ax/ay`、内部采样宽高 `w/h`、含边距的区域宽高 `pw/ph`、材质 ID（`-1` 表示查询来源）；14 个 float32，依次为采样基底 `T/B/N` 三个三维向量、投影范围 `loU/loV/hiU/hiV` 和平面距离。

样本位置由面基底和纹素中心恢复；边距钳制到最近内部样本。批次顺序、几何数量、有限值、单位基底、范围、区域无重叠和采样总数在读取边界校验。生成结果进入已有通道声明、材质烘焙和 GPU 生命周期，详见[固定几何](../../runtime/docs/fixed-geometry.md)。

读取端兼容旧 `version: 1`：头 magic 为 `0x4d544658`，顶点之后直接保存 `sampleCount × 7` 个 float32 样本和 `atlasSize²` 个 int32 索引。新导出器只写版本 2。

来源部件可选 `max_geometry_resolution`、`max_material_resolution` 分别声明几何与所有材质通道的精度上限，合法值为 0.5、1、2、4、8，缺省为 8。运行时按砖关联的来源分别取最大上限，混合砖保持较复杂来源的需求；显式精度命令同样受上限约束。Core 的 11 个细分部件几何上限为 2，其中 9 个管状部件的材质上限为 2，两座塔的材质上限为 8。

## 1.2 部件目录

根清单可声明 `parts: "parts/manifest.json"`。基础 LOD 生成缓存原样携带 `parts/`；完整内容摘要覆盖部件条目。

部件清单使用整数 `version: 1`、`coordinates: "scene-local(x,z,-y)"` 和 `geometry_encoding: "quad-dictionary-xor-v1"`。`atlases` 保存图集尺寸及 color、normal、specular 的条目与字节数；`prototypes` 保存唯一 ID、LOD1–4 的条目/面数/解压长度/误差/边界、原型副本的行优先 3×4 仿射矩阵及总体边界。`targets` 保存附着目标名称和场景坐标边界；`instances` 保存唯一 ID、原型和目标索引、归一化种子、场景坐标偏移、行优先 3×3 线性变换与搜索半径。目标索引 `-1` 表示直接使用绝对种子。

实例可选 `attachment_normal` 为场景坐标中的三个有限浮点数，长度必须为 1。它确定从来源种子出发的双向附着轴；未命中搜索半径内的表面时保留作者位置。缺省时兼容旧目录的最近点附着。

所有二进制数字使用 little-endian。XZ 压缩前按各段固定记录的字节列重排，先保存所有记录的第 0 byte，再保存第 1 byte，以此类推；头部保持原序。

- `tiles.bin.xz`：头为 `uint32 0x4d545054` 和图块数量；随后每图块 10 bytes，包含 `uint16 atlas` 和四个 `uint16` UV 端点。端点单位为半纹素；1×1 图块保留非零 UV 面积以定义切线。
- `<prototype>-<lod>.bin.xz`：头为五个 `uint32`，依次为 `0x4d545047`、四边形数、原点数、边向量数、法线数。随后为原点、边向量、法线三个 float32×3 字典，各自按字节列重排；最后为每面 61 bytes 的记录。
- 面记录依次为原点索引、两个边向量索引（三个 `uint32`），三个剩余角点的九个 `uint32` XOR 残差，`uint32 tile`，四个 `uint16 normal`，一个 8-bit UV 角点选择码。

角点预测为 `p1=p0+e1`、`p2=(p0+e1)+e3`、`p3=p0+e3`；各 float32 位模式与残差 XOR 后恢复源位置。字典保存 Blender 原始局部坐标，解码时转换为场景坐标。贴图 UV、切线及按图集分组的顶点范围在读取线程重建。XZ 解压长度、内存、目录范围、字典索引、UV 和变换有效性在读入边界校验。

运行时驻留、附着及材质边界见[部件实例](../../runtime/docs/parts.md)。
