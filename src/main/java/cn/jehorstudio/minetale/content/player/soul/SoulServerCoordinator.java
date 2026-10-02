package cn.jehorstudio.minetale.content.player.soul;

import cn.jehorstudio.minetale.content.player.soul.SoulCompanionBrain.Action;
import cn.jehorstudio.minetale.content.player.soul.SoulCompanionBrain.InterestKind;
import cn.jehorstudio.minetale.content.player.soul.SoulCompanionBrain.InterestPoint;
import cn.jehorstudio.minetale.content.player.soul.SoulCompanionBrain.Intent;
import cn.jehorstudio.minetale.content.player.soul.SoulCompanionBrain.OrbitProfile;
import cn.jehorstudio.minetale.content.player.soul.SoulCompanionBrain.Stimulus;
import cn.jehorstudio.minetale.content.player.soul.SoulFlight.DriveCommand;
import cn.jehorstudio.minetale.content.player.soul.SoulFlight.FrameState;
import cn.jehorstudio.minetale.content.player.soul.SoulFlight.Gesture;
import cn.jehorstudio.minetale.content.player.soul.SoulFlight.GestureProgram;
import cn.jehorstudio.minetale.content.player.soul.SoulFlight.KinematicState;
import cn.jehorstudio.minetale.content.player.soul.SoulFlight.Limits;
import cn.jehorstudio.minetale.content.player.soul.SoulFlight.OrbitField;
import cn.jehorstudio.minetale.content.player.soul.SoulFlight.ReferenceFrame;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

// 推进权威行为、不可见交互代理与低频动作指令
// 可见 Transform 只由客户端控制器持有。
final class SoulServerCoordinator {
    private static final double RELEASE_IMPULSE_SPEED = 0.46;
    private static final Vec3 WORLD_UP = new Vec3(0.0, 1.0, 0.0);
    private static final double RETURN_INTEGRATION_STEP_TICKS = 0.125;
    private static final double INTEREST_RADIUS = 10.0;
    private final SoulCompanionBrain brain = new SoulCompanionBrain();
    @Nullable
    private UUID entityUuid;
    @Nullable
    private GestureProgram gesture;
    private KinematicState motionState = KinematicState.ZERO;
    private Vec3 previousOwnerPosition = Vec3.ZERO;
    private Vec3 previousOwnerVelocity = Vec3.ZERO;
    private Vec3 launchForward = new Vec3(0.0, 0.0, 1.0);
    private int ownerIdleTicks;
    private Stimulus pendingStimulus = Stimulus.NONE;
    private long planEpoch;

    boolean advance(ServerPlayer owner, SoulEntity entity, Soul.State state) {
        if (!entity.getUUID().equals(this.entityUuid)) {
            resetForEntity(owner, entity);
        }

        long tick = owner.level().getGameTime();
        updateOwnerIdle(owner);
        Vec3 ownerVelocity = owner.getDeltaMovement();
        Vec3 ownerAcceleration = ownerVelocity.subtract(this.previousOwnerVelocity);
        this.previousOwnerVelocity = ownerVelocity;
        FrameState ownerFrame = new FrameState(
                SoulPresentationAnchors.chestAnchor(owner, 1.0F),
                ownerVelocity,
                ownerAcceleration
        );
        KinematicState current = currentState(tick, ownerFrame);

        Stimulus stimulus = this.pendingStimulus;
        this.pendingStimulus = Stimulus.NONE;
        SoulCompanionBrain.Perception perception = perception(owner, entity, current, tick);
        Intent intent = this.brain.update(state, perception, stimulus);

        if (state == Soul.State.RETURNING) {
            if (this.gesture == null || this.gesture.gesture() != Gesture.RETURN) {
                installGesture(entity, returnProgram(++this.planEpoch, tick, current, ownerFrame));
            }
            return applyReturn(entity, state, tick, ownerFrame);
        }

        Gesture desiredGesture = gestureFor(intent.action());
        if (desiredGesture != null) {
            if (this.gesture == null || this.gesture.gesture() != desiredGesture) {
                GestureProgram program = switch (desiredGesture) {
                    case THROW -> throwProgram(++this.planEpoch, tick, current, this.launchForward);
                    case REJECTED_BOUNCE -> rejectedProgram(++this.planEpoch, tick, current, ownerFrame);
                    case RETURN -> throw new IllegalStateException("RETURN 只能由 RETURNING 状态启动");
                };
                installGesture(entity, program);
            }
            applyGesture(entity, state, tick, ownerFrame);
            return false;
        }

        if (this.gesture != null) {
            current = this.gesture.gesture() == Gesture.RETURN
                    ? this.motionState
                    : this.gesture.sample(tick, ownerFrame);
            this.gesture = null;
            entity.clearGestureProgram(++this.planEpoch);
            this.motionState = current;
        }

        DriveCommand command = driveCommand(intent);
        SoulFlight.DrivenStep step = SoulFlight.advanceDriven(
                current,
                command,
                tick,
                (position, radius) -> isClear(owner.level(), entity, position, radius)
        );
        this.motionState = step.state();
        entity.applyProxySample(state, this.motionState);
        return false;
    }

