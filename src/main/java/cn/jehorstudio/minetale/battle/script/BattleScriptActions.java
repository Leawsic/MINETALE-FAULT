package cn.jehorstudio.minetale.battle.script;

import cn.jehorstudio.minetale.battle.logic.action.BattleAction;
import cn.jehorstudio.minetale.battle.logic.action.BattleActionConflict;
import cn.jehorstudio.minetale.battle.logic.action.BattleActionConflictKeys;
import cn.jehorstudio.minetale.battle.logic.action.BattleActionConflictPolicy;
import cn.jehorstudio.minetale.battle.logic.action.BattleActionResult;
import cn.jehorstudio.minetale.battle.logic.action.BattleSoundRequest;
import cn.jehorstudio.minetale.battle.logic.actor.Actor;
import cn.jehorstudio.minetale.battle.logic.actor.ActorAppearance;
import cn.jehorstudio.minetale.battle.logic.actor.ActorCollision;
import cn.jehorstudio.minetale.battle.logic.actor.ActorLifecycle;
import cn.jehorstudio.minetale.battle.logic.actor.ActorRef;
import cn.jehorstudio.minetale.battle.logic.actor.ActorRole;
import cn.jehorstudio.minetale.battle.logic.actor.ActorKindRef;
import cn.jehorstudio.minetale.battle.logic.actor.ActorTemplateRef;
import cn.jehorstudio.minetale.battle.logic.actor.ActorType;
import cn.jehorstudio.minetale.battle.logic.actor.CollisionBox;
import cn.jehorstudio.minetale.battle.logic.actor.CollisionShape;
import cn.jehorstudio.minetale.battle.logic.actor.VisualRef;
import cn.jehorstudio.minetale.battle.logic.action.actions.DamagePlayerAction;
import cn.jehorstudio.minetale.battle.logic.action.actions.DestroyActorAction;
import cn.jehorstudio.minetale.battle.logic.action.actions.HealPlayerAction;
import cn.jehorstudio.minetale.battle.logic.action.actions.RequestSmoothCameraAction;
import cn.jehorstudio.minetale.battle.presentation.VisualConfig;
import cn.jehorstudio.minetale.battle.logic.actor.states.types.BulletStateCache;
import cn.jehorstudio.minetale.battle.logic.actor.states.types.ScriptedActorStateCache;
import cn.jehorstudio.minetale.battle.logic.blackboard.value.BattleScriptValue;
import cn.jehorstudio.minetale.battle.logic.coordinate.BattleCoordinateSpace;
import cn.jehorstudio.minetale.battle.logic.coordinate.BattleCoordinateStateCache;
import cn.jehorstudio.minetale.battle.logic.coordinate.BattleViewMode;
import cn.jehorstudio.minetale.battle.logic.coordinate.CanonicalTransform;
import cn.jehorstudio.minetale.battle.logic.coordinate.CanonicalVec3;
import cn.jehorstudio.minetale.battle.logic.coordinate.CollisionPolicy;
import cn.jehorstudio.minetale.battle.logic.event.contexts.ActorContext;
import cn.jehorstudio.minetale.battle.logic.event.contexts.ActorTargetContext;
import cn.jehorstudio.minetale.battle.logic.event.contexts.BattleActionContext;
import cn.jehorstudio.minetale.battle.logic.states.AppliedRule;
import cn.jehorstudio.minetale.battle.network.payload.BattleResultState;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Map;
import java.util.Arrays;

final class BattleScriptSequenceAction implements BattleAction {
    private final BattleScriptActionFactory factory;
    private final List<JsonObject> actions;
    private int index;
    private BattleAction current;

    BattleScriptSequenceAction(BattleScriptActionFactory factory, List<JsonObject> actions) {
        this.factory = Objects.requireNonNull(factory, "factory");
        this.actions = actions.stream().map(JsonObject::deepCopy).toList();
    }

    @Override
    public BattleActionResult run(BattleActionContext context) {
        while (this.index < this.actions.size()) {
            if (context.instance().ended()) {
                return BattleActionResult.CANCELLED;
            }
            if (this.current == null) {
                this.current = this.factory.create(this.actions.get(this.index));
            }
            BattleActionResult result = this.current.run(context);
            if (context.instance().ended()) {
                return BattleActionResult.CANCELLED;
            }
            if (result == BattleActionResult.RUNNING) {
                return BattleActionResult.RUNNING;
            }
            if (result != BattleActionResult.COMPLETED && result != BattleActionResult.CANCELLED) {
                return result;
            }
            this.index++;
            this.current = null;
        }
        return BattleActionResult.COMPLETED;
    }
}

final class BattleScriptParallelAction implements BattleAction {
    private final List<BattleAction> actions;
    private final boolean[] completed;

    BattleScriptParallelAction(BattleScriptActionFactory factory, List<JsonObject> actions) {
        Objects.requireNonNull(factory, "factory");
        this.actions = actions.stream().map(factory::create).toList();
        this.completed = new boolean[this.actions.size()];
    }

    @Override
    public BattleActionResult run(BattleActionContext context) {
        boolean allCompleted = true;
        for (int i = 0; i < this.actions.size(); i++) {
            if (context.instance().ended()) {
                return BattleActionResult.CANCELLED;
            }
            if (this.completed[i]) {
                continue;
            }
            BattleActionResult result = this.actions.get(i).run(context);
            if (context.instance().ended()) {
                return BattleActionResult.CANCELLED;
            }
            if (result == BattleActionResult.RUNNING) {
                allCompleted = false;
                continue;
            }
            if (result != BattleActionResult.COMPLETED && result != BattleActionResult.CANCELLED) {
                return result;
            }
            this.completed[i] = true;
        }
        for (boolean done : this.completed) {
            allCompleted &= done;
        }
        return allCompleted ? BattleActionResult.COMPLETED : BattleActionResult.RUNNING;
    }
}

final class BattleScriptRandomOneAction implements BattleAction {
    private final BattleScriptRuntime runtime;
    private final BattleScriptActionFactory factory;
    private final List<List<JsonObject>> options;
    private BattleScriptSequenceAction chosen;

    BattleScriptRandomOneAction(BattleScriptRuntime runtime, BattleScriptActionFactory factory, List<List<JsonObject>> options) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.factory = Objects.requireNonNull(factory, "factory");
        this.options = options.stream()
                .map(option -> option.stream().map(JsonObject::deepCopy).toList())
                .toList();
    }

    @Override
    public BattleActionResult run(BattleActionContext context) {
        if (context.instance().ended()) {
            return BattleActionResult.CANCELLED;
        }
        if (this.options.isEmpty()) {
            return BattleActionResult.COMPLETED;
        }
        if (this.chosen == null) {
            this.chosen = new BattleScriptSequenceAction(
                    this.factory,
                    this.options.get(this.runtime.random().nextInt(this.options.size()))
            );
        }
        return this.chosen.run(context);
    }
}

final class BattleScriptRepeatAction implements BattleAction {
    private final BattleScriptRuntime runtime;
    private final BattleScriptActionFactory factory;
    private final JsonObject action;
    private final List<JsonObject> actions;
    private int completedIterations;
    private int loopChecks;
    private BattleScriptSequenceAction current;

    BattleScriptRepeatAction(BattleScriptRuntime runtime, BattleScriptActionFactory factory, JsonObject action, List<JsonObject> actions) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.factory = Objects.requireNonNull(factory, "factory");
        this.action = action.deepCopy();
        this.actions = actions.stream().map(JsonObject::deepCopy).toList();
    }

    @Override
    public BattleActionResult run(BattleActionContext context) {
        int count = this.action.has("count") ? this.action.get("count").getAsInt() : 1;
        count = Math.max(0, count);
        while (this.completedIterations < count) {
            if (context.instance().ended()) {
                return BattleActionResult.CANCELLED;
            }
            this.loopChecks++;
            if (this.loopChecks > this.runtime.definition().compiled().budgets().maxLoopIterations()) {
                return BattleActionResult.FAILED;
            }
            if (this.current == null) {
                this.current = new BattleScriptSequenceAction(this.factory, this.actions);
            }
            BattleActionResult result = this.current.run(context);
            if (context.instance().ended()) {
                return BattleActionResult.CANCELLED;
            }
            if (result == BattleActionResult.RUNNING) {
                return BattleActionResult.RUNNING;
            }
            if (result != BattleActionResult.COMPLETED && result != BattleActionResult.CANCELLED) {
                return result;
            }
            this.completedIterations++;
            this.current = null;
        }
        return BattleActionResult.COMPLETED;
    }
}

