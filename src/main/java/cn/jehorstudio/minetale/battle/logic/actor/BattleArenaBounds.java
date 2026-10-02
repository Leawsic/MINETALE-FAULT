package cn.jehorstudio.minetale.battle.logic.actor;

import cn.jehorstudio.minetale.battle.logic.actor.states.ActorsStatesCache;
import cn.jehorstudio.minetale.battle.logic.actor.states.ActorSingleSnapshot;
import cn.jehorstudio.minetale.battle.logic.actor.states.ActorsStatesSnapshot;
import cn.jehorstudio.minetale.battle.logic.actor.states.types.ScriptedActorStateSnapshot;
import cn.jehorstudio.minetale.battle.logic.coordinate.CanonicalVec3;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

// Arena 来源依次为带 frame tag 的 SCRIPTED_ACTOR、legacy BATTLE_BOX、[-1,1]；clamp 会预留 Actor 的 AABB 尺寸。
public record BattleArenaBounds(CanonicalVec3 min, CanonicalVec3 max, ActorRef provider) {
    public static final BattleArenaBounds LEGACY_DEFAULT = new BattleArenaBounds(
            new CanonicalVec3(-1.0D, -1.0D, -1.0D),
            new CanonicalVec3(1.0D, 1.0D, 1.0D),
            null
    );

    public BattleArenaBounds {
        Objects.requireNonNull(min, "min");
        Objects.requireNonNull(max, "max");
        if (min.x() >= max.x() || min.y() >= max.y() || min.z() >= max.z()) {
            throw new IllegalArgumentException("Arena bounds must satisfy min < max on every axis.");
        }
    }

    public static BattleArenaBounds resolve(ActorsStatesCache actors) {
        Objects.requireNonNull(actors, "actors");
        Actor scripted = actors.activeActors(ActorType.SCRIPTED_ACTOR).stream()
                .filter(BattleArenaBounds::isFrameProvider)
                .min(Comparator.comparingInt((Actor actor) -> providerPriority(actor))
                        .thenComparingInt(actor -> actor.ref().index()))
                .orElse(null);
        if (scripted != null) {
            return fromProvider(scripted);
        }
        List<Actor> legacy = actors.activeActors(ActorType.BATTLE_BOX);
        if (!legacy.isEmpty() && !legacy.getFirst().collisionShape().boxes().isEmpty()) {
            return fromProvider(legacy.getFirst());
        }
        return LEGACY_DEFAULT;
    }

    public static BattleArenaBounds resolve(ActorsStatesSnapshot actors) {
        Objects.requireNonNull(actors, "actors");
        ActorSingleSnapshot scripted = actors.actors(ActorType.SCRIPTED_ACTOR).stream()
                .filter(BattleArenaBounds::isFrameProvider)
                .min(Comparator.comparingInt((ActorSingleSnapshot actor) -> providerPriority(actor))
                        .thenComparingInt(actor -> actor.ref().index()))
                .orElse(null);
        if (scripted != null) {
            return fromProvider(scripted);
        }
        ActorSingleSnapshot legacy = actors.actors(ActorType.BATTLE_BOX).stream()
                .filter(actor -> actor.lifecycle() == ActorLifecycle.ACTIVE)
                .filter(actor -> !actor.collisionShape().boxes().isEmpty())
                .findFirst()
                .orElse(null);
        return legacy == null ? LEGACY_DEFAULT : fromProvider(legacy);
    }

    public CanonicalVec3 clamp(Actor actor, CanonicalVec3 requestedPosition) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(requestedPosition, "requestedPosition");
        ActorCollision.AxisAlignedBounds body = ActorCollision.bounds(actor);
        CanonicalVec3 current = actor.transform().position();
        double minX = this.min.x() - (body.min().x() - current.x());
        double minY = this.min.y() - (body.min().y() - current.y());
        double minZ = this.min.z() - (body.min().z() - current.z());
        double maxX = this.max.x() - (body.max().x() - current.x());
        double maxY = this.max.y() - (body.max().y() - current.y());
        double maxZ = this.max.z() - (body.max().z() - current.z());
        return new CanonicalVec3(
                clampAxis(requestedPosition.x(), minX, maxX),
                clampAxis(requestedPosition.y(), minY, maxY),
                clampAxis(requestedPosition.z(), minZ, maxZ)
        );
    }

    public BoundaryContact contact(Actor actor) {
        Objects.requireNonNull(actor, "actor");
        if (actor.collisionShape().boxes().isEmpty()) {
            return new BoundaryContact(actor.transform().position(), false, false, false);
        }
        ActorCollision.AxisAlignedBounds body = ActorCollision.bounds(actor);
        boolean x = body.min().x() < this.min.x() || body.max().x() > this.max.x();
        boolean y = body.min().y() < this.min.y() || body.max().y() > this.max.y();
        boolean z = body.min().z() < this.min.z() || body.max().z() > this.max.z();
        return new BoundaryContact(clamp(actor, actor.transform().position()), x, y, z);
    }

    public boolean touchesBoundary(Actor actor) {
        return contact(actor).touching();
    }

    private static BattleArenaBounds fromProvider(Actor actor) {
        ActorCollision.AxisAlignedBounds bounds = ActorCollision.bounds(actor);
        return new BattleArenaBounds(bounds.min(), bounds.max(), actor.ref());
    }

    private static BattleArenaBounds fromProvider(ActorSingleSnapshot actor) {
        ActorCollision.AxisAlignedBounds bounds =
                ActorCollision.bounds(actor.transform(), actor.collisionShape());
        return new BattleArenaBounds(bounds.min(), bounds.max(), actor.ref());
    }

    private static boolean isFrameProvider(Actor actor) {
        return actor.active()
                && actor.role().gameplayBody()
                && actor.scripted() != null
                && !actor.collisionShape().boxes().isEmpty()
                && actor.scripted().tags().stream().anyMatch(BattleArenaBounds::isFrameTag);
    }

    private static boolean isFrameProvider(ActorSingleSnapshot actor) {
        return actor.lifecycle() == ActorLifecycle.ACTIVE
                && actor.role().gameplayBody()
                && !actor.collisionShape().boxes().isEmpty()
                && actor.typeSnapshot() instanceof ScriptedActorStateSnapshot scripted
                && scripted.tags().stream().anyMatch(BattleArenaBounds::isFrameTag);
    }

    private static boolean isFrameTag(String tag) {
        return "active_arena".equals(tag) || "primary_frame".equals(tag) || "battle_frame".equals(tag);
    }

    private static int providerPriority(Actor actor) {
        List<String> tags = actor.scripted().tags();
        if (tags.contains("active_arena")) return 0;
        if (tags.contains("primary_frame")) return 1;
        return 2;
    }

    private static int providerPriority(ActorSingleSnapshot actor) {
        ScriptedActorStateSnapshot scripted = (ScriptedActorStateSnapshot) actor.typeSnapshot();
        List<String> tags = scripted.tags();
        if (tags.contains("active_arena")) return 0;
        if (tags.contains("primary_frame")) return 1;
        return 2;
    }

    private static double clampAxis(double value, double min, double max) {
        if (min > max) {
            return (min + max) * 0.5D;
        }
        return Math.max(min, Math.min(max, value));
    }

    public record BoundaryContact(CanonicalVec3 correctedPosition, boolean x, boolean y, boolean z) {
        public BoundaryContact {
            Objects.requireNonNull(correctedPosition, "correctedPosition");
        }

        public boolean touching() {
            return this.x || this.y || this.z;
        }
    }
}
