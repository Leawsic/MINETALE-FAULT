package cn.jehorstudio.minetale.battle.network.server;

import cn.jehorstudio.minetale.battle.network.payload.BattleInitialAckPayload;
import cn.jehorstudio.minetale.battle.network.payload.BattleResultReportPayload;
import cn.jehorstudio.minetale.battle.network.payload.BattleStartAckPayload;
import cn.jehorstudio.minetale.battle.script.BattleDefinition;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class ServerBattleRegistry {
    private static final Map<UUID, BattleInitialHandshake> HANDSHAKES = new HashMap<>();
    private static final Map<UUID, ServerBattleSession> SESSIONS = new HashMap<>();

    private ServerBattleRegistry() {
    }

    public static ServerBattleSession openSession(UUID battleId, Collection<UUID> finalParticipantIds) {
        Objects.requireNonNull(battleId, "battleId");
        ServerBattleSession session = new ServerBattleSession(battleId, finalParticipantIds);
        ServerBattleSession existing = SESSIONS.putIfAbsent(battleId, session);
        if (existing == null) {
            return session;
        }
        if (!existing.finalParticipantIds().equals(session.finalParticipantIds())) {
            throw new IllegalStateException("battle session already exists with different final participants.");
        }
        return existing;
    }

    public static BattleInitialHandshake openHandshake(
            UUID battleId,
            BattleDefinition battleDefinition,
            long startBattleTick,
            long seed,
            Collection<UUID> invitedParticipantIds,
            UUID requiredParticipantId,
            long acknowledgementDeadlineTick
    ) {
        Objects.requireNonNull(battleId, "battleId");
        BattleInitialHandshake handshake = new BattleInitialHandshake(
                battleId,
                battleDefinition,
                startBattleTick,
                seed,
                invitedParticipantIds,
                requiredParticipantId,
                acknowledgementDeadlineTick
        );
        BattleInitialHandshake existing = HANDSHAKES.putIfAbsent(battleId, handshake);
        if (existing != null) {
            throw new IllegalStateException("battle handshake already exists.");
        }
        return handshake;
    }

    public static Optional<BattleInitialHandshake> handshake(UUID battleId) {
        Objects.requireNonNull(battleId, "battleId");
        return Optional.ofNullable(HANDSHAKES.get(battleId));
    }

    public static List<BattleInitialHandshake> handshakes() {
        return List.copyOf(HANDSHAKES.values());
    }

    public static boolean isPlayerBusy(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        boolean inHandshake = HANDSHAKES.values().stream()
                .anyMatch(handshake -> !handshake.finalized() && handshake.invitedParticipantIds().contains(playerId));
        return inHandshake || SESSIONS.values().stream()
                .anyMatch(session -> session.finalParticipantIds().contains(playerId));
    }

    public static void abortHandshake(UUID battleId) {
        Objects.requireNonNull(battleId, "battleId");
        BattleInitialHandshake handshake = HANDSHAKES.get(battleId);
        if (handshake != null && !handshake.finalized()) {
            HANDSHAKES.remove(battleId, handshake);
        }
    }

    public static Optional<BattleInitialHandshake> acceptInitialAck(BattleInitialAckPayload ack) {
        Objects.requireNonNull(ack, "ack");
        BattleInitialHandshake handshake = HANDSHAKES.get(ack.battleId());
        if (handshake == null) {
            return Optional.empty();
        }
        handshake.acceptAck(ack);
        return Optional.of(handshake);
    }

    public static Optional<ServerBattleSession> session(UUID battleId) {
        Objects.requireNonNull(battleId, "battleId");
        return Optional.ofNullable(SESSIONS.get(battleId));
    }

    public static Optional<BattleRelayState> relayState(UUID battleId) {
        return session(battleId).map(ServerBattleSession::relayState);
    }

    public static List<ServerBattleSession> sessions() {
        return List.copyOf(SESSIONS.values());
    }

    public static boolean acceptStartAck(BattleStartAckPayload ack) {
        Objects.requireNonNull(ack, "ack");
        return session(ack.battleId())
                .map(session -> session.acceptStartAck(ack))
                .orElse(false);
    }

    public static boolean acceptResultReport(BattleResultReportPayload report) {
        Objects.requireNonNull(report, "report");
        return session(report.battleId())
                .map(session -> session.acceptResultReport(report))
                .orElse(false);
    }

    public static void closeSession(UUID battleId) {
        Objects.requireNonNull(battleId, "battleId");
        HANDSHAKES.remove(battleId);
        ServerBattleSession session = SESSIONS.remove(battleId);
        if (session != null) {
            session.close();
        }
    }

    public static void clear() {
        HANDSHAKES.clear();
        for (ServerBattleSession session : SESSIONS.values()) {
            session.close();
        }
        SESSIONS.clear();
    }
}
