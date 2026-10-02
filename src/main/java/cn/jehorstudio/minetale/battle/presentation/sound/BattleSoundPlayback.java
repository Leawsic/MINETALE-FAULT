package cn.jehorstudio.minetale.battle.presentation.sound;

import cn.jehorstudio.minetale.battle.logic.BattleInstance;
import cn.jehorstudio.minetale.battle.logic.action.BattleSoundRequest;
import cn.jehorstudio.minetale.battle.logic.action.BattleSoundRequestOccurrence;
import cn.jehorstudio.minetale.lib.client.sound.ClientSoundPlayback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.SoundEngine;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

// 将逻辑层 BattleSoundRequest 一次性提交给客户端 SoundManager。
public final class BattleSoundPlayback {
    private final BattleInstance instance;
    private final List<BattleSpatialSoundInstance> spatialSounds = new ArrayList<>();

    public BattleSoundPlayback(BattleInstance instance) {
        this.instance = Objects.requireNonNull(instance, "instance");
    }

    public void accept(List<BattleSoundRequestOccurrence> occurrences) {
        for (BattleSoundRequestOccurrence occurrence : occurrences) {
            play(occurrence);
        }
        this.spatialSounds.removeIf(sound -> !Minecraft.getInstance().getSoundManager().isActive(sound));
    }

    public void detachAll() {
        this.spatialSounds.forEach(BattleSpatialSoundInstance::detach);
        this.spatialSounds.clear();
    }

    private void play(BattleSoundRequestOccurrence occurrence) {
        BattleSoundRequest request = occurrence.request();
        if (request.sourceActor().isEmpty()) {
            ClientSoundPlayback.playRelative(
                    request.sound(),
                    request.source(),
                    request.volumeMultiplier(),
                    request.pitchMultiplier(),
                    request.playbackSeed()
            );
            return;
        }
        BattleSpatialSoundInstance sound = new BattleSpatialSoundInstance(
                this.instance,
                request.sound(),
                request.source(),
                request.volumeMultiplier(),
                request.pitchMultiplier(),
                request.sourceActor().orElseThrow(),
                request.initialSourcePosition().orElseThrow(),
                request.playbackSeed()
        );
        SoundEngine.PlayResult result = Minecraft.getInstance().getSoundManager().play(sound);
        if (result != SoundEngine.PlayResult.NOT_STARTED) {
            this.spatialSounds.add(sound);
        }
    }
}
