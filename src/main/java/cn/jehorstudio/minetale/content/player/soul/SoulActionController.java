package cn.jehorstudio.minetale.content.player.soul;

import cn.jehorstudio.minetale.content.player.soul.SoulFlight.KinematicState;
import cn.jehorstudio.minetale.content.player.soul.SoulFlight.TrackedStep;
import cn.jehorstudio.minetale.content.player.soul.SoulFlight.TrackingLimits;
import java.util.Objects;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;

// 可见 Transform 的唯一所有者；Action 只提供参考运动与姿态，不能直接写变换。
// 运动模式以有限 jerk 推进二阶状态；HELD/ATTACHED 则刚性采用各自玩家锚点。
final class SoulActionController {
    static final double MAXIMUM_STEP_TICKS = 0.125;
    static final double COMPANION_MAXIMUM_JERK = 0.0032;
    static final double GESTURE_MAXIMUM_JERK = 0.1200;
    static final double RETURN_BASE_MAXIMUM_ACCELERATION = 0.20;
    static final double MAXIMUM_ANGULAR_JERK = 0.055;
    static final double ATTACHMENT_HANDOFF_TICKS = 18.0;
    static final double ATTACHMENT_BRAKING_TICKS = 3.0;

    private static final double EPSILON = 1.0E-9;
    private static final Vec3 LOCAL_TIP = new Vec3(0.0, -1.0, 0.0);
    private static final Vec3 LOCAL_FACE = new Vec3(1.0, 0.0, 0.0);
    private static final Vec3 WORLD_DOWN = new Vec3(0.0, -1.0, 0.0);
    private static final Vec3 DEFAULT_FACE = new Vec3(1.0, 0.0, 0.0);

    private Pose pose;
    private double lastSampleTick = Double.NaN;
    private Vec3 companionHeading = DEFAULT_FACE;
    private Kind lastKind;
    private AttachmentHandoff attachmentHandoff;

    Pose sample(double sampleTick, ActionFrame action) {
        Objects.requireNonNull(action, "action");
        if (!Double.isFinite(sampleTick)) {
            throw new IllegalArgumentException("sampleTick 必须为有限值");
        }
        if (this.pose == null) {
            this.pose = initialPose(action);
            this.lastSampleTick = sampleTick;
            this.lastKind = action.kind();
            return this.pose;
        }

        boolean attachment = isAttachment(action.kind());
        if (attachment && this.lastKind == Kind.RETURN && this.attachmentHandoff == null) {
            this.attachmentHandoff = AttachmentHandoff.start(
                    this.lastSampleTick,
                    this.pose,
                    action
            );
        } else if (!attachment) {
            this.attachmentHandoff = null;
        }

        if (attachment && this.attachmentHandoff == null) {
            this.pose = rigidPose(action);
            this.lastSampleTick = sampleTick;
            this.lastKind = action.kind();
            return this.pose;
        }
        if (sampleTick <= this.lastSampleTick) {
            this.lastKind = action.kind();
            return this.pose;
        }

        if (attachment) {
            this.pose = this.attachmentHandoff.sample(sampleTick, action);
            if (this.attachmentHandoff.finished(sampleTick)) {
                this.attachmentHandoff = null;
            }
            this.lastSampleTick = sampleTick;
            this.lastKind = action.kind();
            return this.pose;
        }

        double elapsedSinceLastSample = sampleTick - this.lastSampleTick;
        int steps = Math.max(1, (int) Math.ceil(elapsedSinceLastSample / MAXIMUM_STEP_TICKS));
        double step = elapsedSinceLastSample / steps;
        for (int index = 0; index < steps; index++) {
            double untilSample = elapsedSinceLastSample - index * step;
            this.pose = integrate(this.pose, action, step, untilSample);
        }
        this.lastSampleTick = sampleTick;
        this.lastKind = action.kind();
        return this.pose;
    }

    private Pose initialPose(ActionFrame action) {
        KinematicState reference = action.initialHint();
        Vec3 tip = switch (action.kind()) {
            case COMPANION -> WORLD_DOWN;
            case HELD -> action.poseTip();
            default -> gestureTangent(reference.velocity(), action.travelDirection());
        };
        Vec3 face = switch (action.kind()) {
            case COMPANION -> companionFace(reference.velocity());
            case HELD -> action.poseFace();
            default -> perpendicularFace(tip, new Vec3(0.0, 1.0, 0.0));
        };
        Quaterniond rotation = orientation(tip, face);
        Vec3 angularVelocity = action.gestureAngularVelocity(tip, reference.velocity().length());
        return new Pose(
                reference.position(),
                reference.velocity(),
                reference.acceleration(),
                rotation,
                angularVelocity,
                Vec3.ZERO
        );
    }

