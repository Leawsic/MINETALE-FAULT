package cn.jehorstudio.minetale.battle.logic.actor;

import cn.jehorstudio.minetale.battle.logic.actor.states.ActorSingleSnapshot;
import cn.jehorstudio.minetale.battle.logic.actor.states.types.*;
import cn.jehorstudio.minetale.battle.logic.coordinate.CanonicalTransform;
import cn.jehorstudio.minetale.battle.logic.coordinate.CanonicalVec3;
import cn.jehorstudio.minetale.battle.logic.coordinate.CollisionPolicy;
import cn.jehorstudio.minetale.battle.network.NetworkObjectId;

import java.util.Objects;
import java.util.UUID;

public final class Actor {
    private final ActorRef ref;
    private final ActorType type;
    private ActorLifecycle lifecycle = ActorLifecycle.ACTIVE;

    private ActorRole role = ActorRole.GAMEPLAY;
    private CanonicalTransform transform = CanonicalTransform.IDENTITY;
    private long transformDiscontinuity;
    private CanonicalVec3 velocity = CanonicalVec3.ZERO;

    private CollisionShape collisionShape = CollisionShape.POINT;
    private CollisionPolicy collisionPolicy;

    private VisualRef visual = VisualRef.voidVisual();

    private ActorRef sourceRef;

    private BulletStateCache bullet;

    private ScriptedActorStateCache scripted;

    private NetworkProxyStateCache networkProxy;

    public Actor(ActorRef ref) {
        this(ref, false);
    }

    private Actor(ActorRef ref, boolean allowNetworkProxy) {
        this.ref = Objects.requireNonNull(ref, "ref");
        this.type = ref.type();
        if (this.type == ActorType.NETWORK_PROXY && !allowNetworkProxy) {
            throw new IllegalArgumentException("NETWORK_PROXY actors must be created with Actor.createNetworkProxy.");
        }
    }

    public static Actor createNetworkProxy(
            ActorRef ref,
            NetworkObjectId networkObjectId,
            UUID ownerPlayerId,
            ActorType proxyType,
            VisualRef visual,
            ActorLifecycle lifecycle
    ) {
        Objects.requireNonNull(ref, "ref");
        if (ref.type() != ActorType.NETWORK_PROXY) {
            throw new IllegalArgumentException("Network proxy actor ref must have NETWORK_PROXY type.");
        }
        Actor actor = new Actor(ref, true);
        actor.ensureNetworkProxyState().setNetworkIdentity(networkObjectId, ownerPlayerId, proxyType);
        actor.setVisual(visual);
        actor.setLifecycle(lifecycle);
        return actor;
    }

    public ActorRef ref() {
        return this.ref;
    }

    public ActorType type() {
        return this.type;
    }

    public ActorLifecycle lifecycle() {
        return this.lifecycle;
    }

    public void setLifecycle(ActorLifecycle lifecycle) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
    }

    public boolean active() {
        return this.lifecycle == ActorLifecycle.ACTIVE;
    }

    public ActorRole role() {
        return this.role;
    }

    public void setRole(ActorRole role) {
        this.role = Objects.requireNonNull(role, "role");
    }

    public CanonicalTransform transform() {
        return this.transform;
    }

    public void setTransform(CanonicalTransform transform) {
        this.transform = Objects.requireNonNull(transform, "transform");
    }

    // 递增断点版本，使表现层跳过本次位置突变两侧的插值。
    public void teleport(CanonicalTransform transform) {
        this.transform = Objects.requireNonNull(transform, "transform");
        this.transformDiscontinuity = Math.incrementExact(this.transformDiscontinuity);
    }

    public long transformDiscontinuity() {
        return this.transformDiscontinuity;
    }

    public CanonicalVec3 velocity() {
        return this.velocity;
    }

    public void setVelocity(CanonicalVec3 velocity) {
        this.velocity = Objects.requireNonNull(velocity, "velocity");
    }

    public CollisionShape collisionShape() {
        return this.collisionShape;
    }

    public void setCollisionShape(CollisionShape collisionShape) {
        this.collisionShape = Objects.requireNonNull(collisionShape, "collisionShape");
    }

    public VisualRef visual() {
        return this.visual;
    }

    public void setVisual(VisualRef visual) {
        this.visual = Objects.requireNonNull(visual, "visual");
    }

    public ActorRef sourceRef() {
        return this.sourceRef;
    }

    public void setSourceRef(ActorRef sourceRef) {
        this.sourceRef = sourceRef;
    }

    public BulletStateCache bullet() {
        return this.bullet;
    }

    public BulletStateCache ensureBulletState() {
        if (this.type != ActorType.BULLET) {
            throw new IllegalStateException("Only BULLET actors can hold BulletStateCache.");
        }
        if (this.bullet == null) {
            this.bullet = new BulletStateCache();
        }
        return this.bullet;
    }

    public CollisionPolicy collisionPolicy() {
        return this.collisionPolicy;
    }

    public void setCollisionPolicy(CollisionPolicy collisionPolicy) {
        this.collisionPolicy = collisionPolicy;
    }

    public ScriptedActorStateCache scripted() {
        return this.scripted;
    }

    public ScriptedActorStateCache ensureScriptedState() {
        if (this.type != ActorType.SCRIPTED_ACTOR) {
            throw new IllegalStateException("Only SCRIPTED_ACTOR actors can hold ScriptedActorStateCache.");
        }
        if (this.scripted == null) {
            this.scripted = new ScriptedActorStateCache();
        }
        return this.scripted;
    }

    public NetworkProxyStateCache networkProxy() {
        return this.networkProxy;
    }

    public NetworkProxyStateCache ensureNetworkProxyState() {
        if (this.type != ActorType.NETWORK_PROXY) {
            throw new IllegalStateException("Only NETWORK_PROXY actors can hold NetworkProxyStateCache.");
        }
        if (this.networkProxy == null) {
            this.networkProxy = new NetworkProxyStateCache();
        }
        return this.networkProxy;
    }

    public ActorSingleSnapshot snapshot() {
        return new ActorSingleSnapshot(
                this.ref,
                this.type,
                this.lifecycle,
                this.role,
                this.transform,
                this.transformDiscontinuity,
                this.velocity,
                this.collisionShape,
                this.collisionPolicy,
                this.visual,
                this.sourceRef,
                this.typeSnapshot()
        );
    }

    private ActorTypeSnapshot typeSnapshot() {
        return switch (this.type) {
            case BATTLE_BOX -> new NoActorTypeSnapshot(ActorType.BATTLE_BOX);
            case PLAYER_SOUL -> PlayerSoulStateSnapshot.DEFAULT;
            case BULLET -> this.bullet == null ? BulletStateSnapshot.EMPTY : this.bullet.snapshot();
            case SCRIPTED_ACTOR -> this.scripted == null ? ScriptedActorStateSnapshot.EMPTY : this.scripted.snapshot();
            case NETWORK_PROXY -> {
                if (this.networkProxy == null) {
                    throw new IllegalStateException("NETWORK_PROXY actor has no NetworkProxyStateCache.");
                }
                yield this.networkProxy.snapshot();
            }
        };
    }
}
