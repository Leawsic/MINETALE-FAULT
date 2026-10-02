package cn.jehorstudio.minetale.battle.logic.actor.component;

import cn.jehorstudio.minetale.battle.logic.action.actions.DestroyActorAction;
import cn.jehorstudio.minetale.battle.logic.actor.Actor;
import cn.jehorstudio.minetale.battle.logic.event.contexts.ActorTargetContext;

final class DestroyOnTouchComponent implements ActorComponentRuntime {
    @Override
    public void onCollidePlayer(ActorComponentContext context, Actor player) {
        if (!"player".equals(ActorComponentJson.lowerString(context.componentData(), "target", "player"))) {
            return;
        }
        if (ActorComponentJson.booleanValue(context.componentData(), "self", true)) {
            context.runAction(new DestroyActorAction(context.self().ref()), new ActorTargetContext(context.self().ref(), player.ref()));
            context.runtime().actors().remove(context.self().ref());
        }
    }
}
