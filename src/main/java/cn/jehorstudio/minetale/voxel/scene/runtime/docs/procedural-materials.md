# Core 程序化材质

`assets/minetale/shaders/include/voxel/core_surface.glsl` 提供 Core 材质采样和 Height 法线计算。`runtime/SceneMaterials` 调用 `shaders/voxel/core_bake.comp`，在精细砖首次发布前分帧生成 GPU LabPBR 缓存。

## 输入与输出

`coreMaterial(materialId, original, voxel, normal)` 返回线性 Base Color、Roughness、Metallic、线性 Emission Color、Emission Strength、Alpha、Height、Bump Strength 和 Bump Distance。Emission Color 与 Strength 保留为独立 HDR 值，由调用方相乘。当前 Principled 使用 IOR 1.5 和默认 Specular 设置；导出器拒绝超出支持范围的默认之外输入。

`original` 是 Blender 共享材质空间中的原始位置，`voxel` 是调用方确定的体素纹理位置；库直接保留 `voxel` 的浮点精度。半格坐标按 `floor(original * 2) * 0.5 + 0.25` 取中心。空间单位为一个方块，坐标采用场景局部空间。调用方先扣除场景摆放位置；`coreToMaterialSpace` 将游戏 `(x,y,z)` 转换为 Blender `(x,-z,y)`，方向采用同一转换。输入 `normal` 是材质空间单位法线。

材质 ID 依次为 Core_Basic、MainBody、MainBody_nolights、Tower、Pillar、Tube、Roof、Screen，取值 0–7。源对象槽位到 ID 的映射保存在 `core_materials.json`。

`coreHeight` 返回 `vec3(Height, Bump Strength, Bump Distance)`，只计算有效依赖。`coreBumpFromNeighbors` 接收中心材质和两个切线正方向的相邻 Height，按实际单格间距计算定向差分。调用方提供材质空间的单位正交 normal/tangent/bitangent；邻域使用相同材质、投影法线和坐标规则，跨页采样同时提供相同网格的边界邻域。Height 只影响着色法线。

## 生成与复用

支持范围以当前 Core 的可达节点为准，包括 Math、Vector Math、Mapping、Map Range、两点 Color Ramp、Mix、White Noise、Distortion 参数为 0 的归一化 2D/3D fBM Noise，以及 3D F1 Chebychev Voronoi Position。有效节点或参数超出范围时报告错误。材质求值范围限定为材质节点，几何节点由几何路径处理。

`core_surface.glsl` 使用 Minecraft `#moj_import`；独立 OpenGL 调用方先展开 include。生成表达式使用 `precise`，已验证 GLSL 430 compute 和 fragment 编译。

一次生成按独立的纹理网格位置、来源法线和材质身份保存唯一上下文。GPU 按实际可变通道选择程序变体，常量／禁用通道直接使用声明值；需要精细法线的变体执行 Height 邻域采样；多个暴露面、矩形边缘和对齐 padding 通过索引复制结果。输入、材质求值、图集 scatter 和 mip 作为独立阶段推进，末批 fence 完成后发布 High。共享图集中的 tile 同时平移 UV 和 `mc_midTexCoord`，base/mip 使用对应 tile 原点。

## Noise 选项

`NOISE_REDUCED_DETAIL=0` 保留完整频层。默认值 `1` 对非发光依赖的 Noise 省去最高频层，并保持原归一化权重；发光颜色和强度的所有上游 Noise 使用完整计算。White Noise、Voronoi 和当前材质 Height 保持一致。该选项会改变部分 Base Color 和 Roughness；效果以同一几何、相机和照明条件确认。

## 验证范围

精细页按 `Precision` 的颜色、PBR、法线独立世界网格中心采样，三个默认精度均为每方块 8 个采样；生成几何支持每方块 0.5、1、2、4、8 个体素。来源法线和材质由几何单元确定，纹理采样按精度、位置和来源上下文去重。颜色精度同时决定自发光采样；PBR 精度决定 specular RGB，LabPBR specular alpha 使用颜色样本，因此 specular 物理尺寸至少等于颜色和 PBR 尺寸中的较大者。法线 Height 单格差分使用法线采样间距。各档及物理容量边界见[多级精度](../../geometry/docs/multiresolution.md)。

Greedy 只合并来源法线完全相同的面，矩形内材质逐纹素保留。每块矩形复制一圈边缘，分配尺寸按 2 对齐，并填满额外对齐纹素；NEAREST 空间采样和两级 mip 的 footprint 始终留在本矩形。增加 mip 级数或改用线性空间过滤时重新确定留边。颜色按 sRGB 解码后平均，法线三维平均后归一化；粗糙度使用平方平均并加入法线方差，金属类别按覆盖数选择，发光强度平均。

库保留 HDR Emission；RGBA8 LabPBR 出口将发光强度限制到 0–1 并映射至 0–254，颜色取 Base Color 与 Emission 的逐通道最大值后限幅。因此该出口使用受限后的颜色和强度，Height 只写入法线。生成需要 OpenGL compute、image load/store 和 SSBO；失败页保留 Low。Iris 借用程序化法线和材质纹理的 view；同尺寸砖共享图集，最后一块砖的退休 fence 完成后销毁图集。材质 SSBO 在 fence 完成后进入容量复用池，最多四组、合计 32 MiB。

验证以当前 `raw.blend` 的八种材质为来源，使用 Blender Cycles 线性通道与独立 OpenGL 输出比较，覆盖六个轴向面和偏移至 `(128,-192,224)` 的采样区域，比较容差为 `1e-4`。法线辅助函数使用解析线性 Height 验证；独立预览用于固定 PBR 条件下的算法比对，Iris 游戏画面采用实际游戏环境验证。

算法语义参考 Blender 官方源码；散列和噪声基础资料见 [Bob Jenkins lookup3](https://burtleburtle.net/bob/c/lookup3.c)、[Ken Perlin Improved Noise](https://mrl.cs.nyu.edu/~perlin/noise/) 和 [Blender Shader 源码](https://projects.blender.org/blender/blender/src/branch/main/source/blender/gpu/shaders/material)。这些资料用于理解算法，实际支持边界由当前导出器和已验证资产限定。

基础级程序法线在纹素中心计算一次，Height 使用两个正向邻居；单格阶跃保持一格过渡，交替高度保留正负斜率。颜色、自发光、粗糙度和金属度保持四点采样。缩小后的法线在 mip 阶段平均，粗糙度按该级法线方差补偿。
