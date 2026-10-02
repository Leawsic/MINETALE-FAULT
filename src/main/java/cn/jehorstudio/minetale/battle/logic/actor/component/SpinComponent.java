package cn.jehorstudio.minetale.battle.logic.actor.component;

import cn.jehorstudio.minetale.battle.logic.actor.Actor;
import cn.jehorstudio.minetale.battle.logic.coordinate.CanonicalTransform;

final class SpinComponent implements ActorComponentRuntime {
    @Override
    public void onTick(ActorComponentContext context) {
        double degreesPerSecond = ActorComponentJson.doubleValue(context.componentData(), "degreesPerSecond", 0.0D);
        if (degreesPerSecond == 0.0D) {
            return;
        }
        Actor actor = context.self();
        double seconds = context.instance().timeline().secondsPerBattleStep();
        String axis = ActorComponentJson.lowerString(context.componentData(), "axis", "roll");
        CanonicalTransform old = actor.transform();
        actor.setTransform(new CanonicalTransform(
                old.position(),
                old.yawDeg() + ("yaw".equals(axis) ? degreesPerSecond * seconds : 0.0D),
                old.pitchDeg() + ("pitch".equals(axis) ? degreesPerSecond * seconds : 0.0D),
                old.rollDeg() + (!"yaw".equals(axis) && !"pitch".equals(axis) ? degreesPerSecond * seconds : 0.0D),
                old.scale()
        ));
    }
}
