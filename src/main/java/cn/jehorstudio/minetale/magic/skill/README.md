# 技能

本模块决定实体如何使用能力。`MagicCasting` 拥有魔力、流派资格、技能掌握、当前选择、锁定与施法会话；它是这些状态的唯一修改入口。非玩家实体使用同一套服务端操作。

| 入口 | 职责 |
| --- | --- |
| [MagicCasting](MagicCasting.java) | 授予／撤销、选择、开始／释放／取消、魔力参数与只读快照 |
| [Skill](Skill.java)、[MagicCatalog](MagicCatalog.java) | Java 技能注册及每次独占的施法会话；common setup 后目录冻结 |
| [gasterimpact/GasterImpact](gasterimpact/GasterImpact.java) | 伽斯特冲击蓄力、配置到 GB 参数的换算、释放 |
| [gasterimpact/GasterPlacement](gasterimpact/GasterPlacement.java) | 有界随机围攻选址，读取 GB 提供的模型包络和现存炮位 |
| [MagicTargeting](MagicTargeting.java) | 准星实体查询（返回 Entity 或 null）、已加载区域视线；供中键锁定及技能调用，不决定技能索敌策略 |
| [MagicNetworking](MagicNetworking.java)、[client/MagicInput](client/MagicInput.java) | 输入意图、序号校验、锁定同步与客户端按键 |
| [MagicCommands](MagicCommands.java) | 状态和施法调试命令；失败只返回 0，不发送失败文字 |

## 伽斯特冲击

起手费、逐 Tick 消费和最大蓄力时长在开始时确定。索敌策略由 `GasterImpact` 自身决定，其他技能不继承此策略。释放时优先使用显式锁定；没有锁定时，准星在锁定范围内命中的可锁定实体只作为本次释放目标，不改变玩家的锁定状态；否则使用视线方向前方 120 格的固定点自由瞄准（距离由 `GasterImpact.FREE_AIM_DISTANCE` 定义）。随后将成功付费 Tick 换算为 0..1 蓄力，再读取当前技能配置换算法术尺寸、伤害和阶段时长，选址并调用 GB。自动释放和手动释放经过同一条路径。

实体目标的选址以目标周围上半球为基础；自由瞄准则以发射者眼部为中心，在水平朝向的左右前方 30..60 度区域选址，并朝前方 120 格目标点发射。48 个方向各采样两个距离。尺寸越大，距离越远且更偏向低俯仰；附近现存 GB 参与分散和避让。最多检查 24 个加权候选，依次接受完整出场空间、稳定炮体空间和炮口空闲；远处不可用时退到发射者附近。仅没有有效世界原点或世界拒绝加入实体时失败并退款，不用预计命中否决释放。

大包络的方块碰撞查询按 chunk section 遍历，跳过整段空气；查询外扩、跨分段大形状和实体碰撞保持原版规则。候选分布、加权随机和退让顺序不变。

锁定是持续意向：遮挡、转身或暂时不可选中不取消。死亡、移除、跨 Level 或超距才失效；初次准星选取仍需要可见。锁定不代表激光能穿墙。

持久化、重生、取消和网络权威约束见 [Magic CONTEXT](../CONTEXT.md)。技能只持有本次施放的状态；GB 生成后归法术实体所有，后续取消蓄力不销毁已生成的炮。
