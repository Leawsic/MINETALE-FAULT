package cn.jehorstudio.minetale.battle.logic.input;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

// 客户端每个 game tick 覆盖一次功能键快照。
public final class BattleInputState {
    private Set<String> heldKeys = Set.of();
    private Set<String> pressedKeys = Set.of();
    private long revision;

    public void updateHeldKeys(Collection<String> heldKeys) {
        updateHeldKeys(heldKeys, Set.of());
    }

    // 显式上升沿用于补足两次轮询之间发生的短按。
    public void updateHeldKeys(Collection<String> heldKeys, Collection<String> explicitlyPressedKeys) {
        Set<String> next = validatedKeys(heldKeys);
        LinkedHashSet<String> pressed = new LinkedHashSet<>(next);
        pressed.removeAll(this.heldKeys);
        pressed.addAll(validatedKeys(explicitlyPressedKeys));
        this.heldKeys = Set.copyOf(next);
        this.pressedKeys = Set.copyOf(pressed);
        this.revision++;
    }

    // 建立 held 基线，避免把界面打开前已按住的键误报为本 tick 上升沿。
    public void synchronizeHeldKeys(Collection<String> heldKeys) {
        this.heldKeys = validatedKeys(heldKeys);
        this.pressedKeys = Set.of();
        this.revision++;
    }

    public boolean isHeld(String key) {
        return this.heldKeys.contains(key);
    }

    public Set<String> pressedKeys() {
        return this.pressedKeys;
    }

    public long revision() {
        return this.revision;
    }

    public void clear() {
        updateHeldKeys(Set.of());
    }

    private static Set<String> validatedKeys(Collection<String> heldKeys) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String key : heldKeys) {
            result.add(BattleInputKey.require(key, "input key"));
        }
        return Set.copyOf(result);
    }
}
