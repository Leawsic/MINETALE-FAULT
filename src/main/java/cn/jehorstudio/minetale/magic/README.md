# 世界内魔法

`magic` 负责世界内的技能施放、法术实体、特殊效果和客户端表现，独立于 Battle 本地仿真。技能「伽斯特冲击」负责解析目标、蓄力、选择炮位并召唤 Gaster Blaster。

## 模块与入口

| 模块 | 职责与入口 |
| --- | --- |
| [skill](skill/README.md) | `MagicCasting` 管理掌握、魔力、选择、锁定与施法会话；`GasterImpact` 实现伽斯特冲击 |
| [spell/gasterblaster](spell/gasterblaster/README.md) | `GasterBlaster.summon` 接收施放参数，法术实体管理生成、发射与销毁 |
| [visual](visual/README.md) | 光束、模型渐隐、镜头震动与 GPU 锁定粒子 |
| [effect](effect/README.md) | Karma 接触伤害与按来源保存的延迟伤害 |
| [MagicCollision](collision/MagicCollision.java) | 客户端与服务端共用的几何查询和传播阻挡规则 |
| [Magic](Magic.java)、[MagicClient](MagicClient.java) | 服务端、客户端装配 |
| [MagicConfig](MagicConfig.java) | 施法与激光视觉的内部默认值 |

首次进入世界获得并选中伽斯特冲击。默认 C 短按施放、按住蓄力、松开释放；满蓄力或魔力不足时自动释放，打开界面或失焦时取消。鼠标中键锁定或切换目标，同目标或空处解除；无可锁定对象时保留原版方块选取。按键可重新绑定。

满蓄力默认达到 10 倍模型尺寸，完全展开后发射 30 Tick；创造模式免魔力。施法反馈来自炮体、光束和命中表现。调试入口为 `/minetale magic`，自身 `status`、`select` 无需管理员权限，其余命令要求权限等级 2。

施法流程见 [skill](skill/README.md)，生命周期和跨模块约束见 [CONTEXT](CONTEXT.md)。
