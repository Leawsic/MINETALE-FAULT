package cn.jehorstudio.minetale.battle.network.client;

import cn.jehorstudio.minetale.battle.network.NetworkObjectId;
import cn.jehorstudio.minetale.battle.network.NetworkSequence;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public final class SourceSequenceTracker {
    private final Map<NetworkObjectId, NetworkSequence> latestByObject = new HashMap<>();

    public NetworkSequence next(NetworkObjectId networkObjectId) {
        Objects.requireNonNull(networkObjectId, "networkObjectId");
        NetworkSequence current = this.latestByObject.get(networkObjectId);
        NetworkSequence next = current == null ? NetworkSequence.ZERO : current.next();
        this.latestByObject.put(networkObjectId, next);
        return next;
    }

    public void reset(NetworkObjectId networkObjectId) {
        this.latestByObject.remove(Objects.requireNonNull(networkObjectId, "networkObjectId"));
    }

    public void clear() {
        this.latestByObject.clear();
    }
}
