package cn.jehorstudio.minetale.battle.logic.action;

// 逻辑层与表现层之间的纯数据边界。
public sealed interface BattleRenderRequestPayload permits
        BattleRenderRequestPayload.SceneTransition,
        BattleRenderRequestPayload.ScreenShake,
        BattleRenderRequestPayload.ScreenFlash,
        BattleRenderRequestPayload.EnvironmentBackgroundOpacity,
        BattleRenderRequestPayload.ForegroundOverlay {

    double durationSeconds();

    record SceneTransition(double durationSeconds) implements BattleRenderRequestPayload {
        public SceneTransition {
            requireDuration(durationSeconds);
        }
    }

    record ScreenShake(double durationSeconds, double intensity) implements BattleRenderRequestPayload {
        public ScreenShake {
            requireDuration(durationSeconds);
            requireUnitInterval(intensity, "intensity");
        }
    }

    record ScreenFlash(double durationSeconds, double intensity, int rgb) implements BattleRenderRequestPayload {
        public ScreenFlash {
            requireDuration(durationSeconds);
            requireUnitInterval(intensity, "intensity");
            if (rgb < 0 || rgb > 0xFFFFFF) {
                throw new IllegalArgumentException("rgb must be between 0x000000 and 0xFFFFFF.");
            }
        }
    }

    // opacity 是持久目标值；零时长立即应用，否则从消费时的当前值过渡。
    record EnvironmentBackgroundOpacity(
            double durationSeconds,
            double opacity
    ) implements BattleRenderRequestPayload {
        public EnvironmentBackgroundOpacity {
            requireDuration(durationSeconds);
            requireUnitInterval(opacity, "opacity");
        }
    }

    // source 与颜色立即替换；opacity 是可带过渡时长的持久目标值。
    record ForegroundOverlay(
            double durationSeconds,
            double opacity,
            ForegroundOverlaySource source,
            int rgb
    ) implements BattleRenderRequestPayload {
        public ForegroundOverlay {
            requireDuration(durationSeconds);
            requireUnitInterval(opacity, "opacity");
            if (source == null) {
                throw new IllegalArgumentException("source must not be null.");
            }
            if (rgb < 0 || rgb > 0xFFFFFF) {
                throw new IllegalArgumentException("rgb must be between 0x000000 and 0xFFFFFF.");
            }
        }
    }

    enum ForegroundOverlaySource {
        COLOR,
        ENVIRONMENT
    }

    private static void requireDuration(double value) {
        if (!Double.isFinite(value) || value < 0.0D) {
            throw new IllegalArgumentException("durationSeconds must be finite and >= 0.");
        }
    }

    private static void requireUnitInterval(double value, String name) {
        requireFinite(value, name);
        if (value < 0.0D || value > 1.0D) {
            throw new IllegalArgumentException(name + " must be between 0 and 1.");
        }
    }

    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite.");
        }
    }
}