final class BattleScriptUntilAction implements BattleAction {
    private final BattleScriptRuntime runtime;
    private final BattleScriptActionFactory factory;
    private final JsonObject action;
    private final List<JsonObject> actions;
    private int loopChecks;
    private BattleScriptSequenceAction current;

    BattleScriptUntilAction(BattleScriptRuntime runtime, BattleScriptActionFactory factory, JsonObject action, List<JsonObject> actions) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.factory = Objects.requireNonNull(factory, "factory");
        this.action = action.deepCopy();
        this.actions = actions.stream().map(JsonObject::deepCopy).toList();
    }

    @Override
    public BattleActionResult run(BattleActionContext context) {
        if (context.instance().ended()) {
            return BattleActionResult.CANCELLED;
        }
        JsonObject when = BattleScriptJson.requireObject(this.action, "when", "until");
        if (this.runtime.conditions().evaluate(when, context)) {
            return BattleActionResult.COMPLETED;
        }
        this.loopChecks++;
        if (this.loopChecks > this.runtime.definition().compiled().budgets().maxLoopIterations()) {
            return BattleActionResult.FAILED;
        }
        if (this.current == null) {
            this.current = new BattleScriptSequenceAction(this.factory, this.actions);
        }
        BattleActionResult result = this.current.run(context);
        if (context.instance().ended()) {
            return BattleActionResult.CANCELLED;
        }
        if (result == BattleActionResult.RUNNING) {
            return BattleActionResult.RUNNING;
        }
        if (result != BattleActionResult.COMPLETED && result != BattleActionResult.CANCELLED) {
            return result;
        }
        this.current = null;
        return BattleActionResult.RUNNING;
    }
}

final class BattleScriptWaitUntilAction implements BattleAction {
    private final BattleScriptRuntime runtime;
    private final JsonObject condition;
    private long waitStartSignalSequence = Long.MIN_VALUE;

    BattleScriptWaitUntilAction(BattleScriptRuntime runtime, JsonObject action) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.condition = BattleScriptJson.requireObject(action, "when", "wait_until").deepCopy();
    }

    @Override
    public BattleActionResult run(BattleActionContext context) {
        if (context.instance().ended()) {
            return BattleActionResult.CANCELLED;
        }
        if (this.waitStartSignalSequence == Long.MIN_VALUE) {
            this.waitStartSignalSequence = this.runtime.signals().currentSequence();
        }
        return this.runtime.conditions().evaluate(this.condition, context, this.waitStartSignalSequence)
                ? BattleActionResult.COMPLETED
                : BattleActionResult.RUNNING;
    }
}

final class BattleScriptIfAction implements BattleAction {
    private final BattleScriptRuntime runtime;
    private final BattleScriptActionFactory factory;
    private final JsonObject action;
    private final List<JsonObject> actions;
    private final List<JsonObject> elseActions;
    private BattleScriptSequenceAction current;
    private boolean selected;

    BattleScriptIfAction(BattleScriptRuntime runtime, BattleScriptActionFactory factory, JsonObject action) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.factory = Objects.requireNonNull(factory, "factory");
        this.action = action.deepCopy();
        this.actions = BattleScriptJson.objectList(BattleScriptJson.optionalArray(action, "actions"), "if.actions");
        this.elseActions = BattleScriptJson.objectList(BattleScriptJson.optionalArray(action, "elseActions"), "if.elseActions");
    }

    @Override
    public BattleActionResult run(BattleActionContext context) {
        if (context.instance().ended()) {
            return BattleActionResult.CANCELLED;
        }
        if (!this.selected) {
            JsonObject when = BattleScriptJson.requireObject(this.action, "when", "if");
            List<JsonObject> selectedActions = this.runtime.conditions().evaluate(when, context) ? this.actions : this.elseActions;
            this.selected = true;
            if (selectedActions.isEmpty()) {
                return BattleActionResult.COMPLETED;
            }
            this.current = new BattleScriptSequenceAction(this.factory, selectedActions);
        }
        return this.current == null ? BattleActionResult.COMPLETED : this.current.run(context);
    }
}

final class BattleScriptSpawnActorAction implements BattleAction {
    private final BattleScriptRuntime runtime;
    private final JsonObject action;

