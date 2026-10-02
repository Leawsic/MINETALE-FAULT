package cn.jehorstudio.minetale.battle.logic.actor.states;

import cn.jehorstudio.minetale.battle.logic.actor.*;
import cn.jehorstudio.minetale.battle.logic.actor.states.types.ActorTypeSnapshot;
import cn.jehorstudio.minetale.battle.logic.coordinate.CanonicalTransform;
import cn.jehorstudio.minetale.battle.logic.coordinate.CanonicalVec3;
import cn.jehorstudio.minetale.battle.logic.coordinate.CollisionPolicy;

import java.util.Objects;

public record ActorSingleSnapshot(
        ActorRef ref,
        ActorType type,
        ActorLifecycle lifecycle,
        ActorRole role,
        CanonicalTransform transform,
        long transformDiscontinuity,
        CanonicalVec3 velocity,
        CollisionShape collisionShape,
        CollisionPolicy collisionPolicy,
        VisualRef visual,
        ActorRef sourceRef,
        ActorTypeSnapshot typeSnapshot
) {
    public ActorSingleSnapshot {
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(lifecycle, "lifecycle");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(transform, "transform");
        if (transformDiscontinuity < 0L) {
            throw new IllegalArgumentException("transformDiscontinuity must be >= 0.");
        }
        Objects.requireNonNull(velocity, "velocity");
        Objects.requireNonNull(collisionShape, "collisionShape");
        Objects.requireNonNull(visual, "visual");
        Objects.requireNonNull(typeSnapshot, "typeSnapshot");
        if (ref.type() != type) {
            throw new IllegalArgumentException("ref type must match actor type.");
        }
        if (typeSnapshot.actorType() != type) {
            throw new IllegalArgumentException("typeSnapshot actorType must match core type.");
        }
    }
}
