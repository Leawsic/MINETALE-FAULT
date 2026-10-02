package cn.jehorstudio.minetale.battle.network;

import cn.jehorstudio.minetale.MineTale;
import cn.jehorstudio.minetale.battle.network.payload.BattleInitialAckPayload;
import cn.jehorstudio.minetale.battle.network.payload.BattleStartFailedPayload;
import cn.jehorstudio.minetale.battle.network.payload.BattleResultReportPayload;
import cn.jehorstudio.minetale.battle.network.payload.BattleResultState;
import cn.jehorstudio.minetale.battle.network.payload.BattleResultSummaryPayload;
import cn.jehorstudio.minetale.battle.network.payload.BattleStartAckPayload;
import cn.jehorstudio.minetale.battle.network.payload.DebugStartBattleRequestPayload;
import cn.jehorstudio.minetale.battle.network.payload.PlayerSoulLowFrequencyPatchPayload;
import cn.jehorstudio.minetale.battle.network.payload.PlayerSoulPositionSnapshotPayload;
import cn.jehorstudio.minetale.battle.network.payload.RelayedPlayerSoulLowFrequencyPatchPayload;
import cn.jehorstudio.minetale.battle.network.payload.RelayedPlayerSoulPositionSnapshotPayload;
import cn.jehorstudio.minetale.battle.network.server.BattleStartCoordinator;
import cn.jehorstudio.minetale.battle.network.server.ServerBattleRegistry;
import cn.jehorstudio.minetale.battle.network.server.ServerBattleSession;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.List;
import java.util.UUID;

public final class BattleNetworking {
    private BattleNetworking() {
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("battle_network_2");
        registrar.playToClient(cn.jehorstudio.minetale.battle.network.payload.BattleInitialPayload.TYPE,
                cn.jehorstudio.minetale.battle.network.payload.BattleInitialPayload.STREAM_CODEC);
        registrar.playToClient(cn.jehorstudio.minetale.battle.network.payload.BattleStartPayload.TYPE,
                cn.jehorstudio.minetale.battle.network.payload.BattleStartPayload.STREAM_CODEC);
        registrar.playToClient(RelayedPlayerSoulPositionSnapshotPayload.TYPE,
                RelayedPlayerSoulPositionSnapshotPayload.STREAM_CODEC);
        registrar.playToClient(RelayedPlayerSoulLowFrequencyPatchPayload.TYPE,
                RelayedPlayerSoulLowFrequencyPatchPayload.STREAM_CODEC);
        registrar.playToClient(BattleResultSummaryPayload.TYPE,
                BattleResultSummaryPayload.STREAM_CODEC);

        registrar.playToServer(DebugStartBattleRequestPayload.TYPE,
                DebugStartBattleRequestPayload.STREAM_CODEC,
                BattleNetworking::handleDebugStartBattleRequest);
        registrar.playToServer(BattleInitialAckPayload.TYPE,
                BattleInitialAckPayload.STREAM_CODEC,
                BattleNetworking::handleBattleInitialAck);
        registrar.playToServer(BattleStartAckPayload.TYPE,
                BattleStartAckPayload.STREAM_CODEC,
                BattleNetworking::handleBattleStartAck);
        registrar.playToServer(BattleStartFailedPayload.TYPE,
                BattleStartFailedPayload.STREAM_CODEC,
                BattleNetworking::handleBattleStartFailed);
        registrar.playToServer(PlayerSoulPositionSnapshotPayload.TYPE,
                PlayerSoulPositionSnapshotPayload.STREAM_CODEC,
                BattleNetworking::handlePlayerSoulPosition);
        registrar.playToServer(PlayerSoulLowFrequencyPatchPayload.TYPE,
                PlayerSoulLowFrequencyPatchPayload.STREAM_CODEC,
                BattleNetworking::handlePlayerSoulPatch);
        registrar.playToServer(BattleResultReportPayload.TYPE,
                BattleResultReportPayload.STREAM_CODEC,
                BattleNetworking::handleBattleResultReport);
    }

