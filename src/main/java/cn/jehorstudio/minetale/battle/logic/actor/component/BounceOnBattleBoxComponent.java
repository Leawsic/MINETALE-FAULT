package cn.jehorstudio.minetale.battle.logic.actor.component;

import cn.jehorstudio.minetale.battle.logic.action.actions.DestroyActorAction;
import cn.jehorstudio.minetale.battle.logic.actor.Actor;
import cn.jehorstudio.minetale.battle.logic.actor.BattleArenaBounds;
import cn.jehorstudio.minetale.battle.logic.coordinate.CanonicalTransform;
import cn.jehorstudio.minetale.battle.logic.coordinate.CanonicalVec3;
import cn.jehorstudio.minetale.battle.logic.event.contexts.ActorContext;
import com.google.gson.JsonObject;

final class BounceOnBattleBoxComponent implements ActorComponentRuntime {
    @Override
    public void onSpawn(ActorComponentContext context) {
        ensureRemainingRebounds(context);
    }

    @Override
    public void onCollideBattleBox(ActorComponentContext context) {
        JsonObject state = context.componentState();
        int remaining = ensureRemainingRebounds(context);
        if (remaining <= 0) {
            handleSpent(context);
            return;
        }

        Actor actor = context.self();
        CanonicalVec3 position = actor.transform().position();
        CanonicalVec3 velocity = actor.velocity();
        BattleArenaBounds.BoundaryContact contact = BattleArenaBounds
                .resolve(context.instance().stateCache().actors())
                .contact(actor);
        if (!contact.touching()) {
            return;
        }

        CanonicalTransform old = actor.transform();
        actor.setTransform(new CanonicalTransform(
                contact.correctedPosition(),
                old.yawDeg(),
                old.pitchDeg(),
                old.rollDeg(),
                old.scale()
        ));
        actor.setVelocity(new CanonicalVec3(
                contact.x() ? reflectedVelocity(position.x(), contact.correctedPosition().x(), velocity.x()) : velocity.x(),
                contact.y() ? reflectedVelocity(position.y(), contact.correctedPosition().y(), velocity.y()) : velocity.y(),
                contact.z() ? reflectedVelocity(position.z(), contact.correctedPosition().z(), velocity.z()) : velocity.z()
        ));
        state.addProperty("remainingRebounds", Math.max(0, remaining - 1));
        if (remaining - 1 <= 0) {
            handleSpent(context);
        }
    }

    private static int ensureRemainingRebounds(ActorComponentContext context) {
        JsonObject state = context.componentState();
        if (!state.has("remainingRebounds")) {
            int initial = ActorComponentJson.intValue(context.componentData(), "remainingRebounds", 256);
            state.addProperty("remainingRebounds", Math.max(0, initial));
        }
        return Math.max(0, ActorComponentJson.intValue(state, "remainingRebounds", 0));
    }

    private static void handleSpent(ActorComponentContext context) {
        String onSpent = ActorComponentJson.lowerString(context.componentData(), "onSpent", "stop");
        boolean destroy = ActorComponentJson.booleanValue(context.componentData(), "destroyWhenSpent", false)
                || "destroy".equals(onSpent);
        if (destroy) {
            context.runAction(new DestroyActorAction(context.self().ref()), new ActorContext(context.self().ref()));
            context.runtime().actors().remove(context.self().ref());
        }
    }

    private static double reflectedVelocity(double position, double correctedPosition, double velocity) {
        if (correctedPosition > position) {
            return Math.abs(velocity);
        }
        if (correctedPosition < position) {
            return -Math.abs(velocity);
        }
        return -velocity;
    }
}
