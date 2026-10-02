package cn.jehorstudio.minetale.battle.logic.action;

public final class CubicBezierEasing {
    public static final CubicBezierEasing BATTLE_TRANSITION =
            new CubicBezierEasing(0.80D, 0.0D, 0.20D, 1.0D);
    public static final CubicBezierEasing BATTLE_CAMERA = BATTLE_TRANSITION;

    private final double x1;
    private final double y1;
    private final double x2;
    private final double y2;

    public CubicBezierEasing(double x1, double y1, double x2, double y2) {
        this.x1 = requireUnit(x1, "x1");
        this.y1 = requireUnit(y1, "y1");
        this.x2 = requireUnit(x2, "x2");
        this.y2 = requireUnit(y2, "y2");
    }

    public double map(double progress) {
        double x = clamp01(progress);
        double t = x;
        for (int i = 0; i < 8; i++) {
            double error = cubic(t, this.x1, this.x2) - x;
            double slope = derivative(t, this.x1, this.x2);
            if (Math.abs(error) < 1.0E-6D || Math.abs(slope) < 1.0E-6D) break;
            t = clamp01(t - error / slope);
        }
        return cubic(t, this.y1, this.y2);
    }

    private static double cubic(double t, double control1, double control2) {
        double inverse = 1.0D - t;
        return 3.0D * inverse * inverse * t * control1
                + 3.0D * inverse * t * t * control2
                + t * t * t;
    }

    private static double derivative(double t, double control1, double control2) {
        double inverse = 1.0D - t;
        return 3.0D * inverse * inverse * control1
                + 6.0D * inverse * t * (control2 - control1)
                + 3.0D * t * t * (1.0D - control2);
    }

    private static double requireUnit(double value, String name) {
        if (!Double.isFinite(value) || value < 0.0D || value > 1.0D) {
            throw new IllegalArgumentException(name + " must be between 0 and 1.");
        }
        return value;
    }

    private static double clamp01(double value) {
        return Math.max(0.0D, Math.min(1.0D, value));
    }
}
