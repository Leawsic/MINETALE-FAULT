package cn.jehorstudio.minetale.battle.logic.action.actions;

import cn.jehorstudio.minetale.battle.logic.action.BattleAction;
import cn.jehorstudio.minetale.battle.logic.action.BattleActionConflict;
import cn.jehorstudio.minetale.battle.logic.action.BattleActionConflictKeys;
import cn.jehorstudio.minetale.battle.logic.action.BattleActionConflictPolicy;
import cn.jehorstudio.minetale.battle.logic.action.BattleActionResult;
import cn.jehorstudio.minetale.battle.logic.action.CubicBezierEasing;
import cn.jehorstudio.minetale.battle.logic.coordinate.BattleCoordinateSpace;
import cn.jehorstudio.minetale.battle.logic.coordinate.BattleCoordinateStateCache;
import cn.jehorstudio.minetale.battle.logic.coordinate.BattleViewMode;
import cn.jehorstudio.minetale.battle.logic.coordinate.CanonicalVec3;
import cn.jehorstudio.minetale.battle.logic.coordinate.ControlPolicy;
import cn.jehorstudio.minetale.battle.logic.event.contexts.BattleActionContext;

import java.util.Objects;

// 统一推进相机、投影、SceneMode 与画面缩放过渡；调用方只声明目标状态。
public final class RequestSmoothCameraAction implements BattleAction {
    private static final double PROJECTION_SWITCH_FOV_DEG = 0.1D;
    private static final double PROJECTION_SWITCH_PROGRESS = 1.0D;

    private final double durationSeconds;
    private final BattleViewMode requestedTargetView;
    private final BattleCoordinateStateCache.SceneMode requestedTargetScene;
    private final Double requestedTargetScale;
    private final Double requestedYawDeg;
    private final Double requestedPitchDeg;
    private final boolean orientationOnly;
    private final RenderModeTransitionSpec renderModeTransition;

    private boolean started;
    private long startedAtBattleTick;
    private long durationBattleTicks;
    private BattleViewMode startView;
    private BattleViewMode targetView;
    private BattleCoordinateStateCache.SceneMode startScene;
    private BattleCoordinateStateCache.SceneMode targetScene;
    private double startScale;
    private double targetScale;
    private double startSoulModelRotationBlend;
    private double targetSoulModelRotationBlend;
    private long renderModeDelayBattleTicks;
    private long renderModeDurationBattleTicks;
    private long totalDurationBattleTicks;
    private double startRenderedBlend;
    private double targetRenderedBlend;

    // 仅修改轨道角度，其他目标值沿用 Action 首次运行时的坐标状态。
    public RequestSmoothCameraAction(double durationSeconds, double targetYawDeg, double targetPitchDeg) {
        this(
                durationSeconds,
                null,
                null,
                null,
                targetYawDeg,
                targetPitchDeg,
                RenderModeTransitionSpec.DISABLED
        );
    }

    public RequestSmoothCameraAction(
            double durationSeconds,
            BattleViewMode targetView,
            BattleCoordinateStateCache.SceneMode targetScene,
            double targetScale,
            RenderModeTransitionSpec renderModeTransition
    ) {
        this(durationSeconds,
                requireSceneTarget(targetView, targetScene, targetScale),
                Objects.requireNonNull(targetScene, "targetScene"),
                requirePositive(targetScale, "targetScale"),
                null,
                null,
                Objects.requireNonNull(renderModeTransition, "renderModeTransition"));
    }

    private static BattleViewMode requireSceneTarget(
            BattleViewMode targetView,
            BattleCoordinateStateCache.SceneMode targetScene,
            double targetScale
    ) {
        Objects.requireNonNull(targetView, "targetView");
        Objects.requireNonNull(targetScene, "targetScene");
        if (targetScene == BattleCoordinateStateCache.SceneMode.TWO_D
                && (targetView.type() != BattleViewMode.Type.ORTHO_2D
                || Double.compare(targetScale, 1.0D) != 0)) {
            throw new IllegalArgumentException("TWO_D scene requires ORTHO_2D and viewScale=1.");
        }
        return targetView;
    }