    BattleScriptSpawnActorAction(BattleScriptRuntime runtime, JsonObject action) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.action = action.deepCopy();
    }

    @Override
    public BattleActionResult run(BattleActionContext context) {
        JsonObject data = mergedActorData();
        String type = BattleScriptJson.optionalString(data, "type").orElse("bullet");
        if ("scripted_actor".equals(type)) {
            return spawnScriptedActor(context, data);
        }
        if (!"bullet".equals(type)) {
            return BattleActionResult.FAILED;
        }
        return spawnBullet(context, data);
    }

    private BattleActionResult spawnBullet(BattleActionContext context, JsonObject data) {
        this.runtime.recordActorSpawn(context.instance().timeline().battleTick());
        BattleScriptEvaluationContext evaluationContext = new BattleScriptEvaluationContext(this.runtime, context);

        Actor actor = context.instance().stateCache().actors().createActor(ActorType.BULLET);
        actor.setTransform(transform(data, evaluationContext));
        actor.setVelocity(velocity(data, evaluationContext));
        actor.setCollisionShape(collision(data));
        if (data.has("collisionPolicy")) actor.setCollisionPolicy(collisionPolicy(data));
        actor.setVisual(visual(data));

        BulletStateCache bulletState = actor.ensureBulletState();
        bulletState.setDamage(numberValue(data, "damage", 2.0D, evaluationContext));
        bulletState.setDamagesPlayer(booleanValue(data, "damagesPlayer", true, evaluationContext));
        bulletState.setDisplayGlobally(booleanValue(data, "displayGlobally", false));
        bulletState.setRemainingRebounds(intValue(data, "remainingRebounds", 256, evaluationContext));
        bulletState.setSpinDegPerSecond(numberValue(data, "spinDegPerSecond", 0.0D, evaluationContext));

        this.runtime.actors().register(actor.ref(), scriptActorId(data), tags(data));
        return BattleActionResult.COMPLETED;
    }

    private BattleActionResult spawnScriptedActor(BattleActionContext context, JsonObject data) {
        this.runtime.recordActorSpawn(context.instance().timeline().battleTick());
        BattleScriptEvaluationContext evaluationContext = new BattleScriptEvaluationContext(this.runtime, context);

        Actor actor = context.instance().stateCache().actors().createActor(ActorType.SCRIPTED_ACTOR);
        actor.setRole(ActorRole.parse(BattleScriptJson.optionalString(data, "role").orElse("gameplay")));
        actor.setTransform(transform(data, evaluationContext));
        actor.setVelocity(velocity(data, evaluationContext));
        actor.setCollisionShape(actor.role().gameplayBody() ? collision(data) : CollisionShape.none());
        if (data.has("collisionPolicy")) actor.setCollisionPolicy(collisionPolicy(data));
        actor.setVisual(visual(data));

        ScriptedActorStateCache scripted = actor.ensureScriptedState();
        scripted.setKind(ActorKindRef.of(BattleScriptJson.requireString(data, "kind", "scripted_actor")));
        scripted.setTemplateRef(ActorTemplateRef.of(templateRef(data)));
        scripted.setRuntimeId(scriptActorId(data).orElse(""));
        scripted.setTags(tags(data));
        initializeVars(scripted, data, this.runtime);

        this.runtime.actors().register(actor.ref(), scriptActorId(data), scripted.tags());
        this.runtime.scriptedActors().dispatchSpawn(actor);
        return BattleActionResult.COMPLETED;
    }

    private JsonObject mergedActorData() {
        String actionType = BattleScriptJson.requireString(this.action, "type", "spawn action");
        String prefabRef = BattleScriptJson.requireString(this.action, "prefab", actionType);
        JsonObject data = this.runtime.definition().compiled().resolveActorPrefab(prefabRef)
                .orElseThrow(() -> new IllegalArgumentException("Unknown actor prefab: " + prefabRef))
                .root();
        data.addProperty("_templateRef", prefabRef);
        for (MapEntry entry : entries(this.action)) {
            if ("type".equals(entry.key()) || "prefab".equals(entry.key())) {
                continue;
            }
            if ("tags".equals(entry.key()) && entry.value().isJsonArray()) {
                data.add("tags", mergeTags(BattleScriptJson.optionalArray(data, "tags"), entry.value().getAsJsonArray()));
                continue;
            }
            if ("transform".equals(entry.key()) && entry.value().isJsonObject() && data.has("transform")) {
                data.add("transform", mergeObject(data.getAsJsonObject("transform"), entry.value().getAsJsonObject()));
                continue;
            }
            if ("tag".equals(entry.key())) {
                JsonArray tags = BattleScriptJson.optionalArray(data, "tags");
                tags.add(entry.value().getAsString());
                data.add("tags", tags);
                continue;
            }
            data.add(entry.key(), entry.value().deepCopy());
        }
        return data;
    }

    private CanonicalTransform transform(JsonObject data, BattleScriptEvaluationContext context) {
        JsonObject transform = BattleScriptJson.optionalObject(data, "transform").orElseGet(JsonObject::new);
        BattleCoordinateSpace space = BattleCoordinateSpace.require(
                BattleScriptJson.optionalString(transform, "space").orElse(BattleCoordinateSpace.CANONICAL.id()),
                "transform.space"
        );
        CanonicalVec3 rotation = vec3(transform, "rotationDeg", CanonicalVec3.ZERO, context);
        return new CanonicalTransform(
                space,
                vec3(transform, "position", CanonicalVec3.ZERO, context),
                rotation,
                vec3(transform, "scale", CanonicalVec3.ONE, context)
        );
    }

    private CanonicalVec3 velocity(JsonObject data, BattleScriptEvaluationContext context) {
        JsonElement element = data.get("velocity");
        if (element == null) {
            return CanonicalVec3.ZERO;
        }
        JsonObject velocity = BattleScriptJson.requireObject(element, "velocity");
        BattleCoordinateSpace.require(
                BattleScriptJson.requireString(velocity, "space", "velocity"),
                "velocity.space"
        );
        return this.runtime.values().evalVector3(
                Objects.requireNonNull(velocity.get("value"), "velocity.value"),
                context
        );
    }

    private static CollisionShape collision(JsonObject data) {
        JsonElement collision = data.get("collision");
        if (collision != null) {
            JsonObject object = BattleScriptJson.requireObject(collision, "collision");
            if (!"none".equals(BattleScriptJson.optionalString(object, "shape").orElse("box"))) {
                BattleCoordinateSpace.require(
                        BattleScriptJson.requireString(object, "space", "collision"),
                        "collision.space"
                );
            }
            String shape = BattleScriptJson.optionalString(object, "shape").orElse("box");
            if ("none".equals(shape)) {
                return CollisionShape.none();
            }
            if (!"box".equals(shape)) {
                throw new IllegalArgumentException("Unsupported collision shape: " + shape);
            }
            CanonicalVec3 halfSize = vec3(object, "halfSize", new CanonicalVec3(0.055D, 0.055D, 0.055D));
            return CollisionShape.obb(new CollisionBox(CanonicalVec3.ZERO, halfSize, 0.0D, 0.0D, 0.0D));
        }
        double halfSize = doubleValue(data, "halfSize", 0.055D);
        return CollisionShape.obb(new CollisionBox(
                CanonicalVec3.ZERO,
                new CanonicalVec3(halfSize, halfSize, halfSize),
                0.0D,
                0.0D,
                0.0D
        ));
    }

    private static CollisionPolicy collisionPolicy(JsonObject data) {
        JsonObject policy = BattleScriptJson.optionalObject(data, "collisionPolicy").orElse(null);
        if (policy == null) return null;
        return switch (BattleScriptJson.requireString(policy, "type", "collisionPolicy")) {
            case "volume_3d" -> CollisionPolicy.VOLUME_3D;
            case "projected_2d" -> CollisionPolicy.PROJECTED_2D;
            case "hybrid_depth_band" -> CollisionPolicy.hybrid(
                    doubleValue(policy, "maxDepthDistance", 0.2D)
            );
            default -> throw new IllegalArgumentException("Unsupported collision policy.");
        };
    }

    static VisualRef visual(JsonObject data) {
        return VisualRef.fromJson(data.get("visual"));
    }

    static CanonicalVec3 vec3(JsonObject object, String member, CanonicalVec3 fallback) {
        JsonElement element = object.get(member);
        if (element == null) {
            return fallback;
        }
        if (!element.isJsonArray()) {
            throw new IllegalArgumentException(member + " must be a 3-number array.");
        }
        JsonArray array = element.getAsJsonArray();
        if (array.size() != 3) {
            throw new IllegalArgumentException(member + " must contain exactly 3 numbers.");
        }
        return new CanonicalVec3(array.get(0).getAsDouble(), array.get(1).getAsDouble(), array.get(2).getAsDouble());
    }

    private CanonicalVec3 vec3(
            JsonObject object,
            String member,
            CanonicalVec3 fallback,
            BattleScriptEvaluationContext context
    ) {
        JsonElement element = object.get(member);
        return element == null ? fallback : this.runtime.values().evalVector3(element, context);
    }

    private static JsonObject mergeObject(JsonObject base, JsonObject overlay) {
        JsonObject result = base.deepCopy();
        for (Map.Entry<String, JsonElement> entry : overlay.entrySet()) {
            result.add(entry.getKey(), entry.getValue().deepCopy());
        }
        return result;
    }

    private static Optional<String> scriptActorId(JsonObject data) {
        return BattleScriptJson.optionalString(data, "actorId")
                .or(() -> BattleScriptJson.optionalString(data, "scriptId"));
    }

    private static String templateRef(JsonObject data) {
        return BattleScriptJson.optionalString(data, "_templateRef")
                .or(() -> BattleScriptJson.optionalString(data, "id"))
                .orElse(BattleScriptJson.requireString(data, "kind", "scripted_actor"));
    }

    private static List<String> tags(JsonObject data) {
        List<String> tags = new ArrayList<>();
        BattleScriptJson.optionalString(data, "tag").ifPresent(tags::add);
        if (data.has("tags")) {
            tags.addAll(BattleScriptJson.stringList(data.getAsJsonArray("tags"), "tags"));
        }
        return List.copyOf(tags);
    }

    private static void initializeVars(ScriptedActorStateCache scripted, JsonObject data, BattleScriptRuntime runtime) {
        JsonObject vars = BattleScriptJson.optionalObject(data, "vars").orElseGet(JsonObject::new);
        BattleScriptEvaluationContext evaluationContext = new BattleScriptEvaluationContext(runtime, null);
        for (Map.Entry<String, JsonElement> entry : vars.entrySet()) {
            JsonObject var = BattleScriptJson.requireObject(entry.getValue(), "scripted_actor.vars." + entry.getKey());
            BattleScriptValue.Type type = BattleScriptValue.Type.parse(
                    BattleScriptJson.requireString(var, "type", "scripted_actor.vars." + entry.getKey()),
                    "scripted_actor.vars." + entry.getKey() + ".type"
            );
            JsonElement initial = var.get("initial");
            if (initial == null) {
                throw new IllegalArgumentException("scripted_actor.vars." + entry.getKey() + ".initial is required.");
            }
            scripted.setVar(entry.getKey(), runtime.values().evalTyped(initial, type, evaluationContext));
        }
    }

    private static JsonArray mergeTags(JsonArray base, JsonArray overlay) {
        JsonArray result = new JsonArray();
        for (JsonElement element : base) {
            result.add(element.deepCopy());
        }
        for (JsonElement element : overlay) {
            result.add(element.deepCopy());
        }
        return result;
    }

    private static int intValue(JsonObject object, String member, int fallback) {
        JsonElement element = object.get(member);
        return element == null ? fallback : element.getAsInt();
    }

    private static double doubleValue(JsonObject object, String member, double fallback) {
        JsonElement element = object.get(member);
        return element == null ? fallback : element.getAsDouble();
    }

    private double numberValue(JsonObject object, String member, double fallback, BattleScriptEvaluationContext context) {
        JsonElement element = object.get(member);
        return element == null ? fallback : this.runtime.values().evalNumber(element, context);
    }

    private int intValue(JsonObject object, String member, int fallback, BattleScriptEvaluationContext context) {
        JsonElement element = object.get(member);
        return element == null
                ? fallback
                : this.runtime.values().evalTyped(element, BattleScriptValue.Type.INT, context).asInt();
    }

    private static boolean booleanValue(JsonObject object, String member, boolean fallback) {
        JsonElement element = object.get(member);
        return element == null ? fallback : element.getAsBoolean();
    }

    private boolean booleanValue(JsonObject object, String member, boolean fallback, BattleScriptEvaluationContext context) {
        JsonElement element = object.get(member);
        return element == null ? fallback : this.runtime.values().evalBoolean(element, context);
    }

    private static List<MapEntry> entries(JsonObject object) {
        List<MapEntry> result = new ArrayList<>();
        for (java.util.Map.Entry<String, JsonElement> entry : object.entrySet()) {
            result.add(new MapEntry(entry.getKey(), entry.getValue()));
        }
        return result;
    }

    private record MapEntry(String key, JsonElement value) {
    }
}

