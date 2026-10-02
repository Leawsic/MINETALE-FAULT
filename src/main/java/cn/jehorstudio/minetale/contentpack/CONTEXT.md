# Content Pack 上下文

- 外部 Content Pack 是只读 `.mtpack` 归档；运行时只把它作为服务端 Data Pack 注册。
- 安装库位于游戏目录的 `minetale/contentpack/`。安装不等于启用，启用状态属于具体世界。
- 正式发布身份由 `contentPackId`、`version` 和 `contentDigest` 共同确定；同一 ID 和版本不得对应不同摘要。
- 开发部署由 `development.workspaceUuid` 标识，不能经正式安装入口导入，也不能成为其他包的依赖。
- 世界活动栈中同一 `contentPackId` 只能启用一个版本，依赖必须已启用、版本匹配且位于依赖者下方。
- 跨包引用只允许直接依赖；目标必须由依赖包显式 export。覆盖还必须指向 `replaceable` export 并显式声明 Override。
- 活动栈验证失败会使本次服务器资源重载失败；上一份已发布的 Battle/Narrative 快照不会被新资源替换。
- 世界侧 `minetale/contentpack-profile.json` 固定仓库 ID、版本和摘要。启动时不允许 Minecraft 静默丢弃缺失归档后继续加载。
- `main` 不是外部归档，而是模组内嵌资源的公共 export catalog；外部包只能覆盖其中标记为 `replaceable` 的条目。