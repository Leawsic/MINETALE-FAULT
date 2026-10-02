package cn.jehorstudio.minetale.narrative.client;

import cn.jehorstudio.minetale.narrative.network.payload.DialogueClosePayload;
import cn.jehorstudio.minetale.narrative.network.payload.DialogueChoicePayload;
import cn.jehorstudio.minetale.narrative.network.payload.DialoguePagePayload;
import net.minecraft.client.Minecraft;

// 客户端唯一拥有活动对话 Screen，并将服务端页面流投影到该界面。
public final class DialogueClientController {
    private DialogueClientController() {
    }

    public static void show(DialoguePagePayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof DialogueScreen screen
                && screen.sessionId().equals(payload.sessionId())) {
            screen.showPage(payload);
            return;
        }
        minecraft.setScreen(new DialogueScreen(payload));
    }

    public static void show(DialogueChoicePayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof DialogueScreen screen
                && screen.sessionId().equals(payload.sessionId())) {
            screen.showChoice(payload);
            return;
        }
        minecraft.setScreen(new DialogueScreen(payload));
    }

    public static void close(DialogueClosePayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof DialogueScreen screen
                && screen.sessionId().equals(payload.sessionId())) {
            minecraft.setScreen(null);
        }
    }
}
