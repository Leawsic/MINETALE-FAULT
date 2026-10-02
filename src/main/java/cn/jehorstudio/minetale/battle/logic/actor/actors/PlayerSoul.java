package cn.jehorstudio.minetale.battle.logic.actor.actors;

import cn.jehorstudio.minetale.battle.logic.BattleLogicStateCache;
import cn.jehorstudio.minetale.battle.logic.actor.Actor;
import cn.jehorstudio.minetale.battle.logic.coordinate.CanonicalTransform;
import cn.jehorstudio.minetale.battle.logic.coordinate.CanonicalVec3;
import cn.jehorstudio.minetale.battle.logic.actor.ActorType;
import cn.jehorstudio.minetale.battle.logic.actor.CollisionBox;
import cn.jehorstudio.minetale.battle.logic.actor.CollisionShape;
import cn.jehorstudio.minetale.battle.logic.actor.VisualRef;
import cn.jehorstudio.minetale.battle.logic.event.LogicEventListener;
import cn.jehorstudio.minetale.battle.logic.event.LogicEvents;
import cn.jehorstudio.minetale.battle.logic.states.PlayerStateCache;

import java.util.Objects;

public final class PlayerSoul {
    private static final double SOUL_COLLISION_HALF_SIZE = 0.0375D;

    // Soul 模型枢轴位于底部中心；碰撞中心须使用该偏移，且不受表现缩放影响。
    private static final CanonicalVec3 MODEL_BOUNDS_CENTER_FROM_PIVOT =
            new CanonicalVec3(0.0D, 0.0D, -0.08D);

    private final BattleLogicStateCache state;

    public PlayerSoul(BattleLogicStateCache state) {
        this.state = Objects.requireNonNull(state, "state");
    }

    @LogicEventListener(LogicEvents.ON_INITIALIZE)
    public void initialize() {
        Actor soul = this.state.actors().createActor(ActorType.PLAYER_SOUL);
        soul.setTransform(CanonicalTransform.IDENTITY);
        soul.setCollisionShape(collisionShapeAtModelCenter());
        soul.setVisual(VisualRef.special("player_soul"));
        this.state.players().initializeLocalPlayer(PlayerStateCache.DEBUG_LOCAL_PLAYER_ID, soul.ref());
    }

    public static CollisionShape collisionShapeAtModelCenter() {
        return CollisionShape.obb(new CollisionBox(
                MODEL_BOUNDS_CENTER_FROM_PIVOT,
                new CanonicalVec3(
                        SOUL_COLLISION_HALF_SIZE,
                        SOUL_COLLISION_HALF_SIZE,
                        SOUL_COLLISION_HALF_SIZE
                ),
                0.0D,
                0.0D,
                0.0D
        ));
    }
}
