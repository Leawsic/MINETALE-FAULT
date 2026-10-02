package cn.jehorstudio.minetale.content.player.soul;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

final class SoulCompanionBrain {
    private static final Vec3 WORLD_UP = new Vec3(0.0, 1.0, 0.0);
    private static final int THROW_TICKS = 15;
    private static final int REJECTED_TICKS = 12;
    private static final double REJOIN_DISTANCE = 9.0;
    private static final double REJOIN_URGE = 0.80;

    private Intent intent = Intent.dormant();
    private double curiosity = 0.25;
    private double followUrge;

    Intent update(Soul.State nextState, Perception perception) {
        return update(nextState, perception, Stimulus.NONE);
    }

    Intent update(Soul.State nextState, Perception perception, Stimulus stimulus) {
        Objects.requireNonNull(nextState, "nextState");
        Objects.requireNonNull(perception, "perception");
        stimulus = stimulus == null ? Stimulus.NONE : stimulus;
        updateDrives(perception);

        if (nextState == Soul.State.ITEM) {
            if (this.intent.action() != Action.DORMANT) {
                this.intent = Intent.dormantAt(perception);
            }
            return this.intent;
        }
        if (nextState == Soul.State.RETURNING) {
            if (this.intent.action() != Action.RETURN_TO_INVENTORY) {
                this.intent = new Intent(
                        Action.RETURN_TO_INVENTORY,
                        perception.ownerPosition(),
                        perception.ownerPosition(),
                        perception.tick(),
                        Long.MAX_VALUE,
                        Long.MAX_VALUE,
                        OrbitProfile.NONE
                );
            }
            return this.intent;
        }

        if (stimulus == Stimulus.RELEASED_FROM_INVENTORY) {
            Vec3 target = perception.soulPosition()
                    .add(normalizedHorizontal(perception.ownerForward()).scale(3.4));
            return install(new Intent(
                    Action.THROW_LAUNCH,
                    target,
                    perception.ownerPosition(),
                    perception.tick(),
                    perception.tick() + THROW_TICKS,
                    perception.tick() + THROW_TICKS,
                    OrbitProfile.NONE
            ));
        }
        if (stimulus == Stimulus.INVENTORY_REJECTED) {
            Vec3 away = normalizedOr(
                    perception.soulPosition().subtract(perception.ownerPosition()),
                    perception.ownerForward().scale(-1.0)
            );
            Vec3 target = perception.soulPosition().add(away.scale(2.4)).add(0.0, 0.35, 0.0);
            return install(new Intent(
                    Action.REJECTED_BOUNCE,
                    target,
                    perception.ownerPosition(),
                    perception.tick(),
                    perception.tick() + 7,
                    perception.tick() + REJECTED_TICKS,
                    OrbitProfile.NONE
            ));
        }

        if (this.intent.action().eventOnly() && perception.tick() < this.intent.expiresAtTick()) {
            return this.intent;
        }

        boolean committed = perception.tick() < this.intent.minimumCommitUntilTick();
        boolean expired = perception.tick() >= this.intent.expiresAtTick();
        boolean reached = perception.soulPosition().distanceToSqr(this.intent.worldTarget())
                <= arrivalRadius(this.intent) * arrivalRadius(this.intent);
        boolean wantsRejoin = (perception.ownerDistance() >= REJOIN_DISTANCE || this.followUrge >= REJOIN_URGE)
                && this.intent.action() != Action.REJOIN;

        if (this.intent.action().companionAction()
                && !expired
                && (committed || !wantsRejoin)
                && !(this.intent.action() == Action.REJOIN && reached && !committed)) {
            return this.intent;
        }

        return install(selectCompanionIntent(perception));
    }

    double curiosity() {
        return this.curiosity;
    }

    double followUrge() {
        return this.followUrge;
    }

    void reset() {
        this.intent = Intent.dormant();
        this.curiosity = 0.25;
        this.followUrge = 0.0;
    }

