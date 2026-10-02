package cn.jehorstudio.minetale.battle.logic.actor.actors;

import cn.jehorstudio.minetale.battle.logic.BattleLogicStateCache;
import cn.jehorstudio.minetale.battle.logic.action.BattleActionRunner;
import cn.jehorstudio.minetale.battle.logic.action.actions.DamagePlayerFromBulletAction;
import cn.jehorstudio.minetale.battle.logic.action.actions.MoveBulletAction;
import cn.jehorstudio.minetale.battle.logic.action.actions.SetPlayerInvincibleAction;
import cn.jehorstudio.minetale.battle.logic.action.actions.SpawnBulletAction;
import cn.jehorstudio.minetale.battle.logic.actor.Actor;
import cn.jehorstudio.minetale.battle.logic.actor.ActorCollision;
import cn.jehorstudio.minetale.battle.logic.coordinate.CanonicalTransform;
import cn.jehorstudio.minetale.battle.logic.coordinate.CanonicalVec3;
import cn.jehorstudio.minetale.battle.logic.actor.ActorType;
import cn.jehorstudio.minetale.battle.logic.actor.CollisionBox;
import cn.jehorstudio.minetale.battle.logic.actor.CollisionShape;
import cn.jehorstudio.minetale.battle.logic.actor.VisualRef;
import cn.jehorstudio.minetale.battle.logic.event.LogicEventDispatcher;
import cn.jehorstudio.minetale.battle.logic.event.LogicEventListener;
import cn.jehorstudio.minetale.battle.logic.event.LogicEvents;
import cn.jehorstudio.minetale.battle.logic.event.contexts.ActorTargetContext;
import cn.jehorstudio.minetale.battle.logic.LogicTimeline;
import cn.jehorstudio.minetale.battle.logic.states.PlayerStateCache;
import cn.jehorstudio.minetale.battle.logic.rules.RuleResolvedView;
import cn.jehorstudio.minetale.lib.ObjModels;

import java.util.Objects;

public final class Bullet {
    private static final double TEST_BULLET_HALF_SIZE = 0.055D;
    private static final int TEST_BULLET_REBOUNDS = 256;

    private final BattleLogicStateCache state;
    private final LogicTimeline timeline;
    private final LogicEventDispatcher events;
    private final BattleActionRunner actions;
    private final boolean spawnInitialTestBullets;

    public Bullet(
            BattleLogicStateCache state,
            LogicTimeline timeline,
            LogicEventDispatcher events,
            BattleActionRunner actions
    ) {
        this(state, timeline, events, actions, true);
    }

    public Bullet(
            BattleLogicStateCache state,
            LogicTimeline timeline,
            LogicEventDispatcher events,
            BattleActionRunner actions,
            boolean spawnInitialTestBullets
    ) {
        this.state = Objects.requireNonNull(state, "state");
        this.timeline = Objects.requireNonNull(timeline, "timeline");
        this.events = Objects.requireNonNull(events, "events");
        this.actions = Objects.requireNonNull(actions, "actions");
        this.spawnInitialTestBullets = spawnInitialTestBullets;
    }

    @LogicEventListener(LogicEvents.ON_INITIALIZE)
    public void spawnInitialTestBullets() {
        if (!this.spawnInitialTestBullets) {
            return;
        }
        this.actions.runNow(testBullet(
                new CanonicalVec3(-0.58D, 0.0D, -0.72D),
                new CanonicalVec3(0.62D, 0.18D, 0.74D),
                VisualRef.objModel(ObjModels.BULLET1),
                0.0D
        ));
        this.actions.runNow(testBullet(
                new CanonicalVec3(0.42D, 0.0D, -0.52D),
                new CanonicalVec3(-0.48D, 0.0D, 0.86D),
                VisualRef.objModel(ObjModels.BULLET1),
                360.0D
        ));
    }

    @LogicEventListener(value = LogicEvents.ON_STEP, priority = 200)
    public void moveBullets() {
        for (Actor bullet : this.state.actors().activeActors(ActorType.BULLET)) {
            this.actions.runNow(new MoveBulletAction(bullet.ref()));
        }
    }

    @LogicEventListener(value = LogicEvents.ON_STEP, priority = -100)
    public void dispatchPlayerHits() {
        RuleResolvedView rules = RuleResolvedView.resolve(
                this.state.rules().snapshot(),
                this.state.coordinates().snapshot()
        );
        for (Actor bullet : this.state.actors().activeActors(ActorType.BULLET)) {
            if (bullet.bullet() == null || !bullet.bullet().damagesPlayer()) {
                continue;
            }

            for (Actor player : this.state.actors().activeActors(ActorType.PLAYER_SOUL)) {
                if (ActorCollision.intersects(bullet, player, rules.viewMode(), rules.collisionPolicy())) {
                    this.events.send(
                            LogicEvents.BULLET_HIT_PLAYER,
                            new ActorTargetContext(bullet.ref(), player.ref())
                    );
                }
            }
        }
    }

    @LogicEventListener(LogicEvents.BULLET_HIT_PLAYER)
    public void damagePlayer() {
        this.actions.runNow(new DamagePlayerFromBulletAction());
    }

    @LogicEventListener(value = LogicEvents.ON_STEP, priority = -200)
    public void expirePlayerInvincible() {
        PlayerStateCache players = this.state.players();
        if (!players.initialized() || !players.invincible()) {
            return;
        }

        long invincibleTicks = this.timeline.secondsToBattleTicks(players.invincibleTimeSeconds());
        long elapsedTicks = this.timeline.battleTick() - players.invincibleStartedAtBattleTick();
        if (elapsedTicks >= invincibleTicks) {
            this.actions.runNow(new SetPlayerInvincibleAction(false));
        }
    }

    private static SpawnBulletAction testBullet(
            CanonicalVec3 position,
            CanonicalVec3 velocity,
            VisualRef visual,
            double spinDegPerSecond
    ) {
        return new SpawnBulletAction(
                new CanonicalTransform(
                        position,
                        0.0D,
                        0.0D,
                        0.0D,
                        CanonicalVec3.ONE
                ),
                velocity,
                CollisionShape.obb(new CollisionBox(
                        CanonicalVec3.ZERO,
                        new CanonicalVec3(TEST_BULLET_HALF_SIZE, TEST_BULLET_HALF_SIZE, TEST_BULLET_HALF_SIZE),
                        0.0D,
                        0.0D,
                        0.0D
                )),
                visual,
                null,
                2.0D,
                true,
                false,
                TEST_BULLET_REBOUNDS,
                spinDegPerSecond
        );
    }
}