final class BattleScriptDestroyActorAction implements BattleAction {
    private final BattleScriptRuntime runtime;
    private final JsonObject action;

    BattleScriptDestroyActorAction(BattleScriptRuntime runtime, JsonObject action) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.action = action.deepCopy();
    }

    @Override
    public BattleActionResult run(BattleActionContext context) {
        for (ActorRef ref : BattleScriptTargetResolver.resolve(this.runtime, this.action, context)) {
            context.instance().stateCache().actors().resolve(ref).ifPresent(actor -> {
                actor.setLifecycle(ActorLifecycle.REMOVED);
                this.runtime.actors().remove(ref);
            });
        }
        return BattleActionResult.COMPLETED;
    }
}

final class BattleScriptCleanupTagAction implements BattleAction {
    private final BattleScriptRuntime runtime;
    private final String tag;

    BattleScriptCleanupTagAction(BattleScriptRuntime runtime, JsonObject action) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.tag = BattleScriptJson.requireString(action, "tag", "cleanup_tag");
    }

    @Override
    public BattleActionResult run(BattleActionContext context) {
        for (ActorRef ref : this.runtime.actors().byTag(this.tag)) {
            context.instance().stateCache().actors().resolve(ref).ifPresent(actor -> actor.setLifecycle(ActorLifecycle.REMOVED));
            this.runtime.actors().remove(ref);
        }
        return BattleActionResult.COMPLETED;
    }
}

final class BattleScriptSetVariableAction implements BattleAction {
    private final BattleScriptRuntime runtime;
    private final String variableId;
    private final JsonElement value;

    BattleScriptSetVariableAction(BattleScriptRuntime runtime, JsonObject action) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.variableId = BattleScriptJson.requireString(action, "var", "set_var");
        this.value = Objects.requireNonNull(action.get("value"), "set_var.value").deepCopy();
    }

    @Override
    public BattleActionResult run(BattleActionContext context) {
        BattleScriptValue current = context.instance().stateCache().values().require(this.variableId);
        context.instance().stateCache().values().set(
                this.variableId,
                this.runtime.values().evalTyped(this.value, current.type(), new BattleScriptEvaluationContext(this.runtime, context))
        );
        return BattleActionResult.COMPLETED;
    }
}

final class BattleScriptModifyVariableAction implements BattleAction {
    private final BattleScriptRuntime runtime;
    private final String variableId;
    private final String op;
    private final JsonElement amount;

    BattleScriptModifyVariableAction(BattleScriptRuntime runtime, JsonObject action) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.variableId = BattleScriptJson.requireString(action, "var", "modify_var");
        this.op = BattleScriptJson.optionalString(action, "op").orElse("add");
        JsonElement amountElement = action.has("amount") ? action.get("amount") : action.get("value");
        this.amount = Objects.requireNonNull(amountElement, "modify_var.amount").deepCopy();
    }

    @Override
    public BattleActionResult run(BattleActionContext context) {
        BattleScriptValue current = context.instance().stateCache().values().require(this.variableId);
        double left = current.asDouble();
        double amountValue = this.runtime.values().evalNumber(this.amount, new BattleScriptEvaluationContext(this.runtime, context));
        double result = switch (this.op) {
            case "add" -> left + amountValue;
            case "sub" -> left - amountValue;
            case "mul" -> left * amountValue;
            case "div" -> left / amountValue;
            case "set" -> amountValue;
            default -> throw new IllegalArgumentException("Unsupported modify_var op: " + this.op);
        };
        context.instance().stateCache().values().set(this.variableId, current.withNumber(result));
        return BattleActionResult.COMPLETED;
    }
}

final class BattleScriptSetActorVariableAction implements BattleAction {
    private final BattleScriptRuntime runtime;
    private final String variableId;
    private final JsonElement value;

    BattleScriptSetActorVariableAction(BattleScriptRuntime runtime, JsonObject action) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.variableId = BattleScriptJson.requireString(action, "var", "set_actor_var");
        this.value = Objects.requireNonNull(action.get("value"), "set_actor_var.value").deepCopy();
    }

    @Override
    public BattleActionResult run(BattleActionContext context) {
        Actor actor = BattleScriptTargetResolver.resolveSelfActor(this.runtime, context).orElse(null);
        if (actor == null || actor.scripted() == null) {
            throw new BattleScriptValueException("set_actor_var requires SCRIPTED_ACTOR self context.");
        }
        BattleScriptValue current = actor.scripted().vars().get(this.variableId);
        if (current == null) {
            throw new IllegalArgumentException("Unknown actor variable: " + this.variableId);
        }
        actor.scripted().setVar(
                this.variableId,
                this.runtime.values().evalTyped(this.value, current.type(), new BattleScriptEvaluationContext(this.runtime, context))
        );
        return BattleActionResult.COMPLETED;
    }
}

final class BattleScriptModifyActorVariableAction implements BattleAction {
    private final BattleScriptRuntime runtime;
    private final String variableId;
    private final String op;
    private final JsonElement amount;

    BattleScriptModifyActorVariableAction(BattleScriptRuntime runtime, JsonObject action) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.variableId = BattleScriptJson.requireString(action, "var", "modify_actor_var");
        this.op = BattleScriptJson.optionalString(action, "op").orElse("add");
        JsonElement amountElement = action.has("amount") ? action.get("amount") : action.get("value");
        this.amount = Objects.requireNonNull(amountElement, "modify_actor_var.amount").deepCopy();
    }

    @Override
    public BattleActionResult run(BattleActionContext context) {
        Actor actor = BattleScriptTargetResolver.resolveSelfActor(this.runtime, context).orElse(null);
        if (actor == null || actor.scripted() == null) {
            throw new BattleScriptValueException("modify_actor_var requires SCRIPTED_ACTOR self context.");
        }
        BattleScriptValue current = actor.scripted().vars().get(this.variableId);
        if (current == null) {
            throw new IllegalArgumentException("Unknown actor variable: " + this.variableId);
        }
        double left = current.asDouble();
        double amountValue = this.runtime.values().evalNumber(this.amount, new BattleScriptEvaluationContext(this.runtime, context));
        double result = switch (this.op) {
            case "add" -> left + amountValue;
            case "sub" -> left - amountValue;
            case "mul" -> left * amountValue;
            case "div" -> left / amountValue;
            case "set" -> amountValue;
            default -> throw new IllegalArgumentException("Unsupported modify_actor_var op: " + this.op);
        };
        actor.scripted().setVar(this.variableId, current.withNumber(result));
        return BattleActionResult.COMPLETED;
    }
}

final class BattleScriptPushRulesetAction implements BattleAction {
    private final BattleScriptRuntime runtime;
    private final String rulesetId;

    BattleScriptPushRulesetAction(BattleScriptRuntime runtime, JsonObject action) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.rulesetId = BattleScriptJson.requireString(action, "id", "push_ruleset");
    }

    @Override
    public BattleActionResult run(BattleActionContext context) {
        this.runtime.pushRuleset("action:" + this.runtime.nextRuleSourceOrdinal(), this.rulesetId);
        return BattleActionResult.COMPLETED;
    }
}

final class BattleScriptPopRulesetAction implements BattleAction {
    private final String rulesetId;

    BattleScriptPopRulesetAction(JsonObject action) {
        this.rulesetId = BattleScriptJson.requireString(action, "id", "pop_ruleset");
    }

    @Override
    public BattleActionResult run(BattleActionContext context) {
        context.instance().stateCache().rules().popLastRuleset(this.rulesetId);
        return BattleActionResult.COMPLETED;
    }
}

final class BattleScriptEmitSignalAction implements BattleAction {
    private final BattleScriptRuntime runtime;
    private final String signal;

