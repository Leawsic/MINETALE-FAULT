# 客户端准备与呈现

## Preparation

`BattleInitialPayload` 只让 `BattlePreparation` 登记 `battleId`。收到正式 `BattleStartPayload` 后，客户端才创建 `BattlePresentation`，收集 `BattleResourceSet` 并请求 `EnvironmentCaptureService` 捕获环境。

`BattlePreparation` 是单例，一次只允许一个候选 Battle。捕获完成并产出可用环境结果后，它调用 `BattlePresentation.activate`；在此之前不设置活动 Battle、不打开 `BattleScreen`，也不提交渲染命令。新的 Preparation 会释放旧候选资源和捕获会话。

## 激活后的职责

`BattlePresentation` 每个客户端 tick：

- 采集本地输入并更新本地玩家 Soul。
- 推进 `BattleInstance`。
- 把已提交逻辑快照送入 `RenderTimeline`。
- 应用远端 Soul 网络采样。
- 消费 `BattleRenderRequest` 和 `BattleSoundRequest`。
- 生成只读的 `RenderSnapshot` 供 `BattleScreen` 渲染。

`Renderer` 和其 render pass 消费场景快照、表现状态与 `BattleResourceSet`；逻辑 Actor 的 canonical transform 不应为镜头过渡或屏幕特效而修改。

## 状态分层

- `BattleLogicStateCache`：玩法状态的可变事实源。
- `BattleLogicStateSnapshot`：Battle step 边界上的只读逻辑快照。
- `RenderTimeline`：在已提交样本间按显示时间采样。
- `PresentationStateCache`：屏幕震动、闪光、背景透明度、前景覆盖等表现状态。
- `BattleResourceSet`：当前 Battle 使用的材质、模型和纹理描述。

环境捕获与资源在 Battle 关闭或 Preparation 被替换时必须释放。活动实例通过 `BattlePresentation.active()` 唯一标识；同时存在两个已激活 Battle 属于非法状态。

