package cn.jehorstudio.minetale.battle.script;

import cn.jehorstudio.minetale.battle.logic.BattleInstance;
import cn.jehorstudio.minetale.battle.logic.BattleRuntimeBootstrap;

import java.util.Objects;

public final class BattleScriptBootstrap {
    private BattleScriptBootstrap() {
    }

    public static BattleRuntimeBootstrap create(BattleDefinition definition, long seed) {
        Objects.requireNonNull(definition, "definition");
        return instance -> register(instance, definition, seed);
    }

    private static void register(BattleInstance instance, BattleDefinition definition, long seed) {
        BattleScriptRuntime runtime = new BattleScriptRuntime(instance, definition, seed);
        instance.registerEventListener(runtime);
        instance.registerEventListener(runtime.scriptedActors());
    }
}
