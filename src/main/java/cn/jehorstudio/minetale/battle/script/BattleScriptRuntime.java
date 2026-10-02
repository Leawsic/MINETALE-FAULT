package cn.jehorstudio.minetale.battle.script;

import cn.jehorstudio.minetale.battle.logic.BattleInstance;
import cn.jehorstudio.minetale.battle.logic.states.AppliedRule;
import cn.jehorstudio.minetale.battle.logic.event.LogicEventListener;
import cn.jehorstudio.minetale.battle.logic.event.LogicEvents;
import cn.jehorstudio.minetale.battle.logic.input.BattleInputKey;

import java.util.List;
import java.util.Objects;
import java.util.Random;

public final class BattleScriptRuntime {
    private final BattleInstance instance;
    private final BattleDefinition definition;
    private final Random random;
    private final Random soundRandom;
    private final BattleScriptActorBindings actors = new BattleScriptActorBindings();
    private final BattleScriptSignals signals = new BattleScriptSignals();
    private final BattleScriptValueEvaluator values = new BattleScriptValueEvaluator();
    private final BattleScriptActionFactory actions;
    private final BattleScriptConditionEvaluator conditions;
    private final BattleScriptPhaseRuntime phaseRuntime;
    private final ScriptedActorRuntime scriptedActors;
    private final BattleScriptInputEventDispatcher inputEvents;
    private long lastInputRevision = Long.MIN_VALUE;
    private long nextRuleSourceOrdinal = 1L;
    private long actorSpawnBudgetTick = -1L;
    private int actorSpawnsThisTick;

    public BattleScriptRuntime(BattleInstance instance, BattleDefinition definition, long seed) {
        this.instance = Objects.requireNonNull(instance, "instance");
        this.definition = Objects.requireNonNull(definition, "definition");
        this.random = new Random(seed);
        this.soundRandom = new Random(seed ^ 0x534F554E445F524EL);
        this.actions = new BattleScriptActionFactory(this);
        this.conditions = new BattleScriptConditionEvaluator(this);
        this.phaseRuntime = new BattleScriptPhaseRuntime(this, this.actions);
        this.scriptedActors = new ScriptedActorRuntime(this, this.actions);
        this.inputEvents = new BattleScriptInputEventDispatcher(this, this.actions);
    }

    @LogicEventListener(LogicEvents.ON_INITIALIZE)
    public void initialize() {
        BattleScriptBudgets budgets = this.definition.compiled().budgets();
        this.instance.actionStacks().setBudgetLimits(
                budgets.maxActionsPerStackPerTick(),
                budgets.maxActionsPerBattleTick()
        );
        this.instance.setRenderRequestBudget(budgets.maxRenderRequestsPerTick());
        BattleScriptEvaluationContext evaluationContext = new BattleScriptEvaluationContext(this, null);
        for (VariableDefinition variable : this.definition.compiled().variables().values()) {
            this.instance.stateCache().values().declare(
                    variable.id(),
                    this.values.evalTyped(variable.initialExpression(), variable.type(), evaluationContext)
            );
        }
        this.phaseRuntime.initialize();
    }

    @LogicEventListener(value = LogicEvents.ON_STEP, priority = 500)
    public void advance() {
        this.phaseRuntime.advance();
        this.signals.endTick(this.instance.timeline().battleTick());
    }

    @LogicEventListener(value = LogicEvents.ON_STEP, priority = 550)
    public void dispatchInputEvents() {
        this.inputEvents.cleanup();
        long revision = this.instance.input().revision();
        if (revision == this.lastInputRevision) {
            return;
        }
        this.lastInputRevision = revision;
        for (String key : this.instance.input().pressedKeys()) {
            BattleInputKey.require(key, "input key");
            this.inputEvents.dispatch(key);
            this.scriptedActors.dispatchKeyPressed(key);
        }
    }

    public BattleInstance instance() {
        return this.instance;
    }

    public BattleDefinition definition() {
        return this.definition;
    }

    public Random random() {
        return this.random;
    }

    Random soundRandom() {
        return this.soundRandom;
    }

    public BattleScriptActorBindings actors() {
        return this.actors;
    }

    public BattleScriptSignals signals() {
        return this.signals;
    }

    public BattleScriptValueEvaluator values() {
        return this.values;
    }

    public void emitSignal(String signal) {
        this.signals.emit(signal, this.instance.timeline().battleTick(), this.definition.compiled().budgets());
        this.scriptedActors.dispatchSignal(signal);
    }

    BattleScriptConditionEvaluator conditions() {
        return this.conditions;
    }

    BattleScriptPhaseRuntime phaseRuntime() {
        return this.phaseRuntime;
    }

    ScriptedActorRuntime scriptedActors() {
        return this.scriptedActors;
    }

    long nextRuleSourceOrdinal() {
        return this.nextRuleSourceOrdinal++;
    }

    void pushRuleset(String source, String rulesetRef) {
        RuleSetDefinition ruleset = this.definition.compiled().resolveRuleSet(rulesetRef)
                .orElseThrow(() -> new IllegalArgumentException("Unknown ruleset: " + rulesetRef));
        List<AppliedRule> appliedRules = BattleScriptRuleMapper.appliedRules(source, ruleset);
        this.instance.stateCache().rules().pushRules(appliedRules, this.definition.compiled().budgets().maxRuleStackDepth());
    }

    void recordActorSpawn(long battleTick) {
        if (this.actorSpawnBudgetTick != battleTick) {
            this.actorSpawnBudgetTick = battleTick;
            this.actorSpawnsThisTick = 0;
        }
        this.actorSpawnsThisTick++;
        if (this.actorSpawnsThisTick > this.definition.compiled().budgets().maxActorSpawnsPerTick()) {
            throw new IllegalStateException("BattleScript actor spawns exceeded maxActorSpawnsPerTick.");
        }
    }
}
