# Content Pack 运行时契约

本文只描述 Java Runtime 当前接受和执行的 `.mtpack` format v1。归档构建规则由 Creator 模块负责。

## 归档边界

`.mtpack` 是 UTF-8 ZIP 兼容归档，必须同时包含 `contentpack.json` 和 `pack.mcmeta`。归档不得包含目录占位、绝对路径、反斜杠、空路径段、`.`、`..` 或仅大小写不同的重复路径。

验证限制如下：

| 项目 | 限制 |
| --- | ---: |
| 条目数 | 8192 |
| 单条目展开大小 | 64 MiB |
| 总展开大小 | 512 MiB |
| 单条目压缩比 | 100:1 |

`resources` 必须精确列出除 `contentpack.json` 外的全部条目。每个条目的 SHA-256 必须匹配，`contentDigest` 则是按路径排序后的 `path + NUL + sha256 + LF` 清单摘要。

## `contentpack.json`

根对象拒绝未知字段，必须符合下列形状：

```json
{
  "format": "minetale-content-pack",
  "formatVersion": 1,
  "contentPackId": "example:pack",
  "displayName": "Example Pack",
  "version": "1.0.0",
  "minMineTaleVersion": "1.0.0",
  "contentDigest": "<64 位小写 SHA-256>",
  "dependencies": [],
  "exports": [],
  "overrides": [],
  "resources": []
}
```

`development` 是唯一可选的根字段，存在时只能包含 UUID 字符串 `workspaceUuid`。外部 `contentPackId` 必须是非 `main` 的 `ResourceLocation`。

### 依赖

```json
{"contentPackId": "example:base", "versionRange": "^1.2.0"}
```

当前范围解析器支持 `*`、精确版本、`< <= = >= >` 的空格合取、`||`、`^` 和 `~`；版本必须是完整 SemVer 三段式。

### Export 与 Override

Export 的 `type` 只能是 `battle`、`actor_prefab`、`pattern`、`dialogue` 或 `story_state`；`replacement` 只能是 `sealed` 或 `replaceable`。`story_state` 可额外声明 `stateAccess: read_only | read_write`。

Override 必须声明 `providerContentPackId`、`resourceId`、`type` 和 `compatibleVersionRange`。Provider 只能是 `main` 或当前包的直接依赖；实际同 ID 资源只有在目标 export 为 `replaceable` 且版本范围匹配时才合法。

### 资源清单

资源项必须包含 `path`、`sha256` 和 `kind`。`kind` 只能是：

- `battle`、`dialogue`、`story_state`：必须提供 `resourceId`；
- `asset`、`data`、`metadata`：不要求 `resourceId`。

`locale` 只允许用于 `dialogue`。清单解析并不推断路径，构建方必须提供完整分类。

## 安装与发现

`ContentPackInstaller.install` 会先复制到安装库临时文件，完成全部验证后再原子移动为 `<sanitized-id>-<version>.mtpack`。正式入口拒绝开发部署；目标身份已存在时，仅接受相同摘要。

`ContentPackRepository` 每次扫描安装库时重新验证所有 `.mtpack`。无效归档不会进入 Pack Repository，拒绝原因会显示在 Content Pack 管理界面。合法 Pack 的仓库 ID 为：

```text
minetale/contentpack/<contentPackId>/<version>/<contentDigest>
minetale/contentpack/dev/<workspaceUuid>
```

## 活动栈验证

服务器资源重载时依次检查：

1. 同一 Content Pack ID 没有多版本并存；
2. `minMineTaleVersion` 已满足；
3. 每个依赖存在、版本匹配、不是开发部署，并位于依赖者下方；
4. 依赖图无环；
5. Runtime 资源冲突都具有合法 export 和 Override；
6. Battle imports 与 Narrative 中的 Battle、Dialogue、Story State 引用遵守直接依赖和访问权。

`actor_prefab` 与 `pattern` export 通过 Battle JSON 内的全局定义收集；其他 export 必须对应本包 `resources` 中的 Runtime 资源。普通 Data Pack 资源是否存在仍由各 Runtime compiler 负责。

## 世界固定

活动栈会写入 `<world>/minetale/contentpack-profile.json`。文件保存每个选中 Pack 的仓库 ID、Content Pack ID、版本、摘要和可选 Workspace UUID。已有 Profile 的世界启动前会把文件与当前选中 Pack 精确比较；缺失或身份变化会中止恢复并要求显式修复。