    BattleScriptEmitSignalAction(BattleScriptRuntime runtime, JsonObject action) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.signal = BattleScriptJson.requireString(action, "signal", "emit_signal");
    }

    @Override
    public BattleActionResult run(BattleActionContext context) {
        this.runtime.emitSignal(this.signal);
        return BattleActionResult.COMPLETED;
    }
}

final class BattleScriptEndPhaseAction implements BattleAction {
    private final BattleScriptRuntime runtime;
    private final Optional<String> targetPhase;

    BattleScriptEndPhaseAction(BattleScriptRuntime runtime, JsonObject action) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.targetPhase = BattleScriptJson.optionalString(action, "to");
    }

    @Override
    public BattleActionResult run(BattleActionContext context) {
        this.runtime.phaseRuntime().requestPhaseEnd(this.targetPhase);
        return BattleActionResult.COMPLETED;
    }
}

final class BattleScriptEndBattleAction implements BattleAction {
    private final BattleScriptRuntime runtime;
    private final Optional<String> outcomeId;
    private final BattleResultState resultState;

    BattleScriptEndBattleAction(BattleScriptRuntime runtime, JsonObject action) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.outcomeId = BattleScriptJson.optionalString(action, "outcome");
        this.resultState = BattleScriptJson.optionalString(action, "result")
                .map(result -> BattleResultState.valueOf(result.toUpperCase(Locale.ROOT)))
                .orElse(BattleResultState.VICTORY);
    }

    @Override
    public BattleActionResult run(BattleActionContext context) {
        BattleResultState resolvedResult = this.outcomeId
                .flatMap(outcome -> this.runtime.definition().compiled().resolveOutcome(outcome))
                .map(OutcomeDefinition::resultState)
                .orElse(this.resultState);
        context.instance().endBattle(resolvedResult);
        return BattleActionResult.COMPLETED;
    }
}

final class BattleScriptSetViewModeAction implements BattleAction {
    private final BattleScriptRuntime runtime;
    private final BattleViewMode viewMode;
    private final BattleCoordinateStateCache.SceneMode sceneMode;
    private final double viewScale;
    private final double durationSeconds;
    private RequestSmoothCameraAction transition;

    BattleScriptSetViewModeAction(BattleScriptRuntime runtime, JsonObject action) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        JsonObject mode = BattleScriptJson.requireObject(action, "viewMode", "set_view_mode");
        String type = BattleScriptJson.requireString(mode, "type", "set_view_mode.viewMode");
        this.viewMode = switch (type) {
            case "ortho_2d" -> BattleViewMode.ORTHO_2D;
            case "ortho_2_5d" -> new BattleViewMode(
                    BattleViewMode.Type.ORTHO_2_5D,
                    BattleScriptSpawnActorAction.vec3(mode, "viewDirection", CanonicalVec3.DEPTH.scale(-1.0D)),
                    BattleScriptSpawnActorAction.vec3(mode, "cameraUp", CanonicalVec3.UP),
                    BattleCoordinateSpace.Axis.parse(
                            BattleScriptJson.optionalString(mode, "lockedAxis").orElse("y"),
                            "viewMode.lockedAxis"
                    ),
                    BattleScriptJson.optionalDouble(mode, "lockedValue", 0.0D),
                    BattleScriptJson.optionalDouble(mode, "orthoHeight", BattleViewMode.DEFAULT_ORTHO_HEIGHT_BU),
                    BattleScriptJson.optionalDouble(mode, "verticalCenterOffset", 0.0D),
                    null,
                    viewFraming(mode)
            );
            case "perspective_3d" -> {
                JsonObject camera = BattleScriptJson.requireObject(mode, "camera", "set_view_mode.viewMode");
                yield BattleViewMode.perspective3d(new BattleViewMode.Camera(
                        BattleScriptSpawnActorAction.vec3(camera, "position", new CanonicalVec3(0.0D, 4.0D, 0.0D)),
                        BattleScriptSpawnActorAction.vec3(camera, "target", CanonicalVec3.ZERO),
                        BattleScriptJson.optionalDouble(camera, "fovDegrees", 45.0D)
                ));
            }
            default -> throw new IllegalArgumentException("Unsupported view mode: " + type);
        };
        String defaultScene = "ortho_2d".equals(type) ? "2d" : "3d";
        this.sceneMode = switch (BattleScriptJson.optionalString(action, "sceneMode").orElse(defaultScene)) {
            case "2d" -> BattleCoordinateStateCache.SceneMode.TWO_D;
            case "3d" -> BattleCoordinateStateCache.SceneMode.THREE_D;
            default -> throw new IllegalArgumentException("set_view_mode.sceneMode must be 2d or 3d.");
        };
        this.viewScale = BattleScriptJson.optionalDouble(action, "viewScale", 1.0D);
        this.durationSeconds = BattleScriptJson.optionalDouble(action, "durationSeconds", 0.0D);
    }

    private static BattleViewMode.Framing viewFraming(JsonObject mode) {
        if (!mode.has("framing")) {
            return BattleViewMode.Framing.fixed();
        }
        JsonObject framing = BattleScriptJson.requireObject(mode, "framing", "set_view_mode.viewMode");
        String type = BattleScriptJson.requireString(framing, "type", "set_view_mode.viewMode.framing");
        if (!"fit_canonical_bounds".equals(type)) {
            throw new IllegalArgumentException("Unsupported view framing: " + type);
        }
        return BattleViewMode.Framing.fitCanonicalBounds(
                BattleScriptSpawnActorAction.vec3(framing, "min", CanonicalVec3.ZERO),
                BattleScriptSpawnActorAction.vec3(framing, "max", CanonicalVec3.ONE),
                BattleScriptJson.optionalDouble(framing, "paddingPercent", 0.0D)
        );
    }

    @Override
    public BattleActionConflict conflict() {
        return BattleActionConflict.of(
                BattleActionConflictKeys.CAMERA_VIEW,
                BattleActionConflictPolicy.CANCEL_OLD
        );
    }

    @Override
    public BattleActionResult run(BattleActionContext context) {
        if (this.transition == null) {
            this.transition = new RequestSmoothCameraAction(
                    this.durationSeconds,
                    this.viewMode,
                    this.sceneMode,
                    this.viewScale,
                    new RequestSmoothCameraAction.RenderModeTransitionSpec(
                            VisualConfig.BATTLE_RENDER_MODE() == VisualConfig.BattleRenderMode.RENDERED,
                            VisualConfig.SCENE_RENDER_MODE_ENTER_DELAY_SECONDS(),
                            VisualConfig.SCENE_RENDER_MODE_CROSSFADE_SECONDS()
                    )
            );
        }
        return this.transition.run(context);
    }
}

final class BattleScriptSetCollisionPolicyAction implements BattleAction {
    private final BattleScriptRuntime runtime;
    private final JsonObject action;
    private final CollisionPolicy policy;

    BattleScriptSetCollisionPolicyAction(BattleScriptRuntime runtime, JsonObject action) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.action = action.deepCopy();
        JsonObject policy = BattleScriptJson.requireObject(action, "policy", "set_collision_policy");
        this.policy = switch (BattleScriptJson.requireString(policy, "type", "set_collision_policy.policy")) {
            case "volume_3d" -> CollisionPolicy.VOLUME_3D;
            case "projected_2d" -> CollisionPolicy.PROJECTED_2D;
            case "hybrid_depth_band" -> CollisionPolicy.hybrid(
                    BattleScriptJson.optionalDouble(policy, "maxDepthDistance", 0.2D)
            );
            default -> throw new IllegalArgumentException("Unsupported collision policy.");
        };
    }

    @Override
    public BattleActionResult run(BattleActionContext context) {
        List<ActorRef> targets = BattleScriptTargetResolver.resolve(this.runtime, this.action, context);
        if (targets.isEmpty()) {
            return BattleActionResult.FAILED;
        }
        for (ActorRef ref : targets) {
            Actor actor = context.instance().stateCache().actors().resolve(ref).orElse(null);
            if (actor == null || !actor.role().gameplayBody()) {
                return BattleActionResult.FAILED;
            }
            actor.setCollisionPolicy(this.policy);
        }
        return BattleActionResult.COMPLETED;
    }
}

final class BattleScriptSetActorPropertyAction implements BattleAction {
    private final BattleScriptRuntime runtime;
    private final JsonObject action;

