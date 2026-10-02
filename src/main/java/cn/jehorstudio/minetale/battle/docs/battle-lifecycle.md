# Battle 启动、同步与结束

## 正式启动

`BattleStartCoordinator.start` 接收发起者、Battle 定义 ID 和候选玩家：

1. 确认定义存在、发起者不忙且属于候选集合。
2. 跳过断线或已处于其他 Battle 的非发起者，生成 `battleId` 与 seed。
3. 向受邀者发送 `BattleInitialPayload`，等待 `BattleInitialAckPayload`。
4. 全员确认时立即确定参与者；10 秒截止时，只要发起者已确认，就使用已确认且仍在线的子集。
5. 向最终参与者发送各自的 `BattleStartPayload`，并中断其活动对话。

发起者未在截止前确认时握手直接取消。`ServerBattleRegistry` 将未完成握手和活动会话都视为 busy。

## 客户端建立实例

`BattleStartPayload` 的共享部分包含 `battleId`、定义 ID、definition hash、schema version、起始 Battle tick、seed 和最终参与者。私有部分只包含当前接收者的初始玩家状态。

`BattlePresentation.startFromBattleStart` 必须在本地 `BattleScriptCatalog` 找到同 ID 定义，并验证 schema version 与 hash。验证通过后才创建关闭调试弹幕的 `BattleInstance`、应用私有玩家状态、创建远端玩家的 `NETWORK_PROXY` Actor，并进入 Preparation。失败时客户端发送 `BattleStartFailedPayload`。

## Battle 内同步

客户端定期向服务端发送本地 Soul 的位置快照和低频状态补丁。`BattleRelayState` 校验 Battle、对象所有者与序列后，把数据转发给其他参与者；其他 Actor 和完整逻辑状态不会经此协议同步。

## 结束

本地 `BattleInstance` 请求结果后，`BattlePresentation` 关闭画面、声音和环境捕获，并发送一次 `BattleResultReportPayload`。服务端接受每名最终参与者的首份结果；全部上报后广播汇总并关闭会话。汇总优先级为：任一 `DEFEAT` 则 `DEFEAT`，否则任一 `ESCAPED` 则 `ESCAPED`，否则 `VICTORY`。

