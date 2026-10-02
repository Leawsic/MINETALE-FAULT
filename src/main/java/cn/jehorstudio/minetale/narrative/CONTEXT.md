# Narrative 边界与运行规则

## 数据快照

`NarrativeReloadListener` 一次读取 story state、Dialogue Profile 和多语言 Dialogue Package，并交给 `NarrativeCompiler` 生成完整 `NarrativeSnapshot`。任何资源失败都会拒绝整份新快照并保留上一份有效快照；重载前活动会话会被中断并保存待恢复栈。

`NarrativeCatalog` 是已编译叙事数据的唯一运行时入口。实体只保存 Dialogue Profile ID，不保存规则或对话正文副本。

## 状态所有权

Story state 必须先在 `story_states` 中声明类型、作用域和默认值。`StoryStateStore` 是唯一修改入口：

- `player` 状态保存在玩家 `NarrativeAttachments.PLAYER_DATA` 中。
- `world` 状态保存在主世界的 `WorldStorySavedData` 中。
- 无效、类型不匹配或越界的已存值读取时回退到定义默认值。
- `set` 和 `add` 节点执行到该步时立即提交，不等待整段对话结束。

## 选择与执行

Dialogue Profile 的规则根据 story state 和白名单世界事实选择 Dialogue Package。规则数组不是优先级；编译器计算逻辑具体度并拒绝可达的歧义组合。进入 Profile 时只选择一次，后续只有 `branch`、choice result、`random` 或 `shuffle_cycle` 等显式节点会再次选择内容。

`DialogueSessionManager` 是服务端权威解释器。每名玩家至多一个活动会话；真实实体目标同时只能被一个会话保留，virtual target 不参与全局保留。客户端只能请求推进当前页或选择当前选项，服务端校验 session、节点、选项和最早推进 tick。

解释器使用显式 Frame 栈，每个 server tick 最多执行 256 个非阻塞步骤。页面或 choice 阻塞执行，直到客户端动作；10 分钟无进展会中断。

## 中断与恢复

受伤、死亡、离线、切换维度、资源重载、服务器停止或 Battle 启动会中断会话。常规中断把执行栈保存到玩家附件，并按 Profile ID 关联；下次与同一 Profile 交互时优先恢复。正常完成、执行错误和无效选择会清除待恢复内容。

`shuffle_cycle` 历史只存在于当前服务端进程内，按玩家、Dialogue Package ID 和节点 ID 隔离；玩家离线或服务器重启后清空。

## 客户端边界

服务端发送已经选定的页面或选项文字、显示策略、portrait 和声音参数。客户端负责打字机、布局、输入和音效播放，不读取 story state，也不决定分支或 Battle 参与者。