    private static Pose rigidPose(ActionFrame action) {
        KinematicState reference = action.target();
        return new Pose(
                reference.position(),
                reference.velocity(),
                reference.acceleration(),
                orientation(action.poseTip(), action.poseFace()),
                Vec3.ZERO,
                Vec3.ZERO
        );
    }

    private Pose integrate(Pose current, ActionFrame action, double step, double untilSample) {
        KinematicState alignedTarget = action.kind() == Kind.RETURN
                ? SoulFlight.rewind(action.target(), Math.max(0.0, untilSample))
                : action.target();
        AngularLimits angularLimits = AngularLimits.forAction(action.kind());
        TrackedStep linearStep = SoulFlight.advanceTracked(
                new KinematicState(current.position(), current.velocity(), current.acceleration()),
                alignedTarget,
                trackingLimits(action.kind(), current, alignedTarget),
                step
        );
        KinematicState nextLinear = linearStep.state();

        double step2 = step * step;

        AngularDemand angularDemand = desiredAngularDemand(current, action);
        Vec3 desiredAngularAcceleration = angularDemand.orientationError()
                .scale(angularLimits.orientationStiffness())
                .add(angularDemand.angularVelocity().subtract(current.angularVelocity())
                        .scale(angularLimits.angularDamping()));
        desiredAngularAcceleration = clampLength(
                desiredAngularAcceleration,
                angularLimits.maximumAngularAcceleration()
        );
        Vec3 angularJerk = clampLength(
                desiredAngularAcceleration.subtract(current.angularAcceleration()),
                MAXIMUM_ANGULAR_JERK
        );
        Quaterniond nextRotation = integrateQuaternion(
                current.rotation(),
                current.angularVelocity(),
                current.angularAcceleration(),
                angularJerk,
                step
        );
        Vec3 nextAngularVelocity = current.angularVelocity()
                .add(current.angularAcceleration().scale(step))
                .add(angularJerk.scale(0.5 * step2));
        Vec3 nextAngularAcceleration = current.angularAcceleration().add(angularJerk.scale(step));

        return new Pose(
                nextLinear.position(),
                nextLinear.velocity(),
                nextLinear.acceleration(),
                nextRotation,
                nextAngularVelocity,
                nextAngularAcceleration
        );
    }

    private AngularDemand desiredAngularDemand(Pose current, ActionFrame action) {
        if (action.kind() != Kind.COMPANION) {
            Vec3 desiredTip = gestureTangent(current.velocity(), current.tipAxis());
            Vec3 orientationError = rotationBetween(current.tipAxis(), desiredTip);
            Vec3 desiredAngularVelocity = action.gestureAngularVelocity(
                    current.tipAxis(),
                    current.velocity().length()
            );
            return new AngularDemand(orientationError, desiredAngularVelocity);
        }

        Vec3 face = companionFace(current.velocity());
        Quaterniond desiredRotation = orientation(WORLD_DOWN, face);
        return new AngularDemand(
                quaternionError(current.rotation(), desiredRotation),
                Vec3.ZERO
        );
    }

    private Vec3 companionFace(Vec3 velocity) {
        Vec3 horizontal = new Vec3(velocity.x, 0.0, velocity.z);
        if (horizontal.lengthSqr() >= 0.025 * 0.025) {
            this.companionHeading = horizontal.normalize();
        }
        return this.companionHeading;
    }

    enum Kind {
        COMPANION,
        ATTACHED,
        HELD,
        THROW,
        RETURN,
        REJECTED_BOUNCE
    }