    BattleScriptSetActorPropertyAction(BattleScriptRuntime runtime, JsonObject action) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.action = action.deepCopy();
    }

    @Override
    public BattleActionResult run(BattleActionContext context) {
        String property = BattleScriptJson.requireString(this.action, "property", "set_actor_property");
        for (ActorRef ref : BattleScriptTargetResolver.resolve(this.runtime, this.action, context)) {
            context.instance().stateCache().actors().resolve(ref).ifPresent(actor -> apply(actor, property));
        }
        return BattleActionResult.COMPLETED;
    }

    private void apply(Actor actor, String property) {
        JsonElement value = this.action.get("value");
        if (value == null) {
            throw new IllegalArgumentException("set_actor_property.value is required.");
        }
        switch (property) {
            case "position" -> teleportPivot(actor, evaluateVector3(actor, value));
            case "bounds_center_position" -> teleportBoundsCenter(actor, evaluateVector3(actor, value));
            case "velocity" -> actor.setVelocity(this.runtime.values().evalVector3(value, new BattleScriptEvaluationContext(this.runtime, contextFor(actor))));
            case "scale" -> {
                CanonicalVec3 scale = this.runtime.values().evalVector3(
                        value,
                        new BattleScriptEvaluationContext(this.runtime, contextFor(actor))
                );
                if (scale.x() <= 0.0D || scale.y() <= 0.0D || scale.z() <= 0.0D) {
                    throw new IllegalArgumentException("set_actor_property.scale components must be > 0.");
                }
                CanonicalTransform transform = actor.transform();
                actor.setTransform(new CanonicalTransform(
                        transform.position(),
                        transform.yawDeg(),
                        transform.pitchDeg(),
                        transform.rollDeg(),
                        scale
                ));
            }
            case "lifecycle" -> actor.setLifecycle(ActorLifecycle.valueOf(value.getAsString().toUpperCase(Locale.ROOT)));
            default -> throw new IllegalArgumentException("Unsupported actor property: " + property);
        }
    }

    // position 始终表示 Actor 枢轴，不代表等于视觉或碰撞中心。
    private static void teleportPivot(Actor actor, CanonicalVec3 pivotPosition) {
        CanonicalTransform transform = actor.transform();
        actor.teleport(new CanonicalTransform(
                pivotPosition,
                transform.yawDeg(),
                transform.pitchDeg(),
                transform.rollDeg(),
                transform.scale()
        ));
    }

    // 目标是碰撞 AABB 中心；实际写回的是 Actor 枢轴位置。
    private static void teleportBoundsCenter(Actor actor, CanonicalVec3 targetCenter) {
        teleportPivot(actor, ActorCollision.pivotPositionForBoundsCenter(actor, targetCenter));
    }

    private CanonicalVec3 evaluateVector3(Actor actor, JsonElement value) {
        return this.runtime.values().evalVector3(
                value,
                new BattleScriptEvaluationContext(this.runtime, contextFor(actor))
        );
    }

    private BattleActionContext contextFor(Actor actor) {
        return new BattleActionContext(this.runtime.instance(), new ActorContext(actor.ref()));
    }
}

final class BattleScriptDestroySelfAction implements BattleAction {
    private final BattleScriptRuntime runtime;

    BattleScriptDestroySelfAction(BattleScriptRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    @Override
    public BattleActionResult run(BattleActionContext context) {
        ActorRef self = BattleScriptTargetResolver.resolveSelf(this.runtime, context).orElse(null);
        if (self == null) {
            return BattleActionResult.CANCELLED;
        }
        this.runtime.actors().remove(self);
        return new DestroyActorAction(self).run(context);
    }
}

final class BattleScriptDamagePlayerAction implements BattleAction {
    private final BattleScriptRuntime runtime;
    private final JsonElement amount;
    private final String attackType;

    BattleScriptDamagePlayerAction(BattleScriptRuntime runtime, JsonObject action) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.amount = action.has("amount") ? action.get("amount").deepCopy() : new com.google.gson.JsonPrimitive(0.0D);
        this.attackType = BattleScriptJson.optionalString(action, "attackType").orElse("white");
    }

    @Override
    public BattleActionResult run(BattleActionContext context) {
        Optional<ActorTargetContext> hit = BattleScriptTargetResolver.resolvePlayerHitContext(this.runtime, context);
        if (hit.isEmpty()) {
            return BattleActionResult.CANCELLED;
        }
        double amountValue = this.runtime.values().evalNumber(this.amount, new BattleScriptEvaluationContext(this.runtime, context));
        return new DamagePlayerAction(amountValue, this.attackType).run(new BattleActionContext(
                context.instance(),
                hit.get(),
                context.stackId()
        ));
    }
}

final class BattleScriptHealPlayerAction implements BattleAction {
    private final BattleScriptRuntime runtime;
    private final JsonElement amount;

    BattleScriptHealPlayerAction(BattleScriptRuntime runtime, JsonObject action) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.amount = action.has("amount") ? action.get("amount").deepCopy() : new com.google.gson.JsonPrimitive(0.0D);
    }

    @Override
    public BattleActionResult run(BattleActionContext context) {
        Optional<ActorTargetContext> hit = BattleScriptTargetResolver.resolvePlayerHitContext(this.runtime, context);
        if (hit.isEmpty()) {
            return BattleActionResult.CANCELLED;
        }
        double amountValue = this.runtime.values().evalNumber(this.amount, new BattleScriptEvaluationContext(this.runtime, context));
        return new HealPlayerAction(amountValue).run(new BattleActionContext(
                context.instance(),
                hit.get(),
                context.stackId()
        ));
    }
}

final class BattleScriptSetSelfVelocityAction implements BattleAction {
    private final BattleScriptRuntime runtime;
    private final JsonObject action;

    BattleScriptSetSelfVelocityAction(BattleScriptRuntime runtime, JsonObject action) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.action = action.deepCopy();
    }

    @Override
    public BattleActionResult run(BattleActionContext context) {
        Actor actor = BattleScriptTargetResolver.resolveSelfActor(this.runtime, context).orElse(null);
        if (actor == null) {
            return BattleActionResult.CANCELLED;
        }
        JsonElement velocity = this.action.get("velocity");
        if (velocity == null) {
            throw new IllegalArgumentException("set_self_velocity.velocity is required.");
        }
        actor.setVelocity(this.runtime.values().evalVector3(velocity, new BattleScriptEvaluationContext(this.runtime, context)));
        return BattleActionResult.COMPLETED;
    }
}

final class BattleScriptSetSelfPositionAction implements BattleAction {
    private final BattleScriptRuntime runtime;
    private final JsonObject action;

    BattleScriptSetSelfPositionAction(BattleScriptRuntime runtime, JsonObject action) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.action = action.deepCopy();
    }

    @Override
    public BattleActionResult run(BattleActionContext context) {
        Actor actor = BattleScriptTargetResolver.resolveSelfActor(this.runtime, context).orElse(null);
        if (actor == null) {
            return BattleActionResult.CANCELLED;
        }
        CanonicalTransform old = actor.transform();
        JsonElement position = this.action.get("position");
        if (position == null) {
            throw new IllegalArgumentException("set_self_position.position is required.");
        }
        actor.setTransform(new CanonicalTransform(
                this.runtime.values().evalVector3(position, new BattleScriptEvaluationContext(this.runtime, context)),
                old.yawDeg(),
                old.pitchDeg(),
                old.rollDeg(),
                old.scale()
        ));
        return BattleActionResult.COMPLETED;
    }
}

final class BattleScriptMoveSelfByVelocityAction implements BattleAction {
    private final BattleScriptRuntime runtime;

    BattleScriptMoveSelfByVelocityAction(BattleScriptRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    @Override
    public BattleActionResult run(BattleActionContext context) {
        Actor actor = BattleScriptTargetResolver.resolveSelfActor(this.runtime, context).orElse(null);
        if (actor == null) {
            return BattleActionResult.CANCELLED;
        }
        double seconds = context.instance().timeline().secondsPerBattleStep();
        CanonicalTransform old = actor.transform();
        actor.setTransform(new CanonicalTransform(
                old.position().add(actor.velocity().scale(seconds)),
                old.yawDeg(),
                old.pitchDeg(),
                old.rollDeg(),
                old.scale()
        ));
        return BattleActionResult.COMPLETED;
    }
}

// 文本与进度条共用执行器。
final class BattleScriptAppearanceAction implements BattleAction {
    private final BattleScriptRuntime runtime;
    private final String type;
    private final JsonObject action;