    void stimulate(Stimulus stimulus) {
        if (stimulus != null && stimulus != Stimulus.NONE) {
            this.pendingStimulus = stimulus;
        }
    }

    // 必须在实体生成包前安装推出指令，避免客户端先以伴飞姿态初始化可见 Transform。
    void prepareRelease(
            ServerPlayer owner,
            SoulEntity entity,
            Vec3 launchDirection,
            boolean usesOwnerRenderVelocity
    ) {
        resetForEntity(owner, entity);
        this.launchForward = normalizedOr(launchDirection, new Vec3(0.0, 0.0, 1.0));
        this.pendingStimulus = Stimulus.RELEASED_FROM_INVENTORY;
        installGesture(entity, throwProgram(
                ++this.planEpoch,
                owner.level().getGameTime(),
                this.motionState,
                this.launchForward,
                usesOwnerRenderVelocity
        ));
    }

    void reset() {
        this.brain.reset();
        this.entityUuid = null;
        this.gesture = null;
        this.motionState = KinematicState.ZERO;
        this.previousOwnerPosition = Vec3.ZERO;
        this.previousOwnerVelocity = Vec3.ZERO;
        this.launchForward = new Vec3(0.0, 0.0, 1.0);
        this.ownerIdleTicks = 0;
        this.pendingStimulus = Stimulus.NONE;
    }

    private void resetForEntity(ServerPlayer owner, SoulEntity entity) {
        this.brain.reset();
        this.entityUuid = entity.getUUID();
        this.gesture = null;
        this.motionState = new KinematicState(entity.position(), entity.getDeltaMovement(), Vec3.ZERO);
        this.previousOwnerPosition = owner.position();
        this.previousOwnerVelocity = owner.getDeltaMovement();
        this.launchForward = normalizedOr(owner.getLookAngle(), new Vec3(0.0, 0.0, 1.0));
        this.ownerIdleTicks = 0;
        this.pendingStimulus = Stimulus.NONE;
    }

    private KinematicState currentState(long tick, FrameState ownerFrame) {
        return this.gesture == null || this.gesture.gesture() == Gesture.RETURN
                ? this.motionState
                : this.gesture.sample(tick, ownerFrame);
    }

    private void updateOwnerIdle(ServerPlayer owner) {
        Vec3 position = owner.position();
        boolean moved = position.distanceToSqr(this.previousOwnerPosition) > 0.0025
                || owner.getDeltaMovement().horizontalDistanceSqr() > 0.0009;
        this.ownerIdleTicks = moved ? 0 : Math.min(20 * 60, this.ownerIdleTicks + 1);
        this.previousOwnerPosition = position;
    }

