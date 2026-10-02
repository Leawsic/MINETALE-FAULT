package cn.jehorstudio.minetale.battle.logic.actor.component;

import cn.jehorstudio.minetale.battle.logic.action.actions.HealPlayerAction;
import cn.jehorstudio.minetale.battle.logic.actor.Actor;
import cn.jehorstudio.minetale.battle.logic.event.contexts.ActorTargetContext;

final class HealOnTouchComponent implements ActorComponentRuntime {
    @Override
    public void onCollidePlayer(ActorComponentContext context, Actor player) {
        if (!"player".equals(ActorComponentJson.lowerString(context.componentData(), "target", "player"))) {
            return;
        }
        double amount = ActorComponentJson.doubleValue(context.componentData(), "amount", 0.0D);
        context.runAction(new HealPlayerAction(amount), new ActorTargetContext(context.self().ref(), player.ref()));
    }
}
