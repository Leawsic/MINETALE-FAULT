# 通用 Raw 与原材质

MSVT 的 OBJ/Blender `.mtscene` 导出会同时生成 Low、体素化前的 Raw、原材质资源和静态索引。Blender Raw 读取与 Low 导出相同的冻结快照，保留求值后的修改器、实例、物化位移、原始位置和逐角法线；材质烘焙只修改快照 UV。源 `.blend` 保持只读。Raw 调试模式显示原网格白板，High 使用原材质。

## 来源协议

`raw/source.json` 声明 `version="1.0"` 和 `material_mode="textured"`，使用已求值三角形并设置 `subdivision=false`。每个面包含三个 `vertices`、同序的单个 `triangles`、材质编号、三个 `uv` 和三个单位 `normals`。坐标为米制 `(x,z,-y)`，UV 保留源 V 方向。导出器按相同位置焊接拓扑并分离连通部件，逐角 UV 和法线保持独立。`closed` 要求每条边恰有一对反向引用。

`materials` 数组保存 `base_color`、`emission`、`opacity`、`roughness`、`metallic` 和 `textures`。颜色常量与 PNG RGB 使用 sRGB 编码值；数据通道使用线性数值。贴图语义为 `base`、`opacity`、`roughness`、`metallic`、`normal`、`ambient_occlusion`、`emission`，路径位于 `raw/materials/*.png`。base、opacity、emission 与对应常量相乘；roughness、metallic 的贴图覆盖常量。PNG 独立保存，纹理 UV 使用重复寻址和双线性采样。

Blender 材质复用 MSVT 的直连字段求值和几何相关通道烘焙。程序节点先转换为 PBR 纹理，Minecraft 读取转换后的 PBR 纹理。支持范围和近似与 MSVT 烘焙器一致；transmission、体积散射等属于当前 LabPBR 表面通道之外，外观近似范围由 MSVT 烘焙器支持边界确定。Low 使用当前单张 opaque atlas 契约。OBJ 保留 MTL 的基础颜色、透明度、粗糙度、金属度、自发光及对应贴图；默认之外的贴图命令选项需要预先烘焙。

## High 生成

工作线程从保守体素化选中的来源三角形，求出最近点的 barycentric 权重，再在该面的 UV 上读取原材质。法线贴图按三角形 UV 帧转换到 High 着色帧。相同位置、来源和法线共享采样结果，输出三通道 RGBA8 位模式；共享 compute 程序负责图集写入和 mip 构建。Core 来源声明 `material_mode="procedural"`，由 GLSL 求值材质。

生成几何支持每方块 0.5、1、2、4、8 个体素；颜色（含自发光）、PBR、法线分别支持每方块 0.5、1、2、4、8 个采样，High 默认几何精度为 2，颜色、PBR、法线精度为 8，见[多级精度](multiresolution.md)。这些设置独立于导出 Low 的 `low_step`。Low 间距沿用 MSVT 导出设置，索引验证其一致性；普通导入资产的四项 0.5 保留该原始间距和原材质，Core 使用[运行时基础 LOD](../../runtime/docs/base-lod-cache.md)。源图集的烘焙分辨率也限定可恢复的材质细节。

源材质读取会校验材质数据、纹理路径和图像有效性；资源大小与生成规模受 Java 数组、图像解码器和可用内存的实际能力约束。静态来源 ID 使用 11 位部件号和 20 位面号，符号位保持为零。源纹理与三角形采样记录计入 `curveCacheMiB` 的来源保留开销。

已验证范围包括 OBJ 多材质贴图、小数 Low 间距、跨页 Raw 裁剪，以及 Blender 旋转、缩放、Bevel、Generated 程序纹理和 Bump。验证使用离线 Java 几何生成和独立 OpenGL compute 上下文。
