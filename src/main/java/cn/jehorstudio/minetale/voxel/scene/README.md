# 分页场景

本模块在客户端显示分页场景。Low 提供完整的基础覆盖；运行时按需为近场空间砖生成 High，并在几何、材质和截面全部就绪后切换该砖的覆盖。Core 使用局部 Catmull-Clark 和程序化材质；通用来源使用已求值的原网格和材质。几何、颜色、PBR 与法线分别使用独立精度。

## 核心对象

每个场景包含一份只读资产、一份可查询曲面、每条生成通道独占的体素生成器和一个客户端运行时。空间页承担文件读取，空间砖承担细节接管；曲面、生成器和 GPU 对象分别拥有各自的缓存。完整数据流和生命周期见[模块设计](docs/architecture.md)。

## 包与入口

| 包 | 职责与入口 |
|---|---|
| [asset](asset/README.md) | 读取 `.mtscene` 容器并解码图像：`SceneAsset`、`SceneImages` |
| [geometry](geometry/README.md) | 查询来源曲面，维护空间目录，生成体素和基础几何：`SceneSurface`、`SceneIndex`、`SceneVoxels`、`SceneBase` |
| [runtime](runtime/README.md) | 接收客户端命令，装载场景，调度需求并管理 GPU 驻留与绘制：`SceneClient`、`SceneLoader`、`SceneRuntime` |

依赖方向为 `runtime → geometry → asset`；`runtime` 同时读取资产和图像。场景包根目录保存跨包约定与设计导航。常规构建只包含运行时源码。

## 深入阅读

- [场景使用](runtime/docs/usage.md)：命令、资源路径和统计口径。
- [CONTEXT.md](CONTEXT.md)：空间约定、所有权、容量和适用边界。
- [已知问题](geometry/docs/known-issues.md)：已确认且独立于重构的问题。
- [.mtscene 文件契约](asset/docs/mtscene-format.md)：编码、来源与 Low 的关系。
- [静态空间索引](geometry/docs/static-spatial-index.md)：离线预处理、保守遮挡和空间查询。
- [基础 LOD 缓存](runtime/docs/base-lod-cache.md)：首次生成、缓存失效和分发包。
- [多级精度](geometry/docs/multiresolution.md)：独立材质档位、相邻截面和接管规则。
- [Core 程序化材质](runtime/docs/procedural-materials.md)与[通用 Raw 材质](geometry/docs/source-materials.md)：两种来源的材质采样。
- [terrain 纹理区域契约](runtime/docs/shader-uv-contract.md)：顶点布局、UV 与 Iris 接入。