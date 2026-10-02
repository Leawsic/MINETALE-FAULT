package cn.jehorstudio.minetale.battle.script;

import cn.jehorstudio.minetale.battle.logic.BattleInstance;
import cn.jehorstudio.minetale.battle.logic.actor.Actor;
import cn.jehorstudio.minetale.battle.logic.actor.ActorRef;
import cn.jehorstudio.minetale.battle.logic.actor.ActorType;
import cn.jehorstudio.minetale.battle.logic.event.contexts.ActorTargetContext;
import cn.jehorstudio.minetale.battle.logic.event.contexts.BattleActionContext;
import cn.jehorstudio.minetale.battle.logic.states.ValueStateCache;

import java.util.Objects;
import java.util.Optional;
import java.util.Random;

public record BattleScriptEvaluationContext(
        BattleScriptRuntime runtime,
        BattleActionContext actionContext,
        Random deterministicRandom
) {
    public BattleScriptEvaluationContext {
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(deterministicRandom, "deterministicRandom");
    }

    public BattleScriptEvaluationContext(BattleScriptRuntime runtime, BattleActionContext actionContext) {
        this(runtime, actionContext, runtime.random());
    }

    public BattleInstance instance() {
        return this.runtime.instance();
    }

    public ValueStateCache scriptValues() {
        return this.instance().stateCache().values();
    }

    public Optional<Actor> self() {
        if (this.actionContext == null) {
            return Optional.empty();
        }
        return BattleScriptTargetResolver.resolveSelfActor(this.runtime, this.actionContext);
    }

    public Optional<Actor> other() {
        if (this.actionContext == null) {
            return Optional.empty();
        }
        if (this.actionContext.eventContext() instanceof ActorTargetContext hit) {
            return this.instance().stateCache().actors().resolve(hit.target());
        }
        return Optional.empty();
    }

    public Optional<Actor> player() {
        if (this.actionContext == null) {
            return Optional.empty();
        }
        if (this.actionContext.eventContext() instanceof ActorTargetContext hit
                && hit.target().type() == ActorType.PLAYER_SOUL) {
            return this.instance().stateCache().actors().resolve(hit.target());
        }
        if (!this.instance().stateCache().players().initialized()) {
            return Optional.empty();
        }
        ActorRef ref = this.instance().stateCache().players().soulRef();
        return this.instance().stateCache().actors().resolve(ref);
    }

    BattleScriptPhaseRuntime phase() {
        return this.runtime.phaseRuntime();
    }

    public Random deterministicRandom() {
        return this.deterministicRandom;
    }
}
