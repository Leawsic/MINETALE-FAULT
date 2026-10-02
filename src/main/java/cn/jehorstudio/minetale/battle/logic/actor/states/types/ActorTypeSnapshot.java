package cn.jehorstudio.minetale.battle.logic.actor.states.types;

import cn.jehorstudio.minetale.battle.logic.actor.ActorType;

public sealed interface ActorTypeSnapshot permits NoActorTypeSnapshot, PlayerSoulStateSnapshot, BulletStateSnapshot, ScriptedActorStateSnapshot, NetworkProxyStateSnapshot {
    ActorType actorType();
}