    record ActionFrame(
            Kind kind,
            KinematicState initialHint,
            KinematicState target,
            Vec3 travelDirection,
            double rollRadiansPerBlock,
            Vec3 poseTip,
            Vec3 poseFace
    ) {
        ActionFrame {
            Objects.requireNonNull(kind, "kind");
            initialHint = initialHint == null ? KinematicState.ZERO : initialHint;
            target = target == null ? initialHint : target;
            travelDirection = kind == Kind.COMPANION
                    ? WORLD_DOWN
                    : normalizedOr(travelDirection, new Vec3(0.0, 0.0, 1.0));
            rollRadiansPerBlock = Double.isFinite(rollRadiansPerBlock) ? rollRadiansPerBlock : 0.0;
            poseTip = normalizedOr(poseTip, WORLD_DOWN);
            poseFace = perpendicularFace(poseTip, normalizedOr(poseFace, DEFAULT_FACE));
        }

        static ActionFrame companion(KinematicState reference) {
            return new ActionFrame(
                    Kind.COMPANION,
                    reference,
                    reference,
                    WORLD_DOWN,
                    0.0,
                    WORLD_DOWN,
                    DEFAULT_FACE
            );
        }

        static ActionFrame held(KinematicState reference, Vec3 tip, Vec3 face) {
            return new ActionFrame(
                    Kind.HELD,
                    reference,
                    reference,
                    face,
                    0.0,
                    tip,
                    face
            );
        }

        static ActionFrame attached(KinematicState reference, Vec3 tip, Vec3 face) {
            return new ActionFrame(
                    Kind.ATTACHED,
                    reference,
                    reference,
                    face,
                    0.0,
                    tip,
                    face
            );
        }

        static ActionFrame gesture(
                Kind kind,
                KinematicState initialHint,
                KinematicState target,
                Vec3 travelDirection,
                double rollRadiansPerBlock
        ) {
            if (kind != Kind.THROW && kind != Kind.RETURN && kind != Kind.REJECTED_BOUNCE) {
                throw new IllegalArgumentException("gesture 只能使用 THROW、RETURN 或 REJECTED_BOUNCE");
            }
            return new ActionFrame(
                    kind,
                    initialHint,
                    target,
                    travelDirection,
                    rollRadiansPerBlock,
                    travelDirection,
                    DEFAULT_FACE
            );
        }

        // 自旋绕当前模型纵轴，速率只能来自可见 Transform 状态。
        Vec3 gestureAngularVelocity(Vec3 currentTipAxis, double visibleSpeed) {
            double speed = Double.isFinite(visibleSpeed) ? Math.max(0.0, visibleSpeed) : 0.0;
            return kind == Kind.COMPANION || kind == Kind.ATTACHED || kind == Kind.HELD
                    ? Vec3.ZERO
                    : normalizedOr(currentTipAxis, travelDirection).scale(rollRadiansPerBlock * speed);
        }
    }

    // 单次采样的二阶 Transform；rotation 必须始终为单位四元数。
    record Pose(
            Vec3 position,
            Vec3 velocity,
            Vec3 acceleration,
            Quaterniond rotation,
            Vec3 angularVelocity,
            Vec3 angularAcceleration
    ) {
        Pose {
            position = finiteOrZero(position);
            velocity = finiteOrZero(velocity);
            acceleration = finiteOrZero(acceleration);
            rotation = normalized(rotation);
            angularVelocity = finiteOrZero(angularVelocity);
            angularAcceleration = finiteOrZero(angularAcceleration);
        }

        @Override
        public Quaterniond rotation() {
            return new Quaterniond(this.rotation);
        }

        Vec3 tipAxis() {
            return rotate(this.rotation, LOCAL_TIP);
        }

        Vec3 faceNormal() {
            return rotate(this.rotation, LOCAL_FACE);
        }

        Vec3 radialRight() {
            return normalizedOr(this.faceNormal().cross(this.tipAxis()), new Vec3(0.0, 0.0, -1.0));
        }
    }

    private record AngularDemand(Vec3 orientationError, Vec3 angularVelocity) {
    }

    private static TrackingLimits trackingLimits(Kind kind, Pose current, KinematicState target) {
        KinematicState currentLinear = new KinematicState(
                current.position(),
                current.velocity(),
                current.acceleration()
        );
        return switch (kind) {
            case COMPANION -> new TrackingLimits(0.12, 0.040, COMPANION_MAXIMUM_JERK);
            case THROW -> new TrackingLimits(0.25, 0.20, GESTURE_MAXIMUM_JERK);
            case RETURN -> SoulFlight.returnTrackingLimits(currentLinear, target);
            case REJECTED_BOUNCE -> new TrackingLimits(0.18, 0.13, GESTURE_MAXIMUM_JERK);
            case ATTACHED, HELD -> throw new IllegalStateException(kind + " 不得进入平移动力学积分");
        };
    }

