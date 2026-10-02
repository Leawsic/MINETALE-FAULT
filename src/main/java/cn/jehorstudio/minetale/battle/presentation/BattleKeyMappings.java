package cn.jehorstudio.minetale.battle.presentation;

import cn.jehorstudio.minetale.MineTale;
import cn.jehorstudio.minetale.lib.client.input.MineTaleKeyCategories;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;

@EventBusSubscriber(modid = MineTale.MODID, value = Dist.CLIENT)
public final class BattleKeyMappings {
    public static final KeyMapping.Category BATTLE_CATEGORY = MineTaleKeyCategories.BATTLE;
    public static final KeyMapping.Category DEBUG_CATEGORY = MineTaleKeyCategories.DEBUG;

    public static final KeyMapping OPEN_BATTLE_SCREEN = key(
            "key.minetale.open_battle_screen", GLFW.GLFW_KEY_B, BATTLE_CATEGORY);
    public static final KeyMapping START_BATTLE_INSTANCE = key(
            "key.minetale.start_battle_instance", GLFW.GLFW_KEY_N, BATTLE_CATEGORY);
    public static final KeyMapping CONFIRM = mouse(
            "key.minetale.confirm", GLFW.GLFW_MOUSE_BUTTON_LEFT, BATTLE_CATEGORY);
    public static final KeyMapping CONFIRM_ALTERNATE = key(
            "key.minetale.confirm_alternate", GLFW.GLFW_KEY_SPACE, BATTLE_CATEGORY);
    public static final KeyMapping SKIP = mouse(
            "key.minetale.skip", GLFW.GLFW_MOUSE_BUTTON_RIGHT, BATTLE_CATEGORY);
    public static final KeyMapping SKIP_ALTERNATE = key(
            "key.minetale.skip_alternate", GLFW.GLFW_KEY_LEFT_SHIFT, BATTLE_CATEGORY);

    public static final KeyMapping SWITCH_PROJECTION = key(
            "key.minetale.battle_switch_to_perspective", GLFW.GLFW_KEY_P, DEBUG_CATEGORY);
    public static final KeyMapping TURN_LEFT = key(
            "key.minetale.battle_debug_turn_left", GLFW.GLFW_KEY_J, DEBUG_CATEGORY);
    public static final KeyMapping TURN_RIGHT = key(
            "key.minetale.battle_debug_turn_right", GLFW.GLFW_KEY_L, DEBUG_CATEGORY);
    public static final KeyMapping TURN_UP = key(
            "key.minetale.battle_debug_turn_up", GLFW.GLFW_KEY_K, DEBUG_CATEGORY);
    public static final KeyMapping TURN_DOWN = key(
            "key.minetale.battle_debug_turn_down", GLFW.GLFW_KEY_I, DEBUG_CATEGORY);
    public static final KeyMapping SWITCH_SCENE = key(
            "key.minetale.battle_debug_switch_scene", GLFW.GLFW_KEY_M, DEBUG_CATEGORY);
    public static final KeyMapping OPEN_VISUAL_EFFECTS_TUNING = key(
            "key.minetale.open_visual_effects_tuning", GLFW.GLFW_KEY_H, DEBUG_CATEGORY);

    @SubscribeEvent
    public static void register(RegisterKeyMappingsEvent event) {
        event.registerCategory(BATTLE_CATEGORY);
        event.registerCategory(DEBUG_CATEGORY);
        event.register(OPEN_BATTLE_SCREEN);
        event.register(START_BATTLE_INSTANCE);
        event.register(CONFIRM);
        event.register(CONFIRM_ALTERNATE);
        event.register(SKIP);
        event.register(SKIP_ALTERNATE);
        event.register(SWITCH_PROJECTION);
        event.register(TURN_LEFT);
        event.register(TURN_RIGHT);
        event.register(TURN_UP);
        event.register(TURN_DOWN);
        event.register(SWITCH_SCENE);
        event.register(OPEN_VISUAL_EFFECTS_TUNING);
    }

    private static KeyMapping key(String name, int keyCode, KeyMapping.Category category) {
        return new KeyMapping(name, InputConstants.Type.KEYSYM, keyCode, category);
    }

    private static KeyMapping mouse(String name, int button, KeyMapping.Category category) {
        return new KeyMapping(name, InputConstants.Type.MOUSE, button, category);
    }

    public static boolean matchesConfirm(KeyEvent event) {
        return CONFIRM.matches(event) || CONFIRM_ALTERNATE.matches(event);
    }

    public static boolean matchesConfirm(MouseButtonEvent event) {
        return CONFIRM.matchesMouse(event) || CONFIRM_ALTERNATE.matchesMouse(event);
    }

    public static boolean matchesSkip(KeyEvent event) {
        return SKIP.matches(event) || SKIP_ALTERNATE.matches(event);
    }

    public static boolean matchesSkip(MouseButtonEvent event) {
        return SKIP.matchesMouse(event) || SKIP_ALTERNATE.matchesMouse(event);
    }

    private BattleKeyMappings() {}
}
