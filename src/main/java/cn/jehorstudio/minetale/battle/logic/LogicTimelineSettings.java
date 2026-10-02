package cn.jehorstudio.minetale.battle.logic;

public final class LogicTimelineSettings {
    public static final int DEFAULT_BATTLE_STEPS_PER_GAME_TICK = 3;
    public static final int DEFAULT_MAX_BATTLE_STEPS_PER_GAME_TICK = 6;

    private LogicTimelineSettings() {
    }

    public static void validate(int battleStepsPerGameTick, int maxBattleStepsPerGameTick) {
        if (battleStepsPerGameTick <= 0) {
            throw new IllegalArgumentException("battleStepsPerGameTick must be positive.");
        }
        if (maxBattleStepsPerGameTick < battleStepsPerGameTick) {
            throw new IllegalArgumentException("maxBattleStepsPerGameTick must be >= battleStepsPerGameTick.");
        }
    }
}
