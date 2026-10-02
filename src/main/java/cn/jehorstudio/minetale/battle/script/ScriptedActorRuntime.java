package cn.jehorstudio.minetale.battle.script;

import cn.jehorstudio.minetale.battle.logic.actor.Actor;
import cn.jehorstudio.minetale.battle.logic.actor.BattleArenaBounds;
import cn.jehorstudio.minetale.battle.logic.actor.ActorCollision;
import cn.jehorstudio.minetale.battle.logic.actor.ActorLifecycle;
import cn.jehorstudio.minetale.battle.logic.actor.ActorType;
import cn.jehorstudio.minetale.battle.logic.actor.component.ActorComponentContext;
import cn.jehorstudio.minetale.battle.logic.actor.component.ActorComponentRegistry;
import cn.jehorstudio.minetale.battle.logic.actor.component.ActorComponentRuntime;
import cn.jehorstudio.minetale.battle.logic.rules.RuleResolvedView;
import cn.jehorstudio.minetale.battle.logic.event.LogicEventListener;
import cn.jehorstudio.minetale.battle.logic.event.LogicEvents;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

final class ScriptedActorRuntime {
    private final BattleScriptRuntime runtime;
    private final ScriptedActorEventDispatcher events;
    private final Set<cn.jehorstudio.minetale.battle.logic.actor.ActorRef> destroyDispatched = new HashSet<>();

    ScriptedActorRuntime(BattleScriptRuntime runtime, BattleScriptActionFactory actions) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.events = new ScriptedActorEventDispatcher(runtime, actions);
    }

    ScriptedActorEventDispatcher events() {
        return this.events;
    }

    @LogicEventListener(value = LogicEvents.ON_STEP, priority = 210)
    public void tickScriptedActors() {
        double seconds = this.runtime.instance().timeline().secondsPerBattleStep();
        for (Actor actor : this.runtime.instance().stateCache().actors().activeActors(ActorType.SCRIPTED_ACTOR)) {
            if (actor.scripted() == null) {
                continue;
            }
            actor.scripted().advanceAge(1L, seconds);
            runComponents(actor, ComponentCall.ON_TICK, null);
            this.events.dispatch(actor, ScriptedActorEventType.ON_TICK);
        }
        this.events.cleanupPendingStacks();
    }

    @LogicEventListener(value = LogicEvents.ON_STEP, priority = 100)
    public void dispatchBattleBoxCollisions() {
        for (Actor actor : this.runtime.instance().stateCache().actors().activeActors(ActorType.SCRIPTED_ACTOR)) {
            if (BattleArenaBounds.resolve(this.runtime.instance().stateCache().actors()).touchesBoundary(actor)) {
                runComponents(actor, ComponentCall.ON_COLLIDE_BATTLE_BOX, null);
                this.events.dispatch(actor, ScriptedActorEventType.ON_COLLIDE_BATTLE_BOX);
            }
        }
    }

    @LogicEventListener(value = LogicEvents.ON_STEP, priority = -90)
    public void dispatchPlayerCollisions() {
        RuleResolvedView rules = RuleResolvedView.resolve(
                this.runtime.instance().stateCache().rules().snapshot(),
                this.runtime.instance().stateCache().coordinates().snapshot()
        );
        List<Actor> players = this.runtime.instance().stateCache().actors().activeActors(ActorType.PLAYER_SOUL);
        for (Actor actor : this.runtime.instance().stateCache().actors().activeActors(ActorType.SCRIPTED_ACTOR)) {
            for (Actor player : players) {
                if (!actor.active() || !player.active()) {
                    continue;
                }
                if (ActorCollision.intersects(
                        actor,
                        player,
                        rules.viewMode(),
                        rules.collisionPolicy()
                )) {
                    runComponents(actor, ComponentCall.ON_COLLIDE_PLAYER, player);
                    this.events.dispatch(actor, ScriptedActorEventType.ON_COLLIDE_PLAYER, player);
                }
            }
        }
    }

    @LogicEventListener(value = LogicEvents.ON_STEP, priority = -300)
    public void dispatchDestroyEvents() {
        for (Actor actor : this.runtime.instance().stateCache().actors().actors(ActorType.SCRIPTED_ACTOR)) {
            if (actor.lifecycle() != ActorLifecycle.REMOVED || this.destroyDispatched.contains(actor.ref())) {
                continue;
            }
            runComponents(actor, ComponentCall.ON_DESTROY, null);
            this.events.dispatch(actor, ScriptedActorEventType.ON_DESTROY);
            this.runtime.actors().remove(actor.ref());
            this.destroyDispatched.add(actor.ref());
        }
    }

    void dispatchSpawn(Actor actor) {
        runComponents(actor, ComponentCall.ON_SPAWN, null);
        this.events.dispatch(actor, ScriptedActorEventType.ON_SPAWN);
    }

    void dispatchSignal(String signal) {
        for (Actor actor : this.runtime.instance().stateCache().actors().activeActors(ActorType.SCRIPTED_ACTOR)) {
            this.events.dispatchSignal(actor, signal);
        }
    }

    void dispatchKeyPressed(String key) {
        for (Actor actor : this.runtime.instance().stateCache().actors().activeActors(ActorType.SCRIPTED_ACTOR)) {
            this.events.dispatchKeyPressed(actor, key);
        }
    }

    private void runComponents(Actor actor, ComponentCall call, Actor target) {
        JsonObject template = template(actor);
        if (template == null || actor.scripted() == null) {
            return;
        }
        JsonArray components = BattleScriptJson.optionalArray(template, "components");
        for (int i = 0; i < components.size(); i++) {
            JsonObject componentData = BattleScriptJson.requireObject(components.get(i), "components[" + i + "]");
            String type = BattleScriptJson.requireString(componentData, "type", "components[" + i + "]");
            ActorComponentRuntime component = ActorComponentRegistry.require(type);
            ActorComponentContext context = new ActorComponentContext(
                    this.runtime.instance(),
                    this.runtime,
                    actor,
                    componentData,
                    actor.scripted().componentState(i + ":" + type),
                    this.runtime.definition().compiled().budgets()
            );
            switch (call) {
                case ON_SPAWN -> component.onSpawn(context);
                case ON_TICK -> component.onTick(context);
                case ON_COLLIDE_PLAYER -> component.onCollidePlayer(context, target);
                case ON_COLLIDE_BATTLE_BOX -> component.onCollideBattleBox(context);
                case ON_DESTROY -> component.onDestroy(context);
            }
        }
    }

    private JsonObject template(Actor actor) {
        if (actor.scripted() == null) {
            return null;
        }
        return this.runtime.definition().compiled()
                .resolveActorPrefab(actor.scripted().templateRef().value())
                .map(ActorPrefabDefinition::root)
                .orElse(null);
    }

    private enum ComponentCall {
        ON_SPAWN,
        ON_TICK,
        ON_COLLIDE_PLAYER,
        ON_COLLIDE_BATTLE_BOX,
        ON_DESTROY
    }
}
