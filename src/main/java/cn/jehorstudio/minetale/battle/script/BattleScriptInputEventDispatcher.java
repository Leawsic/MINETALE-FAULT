package cn.jehorstudio.minetale.battle.script;

import cn.jehorstudio.minetale.battle.logic.action.ActionStack;
import cn.jehorstudio.minetale.battle.logic.event.contexts.NoContext;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// 同一功能键的上一组 Action 未结束时，不得重入对应舞台事件。
final class BattleScriptInputEventDispatcher {
    private final BattleScriptRuntime runtime;
    private final BattleScriptActionFactory actions;
    private final Map<String, Long> pendingStacks = new LinkedHashMap<>();

    BattleScriptInputEventDispatcher(BattleScriptRuntime runtime, BattleScriptActionFactory actions) {
        this.runtime = runtime;
        this.actions = actions;
    }

    void dispatch(String key) {
        List<JsonObject> eventActions = actionsFor(key);
        if (eventActions.isEmpty()) {
            return;
        }
        Long existing = this.pendingStacks.get(key);
        if (existing != null && this.runtime.instance().actionStacks().hasStack(existing)) {
            return;
        }
        ActionStack stack = this.runtime.instance().actionStacks().createStack(0, NoContext.INSTANCE);
        for (JsonObject action : eventActions) {
            stack.push(this.actions.create(action));
        }
        this.pendingStacks.put(key, stack.stackId());
    }

    void cleanup() {
        this.pendingStacks.entrySet().removeIf(entry -> !this.runtime.instance().actionStacks().hasStack(entry.getValue()));
    }

    private List<JsonObject> actionsFor(String key) {
        JsonObject root = this.runtime.definition().root();
        JsonObject events = BattleScriptJson.optionalObject(root, "events").orElseGet(JsonObject::new);
        JsonObject byKey = BattleScriptJson.optionalObject(events, "onKeyPressed").orElseGet(JsonObject::new);
        JsonElement actions = byKey.get(key);
        if (actions == null || !actions.isJsonArray()) {
            return List.of();
        }
        return BattleScriptJson.objectList(actions.getAsJsonArray(), "events.onKeyPressed." + key);
    }
}
