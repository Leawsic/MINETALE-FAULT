package cn.jehorstudio.minetale.battle.presentation.states;

import java.util.Objects;
import java.util.Optional;

// 单次 Renderer.State 提交专属的不可变屏幕效果。
public record ScreenEffectSnapshot(
        Optional<Transition> transition,
        double shakeOffsetX,
        double shakeOffsetY,
        Flash flash,
        ForegroundOverlay foregroundOverlay,
        float environmentBackgroundOpacity
) {
    public static final ScreenEffectSnapshot EMPTY = new ScreenEffectSnapshot(
            Optional.empty(), 0.0D, 0.0D, Flash.NONE, ForegroundOverlay.NONE, 1.0F
    );

    public ScreenEffectSnapshot {
        transition = Objects.requireNonNull(transition, "transition");
        Objects.requireNonNull(flash, "flash");
        Objects.requireNonNull(foregroundOverlay, "foregroundOverlay");
        requireFinite(shakeOffsetX, "shakeOffsetX");
        requireFinite(shakeOffsetY, "shakeOffsetY");
        requireUnit(environmentBackgroundOpacity, "environmentBackgroundOpacity");
    }

    public boolean shaking() {
        return shakeOffsetX != 0.0D || shakeOffsetY != 0.0D;
    }

    public record Transition(long id, float progress) {
        public Transition {
            if (id < 0L) throw new IllegalArgumentException("id must be >= 0.");
            requireUnit(progress, "progress");
        }

        private static void requireUnit(float value, String name) {
            if (!Float.isFinite(value) || value < 0.0F || value > 1.0F) {
                throw new IllegalArgumentException(name + " must be between 0 and 1.");
            }
        }
    }

    // color 为非预乘 RGB；alpha 已按请求顺序合成。
    public record Flash(float red, float green, float blue, float alpha) {
        public static final Flash NONE = new Flash(0.0F, 0.0F, 0.0F, 0.0F);

        public Flash {
            requireUnit(red, "red");
            requireUnit(green, "green");
            requireUnit(blue, "blue");
            requireUnit(alpha, "alpha");
        }

        public boolean active() {
            return alpha > 0.0F;
        }

        private static void requireUnit(float value, String name) {
            if (!Float.isFinite(value) || value < 0.0F || value > 1.0F) {
                throw new IllegalArgumentException(name + " must be between 0 and 1.");
            }
        }
    }

    // 持久前景覆盖使用非预乘 RGB，alpha 是当前时间采样值。
    public record ForegroundOverlay(
            Source source,
            float red,
            float green,
            float blue,
            float alpha
    ) {
        public static final ForegroundOverlay NONE =
                new ForegroundOverlay(Source.COLOR, 0.0F, 0.0F, 0.0F, 0.0F);

        public ForegroundOverlay {
            Objects.requireNonNull(source, "source");
            requireUnit(red, "red");
            requireUnit(green, "green");
            requireUnit(blue, "blue");
            requireUnit(alpha, "alpha");
        }

        public boolean active() {
            return alpha > 0.0F;
        }

        public boolean usesEnvironment() {
            return source == Source.ENVIRONMENT;
        }

        public enum Source {
            COLOR,
            ENVIRONMENT
        }

        private static void requireUnit(float value, String name) {
            if (!Float.isFinite(value) || value < 0.0F || value > 1.0F) {
                throw new IllegalArgumentException(name + " must be between 0 and 1.");
            }
        }
    }

    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException(name + " must be finite.");
    }

    private static void requireUnit(float value, String name) {
        if (!Float.isFinite(value) || value < 0.0F || value > 1.0F) {
            throw new IllegalArgumentException(name + " must be between 0 and 1.");
        }
    }
}
