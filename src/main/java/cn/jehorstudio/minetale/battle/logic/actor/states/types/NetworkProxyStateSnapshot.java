package cn.jehorstudio.minetale.battle.logic.actor.states.types;

import cn.jehorstudio.minetale.battle.logic.actor.ActorType;
import cn.jehorstudio.minetale.battle.network.NetworkObjectId;

import java.util.Objects;
import java.util.UUID;

public record NetworkProxyStateSnapshot(
        NetworkObjectId networkObjectId,
        UUID ownerPlayerId,
        ActorType proxyType,
        ActorTypeSnapshot proxySnapshot
) implements ActorTypeSnapshot {
    public NetworkProxyStateSnapshot {
        Objects.requireNonNull(networkObjectId, "networkObjectId");
        Objects.requireNonNull(ownerPlayerId, "ownerPlayerId");
        Objects.requireNonNull(proxyType, "proxyType");
        Objects.requireNonNull(proxySnapshot, "proxySnapshot");
        if (proxyType == ActorType.NETWORK_PROXY) {
            throw new IllegalArgumentException("proxyType cannot be NETWORK_PROXY.");
        }
        if (proxySnapshot.actorType() != proxyType) {
            throw new IllegalArgumentException("proxySnapshot actorType must match proxyType.");
        }
    }

    @Override
    public ActorType actorType() {
        return ActorType.NETWORK_PROXY;
    }
}
