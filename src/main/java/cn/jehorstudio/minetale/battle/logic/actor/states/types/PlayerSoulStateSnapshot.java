package cn.jehorstudio.minetale.battle.logic.actor.states.types;

import cn.jehorstudio.minetale.battle.logic.actor.ActorType;

import java.util.Objects;

public record PlayerSoulStateSnapshot(PlayerSoulMode mode) implements ActorTypeSnapshot {
    public static final PlayerSoulStateSnapshot DEFAULT = new PlayerSoulStateSnapshot(PlayerSoulMode.NORMAL);

    public PlayerSoulStateSnapshot {
        Objects.requireNonNull(mode, "mode");
    }

    @Override
    public ActorType actorType() {
        return ActorType.PLAYER_SOUL;
    }
}
