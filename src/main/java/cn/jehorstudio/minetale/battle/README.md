# Battle

`battle` 模块负责 Battle 的本地逻辑仿真、BattleScript 加载与执行、联机启动与状态转发，以及客户端画面和声音表现。

## 主要入口

- `script/BattleScriptReloadListener`：从 `data/<namespace>/battles/*.json` 加载定义。
- `script/BattleScriptCompiler`：解析、跨定义引用解析和运行时校验。
- `network/server/BattleStartCoordinator`：发起正式联机 Battle。
- `logic/BattleInstance`：拥有时间线、逻辑状态、事件和 Action Stack。
- `script/BattleScriptRuntime`：把已编译定义接入 `BattleInstance`。
- `presentation/BattlePreparation`：在画面激活前准备资源和环境捕获。
- `presentation/BattlePresentation`：驱动本地实例、网络采样与表现状态。
- `presentation/screen/BattleScreen`：Battle 画面的 Screen 入口。

## 继续阅读

- [边界与不变量](CONTEXT.md)
- [BattleScript 运行时](docs/battlescript-runtime.md)
- [启动、同步与结束](docs/battle-lifecycle.md)
- [客户端准备与呈现](docs/client-presentation.md)

