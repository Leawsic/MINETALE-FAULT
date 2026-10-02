# Template Marker

`minetale:template_marker` 是结构模板内的语义 Block Entity。编辑界面和命令操作领域字段；序列化时语义 ID 使用 NBT Key `marker_id`，因为根 Key `id` 由 Block Entity 类型占用。

## Schema 1

当前字段由 `TemplateMarkerData` 定义：

| 字段 | 含义 |
| --- | --- |
| `schema` | 当前值为 1 |
| `kind` | `connector`、`anchor`、`slot` 或 `volume` |
| `marker_id` | Marker 的 `ResourceLocation` |
| `accepts` | Connector 可接受的对端 ID |
| `connect_mode` | `adjacent` 或 `overlap` |
| `group` | Connector Channel / 分组 |
| `role` | Endpoint / 角色 |
| `final_state` | 模板落地后替换 Marker 的 Block State，默认 `minecraft:air` |
| `priority` | 匹配排序值 |
| `tags` | 附加 ResourceLocation 列表 |
| `payload` | 必须是合法 JSON 字符串 |

`lot` 和 `point` 只用于旧数据读取，分别映射为 `slot` 和 `anchor`，不出现在当前可选 Kind 中。旧数据缺少 `connect_mode` 时按 `overlap` 读取；新建 Marker 默认 `adjacent`。

## 连接

- `adjacent`：子 Marker 放在父 Marker 朝向的相邻方块，两个 Marker 的朝向相反。
- `overlap`：父子 Marker 占用同一世界坐标，用于旧模板或特殊调试。

`ConnectorMatcher` 同时检查 ID/accepts、朝向、Channel、Endpoint、资产旋转/镜像许可和 Occupancy Bounds。匹配成功只产生 Placement/Plan；真正写入世界仍由 `AssetPlacer` 完成。

## 验证与清理

`TemplateMarkerValidation` 检查 Schema、ID、Connector accepts、`final_state` 和 JSON Payload。模板放置时，`TemplateMarkerCleanupProcessor` 会把 Marker 替换为各自的 `final_state`，因此 Marker Block 不应残留在生产地形中。

诊断命令：

```text
/minetale marker inspect <pos>
/minetale marker validate <pos>
/minetale asset transform ...
/minetale asset match ...
```
