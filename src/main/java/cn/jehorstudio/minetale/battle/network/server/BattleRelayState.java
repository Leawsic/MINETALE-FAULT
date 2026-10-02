package cn.jehorstudio.minetale.battle.network.server;

import cn.jehorstudio.minetale.battle.logic.actor.ActorType;
import cn.jehorstudio.minetale.battle.network.NetworkObjectId;
import cn.jehorstudio.minetale.battle.network.payload.PlayerSoulLowFrequencyPatchPayload;
import cn.jehorstudio.minetale.battle.network.payload.PlayerSoulPositionSnapshotPayload;
import cn.jehorstudio.minetale.battle.network.payload.RelayedPlayerSoulLowFrequencyPatchPayload;
import cn.jehorstudio.minetale.battle.network.payload.RelayedPlayerSoulPositionSnapshotPayload;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class BattleRelayState {
    private final UUID battleId;
    private final Map<NetworkObjectId, RelayObjectState> objects = new HashMap<>();

    public BattleRelayState(UUID battleId) {
        this.battleId = Objects.requireNonNull(battleId, "battleId");
    }

    public UUID battleId() {
        return this.battleId;
    }

    public RelayObjectState registerObject(NetworkObjectId networkObjectId, UUID ownerPlayerId, ActorType proxyType) {
        Objects.requireNonNull(networkObjectId, "networkObjectId");
        Objects.requireNonNull(ownerPlayerId, "ownerPlayerId");
        Objects.requireNonNull(proxyType, "proxyType");
        RelayObjectState existing = this.objects.get(networkObjectId);
        if (existing == null) {
            RelayObjectState object = new RelayObjectState(networkObjectId, ownerPlayerId, proxyType);
            this.objects.put(networkObjectId, object);
            return object;
        }
        if (!existing.ownerPlayerId().equals(ownerPlayerId)) {
            throw new IllegalStateException("networkObjectId is already bound to another ownerPlayerId.");
        }
        if (existing.proxyType() != proxyType) {
            throw new IllegalStateException("networkObjectId is already bound to another proxyType.");
        }
        return existing;
    }

    public Optional<RelayObjectState> resolve(NetworkObjectId networkObjectId) {
        Objects.requireNonNull(networkObjectId, "networkObjectId");
        return Optional.ofNullable(this.objects.get(networkObjectId));
    }

    public boolean acceptPosition(PlayerSoulPositionSnapshotPayload payload, long receiveGameTick) {
        requireBattle(payload.battleId());
        RelayObjectState object = this.objects.get(payload.networkObjectId());
        if (object == null || !object.matchesOwnerAndType(payload.ownerPlayerId(), ActorType.PLAYER_SOUL)) {
            return false;
        }
        return object.acceptPosition(payload, receiveGameTick);
    }

    public boolean acceptPatch(PlayerSoulLowFrequencyPatchPayload payload, long receiveGameTick) {
        requireBattle(payload.battleId());
        RelayObjectState object = this.objects.get(payload.networkObjectId());
        if (object == null || !object.matchesOwnerAndType(payload.ownerPlayerId(), ActorType.PLAYER_SOUL)) {
            return false;
        }
        return object.acceptPatch(payload, receiveGameTick);
    }

    public List<RelayedPlayerSoulPositionSnapshotPayload> drainPositionRelays(long relayGameTick) {
        List<RelayedPlayerSoulPositionSnapshotPayload> relays = new ArrayList<>();
        for (RelayObjectState object : this.objects.values()) {
            object.drainPosition(relayGameTick).ifPresent(relays::add);
        }
        return List.copyOf(relays);
    }

    public List<RelayedPlayerSoulLowFrequencyPatchPayload> drainPatchRelays(long relayGameTick) {
        List<RelayedPlayerSoulLowFrequencyPatchPayload> relays = new ArrayList<>();
        for (RelayObjectState object : this.objects.values()) {
            object.drainPatch(relayGameTick).ifPresent(relays::add);
        }
        return List.copyOf(relays);
    }

    public void removeObject(NetworkObjectId networkObjectId) {
        this.objects.remove(Objects.requireNonNull(networkObjectId, "networkObjectId"));
    }

    public void clear() {
        this.objects.clear();
    }

    private void requireBattle(UUID payloadBattleId) {
        if (!this.battleId.equals(Objects.requireNonNull(payloadBattleId, "payloadBattleId"))) {
            throw new IllegalArgumentException("payload battleId does not match relay state.");
        }
    }
}
