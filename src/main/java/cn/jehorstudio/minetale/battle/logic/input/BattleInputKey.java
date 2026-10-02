package cn.jehorstudio.minetale.battle.logic.input;

import java.util.LinkedHashSet;
import java.util.Set;

// BattleScript 只观察 KeyMapping ID。
public final class BattleInputKey {
    public static final String MOVE_FORWARD = "key.forward";
    public static final String MOVE_LEFT = "key.left";
    public static final String MOVE_BACK = "key.back";
    public static final String MOVE_RIGHT = "key.right";
    public static final String JUMP = "key.jump";
    public static final String SNEAK = "key.sneak";
    public static final String CONFIRM = "key.minetale.confirm";
    public static final String SKIP = "key.minetale.skip";

    private static final Set<String> IDS = ids();

    private BattleInputKey() {
    }

    public static Set<String> ids() {
        LinkedHashSet<String> ids = new LinkedHashSet<>(Set.of(
                "key.forward", "key.left", "key.back", "key.right",
                "key.jump", "key.sneak", "key.sprint", "key.inventory",
                "key.swapOffhand", "key.drop", "key.use", "key.attack", "key.pickItem",
                "key.chat", "key.playerlist", "key.command", "key.socialInteractions",
                "key.screenshot", "key.togglePerspective", "key.smoothCamera", "key.fullscreen",
                "key.advancements", "key.quickActions", "key.saveToolbarActivator",
                "key.loadToolbarActivator", "key.spectatorOutlines", "key.spectatorHotbar",
                "key.minetale.open_battle_screen", "key.minetale.start_battle_instance",
                "key.minetale.confirm", "key.minetale.skip",
                "key.minetale.battle_switch_to_perspective",
                "key.minetale.battle_debug_turn_left", "key.minetale.battle_debug_turn_right",
                "key.minetale.battle_debug_turn_up", "key.minetale.battle_debug_turn_down",
                "key.minetale.battle_debug_switch_scene"
        ));
        for (int slot = 1; slot <= 9; slot++) {
            ids.add("key.hotbar." + slot);
        }
        return Set.copyOf(ids);
    }

    public static boolean contains(String id) {
        return IDS.contains(id);
    }

    public static String require(String id, String path) {
        if (!contains(id)) {
            throw new IllegalArgumentException(path + " is not an exposed Minecraft/MineTale key mapping: " + id + ".");
        }
        return id;
    }
}
