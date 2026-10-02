package cn.jehorstudio.minetale.battle.presentation.debug;

import cn.jehorstudio.minetale.battle.logic.BattleInstance;
import cn.jehorstudio.minetale.battle.logic.actor.Actor;
import cn.jehorstudio.minetale.battle.logic.actor.ActorLifecycle;
import cn.jehorstudio.minetale.battle.logic.coordinate.CanonicalTransform;
import cn.jehorstudio.minetale.battle.logic.coordinate.CanonicalVec3;
import cn.jehorstudio.minetale.battle.logic.actor.ActorType;
import cn.jehorstudio.minetale.battle.logic.actor.VisualRef;
import cn.jehorstudio.minetale.battle.logic.actor.actors.PlayerSoul;
import cn.jehorstudio.minetale.battle.logic.actor.states.ActorSingleSnapshot;
import cn.jehorstudio.minetale.battle.network.NetworkObjectId;
import cn.jehorstudio.minetale.battle.presentation.BattlePresentation;
import cn.jehorstudio.minetale.battle.presentation.VisualConfig;

import java.util.UUID;

public final class DebugLocalBattleController {
    private static final UUID DEBUG_REMOTE_PLAYER_ID = UUID.fromString("00000000-0000-0000-0000-00000000b007");

    public BattleInstance createInstance() {
        BattleInstance instance = new BattleInstance();
        createDebugRemoteProxy(instance);
        return instance;
    }

    public void applyClientMovementToLogicState(BattlePresentation presentation) {
        applyDebugRemoteProxyMotion(presentation);
    }

    public void submitRemoteNetworkSamples(BattlePresentation presentation) {
        double displayTime = presentation.renderTimeline().renderBattleTime()
                + VisualConfig.REMOTE_SAMPLE_DELAY_SECONDS() * presentation.instance().timeline().battleTicksPerSecond();
        for (ActorSingleSnapshot actor : presentation.instance().currentStateSnapshot().actors().actors(ActorType.NETWORK_PROXY)) {
            if (actor.lifecycle() == ActorLifecycle.ACTIVE) {
                presentation.submitRemoteNetworkSample(actor, displayTime);
            }
        }
    }

    private static void createDebugRemoteProxy(BattleInstance instance) {
        Actor proxy = instance.stateCache().actors().createNetworkProxyActor(
                NetworkObjectId.playerSoul(instance.battleId(), DEBUG_REMOTE_PLAYER_ID),
                DEBUG_REMOTE_PLAYER_ID,
                ActorType.PLAYER_SOUL,
                VisualRef.special("player_soul"),
                ActorLifecycle.ACTIVE
        );
        proxy.setCollisionShape(PlayerSoul.collisionShapeAtModelCenter());
        proxy.setTransform(new CanonicalTransform(
                new CanonicalVec3(VisualConfig.DEBUG_REMOTE_SOUL_ORBIT_RADIUS(), 0.0D, 0.0D),
                0.0D,
                0.0D,
                0.0D,
                CanonicalTransform.IDENTITY.scale()
        ));
    }

    private static void applyDebugRemoteProxyMotion(BattlePresentation presentation) {
        Actor proxy = presentation.instance()
                .stateCache()
                .actors()
                .activeActors(ActorType.NETWORK_PROXY)
                .stream()
                .findFirst()
                .orElse(null);
        if (proxy == null) {
            return;
        }

        double seconds = presentation.instance().timeline().battleTick()
                * presentation.instance().timeline().secondsPerBattleStep();
        double angle = seconds * VisualConfig.DEBUG_REMOTE_SOUL_ORBIT_RADIANS_PER_SECOND();
        CanonicalVec3 position = new CanonicalVec3(
                Math.cos(angle) * VisualConfig.DEBUG_REMOTE_SOUL_ORBIT_RADIUS(),
                0.0D,
                Math.sin(angle) * VisualConfig.DEBUG_REMOTE_SOUL_ORBIT_RADIUS()
        );
        CanonicalTransform old = proxy.transform();
        proxy.setTransform(new CanonicalTransform(
                position,
                Math.toDegrees(-angle),
                old.pitchDeg(),
                old.rollDeg(),
                old.scale()
        ));
    }

}
