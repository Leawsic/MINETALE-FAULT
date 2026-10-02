package cn.jehorstudio.minetale.battle.logic.actor.states.types;

import cn.jehorstudio.minetale.battle.logic.actor.ActorType;
import cn.jehorstudio.minetale.battle.network.NetworkObjectId;

import java.util.Objects;
import java.util.UUID;

public final class NetworkProxyStateCache {
    private NetworkObjectId networkObjectId;
    private UUID ownerPlayerId;
    private ActorType proxyType;
    private ActorTypeSnapshot proxySnapshot = PlayerSoulStateSnapshot.DEFAULT;

    public boolean initialized() {
        return this.networkObjectId != null && this.ownerPlayerId != null && this.proxyType != null;
    }

    public NetworkObjectId networkObjectId() {
        return this.networkObjectId;
    }

    public UUID ownerPlayerId() {
        return this.ownerPlayerId;
    }

    public void setNetworkIdentity(NetworkObjectId networkObjectId, UUID ownerPlayerId, ActorType proxyType) {
        Objects.requireNonNull(networkObjectId, "networkObjectId");
        this.ownerPlayerId = Objects.requireNonNull(ownerPlayerId, "ownerPlayerId");
        this.proxyType = Objects.requireNonNull(proxyType, "proxyType");
        if (proxyType == ActorType.NETWORK_PROXY) {
            throw new IllegalArgumentException("proxyType cannot be NETWORK_PROXY.");
        }
        if (this.proxySnapshot.actorType() != proxyType) {
            this.proxySnapshot = defaultProxySnapshot(proxyType);
        }
        this.networkObjectId = networkObjectId;
    }

    public ActorType proxyType() {
        return this.proxyType;
    }

    public void setProxySnapshot(ActorType proxyType, ActorTypeSnapshot proxySnapshot) {
        Objects.requireNonNull(proxyType, "proxyType");
        Objects.requireNonNull(proxySnapshot, "proxySnapshot");
        if (proxySnapshot.actorType() != proxyType) {
            throw new IllegalArgumentException("proxySnapshot actorType must match proxyType.");
        }
        if (proxyType == ActorType.NETWORK_PROXY) {
            throw new IllegalArgumentException("proxyType cannot be NETWORK_PROXY.");
        }
        this.proxyType = proxyType;
        this.proxySnapshot = proxySnapshot;
    }

    public NetworkProxyStateSnapshot snapshot() {
        if (!initialized()) {
            throw new IllegalStateException("NetworkProxyStateCache has not been initialized.");
        }
        return new NetworkProxyStateSnapshot(
                this.networkObjectId,
                this.ownerPlayerId,
                this.proxyType,
                this.proxySnapshot
        );
    }

    private static ActorTypeSnapshot defaultProxySnapshot(ActorType proxyType) {
        return switch (proxyType) {
            case PLAYER_SOUL -> PlayerSoulStateSnapshot.DEFAULT;
            case BATTLE_BOX -> new NoActorTypeSnapshot(ActorType.BATTLE_BOX);
            case BULLET -> BulletStateSnapshot.EMPTY;
            case SCRIPTED_ACTOR -> ScriptedActorStateSnapshot.EMPTY;
            case NETWORK_PROXY -> throw new IllegalArgumentException("proxyType cannot be NETWORK_PROXY.");
        };
    }
}
