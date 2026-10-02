package cn.jehorstudio.minetale.battle.logic;

@FunctionalInterface
public interface BattleRuntimeBootstrap {
    BattleRuntimeBootstrap NONE = instance -> {
    };

    void register(BattleInstance instance);
}