    private void updateDrives(Perception perception) {
        double idle = clamp01(perception.ownerIdleTicks() / 160.0);
        double ownerSpeed = clamp01(perception.ownerVelocity().length() / 0.28);
        double explorationDrain = this.intent.action() == Action.INSPECT || this.intent.action() == Action.ROAM
                ? 0.24
                : 0.0;
        double curiosityTarget = clamp01(0.12 + idle * 0.72 - ownerSpeed * 0.24 - explorationDrain);
        this.curiosity = approach(this.curiosity, curiosityTarget, 0.012);

        Vec3 selectionOrigin = this.intent.ownerOriginAtSelection();
        double ownerDisplacement = perception.ownerPosition().distanceTo(selectionOrigin);
        double target = clamp01(
                ownerDisplacement / 3.2 * 0.58
                        + ownerSpeed * 0.28
                        + perception.ownerDistance() / 11.0 * 0.26
        );
        this.followUrge = approach(this.followUrge, target, target > this.followUrge ? 0.10 : 0.025);
    }

    private Intent selectCompanionIntent(Perception perception) {
        int seed = eventSeed(perception, Action.LOITER);
        if (perception.ownerDistance() >= REJOIN_DISTANCE || this.followUrge >= REJOIN_URGE) {
            this.followUrge *= 0.24;
            Vec3 target = sampleCompanionTarget(perception.ownerPosition(), perception.ownerForward(), seed);
            return companionIntent(Action.REJOIN, target, perception, seed, 30, 90);
        }

        double environmentBias = clamp01(this.curiosity * perception.ownerIdleTicks() / 110.0);
        InterestPoint interest = chooseInterest(perception.interests(), seed, environmentBias);
        if (interest != null && shouldInvestigate(interest, seed)) {
            this.curiosity = Math.max(0.08, this.curiosity - 0.32);
            Action action = interest.kind() == InterestKind.PLACE ? Action.ROAM : Action.INSPECT;
            int duration = 110 + (int) Math.round(unit(mix(seed ^ 0x19A45D3B)) * 70.0);
            Vec3 target = inspectionTarget(interest, perception, seed);
            return companionIntent(action, target, perception, seed, 44, duration);
        }

        Vec3 target = sampleCompanionTarget(perception.ownerPosition(), perception.ownerForward(), seed);
        int duration = 150 + (int) Math.round(unit(mix(seed ^ 0x51ED270B)) * 110.0);
        return companionIntent(Action.LOITER, target, perception, seed, 60, duration);
    }

    private boolean shouldInvestigate(InterestPoint interest, int seed) {
        double affinity = switch (interest.kind()) {
            case HOSTILE -> 0.28;
            case CREATURE -> 0.12;
            case PLACE -> 0.04;
            case PLAYER -> 0.0;
        };
        double gate = switch (interest.kind()) {
            case HOSTILE -> 0.42;
            case CREATURE -> 0.55;
            case PLACE -> 0.68;
            case PLAYER -> 0.78;
        };
        double chance = clamp01((this.curiosity + affinity - gate) / Math.max(0.01, 1.0 - gate));
        return unit(mix(seed ^ interest.stableHash())) < chance;
    }

    private static Intent companionIntent(
            Action action,
            Vec3 target,
            Perception perception,
            int seed,
            int minimumCommit,
            int duration
    ) {
        return new Intent(
                action,
                target,
                perception.ownerPosition(),
                perception.tick(),
                perception.tick() + minimumCommit,
                perception.tick() + duration,
                orbitProfile(seed, action)
        );
    }

    private Intent install(Intent next) {
        this.intent = next;
        return next;
    }

    private static double arrivalRadius(Intent intent) {
        return intent.orbit().enabled() ? intent.orbit().radius() * 1.35 : 0.72;
    }

    static Vec3 sampleCompanionTarget(Vec3 ownerPosition, Vec3 ownerForward, int seed) {
        Vec3 forward = normalizedHorizontal(ownerForward);
        Vec3 right = normalizedOr(WORLD_UP.cross(forward), new Vec3(1.0, 0.0, 0.0));
        double forwardDistance = 2.2 + unit(mix(seed ^ 0x32B73A91)) * 1.6;
        double sideDistance = 2.2 + unit(mix(seed ^ 0x6D2B79F5)) * 1.6;
        double verticalDistance = 0.6 + unit(mix(seed ^ 0x142A4F67)) * 0.8;
        double sideSign = (mix(seed ^ 0x79C31DAB) & 1) == 0 ? -1.0 : 1.0;
        double verticalSign = (mix(seed ^ 0x4A9F83C1) & 1) == 0 ? -1.0 : 1.0;
        return ownerPosition
                .add(forward.scale(forwardDistance))
                .add(right.scale(sideDistance * sideSign))
                .add(0.0, verticalDistance * verticalSign, 0.0);
    }

