package cn.jehorstudio.minetale.battle.logic.actor.component;

import cn.jehorstudio.minetale.battle.logic.actor.Actor;
import cn.jehorstudio.minetale.battle.logic.coordinate.CanonicalTransform;

final class VelocityMovementComponent implements ActorComponentRuntime {
    @Override
    public void onTick(ActorComponentContext context) {
        Actor actor = context.self();
        double seconds = context.instance().timeline().secondsPerBattleStep();
        CanonicalTransform old = actor.transform();
        actor.setTransform(new CanonicalTransform(
                old.position().add(actor.velocity().scale(seconds)),
                old.yawDeg(),
                old.pitchDeg(),
                old.rollDeg(),
                old.scale()
        ));
    }
}
