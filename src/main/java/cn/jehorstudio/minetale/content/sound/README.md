# Sound Content

`MineTaleSoundEvents` 集中声明 MineTale 使用的 Sound Event `ResourceLocation`。事件定义位于 `assets/minetale/sounds.json`，实际文件位于 `assets/minetale/sounds/`。

当前仓库包含并可解析的事件：

- `minetale:ui.select`
- `minetale:ui.confirm`
- `minetale:battle.soul_hurt`
- `minetale:dialogue.flowey_voice`
- `minetale:dialogue.flowey_voice_alt`（与上一事件共用文件）

以下 ID 已在 Java 和 `sounds.json` 中声明，但对应 OGG 尚未提交，因此播放时会得到缺失资源：

- `minetale:battle.start`
- `minetale:dialogue.flowey_page_open`
- `minetale:battle.bullet_whoosh`
- `minetale:music.battle_default`

素材归属见项目级[资产声明](../../../../../../../../ASSET_NOTICES.md)及其链接的借物表。播放和触发行为由 [`lib.client.sound`](../../lib/client/sound/README.md) 及具体调用模块负责。
