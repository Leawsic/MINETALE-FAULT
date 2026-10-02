package cn.jehorstudio.minetale.battle.network.server;

import cn.jehorstudio.minetale.battle.network.payload.BattleInitialAckPayload;
import cn.jehorstudio.minetale.battle.network.payload.BattleInitialPayload;
import cn.jehorstudio.minetale.battle.network.payload.BattleParticipant;
import cn.jehorstudio.minetale.battle.network.payload.BattleStartSharedSection;
import cn.jehorstudio.minetale.battle.network.payload.PlayerStateInitialSnapshot;
import cn.jehorstudio.minetale.battle.script.BattleDefinition;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class BattleInitialHandshake {
    private final UUID battleId;
    private final BattleDefinition battleDefinition;
    private final long startBattleTick;
    private final long seed;
    private final List<BattleParticipant> invitedParticipants;
    private final Set<UUID> invitedParticipantIds;
    private final UUID requiredParticipantId;
    private final long acknowledgementDeadlineTick;
    private final Set<UUID> acknowledgedParticipantIds = new LinkedHashSet<>();
    private BattleStartDispatch finalizedStart;

    public BattleInitialHandshake(
            UUID battleId,
            BattleDefinition battleDefinition,
            long startBattleTick,
            long seed,
            Collection<UUID> invitedParticipantIds,
            UUID requiredParticipantId,
            long acknowledgementDeadlineTick
    ) {
        this.battleId = Objects.requireNonNull(battleId, "battleId");
        this.battleDefinition = Objects.requireNonNull(battleDefinition, "battleDefinition");
        this.startBattleTick = startBattleTick;
        this.seed = seed;
        this.invitedParticipantIds = normalizeParticipantIds(invitedParticipantIds);
        this.requiredParticipantId = Objects.requireNonNull(requiredParticipantId, "requiredParticipantId");
        if (!this.invitedParticipantIds.contains(this.requiredParticipantId)) {
            throw new IllegalArgumentException("requiredParticipantId must be invited.");
        }
        this.acknowledgementDeadlineTick = acknowledgementDeadlineTick;
        this.invitedParticipants = this.invitedParticipantIds.stream()
                .map(BattleParticipant::new)
                .toList();
    }

    public UUID battleId() {
        return this.battleId;
    }

    public BattleDefinition battleDefinition() {
        return this.battleDefinition;
    }

    public BattleInitialPayload initialPayload() {
        return new BattleInitialPayload(this.battleId, this.invitedParticipants);
    }

    public boolean acceptAck(BattleInitialAckPayload ack) {
        Objects.requireNonNull(ack, "ack");
        if (this.finalizedStart != null) {
            return false;
        }
        if (!this.battleId.equals(ack.battleId())) {
            throw new IllegalArgumentException("ack battleId does not match handshake.");
        }
        if (!this.invitedParticipantIds.contains(ack.playerId())) {
            return false;
        }
        return this.acknowledgedParticipantIds.add(ack.playerId());
    }

    public Set<UUID> acknowledgedParticipantIds() {
        return Set.copyOf(this.acknowledgedParticipantIds);
    }

    public Set<UUID> invitedParticipantIds() {
        return this.invitedParticipantIds;
    }

    public UUID requiredParticipantId() {
        return this.requiredParticipantId;
    }

    public long acknowledgementDeadlineTick() {
        return this.acknowledgementDeadlineTick;
    }

    public boolean requiredParticipantAcknowledged() {
        return this.acknowledgedParticipantIds.contains(this.requiredParticipantId);
    }

    public boolean finalized() {
        return this.finalizedStart != null;
    }

    public boolean allInvitedParticipantsAcknowledged() {
        return this.acknowledgedParticipantIds.containsAll(this.invitedParticipantIds);
    }

    public BattleStartDispatch finalizeStart(
            Collection<UUID> finalParticipantIds,
            Map<UUID, PlayerStateInitialSnapshot> privatePlayerStates
    ) {
        if (this.finalizedStart != null) {
            return this.finalizedStart;
        }
        Set<UUID> finalIds = normalizeParticipantIds(finalParticipantIds);
        if (!this.acknowledgedParticipantIds.containsAll(finalIds)) {
            throw new IllegalArgumentException("final participants must have acknowledged the handshake.");
        }
        if (finalIds.isEmpty()) {
            throw new IllegalStateException("cannot start battle without acknowledged participants.");
        }
        if (!finalIds.contains(this.requiredParticipantId)) {
            throw new IllegalStateException("cannot start battle without the required participant.");
        }
        BattleStartSharedSection shared = new BattleStartSharedSection(
                this.battleId,
                this.battleDefinition.id(),
                this.battleDefinition.hash(),
                this.battleDefinition.schemaVersion(),
                this.startBattleTick,
                this.seed,
                finalIds.stream()
                        .map(BattleParticipant::new)
                        .toList()
        );
        this.finalizedStart = new BattleStartDispatch(shared, privatePlayerStates);
        ServerBattleRegistry.openSession(this.battleId, finalIds);
        return this.finalizedStart;
    }

    private static Set<UUID> normalizeParticipantIds(Collection<UUID> participantIds) {
        Objects.requireNonNull(participantIds, "participantIds");
        if (participantIds.isEmpty()) {
            throw new IllegalArgumentException("participantIds must not be empty.");
        }
        LinkedHashSet<UUID> normalized = new LinkedHashSet<>();
        for (UUID participantId : participantIds) {
            normalized.add(Objects.requireNonNull(participantId, "participantId"));
        }
        if (normalized.size() != participantIds.size()) {
            throw new IllegalArgumentException("participantIds must not contain duplicates.");
        }
        return Collections.unmodifiableSet(normalized);
    }
}
