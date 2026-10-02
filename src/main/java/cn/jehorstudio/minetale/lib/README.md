# Lib

该模块存放多个功能域共同使用、且不拥有玩法状态的客户端基础能力。

当前内容包括：

- `ObjLoader`、`ObjModels`：四边形 OBJ 资源索引、加载缓存和顶点提交；
- `GeckoModels`：GeckoLib 测试模型的资源映射；
- `Sprites`：战斗 GUI Sprite 元数据；
- `client.render.WorldModelRenderer`：共享世界模型绘制；
- [客户端一次性音效](client/sound/README.md)；
- [GPU 粒子生命周期](client/particle/README.md)。

领域状态和触发规则仍由调用模块拥有。
