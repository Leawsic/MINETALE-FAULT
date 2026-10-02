# Player Soul

Soul 是每名玩家唯一的服务端权威状态。`SoulRegistry.SOUL_ATTACHMENT` 持有该事实；`Soul Item` 和 `SoulEntity` 只是当前状态的投影，不是独立所有者。

## 状态与投影

- `ITEM`：应在玩家物品栏中保留一个不可堆叠的 `minetale:soul`。此状态不绑定世界实体。
- `FLYING`：移除物品投影，绑定一个不可见、不可碰撞、不可受伤的 `SoulEntity` 作为交互代理。
- `RETURNING`：继续使用世界实体推进召回；成功写回物品栏后才切回 `ITEM`，背包已满则回到 `FLYING`。

`Soul.Events` 在玩家 Tick、丢弃物品、容器关闭、死亡、维度切换和克隆时收敛投影。服务端通过 `SoulServerCoordinator` 推进世界代理；客户端通过 `SoulClientPresentation` 和每玩家一个 `SoulActionController` 绘制可见模型，两者的位置不是同一份权威状态。

## 交互

- 丢弃 Soul Item 会启动 `FLYING` 投出，而不是生成普通 `ItemEntity`。
- Soul 留在外部容器并关闭菜单时，`SoulContainerRelease` 延迟从容器开口投出。
- Soul 飞行距离超过 32 格时自动进入 `RETURNING`。
- 玩家双手为空且 Soul 处于 `FLYING` 时，可以长按使用键请求召回。阈值来自客户端配置 `minetale-interaction-client.toml`，默认 0.7 秒，允许 0.1–5 秒。

## 主要入口

- `Soul`：持久状态、状态转换和投影收敛。
- `SoulEntity`：服务端交互代理与低频动作同步。
- `SoulServerCoordinator`、`SoulCompanionBrain`：服务端飞行和召回目标。
- `SoulClientPresentation`、`SoulActionController`：客户端模型与连续运动。
- `SoulRecall`、`SoulRecallClient`：召回配置、输入和网络请求。
