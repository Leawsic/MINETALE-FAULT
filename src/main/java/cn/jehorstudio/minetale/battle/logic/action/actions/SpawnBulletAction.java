package cn.jehorstudio.minetale.battle.logic.action.actions;

import cn.jehorstudio.minetale.battle.logic.action.BattleAction;
import cn.jehorstudio.minetale.battle.logic.action.BattleActionResult;
import cn.jehorstudio.minetale.battle.logic.actor.Actor;
import cn.jehorstudio.minetale.battle.logic.actor.ActorRef;
import cn.jehorstudio.minetale.battle.logic.coordinate.CanonicalTransform;
import cn.jehorstudio.minetale.battle.logic.coordinate.CanonicalVec3;
import cn.jehorstudio.minetale.battle.logic.actor.ActorType;
import cn.jehorstudio.minetale.battle.logic.actor.states.types.BulletStateCache;
import cn.jehorstudio.minetale.battle.logic.actor.CollisionShape;
import cn.jehorstudio.minetale.battle.logic.actor.VisualRef;
import cn.jehorstudio.minetale.battle.logic.event.contexts.BattleActionContext;

import java.util.Objects;

public final class SpawnBulletAction implements BattleAction {
    private final CanonicalTransform transform;
    private final CanonicalVec3 velocity;
    private final CollisionShape collisionShape;
    private final VisualRef visual;
    private final ActorRef sourceRef;
    private final double damage;
    private final boolean damagesPlayer;
    private final boolean displayGlobally;
    private final int remainingRebounds;
    private final double spinDegPerSecond;

    public SpawnBulletAction(
            CanonicalTransform transform,
            CanonicalVec3 velocity,
            CollisionShape collisionShape,
            VisualRef visual,
            ActorRef sourceRef,
            double damage,
            boolean damagesPlayer,
            boolean displayGlobally,
            int remainingRebounds,
            double spinDegPerSecond
    ) {
        this.transform = Objects.requireNonNull(transform, "transform");
        this.velocity = Objects.requireNonNull(velocity, "velocity");
        this.collisionShape = Objects.requireNonNull(collisionShape, "collisionShape");
        this.visual = Objects.requireNonNull(visual, "visual");
        this.sourceRef = sourceRef;
        this.damage = damage;
        this.damagesPlayer = damagesPlayer;
        this.displayGlobally = displayGlobally;
        this.remainingRebounds = remainingRebounds;
        this.spinDegPerSecond = spinDegPerSecond;
    }

    @Override
    public BattleActionResult run(BattleActionContext context) {
        Actor bullet = context.instance().stateCache().actors().createActor(ActorType.BULLET);
        bullet.setTransform(this.transform);
        bullet.setVelocity(this.velocity);
        bullet.setCollisionShape(this.collisionShape);
        bullet.setVisual(this.visual);
        bullet.setSourceRef(this.sourceRef);

        BulletStateCache bulletState = bullet.ensureBulletState();
        bulletState.setDamage(this.damage);
        bulletState.setDamagesPlayer(this.damagesPlayer);
        bulletState.setDisplayGlobally(this.displayGlobally);
        bulletState.setRemainingRebounds(this.remainingRebounds);
        bulletState.setSpinDegPerSecond(this.spinDegPerSecond);

        return BattleActionResult.COMPLETED;
    }
}
