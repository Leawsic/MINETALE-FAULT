package cn.jehorstudio.minetale.battle.logic.actor.actors;

import cn.jehorstudio.minetale.battle.logic.BattleLogicStateCache;
import cn.jehorstudio.minetale.battle.logic.actor.Actor;
import cn.jehorstudio.minetale.battle.logic.actor.ActorAppearance;
import cn.jehorstudio.minetale.battle.logic.coordinate.CanonicalTransform;
import cn.jehorstudio.minetale.battle.logic.coordinate.CanonicalVec3;
import cn.jehorstudio.minetale.battle.logic.actor.ActorType;
import cn.jehorstudio.minetale.battle.logic.actor.CollisionBox;
import cn.jehorstudio.minetale.battle.logic.actor.CollisionShape;
import cn.jehorstudio.minetale.battle.logic.actor.VisualRef;
import cn.jehorstudio.minetale.battle.logic.event.LogicEventListener;
import cn.jehorstudio.minetale.battle.logic.event.LogicEvents;

import java.util.Objects;

public final class BattleBox {
    private final BattleLogicStateCache state;

    public BattleBox(BattleLogicStateCache state) {
        this.state = Objects.requireNonNull(state, "state");
    }

    @LogicEventListener(LogicEvents.ON_INITIALIZE)
    public void initialize() {
        Actor battleBox = this.state.actors().createActor(ActorType.BATTLE_BOX);
        battleBox.setTransform(CanonicalTransform.IDENTITY);
        battleBox.setCollisionShape(CollisionShape.obb(new CollisionBox(
                CanonicalVec3.ZERO,
                CanonicalVec3.ONE,
                0.0D,
                0.0D,
                0.0D
        )));
        battleBox.setVisual(VisualRef.appearance(new ActorAppearance(
                ActorAppearance.Mode.DUAL,
                true,
                ActorAppearance.FrameContent.spatial(
                        new ActorAppearance.WorldSize(2.0D, 2.0D, 2.0D),
                        0.04D,
                        0xFFFFFFFF
                )
        )));
    }
}
