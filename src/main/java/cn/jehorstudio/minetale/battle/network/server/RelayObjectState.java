package cn.jehorstudio.minetale.battle.network.server;

import cn.jehorstudio.minetale.battle.logic.actor.ActorType;
import cn.jehorstudio.minetale.battle.network.NetworkObjectId;
import cn.jehorstudio.minetale.battle.network.NetworkSequence;
import cn.jehorstudio.minetale.battle.network.payload.PlayerSoulLowFrequencyPatchPayload;
import cn.jehorstudio.minetale.battle.network.payload.PlayerSoulPositionSnapshotPayload;
import cn.jehorstudio.minetale.battle.network.payload.RelayedPlayerSoulLowFrequencyPatchPayload;
import cn.jehorstudio.minetale.battle.network.payload.RelayedPlayerSoulPositionSnapshotPayload;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class RelayObjectState {
    private final NetworkObjectId networkObjectId;
    private final UUID ownerPlayerId;
    private final ActorType proxyType;
    private NetworkSequence lastPositionSeq;
    private NetworkSequence lastPatchSeq;
    private long lastReceiveGameTick = -1L;
    private long lastPositionRelayGameTick = -1L;
    private long lastPatchRelayGameTick = -1L;
    private PlayerSoulPositionSnapshotPayload pendingPosition;
    private PlayerSoulLowFrequencyPatchPayload pendingPatch;

    RelayObjectState(NetworkObjectId networkObjectId, UUID ownerPlayerId, ActorType proxyType) {
        this.networkObjectId = Objects.requireNonNull(networkObjectId, "networkObjectId");
        this.ownerPlayerId = Objects.requireNonNull(ownerPlayerId, "ownerPlayerId");
        this.proxyType = Objects.requireNonNull(proxyType, "proxyType");
        if (proxyType == ActorType.NETWORK_PROXY) {
            throw new IllegalArgumentException("proxyType cannot be NETWORK_PROXY.");
        }
    }

    public NetworkObjectId networkObjectId() {
        return this.networkObjectId;
    }

    public UUID ownerPlayerId() {
        return this.ownerPlayerId;
    }

    public ActorType proxyType() {
        return this.proxyType;
    }

    public long lastReceiveGameTick() {
        return this.lastReceiveGameTick;
    }

    public long lastRelayGameTick() {
        return Math.max(this.lastPositionRelayGameTick, this.lastPatchRelayGameTick);
    }

    public long lastPositionRelayGameTick() {
        return this.lastPositionRelayGameTick;
    }

    public long lastPatchRelayGameTick() {
        return this.lastPatchRelayGameTick;
    }

    boolean acceptPosition(PlayerSoulPositionSnapshotPayload payload, long receiveGameTick) {
        validateSource(payload.networkObjectId(), payload.ownerPlayerId(), receiveGameTick);
        if (this.lastPositionSeq != null && !payload.seq().newerThan(this.lastPositionSeq)) {
            return false;
        }
        this.lastPositionSeq = payload.seq();
        this.lastReceiveGameTick = receiveGameTick;
        this.pendingPosition = payload;
        return true;
    }

    boolean acceptPatch(PlayerSoulLowFrequencyPatchPayload payload, long receiveGameTick) {
        validateSource(payload.networkObjectId(), payload.ownerPlayerId(), receiveGameTick);
        if (this.lastPatchSeq != null && !payload.seq().newerThan(this.lastPatchSeq)) {
            return false;
        }
        this.lastPatchSeq = payload.seq();
        this.lastReceiveGameTick = receiveGameTick;
        this.pendingPatch = payload;
        return true;
    }

    boolean matchesOwnerAndType(UUID ownerPlayerId, ActorType proxyType) {
        return this.ownerPlayerId.equals(Objects.requireNonNull(ownerPlayerId, "ownerPlayerId"))
                && this.proxyType == Objects.requireNonNull(proxyType, "proxyType");
    }

    Optional<RelayedPlayerSoulPositionSnapshotPayload> drainPosition(long relayGameTick) {
        if (relayGameTick < 0L) {
            throw new IllegalArgumentException("relayGameTick must be >= 0.");
        }
        if (this.pendingPosition == null || this.lastPositionRelayGameTick == relayGameTick) {
            return Optional.empty();
        }
        RelayedPlayerSoulPositionSnapshotPayload relayed = new RelayedPlayerSoulPositionSnapshotPayload(
                this.pendingPosition,
                this.ownerPlayerId,
                relayGameTick
        );
        this.pendingPosition = null;
        this.lastPositionRelayGameTick = relayGameTick;
        return Optional.of(relayed);
    }

    Optional<RelayedPlayerSoulLowFrequencyPatchPayload> drainPatch(long relayGameTick) {
        if (relayGameTick < 0L) {
            throw new IllegalArgumentException("relayGameTick must be >= 0.");
        }
        if (this.pendingPatch == null) {
            return Optional.empty();
        }
        RelayedPlayerSoulLowFrequencyPatchPayload relayed = new RelayedPlayerSoulLowFrequencyPatchPayload(
                this.pendingPatch,
                this.ownerPlayerId,
                relayGameTick
        );
        this.pendingPatch = null;
        this.lastPatchRelayGameTick = relayGameTick;
        return Optional.of(relayed);
    }

    private void validateSource(NetworkObjectId payloadObjectId, UUID payloadOwnerId, long receiveGameTick) {
        if (receiveGameTick < 0L) {
            throw new IllegalArgumentException("receiveGameTick must be >= 0.");
        }
        if (!this.networkObjectId.equals(payloadObjectId)) {
            throw new IllegalArgumentException("payload networkObjectId does not match relay object.");
        }
        if (!this.ownerPlayerId.equals(payloadOwnerId)) {
            throw new IllegalArgumentException("payload ownerPlayerId does not match relay object.");
        }
    }
}
