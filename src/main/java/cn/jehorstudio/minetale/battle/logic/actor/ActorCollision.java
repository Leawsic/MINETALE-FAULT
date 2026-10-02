package cn.jehorstudio.minetale.battle.logic.actor;

import cn.jehorstudio.minetale.battle.logic.coordinate.BattleViewMode;
import cn.jehorstudio.minetale.battle.logic.coordinate.CanonicalTransform;
import cn.jehorstudio.minetale.battle.logic.coordinate.CanonicalVec3;
import cn.jehorstudio.minetale.battle.logic.coordinate.CollisionPolicy;
import cn.jehorstudio.minetale.battle.logic.coordinate.ProjectionPolicy;
import org.joml.Quaterniond;
import org.joml.Vector2d;
import org.joml.Vector3d;

public final class ActorCollision {
    private static final double EPSILON = 1.0E-8D;

    private ActorCollision() {
    }

    public static AxisAlignedBounds bounds(Actor actor) {
        return bounds(actor.transform(), actor.collisionShape());
    }

    // Snapshot 与活动 Actor 共用该入口，避免两条表现路径产生不同的世界 AABB。
    public static AxisAlignedBounds bounds(CanonicalTransform transform, CollisionShape collisionShape) {
        if (collisionShape.boxes().isEmpty()) {
            CanonicalVec3 position = transform.position();
            return new AxisAlignedBounds(position, position);
        }
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        for (CollisionBox box : collisionShape.boxes()) {
            WorldObb obb = toWorldObb(transform, box);
            for (int xSign : new int[] {-1, 1}) {
                for (int ySign : new int[] {-1, 1}) {
                    for (int zSign : new int[] {-1, 1}) {
                        Vector3d point = new Vector3d(obb.center)
                                .fma(obb.halfExtents.x * xSign, obb.axes[0])
                                .fma(obb.halfExtents.y * ySign, obb.axes[1])
                                .fma(obb.halfExtents.z * zSign, obb.axes[2]);
                        minX = Math.min(minX, point.x);
                        minY = Math.min(minY, point.y);
                        minZ = Math.min(minZ, point.z);
                        maxX = Math.max(maxX, point.x);
                        maxY = Math.max(maxY, point.y);
                        maxZ = Math.max(maxZ, point.z);
                    }
                }
            }
        }
        return new AxisAlignedBounds(
                new CanonicalVec3(minX, minY, minZ),
                new CanonicalVec3(maxX, maxY, maxZ)
        );
    }

    // targetCenter 指向碰撞包围盒中心，返回值仍是 Actor 枢轴位置；该计算不修改 Actor。
    public static CanonicalVec3 pivotPositionForBoundsCenter(Actor actor, CanonicalVec3 targetCenter) {
        AxisAlignedBounds bounds = bounds(actor);
        CanonicalVec3 currentCenter = new CanonicalVec3(
                (bounds.min().x() + bounds.max().x()) * 0.5D,
                (bounds.min().y() + bounds.max().y()) * 0.5D,
                (bounds.min().z() + bounds.max().z()) * 0.5D
        );
        return actor.transform().position().add(targetCenter.subtract(currentCenter));
    }

    public static boolean intersects(
            Actor actor,
            Actor target,
            BattleViewMode viewMode,
            CollisionPolicy defaultPolicy
    ) {
        if (!actor.active() || !target.active()) {
            return false;
        }
        if (!actor.type().participation().collisionParticipant()
                || !target.type().participation().collisionParticipant()) {
            return false;
        }
        if (!actor.role().gameplayBody() || !target.role().gameplayBody()) {
            return false;
        }

        for (CollisionBox actorBox : actor.collisionShape().boxes()) {
            WorldObb actorObb = toWorldObb(actor.transform(), actorBox);
            for (CollisionBox targetBox : target.collisionShape().boxes()) {
                WorldObb targetObb = toWorldObb(target.transform(), targetBox);
                CollisionPolicy policy = actor.collisionPolicy() != null
                        ? actor.collisionPolicy()
                        : target.collisionPolicy() != null ? target.collisionPolicy() : defaultPolicy;
                if (intersects(actorObb, targetObb, policy, viewMode)) {
                    return true;
                }
            }
        }

        return false;
    }

    private static WorldObb toWorldObb(CanonicalTransform transform, CollisionBox box) {
        Quaterniond actorRotation = rotation(transform.yawDeg(), transform.pitchDeg(), transform.rollDeg());
        Quaterniond boxRotation = rotation(box.yawDeg(), box.pitchDeg(), box.rollDeg());
        Quaterniond rotation = actorRotation.mul(boxRotation, new Quaterniond());

        CanonicalVec3 actorScale = transform.scale();
        CanonicalVec3 localCenter = box.center();
        Vector3d scaledCenter = new Vector3d(
                localCenter.x() * actorScale.x(),
                localCenter.y() * actorScale.y(),
                localCenter.z() * actorScale.z()
        );
        actorRotation.transform(scaledCenter);

        CanonicalVec3 position = transform.position();
        Vector3d center = new Vector3d(position.x(), position.y(), position.z()).add(scaledCenter);
        Vector3d halfExtents = new Vector3d(
                Math.abs(box.halfExtents().x() * actorScale.x()),
                Math.abs(box.halfExtents().y() * actorScale.y()),
                Math.abs(box.halfExtents().z() * actorScale.z())
        );

        Vector3d[] axes = new Vector3d[] {
                rotation.transform(new Vector3d(1.0D, 0.0D, 0.0D)).normalize(),
                rotation.transform(new Vector3d(0.0D, 1.0D, 0.0D)).normalize(),
                rotation.transform(new Vector3d(0.0D, 0.0D, 1.0D)).normalize()
        };

        return new WorldObb(center, axes, halfExtents);
    }

