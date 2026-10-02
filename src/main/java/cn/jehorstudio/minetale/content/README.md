# Game Content

该模块注册 MineTale 自带的方块、物品、实体、玩家 Soul 和 Sound Event ID。它提供具体游戏内容。

## 当前内容

- `block.common`：`ruin_brick`、`geo_rock`、`snow_rock` 和 Snowtown Spruce 系列方块及物品。
- `item.common`：`monster_candy` 与 `bone_fragment`。
- [`entity.flowey`](entity/flowey/README.md)：Flowey 实体、Spawn Egg、GeckoLib 表现和 Dialogue Target 接入。
- `entity.monster_npc`：Snowtown 居民使用的外观、尺寸和移动速度目录；实体生命周期在 Worldgen Snowdin 模块。
- [`player.soul`](player/soul/README.md)：每名玩家唯一的 Soul 状态、物品/世界投影、召回与客户端表现。
- [`sound`](sound/README.md)：自定义 Sound Event ID 与已提交音频素材。
- `MineTaleCreativeTab`：把已注册内容放入 MineTale Creative Tab。
