package cn.jehorstudio.minetale.dimension.ebott.transition;

import cn.jehorstudio.minetale.dimension.ebott.transition.seam.DimensionSeam;
import cn.jehorstudio.minetale.dimension.ebott.transition.seam.SeamCrossingDetector;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;
import java.util.UUID;

// 单个玩家一次接近与穿越周期的可变会话，由 TransitionManager 独占修改。
final class TransitionSession {
    private final UUID sessionId;
    private final UUID playerId;
    private final long createdTick;
    private TransitionState state = TransitionState.PREWARMING;
    private CacheLease prewarmLease;
    private SeamCrossingDetector.State crossingState;
    private CrossingSnapshot crossingSnapshot;

    TransitionSession(UUID sessionId, UUID playerId, long createdTick) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.playerId = Objects.requireNonNull(playerId, "playerId");
        this.createdTick = createdTick;
    }

    void attachPrewarmLease(CacheLease lease) {
        Objects.requireNonNull(lease, "lease");
        if (this.prewarmLease != null && this.prewarmLease != lease) {
            throw new IllegalStateException("prewarm lease cannot change within one session");
        }
        this.prewarmLease = lease;
    }

    void completePrewarm(Vec3 sourceProbe, double sourcePlaneY) {
        if (this.state != TransitionState.PREWARMING) {
            throw new IllegalStateException("prewarm can only complete from PREWARMING");
        }
        Objects.requireNonNull(sourceProbe, "sourceProbe");
        double epsilon = SeamCrossingDetector.DEFAULT_CROSS_EPSILON;
        // 碰撞关闭前玩家必在源侧；贴面初始化也必须锁定该滞回侧。
        Vec3 sourceSideProbe = sourceProbe.y <= sourcePlaneY + epsilon
                ? new Vec3(sourceProbe.x, sourcePlaneY + epsilon * 2.0, sourceProbe.z)
                : sourceProbe;
        this.crossingState = SeamCrossingDetector.initialize(
                sourceSideProbe,
                sourcePlaneY,
                epsilon
        );
        advance(TransitionState.PASSABLE);
    }

    SeamCrossingDetector.Observation observeCrossing(
            Vec3 currentProbe,
            double sourcePlaneY,
            double sourceCenterX,
            double sourceCenterZ,
            DimensionSeam.CircularSeamAperture aperture,
            double playerBoundingRadius
    ) {
        if (this.state != TransitionState.PASSABLE || this.crossingState == null) {
            throw new IllegalStateException("crossing can only be observed while PASSABLE");
        }
        SeamCrossingDetector.Observation observation = SeamCrossingDetector.observe(
                this.crossingState,
                Objects.requireNonNull(currentProbe, "currentProbe"),
                sourcePlaneY,
                sourceCenterX,
                sourceCenterZ,
                Objects.requireNonNull(aperture, "aperture"),
                playerBoundingRadius,
                SeamCrossingDetector.DEFAULT_EDGE_SAFETY_MARGIN,
                SeamCrossingDetector.DEFAULT_CROSS_EPSILON
        );
        this.crossingState = observation.state();
        return observation;
    }

    boolean beginCrossing(SeamCrossingDetector.Observation observation, CrossingSnapshot snapshot) {
        Objects.requireNonNull(observation, "observation");
        if (this.state != TransitionState.PASSABLE || !observation.crossed()
                || this.crossingState != observation.state()) {
            return false;
        }
        this.crossingSnapshot = Objects.requireNonNull(snapshot, "snapshot");
        advance(TransitionState.CROSSING);
        return true;
    }

    void complete() {
        advance(TransitionState.COMPLETE);
    }

    void fail() {
        if (!this.state.isTerminal()) {
            advance(TransitionState.FAILED);
        }
    }

    private void advance(TransitionState next) {
        if (!this.state.canAdvanceTo(next)) {
            throw new IllegalStateException("illegal transition edge: " + this.state + " -> " + next);
        }
        this.state = next;
    }

    boolean matches(UUID expectedPlayerId, UUID expectedSessionId) {
        return this.playerId.equals(expectedPlayerId) && this.sessionId.equals(expectedSessionId);
    }

    UUID sessionId() {
        return sessionId;
    }

    UUID playerId() {
        return playerId;
    }

    long createdTick() {
        return createdTick;
    }

    TransitionState state() {
        return state;
    }

    CacheLease prewarmLease() {
        return prewarmLease;
    }

    CrossingSnapshot crossingSnapshot() {
        if (this.crossingSnapshot == null) {
            throw new IllegalStateException("crossing snapshot has not been captured");
        }
        return this.crossingSnapshot;
    }
}