    private record AngularLimits(
            double orientationStiffness,
            double angularDamping,
            double maximumAngularAcceleration
    ) {
        static AngularLimits forAction(Kind kind) {
            return switch (kind) {
                case COMPANION -> new AngularLimits(0.070, 0.34, 0.16);
                case THROW -> new AngularLimits(0.18, 0.48, 0.34);
                case RETURN -> new AngularLimits(0.16, 0.44, 0.30);
                case REJECTED_BOUNCE -> new AngularLimits(0.14, 0.42, 0.26);
                case ATTACHED, HELD -> throw new IllegalStateException(kind + " 不得进入角动力学积分");
            };
        }
    }

    // RETURN 只建立一次相对附件帧的五次端点段；段末残差及一、二阶导数均为零。
    // 附件帧逐帧刚性跟随玩家，不参与持续阻尼。
    private record AttachmentHandoff(
            double startTick,
            KinematicState relativeLinear,
            Quaterniond relativeRotation,
            Vec3 relativeAngularVelocity,
            Vec3 relativeAngularAcceleration
    ) {
        static AttachmentHandoff start(double startTick, Pose current, ActionFrame attachment) {
            KinematicState target = attachment.target();
            KinematicState relativeLinear = new KinematicState(
                    current.position().subtract(target.position()),
                    current.velocity().subtract(target.velocity()),
                    current.acceleration().subtract(target.acceleration())
            );
            Quaterniond targetRotation = orientation(attachment.poseTip(), attachment.poseFace());
            Quaterniond relativeRotation = multiply(current.rotation(), conjugate(targetRotation));
            return new AttachmentHandoff(
                    startTick,
                    relativeLinear,
                    relativeRotation,
                    current.angularVelocity(),
                    current.angularAcceleration()
            );
        }

        Pose sample(double sampleTick, ActionFrame attachment) {
            double elapsed = clamp(sampleTick - this.startTick, 0.0, ATTACHMENT_HANDOFF_TICKS);
            KinematicState brakedLinear = new KinematicState(
                    this.relativeLinear.position(),
                    Vec3.ZERO,
                    Vec3.ZERO
            );
            KinematicState residual;
            QuaternionMotion angularResidual;
            if (elapsed <= ATTACHMENT_BRAKING_TICKS) {
                residual = SoulFlight.quintic(
                        this.relativeLinear,
                        brakedLinear,
                        ATTACHMENT_BRAKING_TICKS,
                        elapsed
                );
                angularResidual = quaternionQuintic(
                        this.relativeRotation,
                        this.relativeAngularVelocity,
                        this.relativeAngularAcceleration,
                        this.relativeRotation,
                        Vec3.ZERO,
                        Vec3.ZERO,
                        ATTACHMENT_BRAKING_TICKS,
                        elapsed
                );
            } else {
                double dockingTicks = ATTACHMENT_HANDOFF_TICKS - ATTACHMENT_BRAKING_TICKS;
                double dockingElapsed = elapsed - ATTACHMENT_BRAKING_TICKS;
                residual = SoulFlight.quintic(
                        brakedLinear,
                        KinematicState.ZERO,
                        dockingTicks,
                        dockingElapsed
                );
                angularResidual = quaternionQuintic(
                        this.relativeRotation,
                        Vec3.ZERO,
                        Vec3.ZERO,
                        new Quaterniond(0.0, 0.0, 0.0, 1.0),
                        Vec3.ZERO,
                        Vec3.ZERO,
                        dockingTicks,
                        dockingElapsed
                );
            }
            KinematicState target = attachment.target();
            Quaterniond targetRotation = orientation(attachment.poseTip(), attachment.poseFace());
            return new Pose(
                    target.position().add(residual.position()),
                    target.velocity().add(residual.velocity()),
                    target.acceleration().add(residual.acceleration()),
                    multiply(angularResidual.rotation(), targetRotation),
                    angularResidual.angularVelocity(),
                    angularResidual.angularAcceleration()
            );
        }

        boolean finished(double sampleTick) {
            return sampleTick - this.startTick >= ATTACHMENT_HANDOFF_TICKS;
        }
    }

