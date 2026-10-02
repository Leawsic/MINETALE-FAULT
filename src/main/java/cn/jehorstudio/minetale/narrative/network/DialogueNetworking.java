package cn.jehorstudio.minetale.narrative.network;

import cn.jehorstudio.minetale.narrative.network.payload.DialogueAdvancePayload;
import cn.jehorstudio.minetale.narrative.network.payload.DialogueClosePayload;
import cn.jehorstudio.minetale.narrative.network.payload.DialogueChoicePayload;
import cn.jehorstudio.minetale.narrative.network.payload.DialogueChoiceSelectPayload;
import cn.jehorstudio.minetale.narrative.network.payload.DialoguePagePayload;
import cn.jehorstudio.minetale.narrative.runtime.DialogueSessionManager;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public final class DialogueNetworking {
    private DialogueNetworking() {
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("dialogue_2");
        registrar.playToClient(DialoguePagePayload.TYPE, DialoguePagePayload.STREAM_CODEC);
        registrar.playToClient(DialogueChoicePayload.TYPE, DialogueChoicePayload.STREAM_CODEC);
        registrar.playToClient(DialogueClosePayload.TYPE, DialogueClosePayload.STREAM_CODEC);
        registrar.playToServer(
                DialogueAdvancePayload.TYPE,
                DialogueAdvancePayload.STREAM_CODEC,
                DialogueNetworking::handleAdvance
        );
        registrar.playToServer(
                DialogueChoiceSelectPayload.TYPE,
                DialogueChoiceSelectPayload.STREAM_CODEC,
                DialogueNetworking::handleChoice
        );
    }

    private static void handleAdvance(DialogueAdvancePayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            DialogueSessionManager.advance(player, payload.sessionId());
        }
    }

    private static void handleChoice(DialogueChoiceSelectPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            DialogueSessionManager.selectChoice(
                    player,
                    payload.sessionId(),
                    payload.choiceId(),
                    payload.optionId()
            );
        }
    }
}
