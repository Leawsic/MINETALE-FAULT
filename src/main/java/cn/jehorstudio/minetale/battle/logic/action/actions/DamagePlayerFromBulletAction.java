package cn.jehorstudio.minetale.battle.logic.action.actions;

import cn.jehorstudio.minetale.battle.logic.action.BattleAction;
import cn.jehorstudio.minetale.battle.logic.action.BattleActionResult;
import cn.jehorstudio.minetale.battle.logic.actor.Actor;
import cn.jehorstudio.minetale.battle.logic.actor.ActorType;
import cn.jehorstudio.minetale.battle.logic.actor.states.types.BulletStateCache;
import cn.jehorstudio.minetale.battle.logic.event.contexts.ActorTargetContext;
import cn.jehorstudio.minetale.battle.logic.event.contexts.BattleActionContext;

public final class DamagePlayerFromBulletAction implements BattleAction {
    @Override
    public BattleActionResult run(BattleActionContext context) {
        ActorTargetContext hit = context.eventContext(ActorTargetContext.class);
        Actor bullet = context.instance().stateCache().actors().resolve(hit.actor()).orElse(null);
        Actor target = context.instance().stateCache().actors().resolve(hit.target()).orElse(null);

        if (bullet == null || target == null || bullet.type() != ActorType.BULLET || target.type() != ActorType.PLAYER_SOUL) {
            return BattleActionResult.FAILED;
        }

        BulletStateCache bulletState = bullet.bullet();
        if (bulletState == null || !bulletState.damagesPlayer() || bulletState.damage() <= 0.0D) {
            return BattleActionResult.CANCELLED;
        }

        return new DamagePlayerAction(bulletState.damage(), "white").run(context);
    }
}