    private record QuaternionMotion(
            Quaterniond rotation,
            Vec3 angularVelocity,
            Vec3 angularAcceleration
    ) {
    }

    private record QuaternionComponents(
            Quaterniond position,
            Quaterniond velocity,
            Quaterniond acceleration
    ) {
    }

    private static QuaternionMotion quaternionQuintic(
            Quaterniond startRotation,
            Vec3 startAngularVelocity,
            Vec3 startAngularAcceleration,
            Quaterniond terminalRotation,
            Vec3 terminalAngularVelocity,
            Vec3 terminalAngularAcceleration,
            double duration,
            double elapsed
    ) {
        Quaterniond start = normalized(startRotation);
        Quaterniond terminal = normalized(terminalRotation);
        if (dot(start, terminal) < 0.0) {
            terminal = scale(terminal, -1.0);
        }
        Quaterniond startVelocity = quaternionDerivative(start, startAngularVelocity);
        Quaterniond startAcceleration = quaternionSecondDerivative(
                start,
                startAngularVelocity,
                startAngularAcceleration
        );
        Quaterniond terminalVelocity = quaternionDerivative(terminal, terminalAngularVelocity);
        Quaterniond terminalAcceleration = quaternionSecondDerivative(
                terminal,
                terminalAngularVelocity,
                terminalAngularAcceleration
        );
        QuaternionComponents raw = quinticQuaternionComponents(
                start,
                startVelocity,
                startAcceleration,
                terminal,
                terminalVelocity,
                terminalAcceleration,
                duration,
                elapsed
        );
        QuaternionComponents unit = normalizedComponents(raw);
        Vec3 angularVelocity = angularVelocity(unit.position(), unit.velocity());
        Vec3 angularAcceleration = angularAcceleration(
                unit.position(),
                unit.velocity(),
                unit.acceleration(),
                angularVelocity
        );
        return new QuaternionMotion(unit.position(), angularVelocity, angularAcceleration);
    }

    private static QuaternionComponents quinticQuaternionComponents(
            Quaterniond startPosition,
            Quaterniond startVelocity,
            Quaterniond startAcceleration,
            Quaterniond terminalPosition,
            Quaterniond terminalVelocity,
            Quaterniond terminalAcceleration,
            double duration,
            double elapsed
    ) {
        double total = Math.max(1.0E-4, duration);
        double time = clamp(elapsed, 0.0, total);
        Quaterniond c0 = startPosition;
        Quaterniond c1 = startVelocity;
        Quaterniond c2 = scale(startAcceleration, 0.5);
        Quaterniond d0 = subtract(
                subtract(subtract(terminalPosition, c0), scale(c1, total)),
                scale(c2, total * total)
        );
        Quaterniond d1 = subtract(
                subtract(terminalVelocity, c1),
                scale(c2, 2.0 * total)
        );
        Quaterniond d2 = subtract(terminalAcceleration, scale(c2, 2.0));
        double total2 = total * total;
        double total3 = total2 * total;
        double total4 = total3 * total;
        double total5 = total4 * total;
        Quaterniond c3 = scale(add(
                subtract(scale(d0, 10.0), scale(d1, 4.0 * total)),
                scale(d2, 0.5 * total2)
        ), 1.0 / total3);
        Quaterniond c4 = scale(subtract(
                add(scale(d0, -15.0), scale(d1, 7.0 * total)),
                scale(d2, total2)
        ), 1.0 / total4);
        Quaterniond c5 = scale(add(
                subtract(scale(d0, 6.0), scale(d1, 3.0 * total)),
                scale(d2, 0.5 * total2)
        ), 1.0 / total5);

        double time2 = time * time;
        double time3 = time2 * time;
        double time4 = time3 * time;
        double time5 = time4 * time;
        Quaterniond position = add(
                add(add(c0, scale(c1, time)), scale(c2, time2)),
                add(scale(c3, time3), add(scale(c4, time4), scale(c5, time5)))
        );
        Quaterniond velocity = add(
                add(c1, scale(c2, 2.0 * time)),
                add(scale(c3, 3.0 * time2), add(scale(c4, 4.0 * time3), scale(c5, 5.0 * time4)))
        );
        Quaterniond acceleration = add(
                scale(c2, 2.0),
                add(scale(c3, 6.0 * time), add(scale(c4, 12.0 * time2), scale(c5, 20.0 * time3)))
        );
        return new QuaternionComponents(position, velocity, acceleration);
    }

