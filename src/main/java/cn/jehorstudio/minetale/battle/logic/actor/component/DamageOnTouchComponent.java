package cn.jehorstudio.minetale.battle.logic.actor.component;

import cn.jehorstudio.minetale.battle.logic.action.actions.DamagePlayerAction;
import cn.jehorstudio.minetale.battle.logic.action.actions.HealPlayerAction;
import cn.jehorstudio.minetale.battle.logic.actor.Actor;
import cn.jehorstudio.minetale.battle.logic.event.contexts.ActorTargetContext;

final class DamageOnTouchComponent implements ActorComponentRuntime {
    @Override
    public void onCollidePlayer(ActorComponentContext context, Actor player) {
        if (!"player".equals(ActorComponentJson.lowerString(context.componentData(), "target", "player"))) {
            return;
        }
        double amount = ActorComponentJson.doubleValue(context.componentData(), "amount", 0.0D);
        String attackType = ActorComponentJson.lowerString(context.componentData(), "attackType", "white");
        ActorTargetContext hit = new ActorTargetContext(context.self().ref(), player.ref());
        if ("green".equals(attackType)
                && "heal".equals(ActorComponentJson.lowerString(context.componentData(), "greenMode", "none"))) {
            context.runAction(new HealPlayerAction(amount), hit);
            return;
        }
        context.runAction(new DamagePlayerAction(amount, attackType), hit);
    }
}
