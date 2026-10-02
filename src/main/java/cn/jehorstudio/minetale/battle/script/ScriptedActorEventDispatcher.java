package cn.jehorstudio.minetale.battle.script;

import cn.jehorstudio.minetale.battle.logic.action.ActionStack;
import cn.jehorstudio.minetale.battle.logic.actor.Actor;
import cn.jehorstudio.minetale.battle.logic.actor.ActorRef;
import cn.jehorstudio.minetale.battle.logic.event.contexts.ActorContext;
import cn.jehorstudio.minetale.battle.logic.event.contexts.ActorTargetContext;
import cn.jehorstudio.minetale.battle.logic.event.contexts.BattleEventContext;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class ScriptedActorEventDispatcher {
    private final BattleScriptRuntime runtime;
    private final BattleScriptActionFactory actions;
    private final Map<EventKey, Long> pendingStacks = new LinkedHashMap<>();

    ScriptedActorEventDispatcher(BattleScriptRuntime runtime, BattleScriptActionFactory actions) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.actions = Objects.requireNonNull(actions, "actions");
    }

    void dispatch(Actor actor, ScriptedActorEventType eventType) {
        dispatch(actor, eventType, new ActorContext(actor.ref()), null);
    }

    void dispatch(Actor actor, ScriptedActorEventType eventType, Actor target) {
        dispatch(actor, eventType, new ActorTargetContext(actor.ref(), target.ref()), null);
    }

    void dispatchSignal(Actor actor, String signal) {
        dispatch(actor, ScriptedActorEventType.ON_SIGNAL, new ActorContext(actor.ref()), signal);
    }

    void dispatchKeyPressed(Actor actor, String key) {
        dispatch(actor, ScriptedActorEventType.ON_KEY_PRESSED, new ActorContext(actor.ref()), key);
    }

    private void dispatch(Actor actor, ScriptedActorEventType eventType, BattleEventContext context, String signal) {
        List<JsonObject> eventActions = eventActions(actor, eventType, signal);
        if (eventActions.isEmpty()) {
            return;
        }

        EventKey key = new EventKey(actor.ref(), eventType, signal == null ? "" : signal);
        Long existingStack = this.pendingStacks.get(key);
        if (existingStack != null && this.runtime.instance().actionStacks().hasStack(existingStack)) {
            return;
        }
        this.pendingStacks.remove(key);

        ActionStack stack = this.runtime.instance().actionStacks().createStack(0, context);
        for (JsonObject action : eventActions) {
            stack.push(this.actions.create(action));
        }
        this.pendingStacks.put(key, stack.stackId());
    }

    void cleanupPendingStacks() {
        this.pendingStacks.entrySet().removeIf(entry -> !this.runtime.instance().actionStacks().hasStack(entry.getValue()));
    }

    private List<JsonObject> eventActions(Actor actor, ScriptedActorEventType eventType, String signal) {
        if (actor.scripted() == null) {
            return List.of();
        }
        JsonObject template = this.runtime.definition().compiled()
                .resolveActorPrefab(actor.scripted().templateRef().value())
                .map(ActorPrefabDefinition::root)
                .orElse(null);
        if (template == null) {
            return List.of();
        }
        JsonObject events = BattleScriptJson.optionalObject(template, "events").orElseGet(JsonObject::new);
        JsonElement eventElement = events.get(eventType.jsonKey());
        if (eventElement == null) {
            return List.of();
        }
        if ((eventType == ScriptedActorEventType.ON_SIGNAL || eventType == ScriptedActorEventType.ON_KEY_PRESSED)
                && eventElement.isJsonObject() && signal != null) {
            JsonObject bySignal = eventElement.getAsJsonObject();
            JsonElement signalActions = bySignal.has(signal) ? bySignal.get(signal) : bySignal.get("*");
            if (signalActions == null) {
                return List.of();
            }
            return BattleScriptJson.objectList(BattleScriptJson.requireArray(wrap(signalActions), "actions", eventType.jsonKey()), eventType.jsonKey());
        }
        if (!eventElement.isJsonArray()) {
            return List.of();
        }
        return BattleScriptJson.objectList(eventElement.getAsJsonArray(), eventType.jsonKey());
    }

    private static JsonObject wrap(JsonElement actions) {
        JsonObject wrapper = new JsonObject();
        if (actions != null) {
            wrapper.add("actions", actions.deepCopy());
        } else {
            wrapper.add("actions", new JsonArray());
        }
        return wrapper;
    }

    private record EventKey(ActorRef actor, ScriptedActorEventType eventType, String signal) {
    }
}