    private static boolean intersects(
            WorldObb a,
            WorldObb b,
            CollisionPolicy policy,
            BattleViewMode viewMode
    ) {
        return switch (policy.type()) {
            case VOLUME_3D -> intersects(a, b);
            case PROJECTED_2D -> projectedIntersects(a, b, viewMode.projection());
            case HYBRID_DEPTH_BAND -> projectedIntersects(a, b, viewMode.projection())
                    && depthDistance(a, b, viewMode.projection()) <= policy.maxDepthDistanceBu() + EPSILON;
        };
    }

    private static boolean projectedIntersects(WorldObb a, WorldObb b, ProjectionPolicy projection) {
        ProjectedObb projectedA = project(a, projection);
        ProjectedObb projectedB = project(b, projection);
        for (Vector2d edge : projectedA.edges) {
            if (separatedOnProjectedAxis(projectedA, projectedB, edge)) {
                return false;
            }
        }
        for (Vector2d edge : projectedB.edges) {
            if (separatedOnProjectedAxis(projectedA, projectedB, edge)) {
                return false;
            }
        }
        return true;
    }

    private static ProjectedObb project(WorldObb obb, ProjectionPolicy projection) {
        ProjectionPolicy.ProjectedPoint center = projection.project(canonical(obb.center));
        Vector2d[] edges = new Vector2d[3];
        for (int i = 0; i < 3; i++) {
            CanonicalVec3 axis = canonical(obb.axes[i]);
            edges[i] = new Vector2d(axis.dot(projection.right()), axis.dot(projection.up()));
        }
        return new ProjectedObb(new Vector2d(center.x(), center.y()), edges, obb.halfExtents);
    }

    private static boolean separatedOnProjectedAxis(ProjectedObb a, ProjectedObb b, Vector2d edge) {
        if (edge.lengthSquared() <= EPSILON) {
            return false;
        }
        Vector2d axis = new Vector2d(-edge.y, edge.x).normalize();
        double distance = Math.abs(new Vector2d(b.center).sub(a.center).dot(axis));
        return distance > projectedRadius(a, axis) + projectedRadius(b, axis) + EPSILON;
    }

    private static double projectedRadius(ProjectedObb obb, Vector2d axis) {
        return obb.halfExtents.x * Math.abs(obb.edges[0].dot(axis))
                + obb.halfExtents.y * Math.abs(obb.edges[1].dot(axis))
                + obb.halfExtents.z * Math.abs(obb.edges[2].dot(axis));
    }

    private static double depthDistance(WorldObb a, WorldObb b, ProjectionPolicy projection) {
        double aDepth = projection.project(canonical(a.center)).depth();
        double bDepth = projection.project(canonical(b.center)).depth();
        return Math.abs(aDepth - bDepth);
    }

    private static CanonicalVec3 canonical(Vector3d value) {
        return new CanonicalVec3(value.x, value.y, value.z);
    }

    private static Quaterniond rotation(double yawDeg, double pitchDeg, double rollDeg) {
        return new Quaterniond()
                .rotateY(Math.toRadians(yawDeg))
                .rotateX(Math.toRadians(pitchDeg))
                .rotateZ(Math.toRadians(rollDeg));
    }

    private static boolean intersects(WorldObb a, WorldObb b) {
        Vector3d translation = new Vector3d(b.center).sub(a.center);

        for (int i = 0; i < 3; i++) {
            if (separatedOnAxis(a, b, translation, a.axes[i])) {
                return false;
            }
            if (separatedOnAxis(a, b, translation, b.axes[i])) {
                return false;
            }
        }

        for (int aAxis = 0; aAxis < 3; aAxis++) {
            for (int bAxis = 0; bAxis < 3; bAxis++) {
                Vector3d crossAxis = new Vector3d(a.axes[aAxis]).cross(b.axes[bAxis]);
                if (crossAxis.lengthSquared() > EPSILON && separatedOnAxis(a, b, translation, crossAxis.normalize())) {
                    return false;
                }
            }
        }

        return true;
    }

    private static boolean separatedOnAxis(WorldObb a, WorldObb b, Vector3d translation, Vector3d axis) {
        double distance = Math.abs(translation.dot(axis));
        double radius = projectedRadius(a, axis) + projectedRadius(b, axis);
        return distance > radius + EPSILON;
    }

    private static double projectedRadius(WorldObb obb, Vector3d axis) {
        return obb.halfExtents.x * Math.abs(axis.dot(obb.axes[0]))
                + obb.halfExtents.y * Math.abs(axis.dot(obb.axes[1]))
                + obb.halfExtents.z * Math.abs(axis.dot(obb.axes[2]));
    }

    private record WorldObb(Vector3d center, Vector3d[] axes, Vector3d halfExtents) {
    }

    private record ProjectedObb(Vector2d center, Vector2d[] edges, Vector3d halfExtents) {
    }

    public record AxisAlignedBounds(CanonicalVec3 min, CanonicalVec3 max) {
        public AxisAlignedBounds {
            if (min.x() > max.x() || min.y() > max.y() || min.z() > max.z()) {
                throw new IllegalArgumentException("Axis-aligned bounds must satisfy min <= max.");
            }
        }
    }
}