    private RequestSmoothCameraAction(
            double durationSeconds,
            BattleViewMode targetView,
            BattleCoordinateStateCache.SceneMode targetScene,
            Double targetScale,
            Double targetYawDeg,
            Double targetPitchDeg,
            RenderModeTransitionSpec renderModeTransition
    ) {
        if (!Double.isFinite(durationSeconds) || durationSeconds < 0.0D) {
            throw new IllegalArgumentException("durationSeconds must be finite and >= 0.");
        }
        if (targetYawDeg != null) requireFinite(targetYawDeg, "targetYawDeg");
        if (targetPitchDeg != null) requireFinite(targetPitchDeg, "targetPitchDeg");
        this.durationSeconds = durationSeconds;
        this.requestedTargetView = targetView;
        this.requestedTargetScene = targetScene;
        this.requestedTargetScale = targetScale;
        this.requestedYawDeg = targetYawDeg;
        this.requestedPitchDeg = targetPitchDeg;
        this.orientationOnly = targetView == null;
        this.renderModeTransition = Objects.requireNonNull(renderModeTransition, "renderModeTransition");
    }

    @Override
    public BattleActionConflict conflict() {
        return BattleActionConflict.of(
                BattleActionConflictKeys.CAMERA_VIEW,
                this.orientationOnly
                        ? BattleActionConflictPolicy.CANCEL_NEW
                        : BattleActionConflictPolicy.CANCEL_OLD
        );
    }

    @Override
    public BattleActionResult run(BattleActionContext context) {
        var coordinates = context.instance().stateCache().coordinates();
        long battleTick = context.instance().timeline().battleTick();
        if (!this.started) {
            this.started = true;
            this.startedAtBattleTick = battleTick;
            this.durationBattleTicks = context.instance().timeline().secondsToBattleTicks(this.durationSeconds);
            this.startView = coordinates.viewMode();
            this.startScene = coordinates.sceneMode();
            this.startScale = coordinates.viewScale();
            this.targetScene = this.requestedTargetScene == null ? this.startScene : this.requestedTargetScene;
            this.targetScale = this.requestedTargetScale == null ? this.startScale : this.requestedTargetScale;
            this.startSoulModelRotationBlend = coordinates.soulModelRotationBlend();
            this.targetSoulModelRotationBlend = this.targetScene == BattleCoordinateStateCache.SceneMode.THREE_D
                    ? 1.0D
                    : 0.0D;
            this.targetView = this.requestedTargetView == null
                    ? withOrbitAngles(this.startView, this.requestedYawDeg, this.requestedPitchDeg)
                    : this.requestedTargetView;
            this.startRenderedBlend = coordinates.renderedBlend();
            boolean sceneChanges = this.startScene != this.targetScene;
            this.targetRenderedBlend = sceneChanges
                    ? targetRenderedBlend(this.targetScene, this.renderModeTransition.renderedTargetEnabled())
                    : this.startRenderedBlend;
            if (this.durationBattleTicks == 0L) {
                this.renderModeDelayBattleTicks = 0L;
                this.renderModeDurationBattleTicks = 0L;
            } else if (sceneChanges && this.startScene == BattleCoordinateStateCache.SceneMode.TWO_D
                    && this.targetRenderedBlend > this.startRenderedBlend) {
                this.renderModeDelayBattleTicks = context.instance().timeline().secondsToBattleTicks(
                        this.renderModeTransition.enterDelaySeconds());
                this.renderModeDurationBattleTicks = context.instance().timeline().secondsToBattleTicks(
                        this.renderModeTransition.durationSeconds());
            } else if (sceneChanges && this.startRenderedBlend != this.targetRenderedBlend) {
                this.renderModeDelayBattleTicks = 0L;
                this.renderModeDurationBattleTicks = context.instance().timeline().secondsToBattleTicks(
                        this.renderModeTransition.durationSeconds());
            } else {
                this.renderModeDelayBattleTicks = 0L;
                this.renderModeDurationBattleTicks = 0L;
            }
            this.totalDurationBattleTicks = Math.max(
                    this.durationBattleTicks,
                    this.renderModeDelayBattleTicks + this.renderModeDurationBattleTicks
            );

            // 进入 3D 时立即开放深度几何；退出 3D 则在过渡完成后再收窄场景策略。
            if (this.startScene != this.targetScene
                    && this.targetScene == BattleCoordinateStateCache.SceneMode.THREE_D) {
                applyScenePolicy(context, this.targetScene);
            }
        }

        long elapsed = battleTick - this.startedAtBattleTick;
        double raw = this.durationBattleTicks == 0L
                ? 1.0D
                : Math.min(1.0D, elapsed / (double) this.durationBattleTicks);
        double eased = CubicBezierEasing.BATTLE_CAMERA.map(raw);
        coordinates.setViewMode(sampleView(this.startView, this.targetView, eased));
        coordinates.setViewScale(lerp(this.startScale, this.targetScale, eased));
        coordinates.setSoulModelRotationBlend(lerp(
                this.startSoulModelRotationBlend,
                this.targetSoulModelRotationBlend,
                eased
        ));
        coordinates.setRenderedBlend(sampleRenderedBlend(
                this.startRenderedBlend,
                this.targetRenderedBlend,
                elapsed,
                this.renderModeDelayBattleTicks,
                this.renderModeDurationBattleTicks
        ));

        if (elapsed >= this.totalDurationBattleTicks) {
            coordinates.setViewMode(this.targetView);
            coordinates.setViewScale(this.targetScale);
            coordinates.setSoulModelRotationBlend(this.targetSoulModelRotationBlend);
            coordinates.setRenderedBlend(this.targetRenderedBlend);
            if (coordinates.sceneMode() != this.targetScene) {
                applyScenePolicy(context, this.targetScene);
            }
            return BattleActionResult.COMPLETED;
        }
        return BattleActionResult.RUNNING;
    }