    private SoulCompanionBrain.Perception perception(
            ServerPlayer owner,
            SoulEntity entity,
            KinematicState current,
            long tick
    ) {
        return new SoulCompanionBrain.Perception(
                tick,
                owner.getUUID().hashCode(),
                owner.getEyePosition(),
                owner.getDeltaMovement(),
                owner.getLookAngle(),
                this.ownerIdleTicks,
                current.position(),
                collectInterests(owner, entity, tick)
        );
    }

    private static List<InterestPoint> collectInterests(ServerPlayer owner, SoulEntity soulEntity, long tick) {
        ServerLevel level = owner.level();
        Vec3 ownerCenter = owner.getEyePosition();
        AABB search = AABB.ofSize(ownerCenter, INTEREST_RADIUS * 2.0, INTEREST_RADIUS * 2.0,
                INTEREST_RADIUS * 2.0);
        List<InterestPoint> interests = new ArrayList<>();
        for (LivingEntity candidate : level.getEntitiesOfClass(
                LivingEntity.class,
                search,
                candidate -> candidate != owner && candidate.isAlive() && !candidate.isSpectator()
        )) {
            Vec3 position = candidate.getBoundingBox().getCenter().add(0.0, 0.18, 0.0);
            double distance = position.distanceTo(ownerCenter);
            if (distance > INTEREST_RADIUS) {
                continue;
            }
            InterestKind kind = candidate instanceof Enemy
                    ? InterestKind.HOSTILE
                    : candidate instanceof Mob
                            ? InterestKind.CREATURE
                            : candidate instanceof Player ? InterestKind.PLAYER : null;
            if (kind != null) {
                interests.add(new InterestPoint(
                        kind,
                        candidate.getUUID(),
                        position,
                        Mth.clamp(1.15 - distance / 12.0, 0.25, 1.0)
                ));
            }
        }

        interests.add(new InterestPoint(
                InterestKind.PLAYER,
                owner.getUUID(),
                ownerCenter,
                0.36
        ));
        int locationEpoch = (int) (tick / 80L);
        for (int index = 0; index < 5; index++) {
            Vec3 place = curiosityPlace(ownerCenter, owner.getUUID().hashCode(), locationEpoch, index);
            if (isClear(level, soulEntity, place, 0.24)) {
                interests.add(new InterestPoint(
                        InterestKind.PLACE,
                        null,
                        place,
                        0.45 + unit(mix(locationEpoch * 31 + index * 17)) * 0.45
                ));
            }
        }
        return List.copyOf(interests);
    }

    private static Vec3 curiosityPlace(Vec3 ownerCenter, int identitySeed, int epoch, int index) {
        int seed = mix(identitySeed ^ epoch * 0x9E3779B9 ^ index * 0x632BE5AB);
        double angle = unit(mix(seed ^ 0x165667B1)) * Math.PI * 2.0;
        double radius = 2.6 + unit(mix(seed ^ 0xD3A2646C)) * 6.8;
        double height = -1.6 + unit(mix(seed ^ 0x2C9277B5)) * 4.4;
        return ownerCenter.add(Math.cos(angle) * radius, height, Math.sin(angle) * radius);
    }

    static DriveCommand driveCommand(Intent intent) {
        Limits limits = switch (intent.action()) {
            case REJOIN -> new Limits(0.36, 0.045, 0.0048);
            case INSPECT -> new Limits(0.29, 0.034, 0.0034);
            case ROAM -> new Limits(0.26, 0.030, 0.0030);
            default -> new Limits(0.22, 0.025, 0.0025);
        };
        OrbitProfile profile = intent.orbit();
        if (!profile.enabled()) {
            return DriveCommand.travel(intent.worldTarget(), limits);
        }
        return DriveCommand.orbit(new OrbitField(
                intent.worldTarget(),
                profile.axis(),
                profile.radius(),
                profile.angularSpeed(),
                profile.verticalAmplitude(),
                profile.phaseOffset() - intent.startedTick() * profile.angularSpeed()
        ), limits);
    }

