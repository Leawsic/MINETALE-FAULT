package cn.jehorstudio.minetale.battle.logic.actor.component;

import cn.jehorstudio.minetale.battle.logic.actor.Actor;

public interface ActorComponentRuntime {
    default void onSpawn(ActorComponentContext context) {
    }

    default void onTick(ActorComponentContext context) {
    }

    default void onCollidePlayer(ActorComponentContext context, Actor player) {
    }

    default void onCollideBattleBox(ActorComponentContext context) {
    }

    default void onDestroy(ActorComponentContext context) {
    }
}
