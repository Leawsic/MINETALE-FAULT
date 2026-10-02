package cn.jehorstudio.minetale.battle.network.server;

import cn.jehorstudio.minetale.battle.logic.actor.ActorType;
import cn.jehorstudio.minetale.battle.network.NetworkObjectId;
import cn.jehorstudio.minetale.battle.network.payload.BattleResultReportPayload;
import cn.jehorstudio.minetale.battle.network.payload.BattleResultState;
import cn.jehorstudio.minetale.battle.network.payload.BattleStartAckPayload;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class ServerBattleSession {
    private final UUID battleId;
    private final Set<UUID> finalParticipantIds;
    private final Set<UUID> startAcknowledgedParticipantIds = new LinkedHashSet<>();
    private final Map<UUID, BattleResultState> resultReports = new HashMap<>();
    private final BattleRelayState relayState;

    ServerBattleSession(UUID battleId, Collection<UUID> finalParticipantIds) {
        this.battleId = Objects.requireNonNull(battleId, "battleId");
        this.finalParticipantIds = normalizeParticipantIds(finalParticipantIds);
        this.relayState = new BattleRelayState(this.battleId);
        registerPlayerSoulRelays();
    }

    public UUID battleId() {
        return this.battleId;
    }

    public Set<UUID> finalParticipantIds() {
        return this.finalParticipantIds;
    }

    public boolean isFinalParticipant(UUID playerId) {
        return this.finalParticipantIds.contains(Objects.requireNonNull(playerId, "playerId"));
    }

    public boolean acceptStartAck(BattleStartAckPayload ack) {
        Objects.requireNonNull(ack, "ack");
        if (!this.battleId.equals(ack.battleId())) {
            throw new IllegalArgumentException("ack battleId does not match battle session.");
        }
        if (!isFinalParticipant(ack.playerId())) {
            return false;
        }
        return this.startAcknowledgedParticipantIds.add(ack.playerId());
    }

    public Set<UUID> startAcknowledgedParticipantIds() {
        return Set.copyOf(this.startAcknowledgedParticipantIds);
    }

    public boolean acceptResultReport(BattleResultReportPayload report) {
        Objects.requireNonNull(report, "report");
        if (!this.battleId.equals(report.battleId())) {
            throw new IllegalArgumentException("result report battleId does not match battle session.");
        }
        if (!isFinalParticipant(report.playerId())) {
            return false;
        }
        this.resultReports.putIfAbsent(report.playerId(), report.resultState());
        return true;
    }

    public Map<UUID, BattleResultState> resultReports() {
        return Map.copyOf(this.resultReports);
    }

    public boolean allParticipantsReportedResult() {
        return this.resultReports.keySet().containsAll(this.finalParticipantIds);
    }

    public BattleResultState aggregateResultState() {
        if (this.resultReports.containsValue(BattleResultState.DEFEAT)) {
            return BattleResultState.DEFEAT;
        }
        if (this.resultReports.containsValue(BattleResultState.ESCAPED)) {
            return BattleResultState.ESCAPED;
        }
        return BattleResultState.VICTORY;
    }

    public BattleRelayState relayState() {
        return this.relayState;
    }

    void close() {
        this.relayState.clear();
    }

    private void registerPlayerSoulRelays() {
        for (UUID participantId : this.finalParticipantIds) {
            this.relayState.registerObject(
                    NetworkObjectId.playerSoul(this.battleId, participantId),
                    participantId,
                    ActorType.PLAYER_SOUL
            );
        }
    }

    private static Set<UUID> normalizeParticipantIds(Collection<UUID> finalParticipantIds) {
        Objects.requireNonNull(finalParticipantIds, "finalParticipantIds");
        if (finalParticipantIds.isEmpty()) {
            throw new IllegalArgumentException("finalParticipantIds must not be empty.");
        }
        LinkedHashSet<UUID> participantIds = new LinkedHashSet<>();
        for (UUID participantId : finalParticipantIds) {
            participantIds.add(Objects.requireNonNull(participantId, "participantId"));
        }
        if (participantIds.size() != finalParticipantIds.size()) {
            throw new IllegalArgumentException("finalParticipantIds must not contain duplicates.");
        }
        return Collections.unmodifiableSet(participantIds);
    }
}