    static Vec3 inspectionTarget(InterestPoint interest, Perception perception, int seed) {
        if (interest.kind() == InterestKind.PLAYER
                && interest.position().distanceToSqr(perception.ownerPosition()) < 0.25) {
            return sampleCompanionTarget(perception.ownerPosition(), perception.ownerForward(), seed);
        }
        return interest.position();
    }

    @Nullable
    static InterestPoint chooseInterest(List<InterestPoint> interests, int seed, double environmentBias) {
        if (interests == null || interests.isEmpty()) {
            return null;
        }
        InterestKind preferredKind = null;
        boolean hasHostile = hasKind(interests, InterestKind.HOSTILE);
        boolean hasCreature = hasKind(interests, InterestKind.CREATURE);
        boolean hasPlace = hasKind(interests, InterestKind.PLACE);
        boolean hasPlayer = hasKind(interests, InterestKind.PLAYER);
        if (hasHostile) {
            preferredKind = InterestKind.HOSTILE;
        } else if (hasCreature) {
            preferredKind = InterestKind.CREATURE;
        } else if (hasPlace && environmentBias >= 0.55) {
            preferredKind = InterestKind.PLACE;
        } else if (hasPlayer) {
            preferredKind = InterestKind.PLAYER;
        } else if (hasPlace) {
            preferredKind = InterestKind.PLACE;
        }

        InterestPoint best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (InterestPoint point : interests) {
            if (point.kind() != preferredKind) {
                continue;
            }
            double jitter = unit(mix(seed ^ point.stableHash())) * 0.18;
            double score = point.salience() + jitter;
            if (score > bestScore) {
                best = point;
                bestScore = score;
            }
        }
        return best;
    }

    private static boolean hasKind(List<InterestPoint> interests, InterestKind kind) {
        return interests.stream().anyMatch(point -> point.kind() == kind);
    }

    private static OrbitProfile orbitProfile(int seed, Action action) {
        if (action != Action.ROAM && action != Action.INSPECT) {
            return OrbitProfile.NONE;
        }
        double tiltX = (unit(mix(seed ^ 0x165667B1)) - 0.5) * 0.48;
        double tiltZ = (unit(mix(seed ^ 0xD3A2646C)) - 0.5) * 0.48;
        Vec3 axis = new Vec3(tiltX, 1.0, tiltZ).normalize();
        double radius = 0.55 + unit(mix(seed ^ 0x2C9277B5)) * 0.45;
        double speed = 0.022 + unit(mix(seed ^ 0xA24BAED4)) * 0.020;
        if ((mix(seed ^ 0x5F356495) & 1) == 0) {
            speed = -speed;
        }
        double verticalAmplitude = 0.12 + unit(mix(seed ^ 0x3C6EF372)) * 0.18;
        double phase = unit(mix(seed ^ 0xBB67AE85)) * Math.PI * 2.0;
        return new OrbitProfile(axis, radius, speed, verticalAmplitude, phase);
    }

    private static int eventSeed(Perception perception, Action action) {
        return mix(perception.identitySeed()
                ^ (int) perception.tick() * 0x9E3779B9
                ^ action.ordinal() * 0x632BE5AB);
    }

    private static Vec3 normalizedHorizontal(Vec3 value) {
        Vec3 horizontal = new Vec3(value.x, 0.0, value.z);
        return normalizedOr(horizontal, new Vec3(0.0, 0.0, 1.0));
    }

    private static Vec3 normalizedOr(Vec3 value, Vec3 fallback) {
        Vec3 safe = finiteOrZero(value);
        return safe.lengthSqr() < 1.0E-8 ? fallback : safe.normalize();
    }

    private static Vec3 finiteOrZero(Vec3 value) {
        return value == null || !Double.isFinite(value.lengthSqr()) ? Vec3.ZERO : value;
    }

    private static double approach(double current, double target, double rate) {
        return current + (target - current) * Mth.clamp(rate, 0.0, 1.0);
    }

