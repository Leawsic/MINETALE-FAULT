package cn.jehorstudio.minetale.narrative.client;

import cn.jehorstudio.minetale.narrative.network.payload.DialogueClosePayload;
import cn.jehorstudio.minetale.narrative.network.payload.DialogueChoicePayload;
import cn.jehorstudio.minetale.narrative.network.payload.DialoguePagePayload;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public final class DialogueClientNetworkHandlers {
    private DialogueClientNetworkHandlers() {
    }

    public static void register(RegisterClientPayloadHandlersEvent event) {
        event.register(DialoguePagePayload.TYPE, DialogueClientNetworkHandlers::handlePage);
        event.register(DialogueChoicePayload.TYPE, DialogueClientNetworkHandlers::handleChoice);
        event.register(DialogueClosePayload.TYPE, DialogueClientNetworkHandlers::handleClose);
    }

    private static void handlePage(DialoguePagePayload payload, IPayloadContext context) {
        Minecraft.getInstance().execute(() -> DialogueClientController.show(payload));
    }

    private static void handleChoice(DialogueChoicePayload payload, IPayloadContext context) {
        Minecraft.getInstance().execute(() -> DialogueClientController.show(payload));
    }

    private static void handleClose(DialogueClosePayload payload, IPayloadContext context) {
        Minecraft.getInstance().execute(() -> DialogueClientController.close(payload));
    }
}
