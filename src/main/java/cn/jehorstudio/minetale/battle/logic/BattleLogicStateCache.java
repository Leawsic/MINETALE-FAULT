package cn.jehorstudio.minetale.battle.logic;

import cn.jehorstudio.minetale.battle.logic.actor.states.ActorsStatesCache;
import cn.jehorstudio.minetale.battle.logic.coordinate.BattleCoordinateStateCache;
import cn.jehorstudio.minetale.battle.logic.states.PhaseStateCache;
import cn.jehorstudio.minetale.battle.logic.states.PlayerStateCache;
import cn.jehorstudio.minetale.battle.logic.states.RuleStateCache;
import cn.jehorstudio.minetale.battle.logic.states.ValueStateCache;

public final class BattleLogicStateCache {
    private final ActorsStatesCache actors = new ActorsStatesCache();
    private final PlayerStateCache players = new PlayerStateCache();
    private final ValueStateCache values = new ValueStateCache();
    private final PhaseStateCache phase = new PhaseStateCache();
    private final RuleStateCache rules = new RuleStateCache();
    private final BattleCoordinateStateCache coordinates = new BattleCoordinateStateCache();

    public ActorsStatesCache actors() {
        return this.actors;
    }

    public PlayerStateCache players() {
        return this.players;
    }

    public ValueStateCache values() {
        return this.values;
    }

    public PhaseStateCache phase() {
        return this.phase;
    }

    public RuleStateCache rules() {
        return this.rules;
    }

    public BattleCoordinateStateCache coordinates() {
        return this.coordinates;
    }

    public void cleanupBattleTickEnd() {
        this.actors.cleanupRemovedActors();
    }

    public BattleLogicStateSnapshot snapshot(long battleTick) {
        return new BattleLogicStateSnapshot(
                battleTick,
                this.actors.snapshot(),
                this.players.snapshot(),
                this.values.snapshot(),
                this.phase.snapshot(),
                this.rules.snapshot(),
                this.coordinates.snapshot()
        );
    }
}