    private static QuaternionComponents normalizedComponents(QuaternionComponents raw) {
        double norm = Math.sqrt(dot(raw.position(), raw.position()));
        if (!Double.isFinite(norm) || norm < EPSILON) {
            return new QuaternionComponents(
                    new Quaterniond(0.0, 0.0, 0.0, 1.0),
                    new Quaterniond(0.0, 0.0, 0.0, 0.0),
                    new Quaterniond(0.0, 0.0, 0.0, 0.0)
            );
        }
        Quaterniond rotation = scale(raw.position(), 1.0 / norm);
        double normVelocity = dot(rotation, raw.velocity());
        Quaterniond rotationVelocity = scale(
                subtract(raw.velocity(), scale(rotation, normVelocity)),
                1.0 / norm
        );
        double normAcceleration = (
                dot(raw.velocity(), raw.velocity())
                        + dot(raw.position(), raw.acceleration())
                        - normVelocity * normVelocity
        ) / norm;
        Quaterniond rotationAcceleration = scale(
                subtract(
                        subtract(raw.acceleration(), scale(rotation, normAcceleration)),
                        scale(rotationVelocity, 2.0 * normVelocity)
                ),
                1.0 / norm
        );
        return new QuaternionComponents(rotation, rotationVelocity, rotationAcceleration);
    }

    private static Quaterniond orientation(Vec3 tipAxis, Vec3 preferredFace) {
        Vec3 tip = normalizedOr(tipAxis, WORLD_DOWN);
        Quaterniond alignment = fromTo(LOCAL_TIP, tip);
        Vec3 alignedFace = rotate(alignment, LOCAL_FACE);
        Vec3 face = perpendicularFace(tip, preferredFace);
        double twist = Math.atan2(
                tip.dot(alignedFace.cross(face)),
                clamp(alignedFace.dot(face), -1.0, 1.0)
        );
        return multiply(fromAxisAngle(tip, twist), alignment);
    }

    private static Vec3 perpendicularFace(Vec3 tipAxis, Vec3 preferredFace) {
        Vec3 projected = preferredFace.subtract(tipAxis.scale(preferredFace.dot(tipAxis)));
        if (projected.lengthSqr() >= EPSILON) {
            return projected.normalize();
        }
        Vec3 fallback = Math.abs(tipAxis.y) < 0.82
                ? new Vec3(0.0, 1.0, 0.0)
                : DEFAULT_FACE;
        return normalizedOr(
                fallback.subtract(tipAxis.scale(fallback.dot(tipAxis))),
                DEFAULT_FACE
        );
    }

    private static Vec3 gestureTangent(Vec3 velocity, Vec3 fallback) {
        Vec3 safeVelocity = finiteOrZero(velocity);
        return safeVelocity.lengthSqr() >= 1.0E-6
                ? safeVelocity.normalize()
                : normalizedOr(fallback, WORLD_DOWN);
    }

    private static Vec3 rotationBetween(Vec3 from, Vec3 to) {
        Vec3 start = normalizedOr(from, WORLD_DOWN);
        Vec3 end = normalizedOr(to, WORLD_DOWN);
        Vec3 cross = start.cross(end);
        double sine = cross.length();
        double cosine = clamp(start.dot(end), -1.0, 1.0);
        if (sine >= EPSILON) {
            return cross.scale(Math.atan2(sine, cosine) / sine);
        }
        if (cosine >= 0.0) {
            return Vec3.ZERO;
        }
        return perpendicularFace(start, DEFAULT_FACE).scale(Math.PI);
    }

    private static Vec3 quaternionError(Quaterniond current, Quaterniond desired) {
        Quaterniond error = multiply(desired, conjugate(current));
        if (error.w() < 0.0) {
            error = new Quaterniond(-error.x(), -error.y(), -error.z(), -error.w());
        }
        double sineHalf = Math.sqrt(error.x() * error.x() + error.y() * error.y() + error.z() * error.z());
        if (sineHalf < EPSILON) {
            return Vec3.ZERO;
        }
        double angle = 2.0 * Math.atan2(sineHalf, clamp(error.w(), -1.0, 1.0));
        return new Vec3(error.x(), error.y(), error.z()).scale(angle / sineHalf);
    }

