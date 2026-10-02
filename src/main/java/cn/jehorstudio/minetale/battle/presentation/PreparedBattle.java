package cn.jehorstudio.minetale.battle.presentation;

import cn.jehorstudio.minetale.battle.presentation.screen.render.BattleResourceSet;

import java.util.Objects;
import java.util.UUID;

// 准备阶段交给原子激活步骤的不可变值。
public record PreparedBattle(
        BattlePresentation presentation,
        BattleResourceSet resources,
        UUID environmentCaptureId
) {
    public PreparedBattle {
        Objects.requireNonNull(presentation, "presentation");
        Objects.requireNonNull(resources, "resources");
        Objects.requireNonNull(environmentCaptureId, "environmentCaptureId");
    }
}