    private long startedAtBattleTick = Long.MIN_VALUE;
    private long ticksPerCharacter;
    private ActorAppearance.TextContent typewriterSource;
    private ActorRef targetRef;

    BattleScriptAppearanceAction(BattleScriptRuntime runtime, JsonObject action) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.type = BattleScriptJson.requireString(action, "type", "appearance action");
        this.action = action.deepCopy();
    }

    @Override
    public BattleActionResult run(BattleActionContext context) {
        Actor actor = resolveTargetActor(context);
        if (actor == null || actor.visual().type() != cn.jehorstudio.minetale.battle.logic.actor.VisualType.APPEARANCE) {
            return BattleActionResult.FAILED;
        }
        ActorAppearance appearance = actor.visual().appearance();
        BattleScriptEvaluationContext evaluation = new BattleScriptEvaluationContext(this.runtime, context);

        try {
            return switch (this.type) {
                case "set_actor_image_texture" -> setImageTexture(actor, appearance, evaluation);
                case "set_actor_appearance_property" -> setAppearanceProperty(actor, appearance, evaluation);
                case "set_actor_text" -> setText(actor, appearance, evaluation);
                case "type_actor_text" -> typeText(actor, appearance, evaluation, context);
                case "insert_actor_text" -> insertText(actor, appearance, evaluation);
                case "delete_actor_text" -> deleteText(actor, appearance, evaluation);
                case "set_actor_progress_min" -> setProgressMinimum(actor, appearance, evaluation);
                case "set_actor_progress_max" -> setProgressMaximum(actor, appearance, evaluation);
                case "set_actor_progress_value" -> setProgressValue(actor, appearance, evaluation);
                default -> BattleActionResult.FAILED;
            };
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return BattleActionResult.FAILED;
        }
    }

    private Actor resolveTargetActor(BattleActionContext context) {
        if (this.targetRef == null) {
            List<ActorRef> targets;
            if (this.action.has("target") || this.action.has("actorId") || this.action.has("tag")) {
                targets = BattleScriptTargetResolver.resolve(this.runtime, this.action, context);
            } else {
                targets = BattleScriptTargetResolver.resolveSelf(this.runtime, context).map(List::of).orElseGet(List::of);
            }
            if (targets.size() != 1) {
                return null;
            }
            this.targetRef = targets.getFirst();
        }
        return this.runtime.instance().stateCache().actors().resolve(this.targetRef).orElse(null);
    }

    private BattleActionResult setImageTexture(
            Actor actor,
            ActorAppearance appearance,
            BattleScriptEvaluationContext evaluation
    ) {
        ResourceLocation texture = ResourceLocation.tryParse(this.runtime.values().evalString(require("texture"), evaluation));
        if (texture == null) {
            return BattleActionResult.FAILED;
        }
        ActorAppearance.SourceRect source;
        ActorAppearance.TextureSize textureSize;
        if (this.action.has("source") || this.action.has("textureSize")) {
            JsonArray sourceArray = require("source").getAsJsonArray();
            JsonArray sizeArray = require("textureSize").getAsJsonArray();
            source = new ActorAppearance.SourceRect(
                    evalInteger(sourceArray.get(0), evaluation, "source[0]"),
                    evalInteger(sourceArray.get(1), evaluation, "source[1]"),
                    evalInteger(sourceArray.get(2), evaluation, "source[2]"),
                    evalInteger(sourceArray.get(3), evaluation, "source[3]")
            );
            textureSize = new ActorAppearance.TextureSize(
                    evalInteger(sizeArray.get(0), evaluation, "textureSize[0]"),
                    evalInteger(sizeArray.get(1), evaluation, "textureSize[1]")
            );
        } else {
            int width = evalInteger(require("textureWidth"), evaluation, "textureWidth");
            int height = evalInteger(require("textureHeight"), evaluation, "textureHeight");
            source = new ActorAppearance.SourceRect(0, 0, width, height);
            textureSize = new ActorAppearance.TextureSize(width, height);
        }
        actor.setVisual(VisualRef.appearance(appearance.withImageTexture(texture, source, textureSize)));
        return BattleActionResult.COMPLETED;
    }

    private int evalInteger(JsonElement value, BattleScriptEvaluationContext evaluation, String name) {
        return requireInteger(this.runtime.values().evalNumber(value, evaluation), name);
    }

    private BattleActionResult setAppearanceProperty(Actor actor, ActorAppearance appearance,
                                                      BattleScriptEvaluationContext evaluation) {
        String property = BattleScriptJson.requireString(this.action, "property", this.type);
        ActorAppearance next = switch (property) {
            case "image_thickness" -> appearance.withImageThickness(number("value", evaluation));
            case "image_tint" -> appearance.withImageTint(color("value", evaluation));
            case "model" -> appearance.withModel(
                    ActorAppearance.parseObjModel(this.runtime.values().evalString(require("model"), evaluation)),
                    color("tint", evaluation));
            case "text_color" -> appearance.withTextColor(color("value", evaluation));
            case "progress_style" -> appearance.withProgressStyle(
                    color("fillColor", evaluation), color("backgroundColor", evaluation),
                    color("borderColor", evaluation), evalInteger(require("borderWidth"), evaluation, "borderWidth"));
            case "progress_thickness" -> appearance.withProgressThickness(number("value", evaluation));
            case "frame_2d_style" -> appearance.withFrame2dStyle(
                    color("backgroundColor", evaluation), color("borderColor", evaluation),
                    evalInteger(require("borderWidth"), evaluation, "borderWidth"));
            case "frame_3d_style" -> appearance.withFrameSpatialStyle(
                    number("thickness", evaluation), color("borderColor", evaluation));
            default -> throw new IllegalArgumentException("Unsupported appearance property: " + property);
        };
        actor.setVisual(VisualRef.appearance(next));
        return BattleActionResult.COMPLETED;
    }

    private double number(String member, BattleScriptEvaluationContext evaluation) {
        return this.runtime.values().evalNumber(require(member), evaluation);
    }

    private int color(String member, BattleScriptEvaluationContext evaluation) {
        return ActorAppearance.parseColor(this.runtime.values().evalString(require(member), evaluation), member);
    }

    private BattleActionResult setText(
            Actor actor,
            ActorAppearance appearance,
            BattleScriptEvaluationContext evaluation
    ) {
        String text = this.runtime.values().evalString(require("text"), evaluation);
        actor.setVisual(VisualRef.appearance(appearance.withText(text)));
        return BattleActionResult.COMPLETED;
    }

    private BattleActionResult typeText(
            Actor actor,
            ActorAppearance appearance,
            BattleScriptEvaluationContext evaluation,
            BattleActionContext context
    ) {
        if (this.startedAtBattleTick == Long.MIN_VALUE) {
            String text = this.runtime.values().evalString(require("text"), evaluation);
            double seconds = this.runtime.values().evalNumber(require("secondsPerCharacter"), evaluation);
            if (!Double.isFinite(seconds) || seconds < 0.0D) {
                return BattleActionResult.FAILED;
            }
            this.typewriterSource = appearance.requireTextContent().withText(text);
            this.startedAtBattleTick = context.instance().timeline().battleTick();
            this.ticksPerCharacter = context.instance().timeline().secondsToBattleTicks(seconds);
            if (this.ticksPerCharacter <= 0L || text.isEmpty()) {
                actor.setVisual(VisualRef.appearance(appearance.withText(text)));
                return BattleActionResult.COMPLETED;
            }
            actor.setVisual(VisualRef.appearance(appearance.withText("")));
            return BattleActionResult.RUNNING;
        }

        int total = this.typewriterSource.text().codePointCount(0, this.typewriterSource.text().length());
        long elapsed = Math.max(0L, context.instance().timeline().battleTick() - this.startedAtBattleTick);
        int visibleCharacters = (int) Math.min(total, elapsed / this.ticksPerCharacter);
        actor.setVisual(VisualRef.appearance(appearance.withText(this.typewriterSource.prefix(visibleCharacters))));
        return visibleCharacters >= total ? BattleActionResult.COMPLETED : BattleActionResult.RUNNING;
    }

    private BattleActionResult insertText(
            Actor actor,
            ActorAppearance appearance,
            BattleScriptEvaluationContext evaluation
    ) {
        int index = requireInteger(this.runtime.values().evalNumber(require("index"), evaluation), "index");
        String text = this.runtime.values().evalString(require("text"), evaluation);
        actor.setVisual(VisualRef.appearance(appearance.insertText(index, text)));
        return BattleActionResult.COMPLETED;
    }

