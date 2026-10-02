# 场景资产

本包读取 `.mtscene`，验证容器边界，复制来源条目并解码图像。`SceneAsset` 拥有 ZIP 和资源快照；`SceneImages` 拥有解码后的通道与 mip 像素；`SceneChannels` 验证并保存材质常量、禁用通道和兼容声明。

入口为 `SceneAsset.open` 和 `SceneImages.read`。几何包从这里读取已验证的条目；运行时接收资产和图像的所有权。本包只依赖文件、编码和图像库。

文件结构见[文件契约](docs/mtscene-format.md)，跨包生命周期见[场景约定](../CONTEXT.md)。

`SceneParts` 读取可选的共享原型、部件 LOD、图集和摆放目录，契约见 [.mtscene 文件格式](docs/mtscene-format.md)。
