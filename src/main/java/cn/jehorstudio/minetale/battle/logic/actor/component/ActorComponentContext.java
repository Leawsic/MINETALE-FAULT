package cn.jehorstudio.minetale.battle.logic.actor.component;

import cn.jehorstudio.minetale.battle.logic.BattleInstance;
import cn.jehorstudio.minetale.battle.logic.action.BattleAction;
import cn.jehorstudio.minetale.battle.logic.action.BattleActionResult;
import cn.jehorstudio.minetale.battle.logic.actor.Actor;
import cn.jehorstudio.minetale.battle.logic.event.contexts.BattleActionContext;
import cn.jehorstudio.minetale.battle.logic.event.contexts.BattleEventContext;
import cn.jehorstudio.minetale.battle.script.BattleScriptBudgets;
import cn.jehorstudio.minetale.battle.script.BattleScriptRuntime;
import com.google.gson.JsonObject;

import java.util.Objects;

public final class ActorComponentContext {
    private final BattleInstance instance;
    private final BattleScriptRuntime runtime;
    private final Actor self;
    private final JsonObject componentData;
    private final JsonObject componentState;
    private final BattleScriptBudgets budgets;

    public ActorComponentContext(
            BattleInstance instance,
            BattleScriptRuntime runtime,
            Actor self,
            JsonObject componentData,
            JsonObject componentState,
            BattleScriptBudgets budgets
    ) {
        this.instance = Objects.requireNonNull(instance, "instance");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.self = Objects.requireNonNull(self, "self");
        this.componentData = Objects.requireNonNull(componentData, "componentData");
        this.componentState = Objects.requireNonNull(componentState, "componentState");
        this.budgets = Objects.requireNonNull(budgets, "budgets");
    }

    public BattleInstance instance() {
        return this.instance;
    }

    public BattleScriptRuntime runtime() {
        return this.runtime;
    }

    public Actor self() {
        return this.self;
    }

    public JsonObject componentData() {
        return this.componentData;
    }

    public JsonObject componentState() {
        return this.componentState;
    }

    public BattleScriptBudgets budgets() {
        return this.budgets;
    }

    public BattleActionResult runAction(BattleAction action, BattleEventContext eventContext) {
        return action.run(new BattleActionContext(this.instance, eventContext));
    }
}
