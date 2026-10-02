package cn.jehorstudio.minetale.battle.logic.action;

import cn.jehorstudio.minetale.battle.logic.actor.ActorRef;
import cn.jehorstudio.minetale.battle.logic.coordinate.CanonicalVec3;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;

import java.util.Objects;
import java.util.Optional;

// 由权威 Battle 逻辑产生、供本地客户端表现层一次性消费的音效请求。
public record BattleSoundRequest(
        ResourceLocation sound,
        SoundSource source,
        double volumeMultiplier,
        double pitchMultiplier,
        long playbackSeed,
        Optional<ActorRef> sourceActor,
        Optional<CanonicalVec3> initialSourcePosition
) {
    public BattleSoundRequest {
        Objects.requireNonNull(sound, "sound");
        Objects.requireNonNull(source, "source");
        sourceActor = Objects.requireNonNull(sourceActor, "sourceActor");
        initialSourcePosition = Objects.requireNonNull(initialSourcePosition, "initialSourcePosition");
        if (sourceActor.isPresent() != initialSourcePosition.isPresent()) {
            throw new IllegalArgumentException("sourceActor and initialSourcePosition must be present together.");
        }
        requireMultiplier(volumeMultiplier, "volumeMultiplier");
        requireMultiplier(pitchMultiplier, "pitchMultiplier");
    }

    private static void requireMultiplier(double value, String name) {
        if (!Double.isFinite(value) || value < 0.0D) {
            throw new IllegalArgumentException(name + " must be finite and >= 0.");
        }
    }
}
