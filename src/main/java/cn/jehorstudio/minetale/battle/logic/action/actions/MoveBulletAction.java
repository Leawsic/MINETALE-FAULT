package cn.jehorstudio.minetale.battle.logic.action.actions;

import cn.jehorstudio.minetale.battle.logic.action.BattleAction;
import cn.jehorstudio.minetale.battle.logic.action.BattleActionResult;
import cn.jehorstudio.minetale.battle.logic.actor.Actor;
import cn.jehorstudio.minetale.battle.logic.actor.ActorRef;
import cn.jehorstudio.minetale.battle.logic.coordinate.CanonicalTransform;
import cn.jehorstudio.minetale.battle.logic.coordinate.CanonicalVec3;
import cn.jehorstudio.minetale.battle.logic.actor.ActorType;
import cn.jehorstudio.minetale.battle.logic.actor.states.types.BulletStateCache;
import cn.jehorstudio.minetale.battle.logic.actor.CollisionBox;
import cn.jehorstudio.minetale.battle.logic.event.contexts.BattleActionContext;

import java.util.Objects;

public final class MoveBulletAction implements BattleAction {
    private final ActorRef bulletRef;

    public MoveBulletAction(ActorRef bulletRef) {
        this.bulletRef = Objects.requireNonNull(bulletRef, "bulletRef");
    }

    @Override
    public BattleActionResult run(BattleActionContext context) {
        Actor bullet = context.instance().stateCache().actors()
                .resolve(this.bulletRef)
                .orElse(null);
        if (bullet == null || bullet.type() != ActorType.BULLET || bullet.bullet() == null) {
            return BattleActionResult.FAILED;
        }

        double seconds = context.instance().timeline().secondsPerBattleStep();
        move(bullet, seconds);
        spin(bullet, bullet.bullet(), seconds);
        bounceIfNeeded(bullet);

        return BattleActionResult.COMPLETED;
    }

    private static void move(Actor bullet, double seconds) {
        CanonicalTransform old = bullet.transform();
        bullet.setTransform(new CanonicalTransform(
                old.position().add(bullet.velocity().scale(seconds)),
                old.yawDeg(),
                old.pitchDeg(),
                old.rollDeg(),
                old.scale()
        ));
    }

    private static void spin(Actor bullet, BulletStateCache state, double seconds) {
        if (state.spinDegPerSecond() == 0.0D) {
            return;
        }

        CanonicalTransform old = bullet.transform();
        bullet.setTransform(new CanonicalTransform(
                old.position(),
                old.yawDeg() + state.spinDegPerSecond() * seconds,
                old.pitchDeg(),
                old.rollDeg(),
                old.scale()
        ));
    }

    private static void bounceIfNeeded(Actor bullet) {
        BulletStateCache state = bullet.bullet();
        if (state.remainingRebounds() <= 0) {
            return;
        }

        double half = largestLocalHalfExtent(bullet);
        CanonicalVec3 position = bullet.transform().position();
        CanonicalVec3 velocity = bullet.velocity();
        boolean bounced = false;

        BounceResult x = bounceAxis(position.x(), velocity.x(), half);
        BounceResult y = bounceAxis(position.y(), velocity.y(), half);
        BounceResult z = bounceAxis(position.z(), velocity.z(), half);

        bounced |= x.bounced;
        bounced |= y.bounced;
        bounced |= z.bounced;

        if (!bounced) {
            return;
        }

        CanonicalTransform old = bullet.transform();
        bullet.setTransform(new CanonicalTransform(
                new CanonicalVec3(x.position, y.position, z.position),
                old.yawDeg(),
                old.pitchDeg(),
                old.rollDeg(),
                old.scale()
        ));
        bullet.setVelocity(new CanonicalVec3(x.velocity, y.velocity, z.velocity));
        state.setRemainingRebounds(state.remainingRebounds() - 1);
    }

    private static double largestLocalHalfExtent(Actor bullet) {
        double half = 0.0D;
        for (CollisionBox box : bullet.collisionShape().boxes()) {
            half = Math.max(half, box.halfExtents().x());
            half = Math.max(half, box.halfExtents().y());
            half = Math.max(half, box.halfExtents().z());
        }
        return half;
    }

    private static BounceResult bounceAxis(double position, double velocity, double halfExtent) {
        double min = -1.0D + halfExtent;
        double max = 1.0D - halfExtent;
        if (position < min) {
            return new BounceResult(min, Math.abs(velocity), true);
        }
        if (position > max) {
            return new BounceResult(max, -Math.abs(velocity), true);
        }
        return new BounceResult(position, velocity, false);
    }

    private record BounceResult(double position, double velocity, boolean bounced) {
    }
}