    public static double sampleRenderedBlend(
            double start,
            double target,
            long elapsedTicks,
            long delayTicks,
            long durationTicks
    ) {
        requireBlend(start, "start");
        requireBlend(target, "target");
        if (elapsedTicks < 0L || delayTicks < 0L || durationTicks < 0L) {
            throw new IllegalArgumentException("Crossfade ticks must be >= 0.");
        }
        if (elapsedTicks < delayTicks) {
            return start;
        }
        double raw = durationTicks == 0L
                ? 1.0D
                : Math.min(1.0D, (elapsedTicks - delayTicks) / (double) durationTicks);
        return lerp(start, target, CubicBezierEasing.BATTLE_TRANSITION.map(raw));
    }

    // 退出 3D 时保留完整场景至最后一帧，与连续视图采样使用同一过渡边界。
    public static BattleCoordinateStateCache.SceneMode sampleSceneMode(
            BattleCoordinateStateCache.SceneMode from,
            BattleCoordinateStateCache.SceneMode to,
            double progress
    ) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        double t = clamp01(progress);
        if (from == BattleCoordinateStateCache.SceneMode.THREE_D
                && to == BattleCoordinateStateCache.SceneMode.TWO_D
                && t < 1.0D) {
            return BattleCoordinateStateCache.SceneMode.THREE_D;
        }
        return to;
    }

    private static double targetRenderedBlend(
            BattleCoordinateStateCache.SceneMode targetScene,
            boolean renderedTargetEnabled
    ) {
        return targetScene == BattleCoordinateStateCache.SceneMode.THREE_D && renderedTargetEnabled
                ? 1.0D
                : 0.0D;
    }

    private static BattleViewMode withOrbitAngles(BattleViewMode current, double yawDeg, double pitchDeg) {
        ViewParameters source = ViewParameters.from(current);
        return source.orthographic
                ? orthographicOrbit(yawDeg, pitchDeg, source.visibleHeight, source.verticalCenterOffset)
                : BattleViewMode.scriptedCamera(BattleViewMode.orbitCamera(
                        yawDeg, pitchDeg, source.distance, source.fovDegrees), source.verticalCenterOffset);
    }

    // 以极窄 FOV 逼近正交投影并保持可见高度连续，避免切换矩阵类型时画面跳变。
    public static BattleViewMode sampleView(BattleViewMode from, BattleViewMode to, double progress) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (from.equals(to)) {
            return to;
        }
        double t = clamp01(progress);
        if (t >= 1.0D) {
            return to;
        }
        ViewParameters start = ViewParameters.from(from);
        ViewParameters target = ViewParameters.from(to);
        double yaw = lerpAngle(start.yawDeg, target.yawDeg, t);
        double pitch = lerp(start.pitchDeg, target.pitchDeg, t);
        double visibleHeight = lerp(start.visibleHeight, target.visibleHeight, t);
        double verticalCenterOffset = lerp(
                start.verticalCenterOffset,
                target.verticalCenterOffset,
                t
        );

        if (start.orthographic == target.orthographic) {
            if (start.orthographic) {
                return orthographicOrbit(yaw, pitch, visibleHeight, verticalCenterOffset);
            }
            double fov = lerp(start.fovDegrees, target.fovDegrees, t);
            return perspectiveMatchingHeight(yaw, pitch, visibleHeight, fov, verticalCenterOffset);
        }

        if (start.orthographic) {
            double switchAt = 1.0D - PROJECTION_SWITCH_PROGRESS;
            if (t < switchAt) {
                return orthographicOrbit(yaw, pitch, visibleHeight, verticalCenterOffset);
            }
            double projectionT = clamp01((t - switchAt) / (1.0D - switchAt));
            double fov = lerp(PROJECTION_SWITCH_FOV_DEG, target.fovDegrees, projectionT);
            return perspectiveMatchingHeight(yaw, pitch, visibleHeight, fov, verticalCenterOffset);
        }

        if (t < PROJECTION_SWITCH_PROGRESS) {
            double projectionT = t / PROJECTION_SWITCH_PROGRESS;
            double fov = lerp(start.fovDegrees, PROJECTION_SWITCH_FOV_DEG, projectionT);
            return perspectiveMatchingHeight(yaw, pitch, visibleHeight, fov, verticalCenterOffset);
        }
        return orthographicOrbit(yaw, pitch, visibleHeight, verticalCenterOffset);
    }

    private static BattleViewMode perspectiveMatchingHeight(
            double yawDeg,
            double pitchDeg,
            double visibleHeight,
            double fovDegrees,
            double verticalCenterOffset
    ) {
        double distance = visibleHeight / (2.0D * Math.tan(Math.toRadians(fovDegrees * 0.5D)));
        return BattleViewMode.scriptedCamera(BattleViewMode.orbitCamera(
                yawDeg, pitchDeg, distance, fovDegrees), verticalCenterOffset);
    }

    private static BattleViewMode orthographicOrbit(
            double yawDeg,
            double pitchDeg,
            double orthoHeight,
            double verticalCenterOffset
    ) {
        BattleViewMode perspective = BattleViewMode.perspective3d(
                BattleViewMode.orbitCamera(yawDeg, pitchDeg, 1.0D, 45.0D));
        return new BattleViewMode(
                BattleViewMode.Type.ORTHO_2_5D,
                perspective.viewDirection(),
                perspective.cameraUp(),
                BattleCoordinateSpace.Axis.Y,
                0.0D,
                orthoHeight,
                verticalCenterOffset,
                null,
                BattleViewMode.Framing.fixed()
        );
    }

    private static void applyScenePolicy(
            BattleActionContext context,
            BattleCoordinateStateCache.SceneMode sceneMode
    ) {
        var coordinates = context.instance().stateCache().coordinates();
        double speed = coordinates.controlPolicy().speedBuPerSecond();
        coordinates.setSceneMode(sceneMode);
        if (sceneMode == BattleCoordinateStateCache.SceneMode.THREE_D) {
            coordinates.setControlPolicy(ControlPolicy.free3d(speed));
            return;
        }
        double soulDepth = context.instance().stateCache().players().snapshot()
                .flatMap(player -> context.instance().stateCache().actors().resolve(player.soulRef()))
                .map(soul -> soul.transform().position().y())
                .orElse(coordinates.controlPolicy().lockedValue());
        coordinates.setControlPolicy(ControlPolicy.planeLocked(
                BattleCoordinateSpace.Axis.Y,
                soulDepth,
                speed
        ));
    }

    private static double lerp(double start, double end, double progress) {
        return start + (end - start) * progress;
    }

    private static double lerpAngle(double start, double end, double progress) {
        double delta = ((end - start + 540.0D) % 360.0D) - 180.0D;
        return start + delta * progress;
    }

    private static double clamp01(double value) {
        return Math.max(0.0D, Math.min(1.0D, value));
    }

    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException(name + " must be finite.");
    }

    private static double requirePositive(double value, String name) {
        if (!Double.isFinite(value) || value <= 0.0D) {
            throw new IllegalArgumentException(name + " must be finite and > 0.");
        }
        return value;
    }

    private static void requireBlend(double value, String name) {
        if (!Double.isFinite(value) || value < 0.0D || value > 1.0D) {
            throw new IllegalArgumentException(name + " must be finite and within [0, 1].");
        }
    }

    public record RenderModeTransitionSpec(
            boolean renderedTargetEnabled,
            double enterDelaySeconds,
            double durationSeconds
    ) {
        private static final RenderModeTransitionSpec DISABLED =
                new RenderModeTransitionSpec(false, 0.0D, 0.0D);

        public RenderModeTransitionSpec {
            if (!Double.isFinite(enterDelaySeconds) || enterDelaySeconds < 0.0D) {
                throw new IllegalArgumentException("enterDelaySeconds must be finite and >= 0.");
            }
            if (!Double.isFinite(durationSeconds) || durationSeconds < 0.0D) {
                throw new IllegalArgumentException("durationSeconds must be finite and >= 0.");
            }
        }
    }

    private record ViewParameters(
            boolean orthographic,
            double yawDeg,
            double pitchDeg,
            double distance,
            double fovDegrees,
            double visibleHeight,
            double verticalCenterOffset
    ) {
        static ViewParameters from(BattleViewMode mode) {
            if (mode.orthographic()) {
                CanonicalVec3 radial = mode.viewDirection().scale(-1.0D);
                double distance = mode.orthoHeight()
                        / (2.0D * Math.tan(Math.toRadians(45.0D * 0.5D)));
                return fromRadial(true, radial.scale(distance), distance, 45.0D,
                        mode.orthoHeight(), mode.verticalCenterOffset());
            }
            BattleViewMode.Camera camera = mode.camera();
            CanonicalVec3 radial = camera.position().subtract(camera.target());
            double distance = radial.length();
            double visibleHeight = 2.0D * distance * Math.tan(Math.toRadians(camera.fovDegrees() * 0.5D));
            return fromRadial(false, radial, distance, camera.fovDegrees(), visibleHeight,
                    mode.verticalCenterOffset());
        }

        private static ViewParameters fromRadial(
                boolean orthographic,
                CanonicalVec3 radial,
                double distance,
                double fovDegrees,
                double visibleHeight,
                double verticalCenterOffset
        ) {
            double pitch = Math.toDegrees(Math.asin(-radial.z() / distance));
            double yaw = Math.toDegrees(Math.atan2(radial.x(), radial.y()));
            return new ViewParameters(orthographic, yaw, pitch, distance, fovDegrees,
                    visibleHeight, verticalCenterOffset);
        }
    }
}
