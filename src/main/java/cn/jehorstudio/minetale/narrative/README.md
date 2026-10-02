# Narrative

`narrative` 模块负责剧情状态、对话资源编译、服务端对话会话、对话网络消息和客户端对话界面。

## 主要入口

- `data/NarrativeReloadListener`：读取三类 datapack 资源并发布快照。
- `data/NarrativeCompiler`：解析资源、检查引用、语言骨架和规则歧义。
- `state/StoryStateStore`：读取和修改 player/world 剧情状态。
- `runtime/DialogueSessionManager`：选择对话、执行节点、处理中断和恢复。
- `network/DialogueNetworking`：接收客户端推进与选择请求。
- `client/DialogueClientController`：维护当前 `DialogueScreen`。

## 继续阅读

- [边界与运行规则](CONTEXT.md)
- [对话 datapack 格式](docs/dialogue-data-pack-format.md)

