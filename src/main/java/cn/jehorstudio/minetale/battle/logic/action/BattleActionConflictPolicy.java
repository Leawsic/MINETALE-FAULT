package cn.jehorstudio.minetale.battle.logic.action;

public enum BattleActionConflictPolicy {
    ALLOW_PARALLEL,
    CANCEL_NEW,
    CANCEL_OLD,
    BLOCK_NEW,
    WAIT_OLD
}
