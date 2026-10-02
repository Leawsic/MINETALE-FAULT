package cn.jehorstudio.minetale.battle.network.client;

import cn.jehorstudio.minetale.MineTale;
import cn.jehorstudio.minetale.battle.network.payload.BattleInitialAckPayload;
import cn.jehorstudio.minetale.battle.network.payload.BattleInitialPayload;
import cn.jehorstudio.minetale.battle.network.payload.BattleResultSummaryPayload;
import cn.jehorstudio.minetale.battle.network.payload.BattleStartAckPayload;
import cn.jehorstudio.minetale.battle.network.payload.BattleStartFailedPayload;
import cn.jehorstudio.minetale.battle.network.payload.BattleStartPayload;
import cn.jehorstudio.minetale.battle.network.payload.RelayedPlayerSoulLowFrequencyPatchPayload;
import cn.jehorstudio.minetale.battle.network.payload.RelayedPlayerSoulPositionSnapshotPayload;
import cn.jehorstudio.minetale.battle.presentation.BattlePresentation;
import cn.jehorstudio.minetale.battle.presentation.BattlePreparation;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

public final class BattleClientNetworkHandlers {
    private static final AtomicReference<BattleStartPayload> PENDING_START = new AtomicReference<>();

    private BattleClientNetworkHandlers() {
    }

    public static void register(RegisterClientPayloadHandlersEvent event) {
        event.register(BattleInitialPayload.TYPE, BattleClientNetworkHandlers::handleInitial);
        event.register(BattleStartPayload.TYPE, BattleClientNetworkHandlers::handleStart);
        event.register(RelayedPlayerSoulPositionSnapshotPayload.TYPE, BattleClientNetworkHandlers::handleRelayedPosition);
        event.register(RelayedPlayerSoulLowFrequencyPatchPayload.TYPE, BattleClientNetworkHandlers::handleRelayedPatch);
        event.register(BattleResultSummaryPayload.TYPE, BattleClientNetworkHandlers::handleResultSummary);
    }

    private static void handleInitial(BattleInitialPayload payload, IPayloadContext context) {
        UUID localPlayerId = localPlayerId();
        if (localPlayerId == null) {
            return;
        }
        boolean invited = payload.invitedParticipants().stream()
                .anyMatch(participant -> participant.playerId().equals(localPlayerId));
        if (invited) {
            BattlePreparation.INSTANCE.beginInitial(payload.battleId());
            ClientPacketDistributor.sendToServer(new BattleInitialAckPayload(payload.battleId(), localPlayerId));
        }
    }

    private static void handleStart(BattleStartPayload payload, IPayloadContext context) {
        PENDING_START.set(payload);
    }

    public static void startPendingBattleIfReady(Minecraft minecraft) {
        BattleStartPayload payload = PENDING_START.get();
        if (payload == null || minecraft.player == null || minecraft.level == null) {
            return;
        }
        if (!PENDING_START.compareAndSet(payload, null)) {
            return;
        }
        startBattle(minecraft, payload);
    }

    private static void startBattle(Minecraft minecraft, BattleStartPayload payload) {
        try {
            BattlePresentation.startFromBattleStart(minecraft, payload);
        } catch (RuntimeException ex) {
            MineTale.LOGGER.error("Could not start BattleScript battle {}", payload.battleId(), ex);
            ClientPacketDistributor.sendToServer(new BattleStartFailedPayload(
                    payload.battleId(),
                    minecraft.player.getUUID(),
                    ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()
            ));
            return;
        }
        ClientPacketDistributor.sendToServer(new BattleStartAckPayload(payload.battleId(), minecraft.player.getUUID()));
    }

    private static void handleRelayedPosition(RelayedPlayerSoulPositionSnapshotPayload payload, IPayloadContext context) {
        BattlePresentation active = BattlePresentation.active();
        if (active == null) {
            active = BattlePreparation.INSTANCE.presentation();
        }
        if (active != null) {
            active.applyRelayedPosition(payload);
        }
    }

    private static void handleRelayedPatch(RelayedPlayerSoulLowFrequencyPatchPayload payload, IPayloadContext context) {
        BattlePresentation active = BattlePresentation.active();
        if (active == null) {
            active = BattlePreparation.INSTANCE.presentation();
        }
        if (active != null) {
            active.applyRelayedLowFrequencyPatch(payload);
        }
    }

    private static void handleResultSummary(BattleResultSummaryPayload payload, IPayloadContext context) {
        MineTale.LOGGER.info("Battle result summary received: battle={}, result={}, accepted={}",
                payload.battleId(),
                payload.resultState(),
                payload.accepted());
    }

    private static UUID localPlayerId() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.player == null ? null : minecraft.player.getUUID();
    }
}
