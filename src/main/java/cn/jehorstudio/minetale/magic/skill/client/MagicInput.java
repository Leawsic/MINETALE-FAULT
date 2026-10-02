package cn.jehorstudio.minetale.magic.skill.client;

import cn.jehorstudio.minetale.magic.Magic;
import cn.jehorstudio.minetale.magic.skill.MagicNetworking;
import cn.jehorstudio.minetale.magic.skill.MagicTargeting;
import cn.jehorstudio.minetale.magic.visual.particle.TargetLockParticles;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;

public final class MagicInput {
    private static final KeyMapping.Category CATEGORY = new KeyMapping.Category(Magic.id("magic"));
    private static final KeyMapping CAST = new KeyMapping("key.minetale.cast", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_C, CATEGORY);
    private static final KeyMapping LOCK = new KeyMapping("key.minetale.lock_target", InputConstants.Type.MOUSE, GLFW.GLFW_MOUSE_BUTTON_MIDDLE, CATEGORY);
    private static MagicNetworking.Status status;
    private static long sequence;
    private static long activeCastId = -1;
    private static ClientLevel inputLevel;

    private MagicInput() {}

    public static void register(IEventBus bus) {
        bus.addListener(MagicInput::keys);
        bus.addListener(MagicInput::payloads);
        NeoForge.EVENT_BUS.addListener(MagicInput::tick);
        NeoForge.EVENT_BUS.addListener(MagicInput::mouse);
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> reset());
    }

    private static void keys(RegisterKeyMappingsEvent event) {
        event.registerCategory(CATEGORY);
        event.register(CAST);
        event.register(LOCK);
    }

    private static void payloads(RegisterClientPayloadHandlersEvent event) {
        event.register(MagicNetworking.Status.TYPE, (payload, context) -> status = payload);
    }

    private static void tick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (inputLevel != mc.level) {
            if (inputLevel != null) status = null;
            activeCastId = -1;
            inputLevel = mc.level;
        }
        if (!acceptsInput(mc)) {
            if (activeCastId >= 0 && mc.player != null && mc.level != null) send(MagicNetworking.CANCEL, activeCastId);
            activeCastId = -1;
            while (CAST.consumeClick()) {}
            while (LOCK.consumeClick()) {}
        } else {
            while (LOCK.consumeClick()) lockIfPossible(mc);
            boolean clicked = false;
            while (CAST.consumeClick()) clicked = true;
            if (activeCastId < 0 && clicked) {
                activeCastId = sequence;
                send(MagicNetworking.BEGIN, activeCastId);
            }
            // consumeClick 捕获两个 Tick 之间完成的短按，因此短按也发送一对有序意图。
            if (activeCastId >= 0 && !CAST.isDown()) {
                send(MagicNetworking.RELEASE, activeCastId);
                activeCastId = -1;
            }
        }
        TargetLockParticles.track(lockedTarget());
    }

    private static void mouse(InputEvent.MouseButton.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (event.getAction() == GLFW.GLFW_PRESS && LOCK.getKey().getType() == InputConstants.Type.MOUSE
                && LOCK.getKey().getValue() == event.getButton() && acceptsInput(mc) && lockIfPossible(mc)) {
            // 仅锁定动作实际消费这次点击时拦截原版 pick block
            event.setCanceled(true);
        }
    }

    private static boolean lockIfPossible(Minecraft mc) {
        double range = status == null ? 64 : status.lockRange();
        if ((status == null || status.lockedId() < 0) && MagicTargeting.pointedEntity(mc.player, range) == null) return false;
        send(MagicNetworking.LOCK, -1);
        return true;
    }

    private static void send(int action, long castId) {
        Minecraft mc = Minecraft.getInstance();
        ClientPacketDistributor.sendToServer(new MagicNetworking.Input(action, sequence++, castId, mc.level.dimension().location()));
    }

    private static boolean acceptsInput(Minecraft mc) {
        return mc.player != null && mc.level != null && mc.player.isAlive() && !mc.player.isSpectator()
                && mc.screen == null && mc.isWindowActive();
    }

    public static Entity lockedTarget() {
        Minecraft mc = Minecraft.getInstance();
        if (status == null || status.lockedId() < 0 || mc.level == null || mc.player == null) return null;
        Entity target = mc.level.getEntity(status.lockedId());
        return target != null && target.isAlive() ? target : null;
    }

    private static void reset() {
        status = null;
        TargetLockParticles.track(null);
        sequence = 0;
        activeCastId = -1;
        inputLevel = null;
    }
}
