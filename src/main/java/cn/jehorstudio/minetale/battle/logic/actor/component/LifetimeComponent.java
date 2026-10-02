package cn.jehorstudio.minetale.battle.logic.actor.component;

import cn.jehorstudio.minetale.battle.logic.action.actions.DestroyActorAction;
import cn.jehorstudio.minetale.battle.logic.event.contexts.ActorContext;

final class LifetimeComponent implements ActorComponentRuntime {
    @Override
    public void onTick(ActorComponentContext context) {
        double seconds = ActorComponentJson.doubleValue(context.componentData(), "seconds", 0.0D);
        if (seconds <= 0.0D || context.self().scripted() == null || context.self().scripted().ageSeconds() < seconds) {
            return;
        }
        context.runAction(new DestroyActorAction(context.self().ref()), new ActorContext(context.self().ref()));
        context.runtime().actors().remove(context.self().ref());
    }
}