    private static double clamp01(double value) {
        return Mth.clamp(value, 0.0, 1.0);
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

    record Perception(
            long tick,
            int identitySeed,
            Vec3 ownerPosition,
            Vec3 ownerVelocity,
            Vec3 ownerForward,
            int ownerIdleTicks,
            Vec3 soulPosition,
            List<InterestPoint> interests
    ) {
        Perception {
            ownerPosition = finiteOrZero(ownerPosition);
            ownerVelocity = finiteOrZero(ownerVelocity);
            ownerForward = normalizedHorizontal(ownerForward);
            ownerIdleTicks = Math.max(0, ownerIdleTicks);
            soulPosition = finiteOrZero(soulPosition);
            interests = interests == null ? List.of() : List.copyOf(interests);
        }

        double ownerDistance() {
            return this.soulPosition.distanceTo(this.ownerPosition);
        }
    }

    record Intent(
            Action action,
            Vec3 worldTarget,
            Vec3 ownerOriginAtSelection,
            long startedTick,
            long minimumCommitUntilTick,
            long expiresAtTick,
            OrbitProfile orbit
    ) {
        Intent {
            Objects.requireNonNull(action, "action");
            worldTarget = finiteOrZero(worldTarget);
            ownerOriginAtSelection = finiteOrZero(ownerOriginAtSelection);
            minimumCommitUntilTick = Math.max(startedTick, minimumCommitUntilTick);
            expiresAtTick = Math.max(minimumCommitUntilTick, expiresAtTick);
            orbit = orbit == null ? OrbitProfile.NONE : orbit;
        }

        static Intent dormant() {
            return new Intent(Action.DORMANT, Vec3.ZERO, Vec3.ZERO, 0L, 0L, Long.MAX_VALUE,
                    OrbitProfile.NONE);
        }

        static Intent dormantAt(Perception perception) {
            return new Intent(Action.DORMANT, perception.soulPosition(), perception.ownerPosition(),
                    perception.tick(), Long.MAX_VALUE, Long.MAX_VALUE, OrbitProfile.NONE);
        }
    }

    record OrbitProfile(
            Vec3 axis,
            double radius,
            double angularSpeed,
            double verticalAmplitude,
            double phaseOffset
    ) {
        static final OrbitProfile NONE = new OrbitProfile(WORLD_UP, 0.0, 0.0, 0.0, 0.0);

        OrbitProfile {
            axis = normalizedOr(axis, WORLD_UP);
            radius = Math.max(0.0, radius);
            verticalAmplitude = Math.max(0.0, verticalAmplitude);
            phaseOffset = Double.isFinite(phaseOffset) ? phaseOffset : 0.0;
            angularSpeed = Double.isFinite(angularSpeed) ? angularSpeed : 0.0;
        }

        boolean enabled() {
            return this.radius > 1.0E-4 && Math.abs(this.angularSpeed) > 1.0E-5;
        }
    }

    record InterestPoint(
            InterestKind kind,
            @Nullable UUID subjectId,
            Vec3 position,
            double salience
    ) {
        InterestPoint {
            Objects.requireNonNull(kind, "kind");
            position = finiteOrZero(position);
            salience = clamp01(salience);
        }

        int stableHash() {
            int subjectHash = this.subjectId == null ? this.position.hashCode() : this.subjectId.hashCode();
            return mix(subjectHash ^ this.kind.ordinal() * 0x9E3779B9);
        }
    }

    enum InterestKind {
        HOSTILE,
        CREATURE,
        PLAYER,
        PLACE
    }

    enum Action {
        DORMANT(false, false),
        THROW_LAUNCH(false, true),
        REJOIN(true, false),
        LOITER(true, false),
        ROAM(true, false),
        INSPECT(true, false),
        RETURN_TO_INVENTORY(false, false),
        REJECTED_BOUNCE(false, true);

        private final boolean companionAction;
        private final boolean eventOnly;

        Action(boolean companionAction, boolean eventOnly) {
            this.companionAction = companionAction;
            this.eventOnly = eventOnly;
        }

        boolean companionAction() {
            return this.companionAction;
        }

        boolean eventOnly() {
            return this.eventOnly;
        }
    }

    enum Stimulus {
        NONE,
        RELEASED_FROM_INVENTORY,
        INVENTORY_REJECTED
    }
}
