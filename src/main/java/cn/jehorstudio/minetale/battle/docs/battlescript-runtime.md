# BattleScript 运行时

## 资源与加载

Battle 定义位于 `data/<namespace>/battles/<path>.json`，资源 ID 为 `<namespace>:<path>`。`BattleScriptReloadListener` 在服务端资源重载时调用 `BattleScriptCompiler.compileLenient`；无效定义被记录并排除，其他合法定义仍会发布到 `BattleScriptCatalog`。

Java `BattleScriptCompiler`、`BattleScriptValidator` 与 Action 实现是实际运行时接受范围的最终依据。仓库内可运行样例位于 `src/main/resources/data/minetale/battles/debug/`。

## 顶层结构

`schemaVersion` 与 `phaseGraph` 必填。当前编译器还处理：

- `variables`：带类型和初始表达式的 Battle 变量。
- `imports`：按 global ID 引入其他定义导出的 ActorPrefab、ruleset、pattern 或 outcome。
- `budgets`：运行时资源上限。
- `actors`：ActorPrefab。
- `rulesets`：供 Phase 或 Action 压入的规则集合。
- `patterns`：可复用 Action 数组。
- `outcomes`：把具名结果映射为 `VICTORY`、`DEFEAT` 或 `ESCAPED`。
- `phaseGraph`：入口 Phase、Phase 内容和转换条件。

本地 ID 不带 namespace；可导出内容通过 `globalId` 暴露为 namespaced ID。定义 hash 同时纳入已导入定义的 hash，依赖内容变化会改变最终 hash。

## 执行

`BattleScriptBootstrap` 为 `BattleInstance` 注册一个 `BattleScriptRuntime`。初始化时声明变量、应用预算并进入 `phaseGraph.entry`。每个 Battle step 推进当前 Phase、Action Stack、输入事件和 Scripted Actor 事件。

Phase 可声明 `rulesets`、`onEnter`、`onTick`、`onExit`、持续时间和按顺序检查的 `transitions`。转换可进入另一 Phase 或结束 Battle。Actor 行为由 prefab、`ScriptedActorStateCache`、组件和事件 Action 共同组成，不在 Actor 类中保存脚本执行器。

当前 Actor 组件：`velocity_movement`、`bounce_on_battle_box`、`spin`、`lifetime`、`damage_on_touch`、`heal_on_touch`、`destroy_on_touch`、`emit_signal_on_touch`。

## Action 范围

当前工厂支持以下类别：

- 流程：`wait`、`wait_until`、`sequence`、`parallel`、`random_one`、`repeat`、`until`、`if`、`if_else`、`call_pattern`。
- Actor：`spawn_actor`、`spawn_bullet`、`destroy_actor`、`cleanup_tag`、`set_actor_property`、`destroy_self` 及 Actor 外观和文本操作。
- 状态：`set_var`、`modify_var`、`set_actor_var`、`modify_actor_var`。
- 规则与事件：`push_ruleset`、`pop_ruleset`、`emit_signal`、`end_phase`、`end_battle`。
- 玩家与移动：`damage_player`、`heal_player`、`set_self_velocity`、`set_self_position`、`move_self_by_velocity`、`set_collision_policy`。
- 表现：`request_render_action`、`play_sound`、`smooth_camera`、`set_view_mode`。

字段、表达式和条件的精确形状不要从本文推断；修改数据前应同时检查 schema、`BattleScriptActionFactory`、`BattleScriptValueEvaluator` 和对应 Action 实现。

## 默认预算

`BattleScriptBudgets.DEFAULT` 当前限制每 tick Actor 生成 256、Signal 派发 512、循环 1024、递归深度 64、规则栈 64、单 Stack Action 256、Battle tick 总 Action 1024、渲染请求 64。定义可在 `budgets` 中逐项覆盖，但所有值必须为 32 位正整数。