    private static void handleDebugStartBattleRequest(DebugStartBattleRequestPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer source)) {
            return;
        }
        MinecraftServer server = source.level().getServer();
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        BattleStartCoordinator.StartResult result = BattleStartCoordinator.start(
                source,
                payload.battleDefinitionId(),
                players
        );
        if (!result.started()) {
            MineTale.LOGGER.warn(
                    "Cannot start debug battle requested by {}: definition={}, status={}",
                    source.getGameProfile().name(),
                    payload.battleDefinitionId(),
                    result.status()
            );
        }
    }

    private static void handleBattleInitialAck(BattleInitialAckPayload ack, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player) || !player.getUUID().equals(ack.playerId())) {
            return;
        }
        BattleStartCoordinator.acceptInitialAck(player, ack);
    }

    private static void handleBattleStartAck(BattleStartAckPayload ack, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player && player.getUUID().equals(ack.playerId())) {
            ServerBattleRegistry.acceptStartAck(ack);
        }
    }

    private static void handleBattleStartFailed(BattleStartFailedPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player && player.getUUID().equals(payload.playerId())) {
            MineTale.LOGGER.warn(
                    "Client failed to start battle. battleId={}, playerId={}, reason={}",
                    payload.battleId(),
                    payload.playerId(),
                    payload.reason()
            );
        }
    }

    private static void handlePlayerSoulPosition(PlayerSoulPositionSnapshotPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player && player.getUUID().equals(payload.ownerPlayerId())) {
            ServerBattleRegistry.relayState(payload.battleId())
                    .ifPresent(relay -> relay.acceptPosition(payload, player.level().getGameTime()));
        }
    }

    private static void handlePlayerSoulPatch(PlayerSoulLowFrequencyPatchPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player && player.getUUID().equals(payload.ownerPlayerId())) {
            ServerBattleRegistry.relayState(payload.battleId())
                    .ifPresent(relay -> relay.acceptPatch(payload, player.level().getGameTime()));
        }
    }

    private static void handleBattleResultReport(BattleResultReportPayload report, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player) || !player.getUUID().equals(report.playerId())) {
            return;
        }
        ServerBattleRegistry.session(report.battleId()).ifPresent(session -> {
            if (!session.acceptResultReport(report)) {
                return;
            }
            PacketDistributor.sendToPlayer(player, new BattleResultSummaryPayload(report.battleId(), report.resultState(), true));
            if (session.allParticipantsReportedResult()) {
                broadcastResultAndClose(player.level().getServer(), session, session.aggregateResultState());
            }
        });
    }

    private static void broadcastResultAndClose(MinecraftServer server, ServerBattleSession session, BattleResultState resultState) {
        for (UUID participantId : session.finalParticipantIds()) {
            ServerPlayer recipient = server.getPlayerList().getPlayer(participantId);
            if (recipient != null) {
                PacketDistributor.sendToPlayer(recipient, new BattleResultSummaryPayload(session.battleId(), resultState, true));
            }
        }
        ServerBattleRegistry.closeSession(session.battleId());
    }

    @EventBusSubscriber(modid = MineTale.MODID)
    public static final class GameBusEvents {
        private GameBusEvents() {
        }

        @SubscribeEvent
        public static void onServerTick(ServerTickEvent.Post event) {
            BattleStartCoordinator.tick(event.getServer());
            for (ServerBattleSession session : ServerBattleRegistry.sessions()) {
                MinecraftServer server = event.getServer();
                long relayGameTick = server.overworld().getGameTime();
                for (RelayedPlayerSoulPositionSnapshotPayload payload : session.relayState().drainPositionRelays(relayGameTick)) {
                    sendRelayedPosition(server, session, payload);
                }
                for (RelayedPlayerSoulLowFrequencyPatchPayload payload : session.relayState().drainPatchRelays(relayGameTick)) {
                    sendRelayedPatch(server, session, payload);
                }
            }
        }

        private static void sendRelayedPosition(
                MinecraftServer server,
                ServerBattleSession session,
                RelayedPlayerSoulPositionSnapshotPayload payload
        ) {
            for (UUID participantId : session.finalParticipantIds()) {
                if (!payload.shouldSendTo(participantId)) {
                    continue;
                }
                ServerPlayer recipient = server.getPlayerList().getPlayer(participantId);
                if (recipient != null) {
                    PacketDistributor.sendToPlayer(recipient, payload);
                }
            }
        }

        private static void sendRelayedPatch(
                MinecraftServer server,
                ServerBattleSession session,
                RelayedPlayerSoulLowFrequencyPatchPayload payload
        ) {
            for (UUID participantId : session.finalParticipantIds()) {
                if (!payload.shouldSendTo(participantId)) {
                    continue;
                }
                ServerPlayer recipient = server.getPlayerList().getPlayer(participantId);
                if (recipient != null) {
                    PacketDistributor.sendToPlayer(recipient, payload);
                }
            }
        }
    }
}
