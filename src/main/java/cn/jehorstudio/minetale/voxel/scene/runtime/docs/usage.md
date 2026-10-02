# 场景使用

```text
/mt_scene load minetale:core 0 64 0
/mt_scene mode paged
/mt_scene mode low
/mt_scene mode raw
/mt_scene mode voxel
/mt_scene precision 4 8 2 2
/mt_scene precision brick 0 0 0 8 8 4 4
/mt_scene greedy true
/mt_scene origin 0 64 0
/mt_scene culling true
/mt_scene stats
/mt_scene reset_metrics
/mt_scene unload
```

场景 ID `minetale:core` 对应资源包中的 `assets/minetale/scene/core.mtscene`，项目文件位于 `src/main/resources/` 下的同一路径。资源包覆盖和重载作用于完整场景。`paged` 对含控制网格的资产使用精细体素，其他资产使用 Raw；`voxel` 显式选择精细体素；`low` 显示基础远景；`raw` 按页请求白板。`greedy false` 关闭矩形合并并重建近场页。场景摆放只影响当前客户端，退出世界时释放。

Low-only 的 MSVT 原生输出使用 `raw_available=false`。按页模式继续显示 Low，Raw 命令报告 Raw 来源缺失。

`stats` 的 `triangles` 是提交的三角形数，包含固定三角形适配共享四边面索引时的退化三角形；`draws` 是绘制调用数。`gpuMiB` 统计 Low、Raw、固定 Mesh 和 High 的实际图集、顶点及索引容量；临时 SSBO 与驱动开销单独统计。`failed` 表示保留 Low 的失败砖数。`uploadFrame`、`uploadTotal` 以字节计；帧间隔统计包含客户端其他工作，`cpuAvg` 只统计场景渲染方法。`gpuBakeP95/max` 表示已完成材质计算批次的 GPU 时间；`stats` 同时记录场景原点和相机位置。

`visibilityQueries` 记录新索引路径的空间查询次数；静止视点及上传接管复用已有查询。`preparedMiB`、`workspaceMiB`、`curveCacheMiB` 和 `indexPayloadMiB` 分别表示待上传数组、生成工作区、曲面缓存和静态索引的 CPU 数据量，口径见[静态空间索引](../../geometry/docs/static-spatial-index.md)。

`precision` 参数依次为几何、颜色（含自发光）、PBR、法线；全局设置会清除逐砖覆盖。砖坐标使用场景局部 8 方块网格，负坐标沿用向下取整。`0.5 0.5 0.5 0.5` 使用常驻基础 LOD；Core 对应真实的 2 方块体素，其他导入资产使用原始 LOD。几何 0.5 配合更高材质精度时生成近场砖。当前接口接受显式档位；High 进入距离为 `min(256, 96 × sqrt(8 / geometry))` 米，退出距离为其 1.25 倍；默认几何 2 对应 192／240 米，进入后直接生成目标精度。High 进入距离两倍以外的闲置成果分帧释放，附近折返复用已有成果。

全局材质精度是距离档位的上限：近处 8，中距离 4，远处 2；进入阈值为 48／96 方块，退出阈值为 56／112 方块。几何精度及 High 覆盖范围独立。逐砖 `precision brick` 使用指定材质精度，不受距离档位裁剪。切档时复用几何快照并重建材质。

材质距离档位切换保持已显示的 High，复用几何生成新材质，并在 GPU 上传完成后整体替换。生成或上传失败保留旧 High；几何精度或所需截面变化沿用对应重建流程。

Core 的模式、空间剔除、Greedy 和精度设置作用于 `needs_subdivision` 组。普通主体与三个保留 Mesh 采用全量常驻几何，所有模式均显示。材质逐批烘焙到完整常驻图集。`fixed=15/15` 表示固定 Mesh 的材质覆盖就绪。基础材质等待期间显示相同几何的白板。支持范围及生命周期见[固定几何](fixed-geometry.md)。

Core 的 11 个细分部件几何上限为每方块 2，其中 9 个管状部件的材质上限同为 2，默认在全通道 0.5 与全通道 2 之间切换；两座塔保持普通材质档位。默认几何 2 的进入／退出距离为 192／240 米，按相机到 8 方块砖 AABB 的最近距离计算。普通材质靠近时在 96 米由 2 升至 4、48 米由 4 升至 8；远离时在 56 米由 8 降至 4、112 米由 4 降至 2。阈值使用世界距离，1080p 与 4K 相同，分辨率和 FOV 不参与调度。

包含部件目录的场景自动加载共享原型，并按屏幕误差选择 LOD1–4。统计中的 `parts/partDraws/partTriangles` 分别表示本帧可见副本、部件提交和三角形数；`partGpuMiB` 与主体 `gpuMiB` 分开计量。`unattached/relocated/maxRelocation` 用于检查附着覆盖和来源差异，行为见[部件实例](parts.md)。
