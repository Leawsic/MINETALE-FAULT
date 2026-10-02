package cn.jehorstudio.minetale.content.player.soul;

import java.util.Objects;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

// Soul 连续运动数学：有限 jerk 目标驱动、伴飞力场与有限手势指令。
final class SoulFlight {
    private static final double EPSILON = 1.0E-8;
    private static final double COLLISION_RADIUS = 0.22;
    private static final double RETURN_TRACKING_FREQUENCY = 0.30;
    private static final double RETURN_BASE_ACCELERATION = 0.20;
    private static final double RETURN_BASE_JERK = 0.12;
    private static final double RETURN_BRAKING_ALLOWANCE = 4.0;
    private static final double RETURN_ACCELERATION_RAMP_TICKS = 2.0;
    private static final double DRIVEN_TRACKING_FREQUENCY = 0.10;
    private static final double DRIVEN_SPEED_HORIZON_FACTOR = 2.70;
    private static final Vec3 WORLD_UP = new Vec3(0.0, 1.0, 0.0);
    private static final Vec3[] ESCAPE_DIRECTIONS = {
            new Vec3(1.0, 0.0, 0.0),
            new Vec3(-1.0, 0.0, 0.0),
            new Vec3(0.0, 1.0, 0.0),
            new Vec3(0.0, -1.0, 0.0),
            new Vec3(0.0, 0.0, 1.0),
            new Vec3(0.0, 0.0, -1.0)
    };

    private SoulFlight() {
    }

    // 三个重合实极点形成临界阻尼三阶误差系统；移动目标只能改变后续 jerk，不能覆盖真实状态。
    static TrackedStep advanceTracked(
            KinematicState current,
            KinematicState target,
            TrackingLimits limits,
            double stepTicks
    ) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(limits, "limits");
        if (!Double.isFinite(stepTicks) || stepTicks <= 0.0) {
            throw new IllegalArgumentException("stepTicks 必须为有限正数");
        }

        double frequency = limits.frequency();
        Vec3 positionError = target.position().subtract(current.position());
        Vec3 velocityError = target.velocity().subtract(current.velocity());
        Vec3 accelerationError = target.acceleration().subtract(current.acceleration());
        Vec3 requestedJerk = positionError.scale(frequency * frequency * frequency)
                .add(velocityError.scale(3.0 * frequency * frequency))
                .add(accelerationError.scale(3.0 * frequency));
        Vec3 jerk = boundedJerk(
                current.acceleration(),
                requestedJerk,
                limits.maximumJerk(),
                limits.maximumAcceleration(),
                stepTicks
        );