    private BattleActionResult deleteText(
            Actor actor,
            ActorAppearance appearance,
            BattleScriptEvaluationContext evaluation
    ) {
        int start = requireInteger(this.runtime.values().evalNumber(require("start"), evaluation), "start");
        int count = requireInteger(this.runtime.values().evalNumber(require("count"), evaluation), "count");
        actor.setVisual(VisualRef.appearance(appearance.deleteText(start, count)));
        return BattleActionResult.COMPLETED;
    }

    private BattleActionResult setProgressMinimum(
            Actor actor,
            ActorAppearance appearance,
            BattleScriptEvaluationContext evaluation
    ) {
        double value = this.runtime.values().evalNumber(require("value"), evaluation);
        actor.setVisual(VisualRef.appearance(appearance.withProgressMinimum(value)));
        return BattleActionResult.COMPLETED;
    }

    private BattleActionResult setProgressMaximum(
            Actor actor,
            ActorAppearance appearance,
            BattleScriptEvaluationContext evaluation
    ) {
        double value = this.runtime.values().evalNumber(require("value"), evaluation);
        actor.setVisual(VisualRef.appearance(appearance.withProgressMaximum(value)));
        return BattleActionResult.COMPLETED;
    }

    private BattleActionResult setProgressValue(
            Actor actor,
            ActorAppearance appearance,
            BattleScriptEvaluationContext evaluation
    ) {
        double value = this.runtime.values().evalNumber(require("value"), evaluation);
        actor.setVisual(VisualRef.appearance(appearance.withProgressValue(value)));
        return BattleActionResult.COMPLETED;
    }

    private JsonElement require(String member) {
        JsonElement value = this.action.get(member);
        if (value == null) {
            throw new IllegalArgumentException(this.type + "." + member + " is required.");
        }
        return value;
    }

    private static int requireInteger(double value, String name) {
        if (!Double.isFinite(value) || value != Math.rint(value) || value < 0.0D || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(name + " must be a non-negative integer.");
        }
        return (int) value;
    }
}

final class BattleScriptPlaySoundAction implements BattleAction {
    private final BattleScriptRuntime runtime;
    private final ResourceLocation sound;
    private final SoundSource source;
    private final JsonElement volumeMultiplier;
    private final JsonElement pitchMultiplier;

    BattleScriptPlaySoundAction(BattleScriptRuntime runtime, JsonObject action) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.sound = BattleScriptJson.requireResourceLocation(
                BattleScriptJson.requireString(action, "sound", "play_sound"),
                "play_sound.sound"
        );
        String sourceName = BattleScriptJson.optionalString(action, "source").orElse("master");
        this.source = Arrays.stream(SoundSource.values())
                .filter(candidate -> candidate.getName().equals(sourceName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown SoundSource: " + sourceName));
        this.volumeMultiplier = action.has("volumeMultiplier")
                ? action.get("volumeMultiplier").deepCopy()
                : new JsonPrimitive(1.0D);
        this.pitchMultiplier = action.has("pitchMultiplier")
                ? action.get("pitchMultiplier").deepCopy()
                : new JsonPrimitive(1.0D);
    }

    @Override
    public BattleActionResult run(BattleActionContext context) {
        BattleScriptEvaluationContext evaluation = new BattleScriptEvaluationContext(
                this.runtime,
                context,
                this.runtime.soundRandom()
        );
        double volume = requireMultiplier(
                this.runtime.values().evalNumber(this.volumeMultiplier, evaluation),
                "volumeMultiplier"
        );
        double pitch = requireMultiplier(
                this.runtime.values().evalNumber(this.pitchMultiplier, evaluation),
                "pitchMultiplier"
        );
        Optional<Actor> sourceActor = BattleScriptTargetResolver.resolveSelfActor(this.runtime, context);
        this.runtime.instance().requestSound(new BattleSoundRequest(
                this.sound,
                this.source,
                volume,
                pitch,
                this.runtime.soundRandom().nextLong(),
                sourceActor.map(Actor::ref),
                sourceActor.map(actor -> actor.transform().position())
        ));
        return BattleActionResult.COMPLETED;
    }

    private static double requireMultiplier(double value, String name) {
        if (!Double.isFinite(value) || value < 0.0D) {
            throw new BattleScriptValueException("play_sound." + name + " must evaluate to a finite value >= 0.");
        }
        return value;
    }
}

final class BattleScriptTargetResolver {
    private BattleScriptTargetResolver() {
    }

    static List<ActorRef> resolve(BattleScriptRuntime runtime, JsonObject action, BattleActionContext context) {
        if (action.has("target")) {
            JsonElement target = action.get("target");
            if (target.isJsonPrimitive() && target.getAsJsonPrimitive().isString()) {
                return resolveKeyword(runtime, context, target.getAsString()).map(List::of).orElseGet(List::of);
            }
            return resolveTargetObject(runtime, BattleScriptJson.requireObject(target, "action.target"));
        }
        if (action.has("actorId")) {
            return runtime.actors().byId(action.get("actorId").getAsString()).map(List::of).orElseGet(List::of);
        }
        if (action.has("tag")) {
            return runtime.actors().byTag(action.get("tag").getAsString());
        }
        return List.of();
    }

    static Optional<ActorRef> resolveSelf(BattleScriptRuntime runtime, BattleActionContext context) {
        return resolveKeyword(runtime, context, "self");
    }

    static Optional<Actor> resolveSelfActor(BattleScriptRuntime runtime, BattleActionContext context) {
        return resolveSelf(runtime, context).flatMap(ref -> runtime.instance().stateCache().actors().resolve(ref));
    }

    static Optional<ActorTargetContext> resolvePlayerHitContext(BattleScriptRuntime runtime, BattleActionContext context) {
        ActorRef self = resolveSelf(runtime, context).orElse(null);
        ActorRef player = resolveKeyword(runtime, context, "player").orElse(null);
        if (self == null || player == null) {
            return Optional.empty();
        }
        return Optional.of(new ActorTargetContext(self, player));
    }

    private static List<ActorRef> resolveTargetObject(BattleScriptRuntime runtime, JsonObject target) {
        return runtime.actors().resolveTarget(new BattleScriptActorBindings.JsonObjectLike() {
            @Override
            public boolean hasString(String member) {
                String alias = "id".equals(member) ? "actorId" : member;
                return target.has(member) && target.get(member).isJsonPrimitive()
                        || target.has(alias) && target.get(alias).isJsonPrimitive();
            }

            @Override
            public String string(String member) {
                if (target.has(member)) {
                    return target.get(member).getAsString();
                }
                String alias = "id".equals(member) ? "actorId" : member;
                return target.get(alias).getAsString();
            }
        });
    }

    private static Optional<ActorRef> resolveKeyword(BattleScriptRuntime runtime, BattleActionContext context, String keyword) {
        return switch (keyword) {
            case "self" -> {
                if (context.eventContext() instanceof ActorTargetContext hit) {
                    yield Optional.of(hit.actor());
                }
                if (context.eventContext() instanceof ActorContext actor) {
                    yield Optional.of(actor.actor());
                }
                yield Optional.empty();
            }
            case "other" -> context.eventContext() instanceof ActorTargetContext hit
                    ? Optional.of(hit.target())
                    : Optional.empty();
            case "player" -> {
                if (context.eventContext() instanceof ActorTargetContext hit
                        && hit.target().type() == ActorType.PLAYER_SOUL) {
                    yield Optional.of(hit.target());
                }
                yield runtime.instance().stateCache().players().initialized()
                        ? Optional.of(runtime.instance().stateCache().players().soulRef())
                        : Optional.empty();
            }
            case "source" -> resolveKeyword(runtime, context, "self")
                    .flatMap(ref -> runtime.instance().stateCache().actors().resolve(ref))
                    .map(Actor::sourceRef);
            default -> Optional.empty();
        };
    }
}

final class BattleScriptRuleMapper {
    private BattleScriptRuleMapper() {
    }

    static List<AppliedRule> appliedRules(String source, RuleSetDefinition ruleset) {
        List<AppliedRule> appliedRules = new ArrayList<>();
        for (RuleDefinition rule : ruleset.rules()) {
            appliedRules.add(new AppliedRule(source, ruleset.localId(), rule.domain(), rule.data()));
        }
        return List.copyOf(appliedRules);
    }
}
