package cn.jehorstudio.minetale.lib.client.sound;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

// 集中播放无空间衰减的客户端本地音效。
public final class ClientSoundPlayback {
    private ClientSoundPlayback() {
    }

    public static SoundEngine.PlayResult playRelative(
            ResourceLocation sound,
            SoundSource source,
            double volumeMultiplier,
            double pitchMultiplier
    ) {
        return playRelative(sound, source, volumeMultiplier, pitchMultiplier, RandomSource.create());
    }

    public static SoundEngine.PlayResult playRelative(
            ResourceLocation sound,
            SoundSource source,
            double volumeMultiplier,
            double pitchMultiplier,
            long randomSeed
    ) {
        return playRelative(
                sound,
                source,
                volumeMultiplier,
                pitchMultiplier,
                RandomSource.create(randomSeed)
        );
    }

    private static SoundEngine.PlayResult playRelative(
            ResourceLocation sound,
            SoundSource source,
            double volumeMultiplier,
            double pitchMultiplier,
            RandomSource random
    ) {
        return Minecraft.getInstance().getSoundManager().play(new SimpleSoundInstance(
                sound,
                source,
                (float) volumeMultiplier,
                (float) pitchMultiplier,
                random,
                false,
                0,
                SoundInstance.Attenuation.NONE,
                0.0D,
                0.0D,
                0.0D,
                true
        ));
    }
}
