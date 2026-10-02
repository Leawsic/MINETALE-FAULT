package cn.jehorstudio.minetale.battle.logic.actor.component;

import cn.jehorstudio.minetale.battle.logic.actor.Actor;

final class EmitSignalOnTouchComponent implements ActorComponentRuntime {
    @Override
    public void onCollidePlayer(ActorComponentContext context, Actor player) {
        if (!"player".equals(ActorComponentJson.lowerString(context.componentData(), "target", "player"))) {
            return;
        }
        String signal = ActorComponentJson.stringValue(context.componentData(), "signal", "");
        if (!signal.isBlank()) {
            context.runtime().emitSignal(signal);
        }
    }
}