        double step2 = stepTicks * stepTicks;
        double step3 = step2 * stepTicks;
        KinematicState next = new KinematicState(
                current.position()
                        .add(current.velocity().scale(stepTicks))
                        .add(current.acceleration().scale(0.5 * step2))
                        .add(jerk.scale(step3 / 6.0)),
                current.velocity()
                        .add(current.acceleration().scale(stepTicks))
                        .add(jerk.scale(0.5 * step2)),
                current.acceleration().add(jerk.scale(stepTicks))
        );
        return new TrackedStep(next, jerk);
    }

    // RETURNING 加速度上界由相对速率与四格制动余量推导，并仍通过有限 jerk 连续建立。
    static TrackingLimits returnTrackingLimits(KinematicState current, KinematicState target) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(target, "target");
        double relativeSpeed = current.velocity().subtract(target.velocity()).length();
        double brakingAcceleration = relativeSpeed * relativeSpeed / (2.0 * RETURN_BRAKING_ALLOWANCE);
        double maximumAcceleration = Math.max(
                RETURN_BASE_ACCELERATION,
                target.acceleration().length() + brakingAcceleration
        );
        double maximumJerk = Math.max(
                RETURN_BASE_JERK,
                maximumAcceleration / RETURN_ACCELERATION_RAMP_TICKS
        );
        return new TrackingLimits(
                RETURN_TRACKING_FREQUENCY,
                maximumAcceleration,
                maximumJerk
        );
    }

    // 按常加速度从端点二阶状态回推，使移动目标与离散小段起点对齐。
    static KinematicState rewind(KinematicState endpoint, double ticks) {
        Objects.requireNonNull(endpoint, "endpoint");
        if (!Double.isFinite(ticks) || ticks < 0.0) {
            throw new IllegalArgumentException("ticks 必须为有限非负数");
        }
        return new KinematicState(
                endpoint.position()
                        .subtract(endpoint.velocity().scale(ticks))
                        .add(endpoint.acceleration().scale(0.5 * ticks * ticks)),
                endpoint.velocity().subtract(endpoint.acceleration().scale(ticks)),
                endpoint.acceleration()
        );
    }

    // 伴飞与盘旋共用同一临界阻尼吸引子；远距限速不制造到达死区，避免 jerk 饱和形成极限环。
    static DrivenStep advanceDriven(
            KinematicState current,
            DriveCommand command,
            double tick,
            MotionEnvironment environment
    ) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(command, "command");
        environment = environment == null ? MotionEnvironment.ALWAYS_CLEAR : environment;

        KinematicState desired = drivenTarget(current, command, tick);
        Vec3 avoidance = obstacleAcceleration(current, command, environment);
        KinematicState trackingTarget = new KinematicState(
                desired.position(),
                desired.velocity(),
                desired.acceleration().add(avoidance)
        );
        TrackedStep tracked = advanceTracked(
                current,
                trackingTarget,
                new TrackingLimits(
                        DRIVEN_TRACKING_FREQUENCY,
                        command.limits().maximumAcceleration(),
                        command.limits().maximumJerk()
                ),
                1.0
        );
        KinematicState next = tracked.state();

        if (!environment.isClear(next.position(), COLLISION_RADIUS)) {
            KinematicState resolved = resolveCollision(current, next.velocity(), command, environment);
            return new DrivenStep(resolved, tracked.jerk());
        }
        return new DrivenStep(next, tracked.jerk());
    }

    private static KinematicState drivenTarget(KinematicState current, DriveCommand command, double tick) {
        KinematicState requested = command.orbit() == null
                ? new KinematicState(command.destination(), Vec3.ZERO, Vec3.ZERO)
                : command.orbit().at(tick + 1.0);
        Vec3 targetVelocity = clampLength(
                requested.velocity(),
                command.limits().maximumSpeed() * 0.65
        );
        double remainingSpeed = Math.max(
                command.limits().maximumSpeed() * 0.15,
                command.limits().maximumSpeed() - targetVelocity.length()
        );
        double horizon = DRIVEN_SPEED_HORIZON_FACTOR
                * remainingSpeed
                / DRIVEN_TRACKING_FREQUENCY;
        Vec3 boundedDisplacement = clampLength(
                requested.position().subtract(current.position()),
                horizon
        );
        return new KinematicState(
                current.position().add(boundedDisplacement),
                targetVelocity,
                requested.acceleration()
        );
    }

    private static Vec3 obstacleAcceleration(
            KinematicState current,
            DriveCommand command,
            MotionEnvironment environment
    ) {
        Vec3 probe = current.position()
                .add(current.velocity().scale(3.0))
                .add(current.acceleration().scale(3.0));
        if (environment.isClear(probe, COLLISION_RADIUS)) {
            return Vec3.ZERO;
        }

        Vec3 destinationDirection = normalizedOr(
                command.destination().subtract(current.position()),
                WORLD_UP
        );
        Vec3 best = WORLD_UP;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (Vec3 direction : ESCAPE_DIRECTIONS) {
            Vec3 candidate = current.position().add(direction.scale(0.72));
            if (!environment.isClear(candidate, COLLISION_RADIUS)) {
                continue;
            }
            double score = direction.dot(destinationDirection) * 0.45 + direction.y * 0.18;
            if (score > bestScore) {
                best = direction;
                bestScore = score;
            }
        }
        return best.scale(command.limits().maximumAcceleration() * 0.92);
    }

    private static KinematicState resolveCollision(
            KinematicState current,
            Vec3 proposedVelocity,
            DriveCommand command,
            MotionEnvironment environment
    ) {
        Vec3[] slides = {
                new Vec3(proposedVelocity.x, 0.0, 0.0),
                new Vec3(0.0, proposedVelocity.y, 0.0),
                new Vec3(0.0, 0.0, proposedVelocity.z),
                new Vec3(proposedVelocity.x, proposedVelocity.y, 0.0),
                new Vec3(proposedVelocity.x, 0.0, proposedVelocity.z),
                new Vec3(0.0, proposedVelocity.y, proposedVelocity.z)
        };
        Vec3 best = Vec3.ZERO;
        double bestProgress = 0.0;
        Vec3 towardGoal = normalizedOr(command.destination().subtract(current.position()), WORLD_UP);
        for (Vec3 slide : slides) {
            Vec3 candidate = current.position().add(slide);
            if (!environment.isClear(candidate, COLLISION_RADIUS)) {
                continue;
            }
            double progress = slide.dot(towardGoal) + slide.length() * 0.12;
            if (progress > bestProgress) {
                best = slide;
                bestProgress = progress;
            }
        }
        Vec3 velocity = best.lengthSqr() < EPSILON ? proposedVelocity.scale(0.18) : best;
        Vec3 position = best.lengthSqr() < EPSILON ? current.position() : current.position().add(best);
        Vec3 acceleration = velocity.subtract(current.velocity());
        return new KinematicState(
                position,
                clampLength(velocity, command.limits().maximumSpeed()),
                clampLength(acceleration, command.limits().maximumAcceleration())
        );
    }

    record KinematicState(Vec3 position, Vec3 velocity, Vec3 acceleration) {
        static final KinematicState ZERO = new KinematicState(Vec3.ZERO, Vec3.ZERO, Vec3.ZERO);

        KinematicState {
            position = finiteOrZero(position);
            velocity = finiteOrZero(velocity);
            acceleration = finiteOrZero(acceleration);
        }

        KinematicState add(KinematicState other) {
            return new KinematicState(
                    this.position.add(other.position),
                    this.velocity.add(other.velocity),
                    this.acceleration.add(other.acceleration)
            );
        }
    }

    record Limits(double maximumSpeed, double maximumAcceleration, double maximumJerk) {
        Limits {
            requirePositive(maximumSpeed, "maximumSpeed");
            requirePositive(maximumAcceleration, "maximumAcceleration");
            requirePositive(maximumJerk, "maximumJerk");
        }
    }

    record TrackingLimits(double frequency, double maximumAcceleration, double maximumJerk) {
        TrackingLimits {
            requirePositive(frequency, "frequency");
            requirePositive(maximumAcceleration, "maximumAcceleration");
            requirePositive(maximumJerk, "maximumJerk");
        }
    }

    record TrackedStep(KinematicState state, Vec3 jerk) {
        TrackedStep {
            Objects.requireNonNull(state, "state");
            jerk = finiteOrZero(jerk);
        }
    }

    record DriveCommand(Vec3 destination, @Nullable OrbitField orbit, Limits limits) {
        DriveCommand {
            destination = finiteOrZero(destination);
            Objects.requireNonNull(limits, "limits");
        }

        static DriveCommand travel(Vec3 destination, Limits limits) {
            return new DriveCommand(destination, null, limits);
        }

        static DriveCommand orbit(OrbitField orbit, Limits limits) {
            Objects.requireNonNull(orbit, "orbit");
            return new DriveCommand(orbit.center(), orbit, limits);
        }
    }

    // 三维螺旋是力场吸引子而非轨迹，不要把 Soul 直接放到解析曲线上。
    record OrbitField(
            Vec3 center,
            Vec3 axis,
            double radius,
            double angularSpeed,
            double verticalAmplitude,
            double phaseOffset
    ) {
        OrbitField {
            center = finiteOrZero(center);
            axis = normalizedOr(axis, WORLD_UP);
            requirePositive(radius, "orbit.radius");
            if (!Double.isFinite(angularSpeed) || Math.abs(angularSpeed) < 1.0E-6) {
                throw new IllegalArgumentException("orbit.angularSpeed 必须为有限非零值");
            }
            verticalAmplitude = Math.max(0.0, verticalAmplitude);
            phaseOffset = Double.isFinite(phaseOffset) ? phaseOffset : 0.0;
        }

        KinematicState at(double tick) {
            Vec3 basisRight = perpendicular(this.axis);
            Vec3 basisUp = normalizedOr(this.axis.cross(basisRight), WORLD_UP);
            double phase = this.phaseOffset + this.angularSpeed * tick;
            double halfPhase = phase * 0.5 + this.phaseOffset * 0.37;
            double cosine = Math.cos(phase);
            double sine = Math.sin(phase);
            double axial = this.verticalAmplitude * Math.sin(halfPhase);
            double axialVelocity = this.verticalAmplitude * 0.5 * this.angularSpeed * Math.cos(halfPhase);
            double axialAcceleration = -this.verticalAmplitude * 0.25
                    * this.angularSpeed * this.angularSpeed * Math.sin(halfPhase);
            Vec3 position = this.center
                    .add(basisRight.scale(this.radius * cosine))
                    .add(basisUp.scale(this.radius * sine))
                    .add(this.axis.scale(axial));
            Vec3 velocity = basisRight.scale(-this.radius * this.angularSpeed * sine)
                    .add(basisUp.scale(this.radius * this.angularSpeed * cosine))
                    .add(this.axis.scale(axialVelocity));
            Vec3 acceleration = basisRight.scale(-this.radius * this.angularSpeed * this.angularSpeed * cosine)
                    .add(basisUp.scale(-this.radius * this.angularSpeed * this.angularSpeed * sine))
                    .add(this.axis.scale(axialAcceleration));
            return new KinematicState(position, velocity, acceleration);
        }
    }

    record DrivenStep(KinematicState state, Vec3 jerk) {
    }

    @FunctionalInterface
    interface MotionEnvironment {
        MotionEnvironment ALWAYS_CLEAR = (position, radius) -> true;

        boolean isClear(Vec3 position, double radius);
    }

    record FrameState(Vec3 origin, Vec3 velocity, Vec3 acceleration) {
        static final FrameState WORLD = new FrameState(Vec3.ZERO, Vec3.ZERO, Vec3.ZERO);

        FrameState {
            origin = finiteOrZero(origin);
            velocity = finiteOrZero(velocity);
            acceleration = finiteOrZero(acceleration);
        }

        KinematicState toLocal(KinematicState world) {
            return new KinematicState(
                    world.position().subtract(this.origin),
                    world.velocity().subtract(this.velocity),
                    world.acceleration().subtract(this.acceleration)
            );
        }

        KinematicState toWorld(KinematicState local) {
            return new KinematicState(
                    local.position().add(this.origin),
                    local.velocity().add(this.velocity),
                    local.acceleration().add(this.acceleration)
            );
        }
    }

    // Gesture 仅表示具有明确起终状态的投掷、召回与满包弹回。
    record GestureProgram(
            long planEpoch,
            long startTick,
            double durationTicks,
            Gesture gesture,
            ReferenceFrame referenceFrame,
            KinematicState start,
            KinematicState terminal,
            Vec3 spiralAxis,
            double spiralRadius,
            double spiralTurns,
            double rollRadiansPerBlock,
            boolean usesOwnerRenderVelocity
    ) {
        GestureProgram {
            durationTicks = Math.max(1.0, durationTicks);
            Objects.requireNonNull(gesture, "gesture");
            Objects.requireNonNull(referenceFrame, "referenceFrame");
            start = start == null ? KinematicState.ZERO : start;
            terminal = terminal == null ? KinematicState.ZERO : terminal;
            spiralAxis = normalizedOr(spiralAxis, normalizedOr(
                    terminal.position().subtract(start.position()),
                    new Vec3(0.0, 0.0, 1.0)
            ));
            spiralRadius = Math.max(0.0, spiralRadius);
            spiralTurns = Double.isFinite(spiralTurns) ? spiralTurns : 0.0;
            rollRadiansPerBlock = Double.isFinite(rollRadiansPerBlock) ? rollRadiansPerBlock : 0.0;
        }

        KinematicState sample(double gameTick, FrameState currentFrame) {
            return sampleAtElapsed(gameTick - this.startTick, currentFrame);
        }

        // THROW 使用客户端锁存的可见惯性；冲量来自动作端点速度差，不能读取服务端玩家速度。
        GestureProgram withThrowInheritedVelocity(Vec3 inheritedVelocity) {
            if (this.gesture != Gesture.THROW) {
                throw new IllegalStateException("只有 THROW 指令能够替换继承速度");
            }
            Vec3 inherited = finiteOrZero(inheritedVelocity);
            Vec3 impulse = this.start.velocity().subtract(this.terminal.velocity());
            KinematicState adjustedStart = new KinematicState(
                    this.start.position(),
                    inherited.add(impulse),
                    this.start.acceleration()
            );
            KinematicState adjustedTerminal = new KinematicState(
                    adjustedStart.position()
                            .add(inherited.scale(this.durationTicks))
                            .add(impulse.scale(this.durationTicks * 0.5)),
                    inherited,
                    this.terminal.acceleration()
            );
            Vec3 adjustedTravelDirection = normalizedOr(adjustedStart.velocity(), this.spiralAxis);
            return new GestureProgram(
                    this.planEpoch,
                    this.startTick,
                    this.durationTicks,
                    this.gesture,
                    this.referenceFrame,
                    adjustedStart,
                    adjustedTerminal,
                    adjustedTravelDirection,
                    this.spiralRadius,
                    this.spiralTurns,
                    this.rollRadiansPerBlock,
                    this.usesOwnerRenderVelocity
            );
        }

        KinematicState sampleAtElapsed(double elapsedTicks, FrameState currentFrame) {
            KinematicState local = sampleLocalAtElapsed(elapsedTicks);
            return this.referenceFrame == ReferenceFrame.WORLD ? local : currentFrame.toWorld(local);
        }

        KinematicState sampleLocalAtElapsed(double elapsedTicks) {
            double elapsed = Mth.clamp(elapsedTicks, 0.0, this.durationTicks);
            if (this.gesture == Gesture.RETURN) {
                return boundedReturnGuide(elapsed);
            }
            KinematicState base = quintic(this.start, this.terminal, this.durationTicks, elapsed);
            if (this.spiralRadius <= EPSILON || Math.abs(this.spiralTurns) <= EPSILON) {
                return base;
            }
            return base.add(spiralOffset(this, elapsed));
        }

        // 返回的是有界引导目标而非可见轨迹；真实 Transform 始终由 Action Controller 积分。
        // RETURN 使用零端点导数基线叠加有限螺旋，THROW/REJECTED 直接跟随终点。
        KinematicState guidanceTargetAtElapsed(double elapsedTicks, FrameState currentFrame) {
            double elapsed = Mth.clamp(elapsedTicks, 0.0, this.durationTicks);
            KinematicState localTarget = this.gesture == Gesture.RETURN
                    ? boundedReturnGuide(elapsed)
                    : this.terminal;
            return this.referenceFrame == ReferenceFrame.WORLD
                    ? localTarget
                    : currentFrame.toWorld(localTarget);
        }

        private KinematicState boundedReturnGuide(double elapsed) {
            KinematicState boundedBase = quintic(
                    new KinematicState(this.start.position(), Vec3.ZERO, Vec3.ZERO),
                    new KinematicState(this.terminal.position(), Vec3.ZERO, Vec3.ZERO),
                    this.durationTicks,
                    elapsed
            );
            return this.spiralRadius <= EPSILON || Math.abs(this.spiralTurns) <= EPSILON
                    ? boundedBase
                    : boundedBase.add(spiralOffset(this, elapsed));
        }

        boolean finished(double gameTick) {
            return gameTick - this.startTick >= this.durationTicks;
        }
    }

    enum Gesture {
        THROW,
        RETURN,
        REJECTED_BOUNCE
    }

    enum ReferenceFrame {
        WORLD,
        OWNER_TRANSLATION
    }

    static KinematicState quintic(
            KinematicState start,
            KinematicState terminal,
            double duration,
            double elapsed
    ) {
        double total = Math.max(1.0E-4, duration);
        double time = Mth.clamp(elapsed, 0.0, total);
        Vec3 c0 = start.position();
        Vec3 c1 = start.velocity();
        Vec3 c2 = start.acceleration().scale(0.5);
        Vec3 d0 = terminal.position()
                .subtract(c0)
                .subtract(c1.scale(total))
                .subtract(c2.scale(total * total));
        Vec3 d1 = terminal.velocity()
                .subtract(c1)
                .subtract(c2.scale(2.0 * total));
        Vec3 d2 = terminal.acceleration().subtract(c2.scale(2.0));
        double t2 = total * total;
        double t3 = t2 * total;
        double t4 = t3 * total;
        double t5 = t4 * total;
        Vec3 c3 = d0.scale(10.0)
                .subtract(d1.scale(4.0 * total))
                .add(d2.scale(0.5 * t2))
                .scale(1.0 / t3);
        Vec3 c4 = d0.scale(-15.0)
                .add(d1.scale(7.0 * total))
                .subtract(d2.scale(t2))
                .scale(1.0 / t4);
        Vec3 c5 = d0.scale(6.0)
                .subtract(d1.scale(3.0 * total))
                .add(d2.scale(0.5 * t2))
                .scale(1.0 / t5);

        double x2 = time * time;
        double x3 = x2 * time;
        double x4 = x3 * time;
        double x5 = x4 * time;
        Vec3 position = c0
                .add(c1.scale(time))
                .add(c2.scale(x2))
                .add(c3.scale(x3))
                .add(c4.scale(x4))
                .add(c5.scale(x5));
        Vec3 velocity = c1
                .add(c2.scale(2.0 * time))
                .add(c3.scale(3.0 * x2))
                .add(c4.scale(4.0 * x3))
                .add(c5.scale(5.0 * x4));
        Vec3 acceleration = c2.scale(2.0)
                .add(c3.scale(6.0 * time))
                .add(c4.scale(12.0 * x2))
                .add(c5.scale(20.0 * x3));
        return new KinematicState(position, velocity, acceleration);
    }

    private static KinematicState spiralOffset(GestureProgram program, double elapsed) {
        double duration = program.durationTicks();
        double progress = Mth.clamp(elapsed / duration, 0.0, 1.0);
        double p2 = progress * progress;
        double p3 = p2 * progress;
        double p4 = p3 * progress;
        double p5 = p4 * progress;
        double p6 = p5 * progress;
        double envelope = 64.0 * (p3 - 3.0 * p4 + 3.0 * p5 - p6);
        double envelopeDerivative = 192.0 * p2 - 768.0 * p3 + 960.0 * p4 - 384.0 * p5;
        double envelopeSecondDerivative = 384.0 * progress - 2304.0 * p2 + 3840.0 * p3 - 1920.0 * p4;

        Vec3 axis = program.spiralAxis();
        Vec3 basisRight = perpendicular(axis);
        Vec3 basisUp = normalizedOr(axis.cross(basisRight), WORLD_UP);
        double omega = Math.PI * 2.0 * program.spiralTurns() / duration;
        double phase = omega * elapsed;
        double cosine = Math.cos(phase);
        double sine = Math.sin(phase);
        Vec3 radial = basisRight.scale(cosine).add(basisUp.scale(sine));
        Vec3 tangent = basisRight.scale(-sine).add(basisUp.scale(cosine));
        double radius = program.spiralRadius();
        double envelopeVelocity = envelopeDerivative / duration;
        double envelopeAcceleration = envelopeSecondDerivative / (duration * duration);
        return new KinematicState(
                radial.scale(radius * envelope),
                radial.scale(radius * envelopeVelocity)
                        .add(tangent.scale(radius * envelope * omega)),
                radial.scale(radius * (envelopeAcceleration - envelope * omega * omega))
                        .add(tangent.scale(radius * 2.0 * envelopeVelocity * omega))
        );
    }

    private static Vec3 perpendicular(Vec3 axis) {
        Vec3 reference = Math.abs(axis.y) < 0.84 ? WORLD_UP : new Vec3(1.0, 0.0, 0.0);
        return normalizedOr(reference.cross(axis), new Vec3(1.0, 0.0, 0.0));
    }

    private static Vec3 clampLength(Vec3 value, double maximum) {
        double lengthSqr = value.lengthSqr();
        if (lengthSqr <= maximum * maximum || lengthSqr < EPSILON) {
            return value;
        }
        return value.scale(maximum / Math.sqrt(lengthSqr));
    }

    private static Vec3 boundedJerk(
            Vec3 acceleration,
            Vec3 requestedJerk,
            double maximumJerk,
            double maximumAcceleration,
            double step
    ) {
        Vec3 jerk = clampLength(requestedJerk, maximumJerk);
        double accelerationLengthSqr = acceleration.lengthSqr();
        if (accelerationLengthSqr > maximumAcceleration * maximumAcceleration) {
            Vec3 proposedAcceleration = acceleration.add(jerk.scale(step));
            if (proposedAcceleration.lengthSqr() >= accelerationLengthSqr) {
                return acceleration.normalize().scale(-maximumJerk);
            }
            return jerk;
        }
        Vec3 boundedAcceleration = clampLength(
                acceleration.add(jerk.scale(step)),
                maximumAcceleration
        );
        return boundedAcceleration.subtract(acceleration).scale(1.0 / step);
    }

    private static Vec3 normalizedOr(Vec3 value, Vec3 fallback) {
        Vec3 safe = finiteOrZero(value);
        return safe.lengthSqr() < EPSILON ? fallback : safe.normalize();
    }

    private static Vec3 finiteOrZero(Vec3 value) {
        return value == null || !Double.isFinite(value.lengthSqr()) ? Vec3.ZERO : value;
    }

    private static void requirePositive(double value, String name) {
        if (!Double.isFinite(value) || value <= 0.0) {
            throw new IllegalArgumentException(name + " 必须为有限正数");
        }
    }
}
