# Placement Blocker

`minetale:placement_blocker` 是模板内的占位方块，用来声明“目标位置必须可替换”。Scanner 只记录它在模板中的局部坐标；Validator 在应用 Rotation/Mirror 后检查对应世界坐标。

允许的目标状态：

- Air、Structure Void、Snow；
- 无 Fluid、不是 Log/Leaves，且 `BlockState.canBeReplaced()` 为 true；
- 已存在的 Placement Blocker。

其余 Liquid、阻挡运动、有 Collision Shape 或不可替换状态都会使校验失败。结果包含失败数量、世界坐标和具体原因。

Placement Blocker 只参与前置校验，不应出现在最终结构中；`PlacementBlockerCleanupProcessor` 会在模板放置时清理它。

诊断命令：

```text
/minetale asset scan_blockers <template>
/minetale asset validate_blockers <template> <origin> <rotation> <mirror>
```