    private static boolean isAttachment(Kind kind) {
        return kind == Kind.ATTACHED || kind == Kind.HELD;
    }

    private static Quaterniond fromTo(Vec3 from, Vec3 to) {
        Vec3 start = normalizedOr(from, LOCAL_TIP);
        Vec3 end = normalizedOr(to, WORLD_DOWN);
        double dot = clamp(start.dot(end), -1.0, 1.0);
        if (dot > 1.0 - EPSILON) {
            return new Quaterniond(0.0, 0.0, 0.0, 1.0);
        }
        if (dot < -1.0 + EPSILON) {
            return fromAxisAngle(perpendicularFace(start, DEFAULT_FACE), Math.PI);
        }
        Vec3 cross = start.cross(end);
        return normalized(new Quaterniond(cross.x, cross.y, cross.z, 1.0 + dot));
    }

    private static Quaterniond fromAxisAngle(Vec3 axis, double angle) {
        Vec3 direction = normalizedOr(axis, DEFAULT_FACE);
        double half = angle * 0.5;
        double sine = Math.sin(half);
        return new Quaterniond(
                direction.x * sine,
                direction.y * sine,
                direction.z * sine,
                Math.cos(half)
        );
    }

    private static Quaterniond multiply(Quaterniond left, Quaterniond right) {
        return normalized(multiplyRaw(left, right));
    }

    // 世界角速度使用四元数微分方程做 RK4 积分，禁止欧拉角中转。
    private static Quaterniond integrateQuaternion(
            Quaterniond rotation,
            Vec3 angularVelocity,
            Vec3 angularAcceleration,
            Vec3 angularJerk,
            double step
    ) {
        Quaterniond start = normalized(rotation);
        Quaterniond k1 = quaternionDerivative(
                start,
                angularVelocityAt(angularVelocity, angularAcceleration, angularJerk, 0.0)
        );
        Quaterniond q2 = normalized(add(start, scale(k1, step * 0.5)));
        Quaterniond k2 = quaternionDerivative(
                q2,
                angularVelocityAt(angularVelocity, angularAcceleration, angularJerk, step * 0.5)
        );
        Quaterniond q3 = normalized(add(start, scale(k2, step * 0.5)));
        Quaterniond k3 = quaternionDerivative(
                q3,
                angularVelocityAt(angularVelocity, angularAcceleration, angularJerk, step * 0.5)
        );
        Quaterniond q4 = normalized(add(start, scale(k3, step)));
        Quaterniond k4 = quaternionDerivative(
                q4,
                angularVelocityAt(angularVelocity, angularAcceleration, angularJerk, step)
        );
        Quaterniond weighted = add(
                add(k1, scale(k2, 2.0)),
                add(scale(k3, 2.0), k4)
        );
        return normalized(add(start, scale(weighted, step / 6.0)));
    }

    private static Vec3 angularVelocityAt(
            Vec3 velocity,
            Vec3 acceleration,
            Vec3 jerk,
            double elapsed
    ) {
        return velocity
                .add(acceleration.scale(elapsed))
                .add(jerk.scale(0.5 * elapsed * elapsed));
    }

    private static Quaterniond quaternionDerivative(Quaterniond rotation, Vec3 angularVelocity) {
        return scale(multiplyRaw(
                new Quaterniond(angularVelocity.x, angularVelocity.y, angularVelocity.z, 0.0),
                rotation
        ), 0.5);
    }

    private static Quaterniond quaternionSecondDerivative(
            Quaterniond rotation,
            Vec3 angularVelocity,
            Vec3 angularAcceleration
    ) {
        Quaterniond velocityDerivative = quaternionDerivative(rotation, angularVelocity);
        Quaterniond accelerationTerm = scale(multiplyRaw(
                new Quaterniond(
                        angularAcceleration.x,
                        angularAcceleration.y,
                        angularAcceleration.z,
                        0.0
                ),
                rotation
        ), 0.5);
        Quaterniond velocityTerm = scale(multiplyRaw(
                new Quaterniond(angularVelocity.x, angularVelocity.y, angularVelocity.z, 0.0),
                velocityDerivative
        ), 0.5);
        return add(accelerationTerm, velocityTerm);
    }