    private void applyGesture(
            SoulEntity entity,
            Soul.State state,
            long tick,
            FrameState ownerFrame
    ) {
        if (this.gesture == null) {
            throw new IllegalStateException("applyGesture 需要已安装的手势");
        }
        KinematicState sample = this.gesture.sample(tick, ownerFrame);
        this.motionState = sample;
        entity.applyProxySample(state, sample);
    }

    // 服务端代理与客户端可见状态独立追随同一有界目标；结束后只发布单调完成里程碑。
    private boolean applyReturn(
            SoulEntity entity,
            Soul.State state,
            long tick,
            FrameState ownerFrame
    ) {
        if (this.gesture == null || this.gesture.gesture() != Gesture.RETURN) {
            throw new IllegalStateException("applyReturn 需要已安装的 RETURN 手势");
        }
        KinematicState target = this.gesture.guidanceTargetAtElapsed(
                tick - this.gesture.startTick(),
                ownerFrame
        );
        KinematicState next = advanceReturnProxy(this.motionState, target);
        this.motionState = next;
        entity.applyProxySample(state, next);
        return isReturnComplete(this.gesture, tick);
    }

    static KinematicState advanceReturnProxy(KinematicState current, KinematicState targetAtTickEnd) {
        int substeps = (int) Math.ceil(1.0 / RETURN_INTEGRATION_STEP_TICKS);
        double step = 1.0 / substeps;
        KinematicState next = current;
        for (int index = 0; index < substeps; index++) {
            KinematicState alignedTarget = SoulFlight.rewind(targetAtTickEnd, 1.0 - index * step);
            next = SoulFlight.advanceTracked(
                    next,
                    alignedTarget,
                    SoulFlight.returnTrackingLimits(next, alignedTarget),
                    step
            ).state();
        }
        return next;
    }

    // RETURN 时长是唯一运动里程碑；低频代理误差不得永久否决完成，库存空间由 Soul 判定。
    static boolean isReturnComplete(GestureProgram gesture, long tick) {
        return gesture.gesture() == Gesture.RETURN
                && gesture.finished(tick);
    }

    private void installGesture(SoulEntity entity, GestureProgram program) {
        this.gesture = program;
        entity.installGestureProgram(program);
    }

    @Nullable
    private static Gesture gestureFor(Action action) {
        return switch (action) {
            case THROW_LAUNCH -> Gesture.THROW;
            case REJECTED_BOUNCE -> Gesture.REJECTED_BOUNCE;
            default -> null;
        };
    }

    static GestureProgram throwProgram(
            long planEpoch,
            long startTick,
            KinematicState current,
            Vec3 launchDirection
    ) {
        return throwProgram(planEpoch, startTick, current, launchDirection, false);
    }

    static GestureProgram throwProgram(
            long planEpoch,
            long startTick,
            KinematicState current,
            Vec3 launchDirection,
            boolean usesOwnerRenderVelocity
    ) {
        Vec3 forward = normalizedOr(launchDirection, new Vec3(0.0, 0.0, 1.0));
        double duration = 15.0;
        Vec3 launchVelocity = current.velocity();
        if (launchVelocity.lengthSqr() < 1.0E-8) {
            launchVelocity = forward.scale(RELEASE_IMPULSE_SPEED);
        }
        Vec3 impulseVelocity = releaseImpulseVelocity(forward);
        Vec3 inheritedVelocity = launchVelocity.subtract(impulseVelocity);
        Vec3 travelDirection = normalizedOr(launchVelocity, forward);
        KinematicState launch = new KinematicState(current.position(), launchVelocity, current.acceleration());
        KinematicState terminal = throwTerminal(current.position(), duration, inheritedVelocity, impulseVelocity);
        return new GestureProgram(
                planEpoch,
                startTick,
                duration,
                Gesture.THROW,
                ReferenceFrame.WORLD,
                launch,
                terminal,
                travelDirection,
                0.0,
                0.0,
                5.2,
                usesOwnerRenderVelocity
        );
    }

