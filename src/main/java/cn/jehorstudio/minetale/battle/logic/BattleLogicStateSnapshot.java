package cn.jehorstudio.minetale.battle.logic;

import cn.jehorstudio.minetale.battle.logic.actor.states.ActorsStatesSnapshot;
import cn.jehorstudio.minetale.battle.logic.states.PhaseStateSnapshot;
import cn.jehorstudio.minetale.battle.logic.states.PlayerStateSnapshot;
import cn.jehorstudio.minetale.battle.logic.states.RuleStateSnapshot;
import cn.jehorstudio.minetale.battle.logic.states.ValueStateSnapshot;
import cn.jehorstudio.minetale.battle.logic.coordinate.BattleCoordinateStateCache;

import java.util.Objects;
import java.util.Optional;

public record BattleLogicStateSnapshot(
        long battleTick,
        ActorsStatesSnapshot actors,
        Optional<PlayerStateSnapshot> localPlayer,
        ValueStateSnapshot values,
        PhaseStateSnapshot phase,
        RuleStateSnapshot rules,
        BattleCoordinateStateCache.Snapshot coordinates
) {
    public BattleLogicStateSnapshot {
        if (battleTick < 0L) {
            throw new IllegalArgumentException("battleTick must be >= 0.");
        }
        Objects.requireNonNull(actors, "actors");
        localPlayer = Objects.requireNonNull(localPlayer, "localPlayer");
        Objects.requireNonNull(values, "values");
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(rules, "rules");
        Objects.requireNonNull(coordinates, "coordinates");
    }
}
