# Battle 边界与不变量

## 运行模型

正式 Battle 由服务端选定定义、参与者、seed 和起始 Battle tick。客户端必须在本地目录中找到 ID、schema version 与 hash 都匹配的定义，随后各自创建 `BattleInstance` 并执行同一份逻辑。服务端不运行完整战斗仿真。

`BattleInstance` 是单个客户端内的逻辑所有者。它拥有 `LogicTimeline`、`BattleLogicStateCache`、`LogicEventDispatcher`、`ActionStackManager` 以及脚本运行时。逻辑写入可变 cache；渲染和事件消费快照，不直接拥有逻辑状态。

## 固定边界

- `battle.script` 只描述并驱动 Battle。
- `battle.network` 只负责握手、参与者集合、远端 Soul 数据转发和结果汇总。
- `battle.presentation` 只在客户端运行；它推进本地 `BattleInstance`，消费逻辑快照、渲染请求和声音请求。
- `BattleScriptCatalog` 保存当前已加载定义。网络启动只发送定义引用、hash、schema version、seed 与初始状态，不发送完整 JSON。
- 无参 `BattleInstance` 和 `DebugLocalBattleController` 是本地调试路径。

## 时间与状态

`LogicTimeline` 将 Minecraft game tick 转换为 Battle step，并限制追赶步数。每个 step 的顺序是：推进 Action Stack、派发 step 事件、生成逻辑快照、派发 snapshot 事件、清理 tick 末暂态。

BattleScript 的普通随机与声音随机使用两个独立 `Random`；新增音效调用不得改变玩法随机序列。脚本预算在初始化时写入 Action Stack、Actor 生成、Signal、规则栈和渲染请求等边界。

## 当前联机限制

远端同步目前只覆盖玩家 Soul 的高频位置和低频状态补丁。每个客户端仍自行推进脚本和本地逻辑；服务端仅检查消息所属 Battle 与发送者身份，不校验完整仿真结果。结果在所有参与者上报后按 `DEFEAT`、`ESCAPED`、`VICTORY` 的优先级汇总。
