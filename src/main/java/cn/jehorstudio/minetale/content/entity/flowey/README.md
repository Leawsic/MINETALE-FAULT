# Flowey

该模块实现 `minetale:flowey` 实体、Spawn Egg 和 GeckoLib 表现。

## 当前行为

- 实体固定原地，不接受水平推动、击退、拴绳或常规伤害；`/kill` 仍可移除。
- 实体要求持久化，不因远离玩家自然消失。
- 服务端在 8 格内选择有视线且位于可转动范围内的最近玩家，先转动脸部、再转动茎部；无目标时偶尔进行随机 Idle 注视。
- 注视角通过 Synched Entity Data 发往客户端，由 `FloweyModel` 应用到 GeckoLib Bone。
- 右键交互会调用 `DialogueSessionManager.tryStart`。默认 Dialogue Profile 是 `minetale:flowey`，也会随实体数据保存自定义 Profile ID。
- `FloweySpawnEggItem` 在方块或流体上生成实体时，会把根部朝向使用者。

主要入口是 `Flowey`、`FloweyRegistry`、`FloweyRenderer` 和 `FloweySpawnEggItem`；Dialogue 内容本身位于 Narrative Data Pack。
