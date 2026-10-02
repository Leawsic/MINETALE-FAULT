# 已知问题

## 构建结果和回读结果采用两条全互通查询路径

`SceneVisibility` 的构造函数根据 `connections` 计算一次 `unrestricted`。`build` 使用全零 `links` 创建对象，再逐页填充连通位，因此构建结果保留 `false`。当所有页面最终六向互通时，同一数据经过 `write` / `read` 后，构造函数得到 `true`。

两种对象分别采用查询路径：直接使用 `build` 的结果执行 flood fill，从文件回读的结果直接使用空间树。当前离线工具会写入构建结果，客户端从文件读取；运行时基础 LOD 目录沿用空间树查询。已确认差异影响直接查询构建结果的路径；当前客户端运行观测为完整绘制。

复现条件如下：构造一个 32 方块页、blocker 数量为 0 的 Low 占用和合法来源，再调用 `SceneVisibility.build`。此时 `connections[0]` 为 `(1L << 36) - 1`，`unrestricted` 为 `false`；序列化后再读取，`unrestricted` 变为 `true`。修复目标是让构建和回读采用同一路径。
