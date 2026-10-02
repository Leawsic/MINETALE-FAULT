# 对话 datapack 格式

本文只描述当前 `NarrativeCompiler` 接受的数据。可运行示例位于 `src/main/resources/data/minetale/`。

## 资源布局

```text
data/<namespace>/
├── story_states/<path>.json
├── dialogue_profiles/<path>.json
└── dialogues/
    └── <locale>/<path>.json
```

文件路径决定资源 ID。例如 `dialogues/zh_cn/flowey/chatter.json` 的 Dialogue Package ID 是 `minetale:flowey/chatter`，locale 不属于 ID。

## Story state

每个状态一个文件：

```json
{
  "scope": "player",
  "type": "int",
  "default": 0,
  "min": 0
}
```

- `scope`：`player` 或 `world`。
- `type`：`boolean`、`int` 或 `id`。
- `int` 可选 `min`、`max`。
- `id` 必须提供非空 `allowed` namespaced ID 数组。
- `default` 必须符合类型和范围。

## Dialogue Profile

Profile 把条件映射到 Dialogue Package：

```json
{
  "rules": [
    {
      "when": [
        {"state": "minetale:met_flowey", "op": "eq", "value": false}
      ],
      "run": "minetale:flowey/first_meeting"
    },
    {
      "when": [],
      "run": "minetale:flowey/chatter"
    }
  ]
}
```

同一条规则的 `when` 是 AND；指向同一 `run` 的多条规则是 OR。空 `when` 是 fallback。选择依据条件集合的逻辑具体度，不依据数组顺序；编译器会拒绝永远不成立的规则和无法判定唯一结果的重叠规则。

条件必须恰好包含 `state` 或 `read`：

- `boolean`：`eq`。
- `int`：`eq`、`ne`、`lt`、`le`、`gt`、`ge`。
- `id`：`eq`、`ne`。

当前 `read` 白名单：

- `interactor.biome`
- `interactor.sneaking`
- `target.biome`
- `target.entity_type`
- `level.dimension`
- `level.time_of_day`
- `level.raining`
- `level.thundering`

## Dialogue Package

```json
{
  "placement": "bottom",
  "portrait_mode": "no_portrait",
  "camera": "locked",
  "body": {
    "id": "root",
    "type": "page",
    "lines": [{"literal": "这是一行文本。"}]
  }
}
```

包级字段：

- `placement`：`top` 或 `bottom`。
- `portrait_mode`：`portrait` 或 `no_portrait`。
- `camera`：可省略，默认为 `locked`；另一个值是 `free`。
- `body`：一个执行节点。

每个节点都必须有包内唯一的稳定 `id` 和显式 `type`。当前节点类型：

- `sequence`：按顺序执行 `children`。
- `page`：显示 `lines[].literal`。
- `choice`：显示 `options`；每个 option 的 `results` 使用与 Profile 相同的规则格式选择后续包。
- `set`：把已声明状态设置为 `value`。
- `add`：给 int 状态增加 `value`。
- `run`：执行 `package`，完成后返回当前包。
- `branch`：用 `rules` 选择并执行一个包。
- `random`：从 `packages` 等概率选择一个包，允许连续重复。
- `shuffle_cycle`：从 `packages` 选择，当前循环内不重复。
- `start_battle`：通过正式 `BattleStartCoordinator` 启动 `battle`。

`start_battle.participants.scope` 为 `self` 或 `nearby`。`nearby` 必须提供 `range`，`self` 禁止提供。

## Page 与 choice

`page` 的 `advance` 默认为 `manual`，可设为 `auto`；auto page 必须提供非负 `auto_delay_seconds`。`reveal` 默认为 `skippable`，也可设为 `unskippable`。`characters_per_second` 默认 30。

`choice` 始终手动推进，禁止 `advance` 和 `auto_delay_seconds`；它同样支持 `reveal`、`characters_per_second`、portrait 与 sound。

`portrait_mode: portrait` 时每个 page/choice 必须提供 `portrait`；`no_portrait` 时禁止提供。编译器不自动拆页，作者需要控制每页内容长度。

## 页面声音

page 和 choice 的 `sound` 可包含 `per_page`、`per_grapheme` 或两者。每个槽可使用单个对象或最多 64 项的随机候选数组。

每个声音对象必须有 `event`；`source` 默认 `voice`。`volume_multiplier` 与 `pitch_multiplier` 可写固定数字或包含 `min`、`max` 的对象，且必须为有限数。声明的 Sound Event 还必须已经注册并有可播放资源；当前资源状态见 [资产声明](../../../../../../../../ASSET_NOTICES.md)。

## 多语言

每个逻辑包必须有 `zh_cn` 变体。玩家语言缺失时回退到 `zh_cn`。同一包的所有语言必须保持相同语义骨架；只允许以下内容不同：

- `lines[].literal`
- choice option 的 `literal`
- auto page 的 `auto_delay_seconds`

节点 ID、结构、条件、状态操作、portrait、声音、策略和包引用都必须一致，否则整份 Narrative 快照拒绝发布。
