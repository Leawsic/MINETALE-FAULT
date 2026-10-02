package cn.jehorstudio.minetale.battle.logic.coordinate;

import java.util.Objects;

// BattleInstance 持有的权威视图与输入策略，保存 canonical 事实。
public final class BattleCoordinateStateCache {
    private BattleViewMode viewMode = BattleViewMode.ORTHO_2D;
    private ControlPolicy controlPolicy = ControlPolicy.PLANE_LOCKED_XZ;
    private SceneMode sceneMode = SceneMode.TWO_D;
    private double viewScale = 1.0D;
    private double soulModelRotationBlend;
    private double renderedBlend;

    public BattleViewMode viewMode() {
        return this.viewMode;
    }

    public void setViewMode(BattleViewMode viewMode) {
        this.viewMode = Objects.requireNonNull(viewMode, "viewMode");
    }

    public ControlPolicy controlPolicy() {
        return this.controlPolicy;
    }

    public void setControlPolicy(ControlPolicy controlPolicy) {
        this.controlPolicy = Objects.requireNonNull(controlPolicy, "controlPolicy");
    }

    public SceneMode sceneMode() {
        return this.sceneMode;
    }

    public void setSceneMode(SceneMode sceneMode) {
        this.sceneMode = Objects.requireNonNull(sceneMode, "sceneMode");
    }

    public double viewScale() {
        return this.viewScale;
    }

    public void setViewScale(double viewScale) {
        if (!Double.isFinite(viewScale) || viewScale <= 0.0D) {
            throw new IllegalArgumentException("viewScale must be finite and > 0.");
        }
        this.viewScale = viewScale;
    }

    public double soulModelRotationBlend() {
        return this.soulModelRotationBlend;
    }

    public void setSoulModelRotationBlend(double soulModelRotationBlend) {
        if (!Double.isFinite(soulModelRotationBlend)
                || soulModelRotationBlend < 0.0D
                || soulModelRotationBlend > 1.0D) {
            throw new IllegalArgumentException("soulModelRotationBlend must be finite and within [0, 1].");
        }
        this.soulModelRotationBlend = soulModelRotationBlend;
    }

    public double renderedBlend() {
        return this.renderedBlend;
    }

    public void setRenderedBlend(double renderedBlend) {
        if (!Double.isFinite(renderedBlend) || renderedBlend < 0.0D || renderedBlend > 1.0D) {
            throw new IllegalArgumentException("renderedBlend must be finite and within [0, 1].");
        }
        this.renderedBlend = renderedBlend;
    }

    public Snapshot snapshot() {
        return new Snapshot(
                this.viewMode,
                this.controlPolicy,
                this.sceneMode,
                this.viewScale,
                this.soulModelRotationBlend,
                this.renderedBlend
        );
    }

    public record Snapshot(
            BattleViewMode viewMode,
            ControlPolicy controlPolicy,
            SceneMode sceneMode,
            double viewScale,
            double soulModelRotationBlend,
            double renderedBlend
    ) {
        public Snapshot(
                BattleViewMode viewMode,
                ControlPolicy controlPolicy,
                SceneMode sceneMode,
                double viewScale
        ) {
            this(viewMode, controlPolicy, sceneMode, viewScale, 0.0D, 0.0D);
        }

        public Snapshot(
                BattleViewMode viewMode,
                ControlPolicy controlPolicy,
                SceneMode sceneMode,
                double viewScale,
                double soulModelRotationBlend
        ) {
            this(viewMode, controlPolicy, sceneMode, viewScale, soulModelRotationBlend, 0.0D);
        }

        public Snapshot {
            Objects.requireNonNull(viewMode, "viewMode");
            Objects.requireNonNull(controlPolicy, "controlPolicy");
            Objects.requireNonNull(sceneMode, "sceneMode");
            if (!Double.isFinite(viewScale) || viewScale <= 0.0D) {
                throw new IllegalArgumentException("viewScale must be finite and > 0.");
            }
            if (!Double.isFinite(soulModelRotationBlend)
                    || soulModelRotationBlend < 0.0D
                    || soulModelRotationBlend > 1.0D) {
                throw new IllegalArgumentException("soulModelRotationBlend must be finite and within [0, 1].");
            }
            if (!Double.isFinite(renderedBlend) || renderedBlend < 0.0D || renderedBlend > 1.0D) {
                throw new IllegalArgumentException("renderedBlend must be finite and within [0, 1].");
            }
            if (sceneMode == SceneMode.TWO_D && renderedBlend != 0.0D) {
                throw new IllegalArgumentException("TWO_D scene requires renderedBlend=0.");
            }
        }
    }

    public enum SceneMode {
        TWO_D,
        THREE_D
    }
}
