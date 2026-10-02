# 场景客户端运行时

本包把资产和 CPU 几何接入当前世界，并管理异步装载、需求调度、GPU 驻留、接管和释放。

| 职责 | 入口及内部实现 |
|---|---|
| 命令与世界事件 | `SceneClient` |
| 装载和基础缓存 | `SceneLoader`、`SceneBaseCache` |
| 接缝缓存与场景配置 | `SceneSeamCache`、`SceneConfig` |
| 调度和覆盖切换 | `SceneRuntime` |
| 固定网格常驻及材质装载 | `SceneFixed` |
| 部件实例与表面附着 | `SceneInstances`、`SceneAttachments`、`SceneInstanceShader` |
| GPU 上传与绘制 | `SceneGpu`、`SceneMaterials`、`SceneTexture`、`SceneTerrain` |

调度和 GPU 对象共享上传、就绪与驻留状态，并集中在本包内维护。`SceneClient` 接收外部事件，其余实现类型保持包内可见。最多两条生成通道独占 CPU 工作区，运行中及待上传成果共用最多四项队列；渲染线程分批上传、发布接管，并在 fence 完成后回收 GPU 资源。High 进入距离两倍以外的闲置成果分帧释放，附近折返复用已有成果。

深入阅读：[场景使用](docs/usage.md)、[部件实例与 LOD](docs/parts.md)、[固定几何与纹理生命周期](docs/fixed-geometry.md)、[基础缓存](docs/base-lod-cache.md)、[程序化材质](docs/procedural-materials.md)、[terrain 契约](docs/shader-uv-contract.md)、[场景约定](../CONTEXT.md)。

移动相机的可见性逐帧更新；High 请求范围以外且没有 High 覆盖的页只做页级剔除。可见砖集合和覆盖状态决定页绘制列表的失效，细节需求最多每 50 ms 合并规划一次；模式和精度命令立即生效。已有 Low 始终提供完整覆盖，异步 High 完成后接管。

游戏内“体素 → 大场景”提供 Low 接缝磁盘缓存开关与路径；默认关闭磁盘缓存，内存复用始终启用。配置保存到 `minetale-voxel-client.toml`，在下次加载场景时应用。缓存契约见[基础缓存](docs/base-lod-cache.md)。