    static Vec3 releaseVelocity(Vec3 inheritedVelocity, Vec3 launchDirection) {
        Vec3 inherited = inheritedVelocity == null ? Vec3.ZERO : inheritedVelocity;
        return inherited.add(releaseImpulseVelocity(launchDirection));
    }

    private static Vec3 releaseImpulseVelocity(Vec3 launchDirection) {
        return normalizedOr(launchDirection, new Vec3(0.0, 0.0, 1.0)).scale(RELEASE_IMPULSE_SPEED);
    }

    private static KinematicState throwTerminal(
            Vec3 startPosition,
            double duration,
            Vec3 inheritedVelocity,
            Vec3 impulseVelocity
    ) {
        return new KinematicState(
                startPosition
                        .add(inheritedVelocity.scale(duration))
                        .add(impulseVelocity.scale(duration * 0.5)),
                inheritedVelocity,
                Vec3.ZERO
        );
    }

    private static GestureProgram returnProgram(
            long planEpoch,
            long startTick,
            KinematicState current,
            FrameState ownerFrame
    ) {
        KinematicState localStart = ownerFrame.toLocal(current);
        KinematicState terminal = KinematicState.ZERO;
        Vec3 displacement = terminal.position().subtract(localStart.position());
        double distance = displacement.length();
        double duration = Mth.clamp(17.0 + distance * 1.65, 18.0, 40.0);
        double winding = (planEpoch & 1L) == 0L ? -1.0 : 1.0;
        double turns = winding * Mth.clamp(1.45 + distance * 0.14, 1.6, 3.4);
        return new GestureProgram(
                planEpoch,
                startTick,
                duration,
                Gesture.RETURN,
                ReferenceFrame.OWNER_TRANSLATION,
                localStart,
                terminal,
                normalizedOr(displacement, new Vec3(0.0, -1.0, 0.0)),
                Mth.clamp(distance * 0.10, 0.22, 0.52),
                turns,
                winding * 5.2,
                false
        );
    }

    private static GestureProgram rejectedProgram(
            long planEpoch,
            long startTick,
            KinematicState current,
            FrameState ownerFrame
    ) {
        Vec3 away = normalizedOr(
                current.position().subtract(ownerFrame.origin()).add(0.0, 0.28, 0.0),
                new Vec3(0.0, 0.25, -1.0)
        );
        double duration = 12.0;
        double speed = 0.35;
        KinematicState rebound = new KinematicState(current.position(), away.scale(speed), Vec3.ZERO);
        KinematicState terminal = new KinematicState(
                current.position().add(away.scale(speed * duration * 0.5)),
                Vec3.ZERO,
                Vec3.ZERO
        );
        return new GestureProgram(
                planEpoch,
                startTick,
                duration,
                Gesture.REJECTED_BOUNCE,
                ReferenceFrame.WORLD,
                rebound,
                terminal,
                away,
                0.0,
                0.0,
                0.0,
                false
        );
    }

    private static boolean isClear(
            ServerLevel level,
            SoulEntity entity,
            Vec3 center,
            double radius
    ) {
        return level.noBlockCollision(entity, AABB.ofSize(center, radius * 2.0, radius * 2.0, radius * 2.0));
    }

    private static Vec3 normalizedOr(Vec3 value, Vec3 fallback) {
        return value == null || !Double.isFinite(value.lengthSqr()) || value.lengthSqr() < 1.0E-8
                ? fallback
                : value.normalize();
    }

    private static int mix(int value) {
        value ^= value >>> 16;
        value *= 0x7FEB352D;
        value ^= value >>> 15;
        value *= 0x846CA68B;
        return value ^ value >>> 16;
    }

    private static double unit(int value) {
        return (value & 0x7FFFFFFF) / (double) Integer.MAX_VALUE;
    }

}
