# 魔法特殊效果

特殊效果拥有受影响实体上的持续规则，与使用它的技能或法术生命周期分离。当前成员是 [karma/Karma](karma/Karma.java)。

`Karma.hit` 通过标准伤害流程执行接触伤害并积累延迟欠量；`canHarm` 统一检查归属、PvP 与队伍友伤。GB 只提供伤害归属与本次强度。

Karma Attachment 按来源 UUID 保存份额、名称与玩家标记，按首次进入池的顺序排空；总量由份额求和。持续接触不重置排空计时。受害者卸载后保存欠量而不离线结算，重生不复制。

接触与延迟伤害使用独立 DamageType，跳过护甲、受击冷却并取消击退；延迟部分不会把目标降到 1 HP 以下。`KarmaCooldownMixin` 保护普通攻击的无敌帧和差额状态，不在整个回调后恢复旧快照。来源失效或实体跨 Level 时停止相应结算。

包迁移不改变 Attachment、DamageType 或数据资源 ID。没有 Karma HUD，伤害机制保留。全域生命周期约束见 [Magic CONTEXT](../CONTEXT.md)。
