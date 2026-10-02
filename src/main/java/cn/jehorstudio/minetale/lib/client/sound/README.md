# Client Sound

该子模块只有一个共享入口：`ClientSoundPlayback.playRelative(...)`。

它创建不衰减、相对 Listener 的一次性 `SimpleSoundInstance`，并立即交给当前客户端 `SoundManager`。调用方提供 Sound Event ID、`SoundSource`、音量乘数和音调乘数；可选重载允许传入固定随机种子。

返回值是 `SoundEngine.PlayResult`。本模块不排队、不重试、不持有播放句柄，也不负责：

- Battle 的 Actor 空间定位；
- 循环、暂停、停止或淡入淡出。

这些语义由调用模块或 Minecraft 声音引擎决定。
