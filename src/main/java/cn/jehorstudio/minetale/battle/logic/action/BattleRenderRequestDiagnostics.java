package cn.jehorstudio.minetale.battle.logic.action;

// 记录单个 battle tick 的表现请求预算；超额只丢弃表现，不回滚权威战斗逻辑。
public record BattleRenderRequestDiagnostics(
        long battleTick,
        int budget,
        int accepted,
        int dropped,
        int pending,
        int pendingLimit
) {
}
