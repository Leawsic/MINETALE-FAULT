package cn.jehorstudio.minetale.battle.network.client;

import cn.jehorstudio.minetale.battle.logic.actor.ActorRef;
import cn.jehorstudio.minetale.battle.network.NetworkObjectId;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class NetworkProxyIndex {
    private final Map<NetworkObjectId, ActorRef> actorsByNetworkObjectId = new HashMap<>();

    public void bind(NetworkObjectId networkObjectId, ActorRef actorRef) {
        Objects.requireNonNull(networkObjectId, "networkObjectId");
        Objects.requireNonNull(actorRef, "actorRef");
        ActorRef previous = this.actorsByNetworkObjectId.putIfAbsent(networkObjectId, actorRef);
        if (previous != null && !previous.equals(actorRef)) {
            throw new IllegalStateException("networkObjectId is already bound to another actorRef.");
        }
    }

    public Optional<ActorRef> resolve(NetworkObjectId networkObjectId) {
        Objects.requireNonNull(networkObjectId, "networkObjectId");
        return Optional.ofNullable(this.actorsByNetworkObjectId.get(networkObjectId));
    }

    public void unbind(NetworkObjectId networkObjectId) {
        this.actorsByNetworkObjectId.remove(Objects.requireNonNull(networkObjectId, "networkObjectId"));
    }

    public void clear() {
        this.actorsByNetworkObjectId.clear();
    }
}
