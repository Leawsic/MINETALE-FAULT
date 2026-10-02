package cn.jehorstudio.minetale.battle.network.server;

import cn.jehorstudio.minetale.MineTale;
import cn.jehorstudio.minetale.battle.logic.actor.ActorRef;
import cn.jehorstudio.minetale.battle.logic.actor.ActorType;
import cn.jehorstudio.minetale.battle.logic.states.PlayerStateSnapshot;
import cn.jehorstudio.minetale.battle.network.payload.BattleInitialAckPayload;
import cn.jehorstudio.minetale.battle.network.payload.PlayerStateInitialSnapshot;
import cn.jehorstudio.minetale.battle.script.BattleDefinition;
import cn.jehorstudio.minetale.battle.script.BattleScriptCatalog;
import cn.jehorstudio.minetale.narrative.runtime.DialogueSessionManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

// 统一持有 Battle 启动握手、ACK 超时、参与者筛选与最终下发；调用方提供定义和候选玩家。
public final class BattleStartCoordinator {
    private static final long INITIAL_ACK_TIMEOUT_TICKS = 20L * 10L;

    private BattleStartCoordinator() {
    }

    public enum Status {
        STARTED,
        MISSING_DEFINITION,
        INITIATOR_BUSY,
        INITIATOR_NOT_CANDIDATE
    }

    public record StartResult(Status status, UUID battleId) {
        public StartResult {
            Objects.requireNonNull(status, "status");
        }

        public boolean started() {
            return this.status == Status.STARTED;
        }
    }

    public static StartResult start(
            ServerPlayer initiator,
            ResourceLocation battleDefinitionId,
            Collection<ServerPlayer> candidates
    ) {
        Objects.requireNonNull(initiator, "initiator");
        Objects.requireNonNull(battleDefinitionId, "battleDefinitionId");
        Objects.requireNonNull(candidates, "candidates");

        BattleDefinition definition = BattleScriptCatalog.get(battleDefinitionId).orElse(null);
        if (definition == null) {
            return new StartResult(Status.MISSING_DEFINITION, null);
        }
        if (ServerBattleRegistry.isPlayerBusy(initiator.getUUID())) {
            return new StartResult(Status.INITIATOR_BUSY, null);
        }

        Map<UUID, ServerPlayer> unique = new LinkedHashMap<>();
        for (ServerPlayer candidate : candidates) {
            if (candidate != null && candidate.connection != null) {
                unique.putIfAbsent(candidate.getUUID(), candidate);
            }
        }
        if (!unique.containsKey(initiator.getUUID())) {
            return new StartResult(Status.INITIATOR_NOT_CANDIDATE, null);
        }

        List<ServerPlayer> invited = new ArrayList<>();
        invited.add(initiator);
        for (ServerPlayer candidate : unique.values()) {
            if (!candidate.getUUID().equals(initiator.getUUID())
                    && !ServerBattleRegistry.isPlayerBusy(candidate.getUUID())) {
                invited.add(candidate);
            }
        }

        UUID battleId = UUID.randomUUID();
        long now = initiator.level().getServer().overworld().getGameTime();
        long seed = initiator.level().getRandom().nextLong();
        BattleInitialHandshake handshake = ServerBattleRegistry.openHandshake(
                battleId,
                definition,
                0L,
                seed,
                invited.stream().map(ServerPlayer::getUUID).toList(),
                initiator.getUUID(),
                now + INITIAL_ACK_TIMEOUT_TICKS
        );
        for (ServerPlayer player : invited) {
            PacketDistributor.sendToPlayer(player, handshake.initialPayload());
        }
        MineTale.LOGGER.info(
                "Started battle initial handshake {} with definition {} for {} participant candidate(s)",
                battleId,
                definition.id(),
                invited.size()
        );
        return new StartResult(Status.STARTED, battleId);
    }

    public static void acceptInitialAck(ServerPlayer player, BattleInitialAckPayload ack) {
        if (!player.getUUID().equals(ack.playerId())) {
            return;
        }
        ServerBattleRegistry.acceptInitialAck(ack).ifPresent(handshake -> {
            if (handshake.allInvitedParticipantsAcknowledged() && !handshake.finalized()) {
                finalizeStart(player.level().getServer(), handshake);
            }
        });
    }

    public static void tick(MinecraftServer server) {
        long now = server.overworld().getGameTime();
        for (BattleInitialHandshake handshake : ServerBattleRegistry.handshakes()) {
            if (handshake.finalized() || now < handshake.acknowledgementDeadlineTick()) {
                continue;
            }
            if (!handshake.requiredParticipantAcknowledged()) {
                MineTale.LOGGER.warn(
                        "Aborted battle handshake {} because required participant {} did not acknowledge",
                        handshake.battleId(),
                        handshake.requiredParticipantId()
                );
                ServerBattleRegistry.abortHandshake(handshake.battleId());
                continue;
            }
            finalizeStart(server, handshake);
        }
    }

    private static void finalizeStart(MinecraftServer server, BattleInitialHandshake handshake) {
        if (handshake.finalized() || !handshake.requiredParticipantAcknowledged()) {
            return;
        }
        List<UUID> finalParticipantIds = handshake.acknowledgedParticipantIds().stream()
                .filter(id -> server.getPlayerList().getPlayer(id) != null)
                .toList();
        if (!finalParticipantIds.contains(handshake.requiredParticipantId())) {
            ServerBattleRegistry.abortHandshake(handshake.battleId());
            return;
        }

        BattleStartDispatch dispatch = handshake.finalizeStart(
                finalParticipantIds,
                privatePlayerStates(finalParticipantIds)
        );
        for (UUID participantId : finalParticipantIds) {
            ServerPlayer recipient = server.getPlayerList().getPlayer(participantId);
            if (recipient != null) {
                DialogueSessionManager.interrupt(recipient, "battle_start");
                PacketDistributor.sendToPlayer(recipient, dispatch.payloadFor(participantId));
            }
        }
    }

    private static Map<UUID, PlayerStateInitialSnapshot> privatePlayerStates(Iterable<UUID> participantIds) {
        Map<UUID, PlayerStateInitialSnapshot> states = new HashMap<>();
        for (UUID participantId : participantIds) {
            PlayerStateSnapshot snapshot = new PlayerStateSnapshot(
                    participantId,
                    ActorRef.of(ActorType.PLAYER_SOUL, 0, 0),
                    20,
                    20,
                    1,
                    false,
                    1,
                    0L,
                    List.of(),
                    List.of()
            );
            states.put(participantId, new PlayerStateInitialSnapshot(participantId, snapshot));
        }
        return Map.copyOf(states);
    }
}