    private static Vec3 angularVelocity(Quaterniond rotation, Quaterniond rotationVelocity) {
        Quaterniond angular = scale(multiplyRaw(rotationVelocity, conjugate(rotation)), 2.0);
        return finiteOrZero(new Vec3(angular.x(), angular.y(), angular.z()));
    }

    private static Vec3 angularAcceleration(
            Quaterniond rotation,
            Quaterniond rotationVelocity,
            Quaterniond rotationAcceleration,
            Vec3 angularVelocity
    ) {
        Quaterniond velocityContribution = scale(multiplyRaw(
                new Quaterniond(angularVelocity.x, angularVelocity.y, angularVelocity.z, 0.0),
                rotationVelocity
        ), 0.5);
        Quaterniond angular = scale(multiplyRaw(
                subtract(rotationAcceleration, velocityContribution),
                conjugate(rotation)
        ), 2.0);
        return finiteOrZero(new Vec3(angular.x(), angular.y(), angular.z()));
    }

    private static Quaterniond multiplyRaw(Quaterniond left, Quaterniond right) {
        return new Quaterniond(
                left.w() * right.x() + left.x() * right.w()
                        + left.y() * right.z() - left.z() * right.y(),
                left.w() * right.y() - left.x() * right.z()
                        + left.y() * right.w() + left.z() * right.x(),
                left.w() * right.z() + left.x() * right.y()
                        - left.y() * right.x() + left.z() * right.w(),
                left.w() * right.w() - left.x() * right.x()
                        - left.y() * right.y() - left.z() * right.z()
        );
    }

    private static Quaterniond add(Quaterniond left, Quaterniond right) {
        return new Quaterniond(
                left.x() + right.x(),
                left.y() + right.y(),
                left.z() + right.z(),
                left.w() + right.w()
        );
    }

    private static Quaterniond subtract(Quaterniond left, Quaterniond right) {
        return new Quaterniond(
                left.x() - right.x(),
                left.y() - right.y(),
                left.z() - right.z(),
                left.w() - right.w()
        );
    }

    private static double dot(Quaterniond left, Quaterniond right) {
        return left.x() * right.x()
                + left.y() * right.y()
                + left.z() * right.z()
                + left.w() * right.w();
    }

    private static Quaterniond scale(Quaterniond value, double factor) {
        return new Quaterniond(
                value.x() * factor,
                value.y() * factor,
                value.z() * factor,
                value.w() * factor
        );
    }

    private static Quaterniond conjugate(Quaterniond value) {
        Quaterniond normalized = normalized(value);
        return new Quaterniond(-normalized.x(), -normalized.y(), -normalized.z(), normalized.w());
    }

    private static Vec3 rotate(Quaterniond rotation, Vec3 vector) {
        Quaterniond unit = normalized(rotation);
        Vec3 imaginary = new Vec3(unit.x(), unit.y(), unit.z());
        Vec3 twiceCross = imaginary.cross(vector).scale(2.0);
        return vector.add(twiceCross.scale(unit.w())).add(imaginary.cross(twiceCross));
    }

    private static Quaterniond normalized(Quaterniond value) {
        Quaterniond safe = value == null ? new Quaterniond() : new Quaterniond(value);
        double normSqr = safe.x() * safe.x() + safe.y() * safe.y()
                + safe.z() * safe.z() + safe.w() * safe.w();
        if (!Double.isFinite(normSqr) || normSqr < EPSILON) {
            return new Quaterniond(0.0, 0.0, 0.0, 1.0);
        }
        double inverse = 1.0 / Math.sqrt(normSqr);
        return new Quaterniond(
                safe.x() * inverse,
                safe.y() * inverse,
                safe.z() * inverse,
                safe.w() * inverse
        );
    }

    private static Vec3 clampLength(Vec3 value, double maximum) {
        Vec3 safe = finiteOrZero(value);
        double lengthSqr = safe.lengthSqr();
        return lengthSqr <= maximum * maximum || lengthSqr < EPSILON
                ? safe
                : safe.scale(maximum / Math.sqrt(lengthSqr));
    }

    private static Vec3 normalizedOr(Vec3 value, Vec3 fallback) {
        Vec3 safe = finiteOrZero(value);
        return safe.lengthSqr() < EPSILON ? fallback : safe.normalize();
    }

    private static Vec3 finiteOrZero(Vec3 value) {
        return value == null || !Double.isFinite(value.lengthSqr()) ? Vec3.ZERO : value;
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
