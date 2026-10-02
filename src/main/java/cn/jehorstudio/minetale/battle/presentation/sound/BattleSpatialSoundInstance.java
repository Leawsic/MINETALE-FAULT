package cn.jehorstudio.minetale.battle.presentation.sound;

import cn.jehorstudio.minetale.battle.logic.BattleInstance;
import cn.jehorstudio.minetale.battle.logic.actor.ActorRef;
import cn.jehorstudio.minetale.battle.logic.coordinate.BattleViewMode;
import cn.jehorstudio.minetale.battle.logic.coordinate.CanonicalVec3;
import cn.jehorstudio.minetale.battle.logic.rules.RuleResolvedView;
import com.mojang.blaze3d.audio.ListenerTransform;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;

// 每 tick 将 Battle canonical 相对向量重新映射到当前 Minecraft listener 坐标系。
final class BattleSpatialSoundInstance extends AbstractTickableSoundInstance {
    private static final double ENGINE_UNITS_PER_L = 4.0D;

    private BattleInstance instance;
    private final ActorRef sourceActor;
    private CanonicalVec3 sourcePosition;
    private Vec3 lastEnginePosition = Vec3.ZERO;

    BattleSpatialSoundInstance(
            BattleInstance instance,
            ResourceLocation sound,
            SoundSource source,
            double volumeMultiplier,
            double pitchMultiplier,
            ActorRef sourceActor,
            CanonicalVec3 initialSourcePosition,
            long randomSeed
    ) {
        super(SoundEvent.createVariableRangeEvent(sound), source, RandomSource.create(randomSeed));
        this.instance = Objects.requireNonNull(instance, "instance");
        this.sourceActor = Objects.requireNonNull(sourceActor, "sourceActor");
        this.sourcePosition = Objects.requireNonNull(initialSourcePosition, "initialSourcePosition");
        this.volume = (float) volumeMultiplier;
        this.pitch = (float) pitchMultiplier;
        this.looping = false;
        this.relative = false;
        this.attenuation = Attenuation.LINEAR;
        updatePosition();
    }

    @Override
    public void tick() {
        if (this.instance != null) {
            updatePosition();
        }
    }

    void detach() {
        if (this.instance != null) {
            updatePosition();
            this.instance = null;
        }
    }

    private void updatePosition() {
        BattleInstance current = this.instance;
        if (current == null) {
            apply(this.lastEnginePosition);
            return;
        }
        current.stateCache().actors().resolve(this.sourceActor)
                .ifPresent(actor -> this.sourcePosition = actor.transform().position());
        if (!current.stateCache().players().initialized()) {
            return;
        }
        CanonicalVec3 soulPosition = current.stateCache().actors()
                .resolve(current.stateCache().players().soulRef())
                .map(actor -> actor.transform().position())
                .orElse(null);
        if (soulPosition == null) {
            return;
        }

        BattleViewMode view = RuleResolvedView.resolve(
                current.stateCache().rules().snapshot(),
                current.stateCache().coordinates().snapshot()
        ).viewMode();
        CanonicalVec3 forward = view.viewDirection();
        CanonicalVec3 right = forward.cross(view.cameraUp()).normalize();
        CanonicalVec3 up = right.cross(forward).normalize();
        CanonicalVec3 delta = this.sourcePosition.subtract(soulPosition);

        ListenerTransform listener = Minecraft.getInstance().getSoundManager().getListenerTransform();
        Vec3 enginePosition = listener.position()
                .add(listener.right().scale(delta.dot(right) * ENGINE_UNITS_PER_L))
                .add(listener.up().scale(delta.dot(up) * ENGINE_UNITS_PER_L))
                .add(listener.forward().scale(delta.dot(forward) * ENGINE_UNITS_PER_L));
        this.lastEnginePosition = enginePosition;
        apply(enginePosition);
    }

    private void apply(Vec3 position) {
        this.x = position.x;
        this.y = position.y;
        this.z = position.z;
    }
}
