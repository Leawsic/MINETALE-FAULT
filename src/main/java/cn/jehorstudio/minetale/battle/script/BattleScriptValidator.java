package cn.jehorstudio.minetale.battle.script;

import cn.jehorstudio.minetale.battle.network.payload.BattleResultState;
import cn.jehorstudio.minetale.battle.logic.actor.component.ActorComponentRegistry;
import cn.jehorstudio.minetale.battle.logic.actor.VisualRef;
import cn.jehorstudio.minetale.battle.logic.input.BattleInputKey;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Arrays;
import net.minecraft.sounds.SoundSource;

final class BattleScriptValidator {
    private static final BattleScriptValueExprTypeChecker TYPE_CHECKER = new BattleScriptValueExprTypeChecker();
    private static final Set<String> ACTOR_TEMPLATE_TYPES = Set.of("bullet", "scripted_actor");
    private static final Set<String> CANONICAL_TRANSFORM_FIELDS = Set.of(
            "space",
            "position",
            "rotationDeg",
            "scale"
    );
    private static final Set<String> CANONICAL_VELOCITY_FIELDS = Set.of("space", "value");
    private static final Set<String> SCRIPTED_ACTOR_EVENTS = Set.of(
            "onSpawn",
            "onTick",
            "onCollidePlayer",
            "onCollideBattleBox",
            "onDestroy",
            "onSignal",
            "onKeyPressed"
    );

    private BattleScriptValidator() {
    }

    static void validate(CompiledBattleDefinition definition) {
        BattleScriptTypeContext stageTypeContext = BattleScriptTypeContext.stage(definition);
        validateStageEvents(definition);
        for (VariableDefinition variable : definition.variables().values()) {
            TYPE_CHECKER.validate(
                    variable.initialExpression(),
                    BattleScriptValueType.fromValueType(variable.type()),
                    stageTypeContext,
                    definition.id() + ".variables." + variable.id() + ".initial"
            );
        }
        for (ActorPrefabDefinition actor : definition.actorPrefabs().values()) {
            validateActorTemplate(definition, actor, definition.id() + ".actors." + actor.localId());
        }
        for (RuleSetDefinition ruleSet : definition.ruleSets().values()) {
            validateRuleSet(ruleSet, definition.id() + ".rulesets." + ruleSet.localId());
        }
        for (PhaseDefinition phase : definition.phaseGraph().phases().values()) {
            for (String ruleSetRef : phase.ruleSetRefs()) {
                requireRuleSet(definition, ruleSetRef, definition.id() + ".phaseGraph.phases." + phase.id() + ".rulesets");
            }
            validateActions(definition, phase.onEnterActions(), definition.id() + ".phaseGraph.phases." + phase.id() + ".onEnter", 0);
            validateActions(definition, phase.onTickActions(), definition.id() + ".phaseGraph.phases." + phase.id() + ".onTick", 0);
            validateActions(definition, phase.onExitActions(), definition.id() + ".phaseGraph.phases." + phase.id() + ".onExit", 0);
            for (TransitionDefinition transition : phase.transitions()) {
                transition.targetPhase().ifPresent(target -> {
                    if (!definition.phaseGraph().phases().containsKey(target)) {
                        throw new BattleScriptValidationException(definition.id() + " transition targets unknown phase " + target + ".");
                    }
                });
                validateCondition(definition, transition.condition(), definition.id() + ".phaseGraph.phases." + phase.id() + ".transition.when", 0);
            }
        }
        for (PatternDefinition pattern : definition.patterns().values()) {
            validateActions(definition, pattern.actions(), definition.id() + ".patterns." + pattern.localId(), 0);
        }
    }

    private static void validateActions(CompiledBattleDefinition definition, List<JsonObject> actions, String path, int depth) {
        validateActions(definition, actions, path, depth, null);
    }

    private static void validateActions(CompiledBattleDefinition definition, List<JsonObject> actions, String path, int depth, Map<String, cn.jehorstudio.minetale.battle.logic.blackboard.value.BattleScriptValue.Type> actorVarTypes) {
        if (depth > definition.budgets().maxRecursionDepth()) {
            throw new BattleScriptValidationException(path + " exceeds maxRecursionDepth.");
        }
        for (int i = 0; i < actions.size(); i++) {
            validateAction(definition, actions.get(i), path + "[" + i + "]", depth, actorVarTypes);
        }
    }

    private static void validateAction(CompiledBattleDefinition definition, JsonObject action, String path, int depth) {
        validateAction(definition, action, path, depth, null);
    }

    private static void validateAction(CompiledBattleDefinition definition, JsonObject action, String path, int depth, Map<String, cn.jehorstudio.minetale.battle.logic.blackboard.value.BattleScriptValue.Type> actorVarTypes) {
        String type = BattleScriptJson.requireString(action, "type", path);
        BattleScriptTypeContext typeContext = typeContext(definition, actorVarTypes);
        switch (type) {
            case "wait" -> validateWait(action, path);
            case "wait_until" -> {
                if (!action.has("when")) {
                    throw new BattleScriptValidationException(path + ".when is required for wait_until.");
                }
                validateCondition(definition, BattleScriptJson.requireObject(action, "when", path), path + ".when", depth + 1, actorVarTypes);
            }
            case "end_battle" -> validateBattleResult(definition, action, path);
            case "request_render_action" -> validateRenderActionRequest(action, path);
            case "play_sound" -> validatePlaySound(action, path, typeContext);
            case "smooth_camera" -> validateSmoothCamera(action, path);
            case "set_view_mode" -> validateViewMode(action, path);
            case "set_collision_policy" -> {
                validateTarget(action, path);
                validateCollisionPolicy(
                        BattleScriptJson.requireObject(action, "policy", path),
                        path + ".policy"
                );
            }
            case "emit_signal" -> BattleScriptJson.requireString(action, "signal", path);
            case "cleanup_tag" -> BattleScriptJson.requireString(action, "tag", path);
            case "destroy_actor" -> validateTarget(action, path);
            case "destroy_self" -> {
            }
            case "damage_player", "heal_player" -> validateOptionalValueExpr(action, "amount", path, BattleScriptValueType.NUMBER, typeContext);
            case "set_self_velocity" -> validateRequiredValueExpr(action, "velocity", path, BattleScriptValueType.VECTOR3, typeContext);
            case "set_self_position" -> validateRequiredValueExpr(action, "position", path, BattleScriptValueType.VECTOR3, typeContext);
            case "move_self_by_velocity" -> {
            }
            case "end_phase" -> BattleScriptJson.optionalString(action, "to").ifPresent(target -> {
                if (!definition.phaseGraph().phases().containsKey(target)) {
                    throw new BattleScriptValidationException(path + ".to references unknown phase " + target + ".");
                }
            });
            case "pop_ruleset" -> requireRuleSet(definition, BattleScriptJson.requireString(action, "id", path), path + ".id");
            case "spawn_actor" -> {
                String prefab = BattleScriptJson.requireString(action, "prefab", path);
                requireActorPrefab(definition, prefab, path + ".prefab");
                validateSpawnAction(action, path, typeContext);
            }
            case "spawn_bullet" -> {
                if (!action.has("prefab")) {
                    throw new BattleScriptValidationException(
                            BattleScriptDiagnosticCode.MISSING_SPAWN_BULLET_PREFAB,
                            path + ".prefab",
                            "spawn_bullet.prefab is required"
                    );
                }
                String prefab = BattleScriptJson.requireString(action, "prefab", path);
                requireActorPrefab(definition, prefab, path + ".prefab");
                validateSpawnAction(action, path, typeContext);
            }
            case "set_var" -> {
                String varName = BattleScriptJson.requireString(action, "var", path);
                requireVariable(definition, varName, path + ".var");
                if (!action.has("value")) {
                    throw new BattleScriptValidationException(path + ".value is required.");
                }
                TYPE_CHECKER.validate(
                        action.get("value"),
                        BattleScriptValueType.fromValueType(definition.variables().get(varName).type()),
                        typeContext,
                        path + ".value"
                );
            }
            case "modify_var" -> {
                String varName = BattleScriptJson.requireString(action, "var", path);
                requireVariable(definition, varName, path + ".var");
                if (!isNumeric(definition.variables().get(varName).type())) {
                    throw new BattleScriptValidationException(path + ".var must be numeric for modify_var.");
                }
                if (!action.has("amount") && !action.has("value")) {
                    throw new BattleScriptValidationException(path + " must contain amount or value.");
                }
                TYPE_CHECKER.validate(action.has("amount") ? action.get("amount") : action.get("value"), BattleScriptValueType.NUMBER, typeContext, path + ".amount");
            }
            case "set_actor_var" -> {
                if (actorVarTypes == null) {
                    throw new BattleScriptValidationException(path + " requires actor event context.");
                }
                String varName = BattleScriptJson.requireString(action, "var", path);
                if (!action.has("value")) {
                    throw new BattleScriptValidationException(path + ".value is required.");
                }
                cn.jehorstudio.minetale.battle.logic.blackboard.value.BattleScriptValue.Type expectedType = actorVarTypes.get(varName);
                if (expectedType == null) {
                    throw new BattleScriptValidationException(path + ".var references unknown actor variable " + varName + ".");
                }
                TYPE_CHECKER.validate(action.get("value"), BattleScriptValueType.fromValueType(expectedType), typeContext, path + ".value");
            }
            case "modify_actor_var" -> {
                if (actorVarTypes == null) {
                    throw new BattleScriptValidationException(path + " requires actor event context.");
                }
                String varName = BattleScriptJson.requireString(action, "var", path);
                cn.jehorstudio.minetale.battle.logic.blackboard.value.BattleScriptValue.Type varType = actorVarTypes.get(varName);
                if (varType == null) {
                    throw new BattleScriptValidationException(path + ".var references unknown actor variable " + varName + ".");
                }
                if (!isNumeric(varType)) {
                    throw new BattleScriptValidationException(path + ".var must be numeric for modify_actor_var.");
                }
                if (!action.has("amount") && !action.has("value")) {
                    throw new BattleScriptValidationException(path + " must contain amount or value.");
                }
                TYPE_CHECKER.validate(action.has("amount") ? action.get("amount") : action.get("value"), BattleScriptValueType.NUMBER, typeContext, path + ".amount");
            }
            case "push_ruleset" -> requireRuleSet(definition, BattleScriptJson.requireString(action, "id", path), path + ".id");
            case "call_pattern" -> requirePattern(definition, BattleScriptJson.requireString(action, "id", path), path + ".id");
            case "set_actor_property" -> {
                validateTarget(action, path);
                BattleScriptJson.requireString(action, "property", path);
                if (!action.has("value")) {
                    throw new BattleScriptValidationException(path + ".value is required.");
                }
                String property = BattleScriptJson.requireString(action, "property", path);
                BattleScriptValueType expected = switch (property) {
                    case "position", "bounds_center_position", "velocity", "scale" ->
                            BattleScriptValueType.VECTOR3;
                    case "lifecycle" -> BattleScriptValueType.STRING;
                    default -> throw new BattleScriptValidationException(
                            path + ".property has unsupported actor property: " + property + "."
                    );
                };
                TYPE_CHECKER.validate(action.get("value"), expected, typeContext, path + ".value");
            }
            case "set_actor_image_texture" -> {
                validateAppearanceActionTarget(action, actorVarTypes, path);
                validateRequiredValue(action, "texture", path, BattleScriptValueType.STRING, typeContext);
                boolean modern = action.has("source") || action.has("textureSize");
                boolean legacy = action.has("textureWidth") || action.has("textureHeight");
                if (modern && legacy) {
                    throw new BattleScriptValidationException(path + " must not mix source/textureSize with legacy textureWidth/textureHeight.");
                }
                if (modern) {
                    validateRequiredNumberArray(action, "source", 4, path, typeContext);
                    validateRequiredNumberArray(action, "textureSize", 2, path, typeContext);
                } else {
                    validateRequiredValue(action, "textureWidth", path, BattleScriptValueType.NUMBER, typeContext);
                    validateRequiredValue(action, "textureHeight", path, BattleScriptValueType.NUMBER, typeContext);
                }
            }
            case "set_actor_text" -> {
                validateAppearanceActionTarget(action, actorVarTypes, path);
                validateRequiredValue(action, "text", path, BattleScriptValueType.STRING, typeContext);
            }
            case "set_actor_appearance_property" -> validateAppearancePropertyAction(action, actorVarTypes, path, typeContext);
            case "type_actor_text" -> {
                validateAppearanceActionTarget(action, actorVarTypes, path);
                validateRequiredValue(action, "text", path, BattleScriptValueType.STRING, typeContext);
                validateRequiredValue(action, "secondsPerCharacter", path, BattleScriptValueType.NUMBER, typeContext);
            }
            case "insert_actor_text" -> {
                validateAppearanceActionTarget(action, actorVarTypes, path);
                validateRequiredValue(action, "index", path, BattleScriptValueType.NUMBER, typeContext);
                validateRequiredValue(action, "text", path, BattleScriptValueType.STRING, typeContext);
            }
            case "delete_actor_text" -> {
                validateAppearanceActionTarget(action, actorVarTypes, path);
                validateRequiredValue(action, "start", path, BattleScriptValueType.NUMBER, typeContext);
                validateRequiredValue(action, "count", path, BattleScriptValueType.NUMBER, typeContext);
            }
            case "set_actor_progress_min", "set_actor_progress_max", "set_actor_progress_value" -> {
                validateAppearanceActionTarget(action, actorVarTypes, path);
                validateRequiredValue(action, "value", path, BattleScriptValueType.NUMBER, typeContext);
            }
            case "sequence", "parallel" -> validateActions(definition, nestedActions(action, path), path + ".actions", depth + 1, actorVarTypes);
            case "random_one" -> {
                JsonArray options = action.has("options")
                        ? BattleScriptJson.requireArray(action, "options", path)
                        : BattleScriptJson.requireArray(action, "actions", path);
                for (int i = 0; i < options.size(); i++) {
                    JsonElement option = options.get(i);
                    if (option.isJsonArray()) {
                        validateActions(definition, BattleScriptJson.objectList(option.getAsJsonArray(), path + ".options[" + i + "]"), path + ".options[" + i + "]", depth + 1, actorVarTypes);
                    } else {
                        validateAction(definition, BattleScriptJson.requireObject(option, path + ".options[" + i + "]"), path + ".options[" + i + "]", depth + 1, actorVarTypes);
                    }
                }
            }
            case "repeat", "until" -> {
                if ("until".equals(type) && !action.has("when")) {
                    throw new BattleScriptValidationException(path + ".when is required for until.");
                }
                if (action.has("when")) {
                    validateCondition(definition, BattleScriptJson.requireObject(action, "when", path), path + ".when", depth + 1, actorVarTypes);
                }
                validateActions(definition, nestedActions(action, path), path + ".actions", depth + 1, actorVarTypes);
            }
            case "if", "if_else" -> {
                if (!action.has("when")) {
                    throw new BattleScriptValidationException(path + ".when is required for " + type + ".");
                }
                validateCondition(definition, BattleScriptJson.requireObject(action, "when", path), path + ".when", depth + 1, actorVarTypes);
                validateActions(definition, nestedActions(action, path), path + ".actions", depth + 1, actorVarTypes);
                if (action.has("elseActions")) {
                    validateActions(
                            definition,
                            BattleScriptJson.objectList(BattleScriptJson.requireArray(action, "elseActions", path), path + ".elseActions"),
                            path + ".elseActions",
                            depth + 1,
                            actorVarTypes
                    );
                }
            }
            default -> throw new BattleScriptValidationException(path + " has unsupported action type: " + type);
        }
    }

    private static void requireActorEventContext(
            Map<String, cn.jehorstudio.minetale.battle.logic.blackboard.value.BattleScriptValue.Type> actorVarTypes,
            String path
    ) {
        if (actorVarTypes == null) {
            throw new BattleScriptValidationException(path + " requires actor event context.");
        }
    }

    private static void validateAppearanceActionTarget(
            JsonObject action,
            Map<String, cn.jehorstudio.minetale.battle.logic.blackboard.value.BattleScriptValue.Type> actorVarTypes,
            String path
    ) {
        if (action.has("target") || action.has("actorId") || action.has("tag")) {
            validateTarget(action, path);
            if (action.has("tag")) {
                throw new BattleScriptValidationException(path + ".tag is not supported by single-target appearance actions.");
            }
            if (action.has("target") && action.get("target").isJsonObject()
                    && action.getAsJsonObject("target").has("tag")) {
                throw new BattleScriptValidationException(path + ".target.tag is not supported by single-target appearance actions.");
            }
            return;
        }
        requireActorEventContext(actorVarTypes, path);
    }

    private static void validateRequiredValue(
            JsonObject action,
            String member,
            String path,
            BattleScriptValueType expected,
            BattleScriptTypeContext typeContext
    ) {
        if (!action.has(member)) {
            throw new BattleScriptValidationException(path + "." + member + " is required.");
        }
        TYPE_CHECKER.validate(action.get(member), expected, typeContext, path + "." + member);
    }

    private static void validateRequiredNumberArray(
            JsonObject action,
            String member,
            int expectedSize,
            String path,
            BattleScriptTypeContext typeContext
    ) {
        JsonArray values = BattleScriptJson.requireArray(action, member, path);
        if (values.size() != expectedSize) {
            throw new BattleScriptValidationException(path + "." + member + " must contain exactly " + expectedSize + " values.");
        }
        for (int i = 0; i < values.size(); i++) {
            TYPE_CHECKER.validate(values.get(i), BattleScriptValueType.NUMBER, typeContext, path + "." + member + "[" + i + "]");
        }
    }

    private static List<JsonObject> nestedActions(JsonObject action, String path) {
        if (action.has("actions")) {
            return BattleScriptJson.objectList(BattleScriptJson.requireArray(action, "actions", path), path + ".actions");
        }
        if (action.has("action")) {
            return List.of(BattleScriptJson.requireObject(action, "action", path).deepCopy());
        }
        throw new BattleScriptValidationException(path + " must contain actions or action.");
    }

    private static void validateWait(JsonObject action, String path) {
        validateAllowedMembers(action, path, Set.of("type", "seconds", "ticks"));
        if (action.has("seconds") == action.has("ticks")) {
            throw new BattleScriptValidationException(path + " must contain exactly one of seconds or ticks.");
        }
        if (action.has("seconds")) {
            JsonElement secondsElement = action.get("seconds");
            if (!secondsElement.isJsonPrimitive() || !secondsElement.getAsJsonPrimitive().isNumber()) {
                throw new BattleScriptValidationException(path + ".seconds must be a static number.");
            }
            double seconds = secondsElement.getAsDouble();
            if (!Double.isFinite(seconds) || seconds < 0.0D) {
                throw new BattleScriptValidationException(path + ".seconds must be finite and >= 0.");
            }
        }
        if (action.has("ticks")) {
            JsonElement ticksElement = action.get("ticks");
            if (!ticksElement.isJsonPrimitive() || !ticksElement.getAsJsonPrimitive().isNumber()) {
                throw new BattleScriptValidationException(path + ".ticks must be a static integer.");
            }
            double ticks = ticksElement.getAsDouble();
            if (!Double.isFinite(ticks) || ticks < 0.0D || ticks != Math.rint(ticks) || ticks > Long.MAX_VALUE) {
                throw new BattleScriptValidationException(path + ".ticks must be a non-negative integer in the Java long range.");
            }
        }
    }

    private static void validatePlaySound(
            JsonObject action,
            String path,
            BattleScriptTypeContext typeContext
    ) {
        String sound = BattleScriptJson.requireString(action, "sound", path);
        BattleScriptJson.requireResourceLocation(sound, path + ".sound");
        String source = BattleScriptJson.optionalString(action, "source").orElse("master");
        boolean validSource = Arrays.stream(SoundSource.values())
                .anyMatch(candidate -> candidate.getName().equals(source));
        if (!validSource) {
            throw new BattleScriptValidationException(path + ".source has unknown SoundSource " + source + ".");
        }
        validateOptionalValueExpr(action, "volumeMultiplier", path, BattleScriptValueType.NUMBER, typeContext);
        validateOptionalValueExpr(action, "pitchMultiplier", path, BattleScriptValueType.NUMBER, typeContext);
    }

    private static void validateStageEvents(CompiledBattleDefinition definition) {
        JsonObject events = BattleScriptJson.optionalObject(definition.root(), "events").orElseGet(JsonObject::new);
        for (Map.Entry<String, JsonElement> entry : events.entrySet()) {
            if (!"onKeyPressed".equals(entry.getKey())) {
                throw new BattleScriptValidationException(definition.id() + ".events has unsupported event: " + entry.getKey() + ".");
            }
            validateKeyEventMap(definition, entry.getValue(), definition.id() + ".events.onKeyPressed", null);
        }
    }

    private static void validateAppearancePropertyAction(JsonObject action, Map<String, cn.jehorstudio.minetale.battle.logic.blackboard.value.BattleScriptValue.Type> actorVarTypes,
                                                         String path, BattleScriptTypeContext typeContext) {
        validateAppearanceActionTarget(action, actorVarTypes, path);
        String property = BattleScriptJson.requireString(action, "property", path);
        switch (property) {
            case "image_thickness" -> validateRequiredValue(action, "value", path, BattleScriptValueType.NUMBER, typeContext);
            case "image_tint", "text_color" -> validateRequiredValue(action, "value", path, BattleScriptValueType.STRING, typeContext);
            case "model" -> {
                validateRequiredValue(action, "model", path, BattleScriptValueType.STRING, typeContext);
                validateRequiredValue(action, "tint", path, BattleScriptValueType.STRING, typeContext);
            }
            case "progress_style" -> {
                validateRequiredValue(action, "fillColor", path, BattleScriptValueType.STRING, typeContext);
                validateRequiredValue(action, "backgroundColor", path, BattleScriptValueType.STRING, typeContext);
                validateRequiredValue(action, "borderColor", path, BattleScriptValueType.STRING, typeContext);
                validateRequiredValue(action, "borderWidth", path, BattleScriptValueType.NUMBER, typeContext);
            }
            case "progress_thickness" -> validateRequiredValue(action, "value", path, BattleScriptValueType.NUMBER, typeContext);
            case "frame_2d_style" -> {
                validateRequiredValue(action, "backgroundColor", path, BattleScriptValueType.STRING, typeContext);
                validateRequiredValue(action, "borderColor", path, BattleScriptValueType.STRING, typeContext);
                validateRequiredValue(action, "borderWidth", path, BattleScriptValueType.NUMBER, typeContext);
            }
            case "frame_3d_style" -> {
                validateRequiredValue(action, "thickness", path, BattleScriptValueType.NUMBER, typeContext);
                validateRequiredValue(action, "borderColor", path, BattleScriptValueType.STRING, typeContext);
            }
            default -> throw new BattleScriptValidationException(path + ".property has unsupported appearance property: " + property + ".");
        }
    }

    private static void validateRuleSet(RuleSetDefinition ruleSet, String path) {
        for (int i = 0; i < ruleSet.rules().size(); i++) {
            JsonObject rule = ruleSet.rules().get(i).data();
            String rulePath = path + ".rules[" + i + "]";
            switch (ruleSet.rules().get(i).domain()) {
                case "player_control" -> validatePlayerControlRule(rule, rulePath);
                case "damage" -> validateDamageRule(rule, rulePath);
                default -> throw new BattleScriptValidationException(
                        rulePath + ".domain has unsupported stable rule domain: "
                                + ruleSet.rules().get(i).domain() + "."
                );
            }
        }
    }

    private static void validatePlayerControlRule(JsonObject rule, String path) {
        validateAllowedMembers(rule, path, Set.of("domain", "controller"));
        JsonObject controller = BattleScriptJson.requireObject(rule, "controller", path);
        validateAllowedMembers(controller, path + ".controller", Set.of(
                "type", "moveSpeedPerSecond", "allowVertical", "clampToBattleBox", "lockedAxis", "lockedValue"
        ));
        String type = BattleScriptJson.requireString(controller, "type", path + ".controller");
        if (!Set.of("red_free", "plane_locked", "free_3d", "locked", "none").contains(type)) {
            throw new BattleScriptValidationException(path + ".controller.type is unsupported: " + type + ".");
        }
        if (controller.has("moveSpeedPerSecond")) {
            double speed = requireStaticNumber(controller.get("moveSpeedPerSecond"), path + ".controller.moveSpeedPerSecond");
            if (speed <= 0.0D) {
                throw new BattleScriptValidationException(path + ".controller.moveSpeedPerSecond must be > 0.");
            }
        }
        validateOptionalBoolean(controller, "allowVertical", path + ".controller");
        validateOptionalBoolean(controller, "clampToBattleBox", path + ".controller");
        if (controller.has("lockedAxis")) {
            String axis = BattleScriptJson.requireString(controller, "lockedAxis", path + ".controller");
            if (!Set.of("x", "y", "z").contains(axis)) {
                throw new BattleScriptValidationException(path + ".controller.lockedAxis must be x, y, or z.");
            }
        }
        if (controller.has("lockedValue")) {
            requireStaticNumber(controller.get("lockedValue"), path + ".controller.lockedValue");
        }
    }

    private static void validateDamageRule(JsonObject rule, String path) {
        validateAllowedMembers(rule, path, Set.of("domain", "invincibleSeconds"));
        if (!rule.has("invincibleSeconds")) {
            throw new BattleScriptValidationException(path + ".invincibleSeconds is required.");
        }
        double seconds = requireStaticNumber(rule.get("invincibleSeconds"), path + ".invincibleSeconds");
        if (seconds < 0.0D) {
            throw new BattleScriptValidationException(path + ".invincibleSeconds must be >= 0.");
        }
    }

    private static double requireStaticNumber(JsonElement element, String path) {
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new BattleScriptValidationException(path + " must be a static number.");
        }
        double value = element.getAsDouble();
        if (!Double.isFinite(value)) {
            throw new BattleScriptValidationException(path + " must be finite.");
        }
        return value;
    }

    private static void validateOptionalBoolean(JsonObject object, String member, String path) {
        if (object.has(member)
                && (!object.get(member).isJsonPrimitive() || !object.get(member).getAsJsonPrimitive().isBoolean())) {
            throw new BattleScriptValidationException(path + "." + member + " must be boolean.");
        }
    }

    private static void validateRenderActionRequest(JsonObject action, String path) {
        validateAllowedMembers(action, path, Set.of("type", "action"));
        JsonObject request = BattleScriptJson.requireObject(action, "action", path);
        String type = BattleScriptJson.requireString(request, "type", path + ".action");
        switch (type) {
            case "scene_transition" -> {
                validateAllowedMembers(request, path + ".action", Set.of("type", "durationSeconds"));
                validateOptionalNonNegativeNumber(request, "durationSeconds", path + ".action");
            }
            case "screen_shake" -> {
                validateAllowedMembers(request, path + ".action", Set.of("type", "durationSeconds", "intensity"));
                validateOptionalNonNegativeNumber(request, "durationSeconds", path + ".action");
                validateOptionalNumberInRange(request, "intensity", path + ".action", 0.0D, 1.0D);
            }
            case "screen_flash" -> {
                validateAllowedMembers(
                        request,
                        path + ".action",
                        Set.of("type", "durationSeconds", "intensity", "color")
                );
                validateOptionalNonNegativeNumber(request, "durationSeconds", path + ".action");
                validateOptionalNumberInRange(request, "intensity", path + ".action", 0.0D, 1.0D);
                if (request.has("color")) {
                    String color = BattleScriptJson.requireString(request, "color", path + ".action");
                    if (!color.matches("#[0-9a-fA-F]{6}")) {
                        throw new BattleScriptValidationException(path + ".action.color must use #RRGGBB.");
                    }
                }
            }
            case "environment_background_opacity" -> {
                validateAllowedMembers(
                        request,
                        path + ".action",
                        Set.of("type", "durationSeconds", "opacity")
                );
                validateOptionalNonNegativeNumber(request, "durationSeconds", path + ".action");
                validateOptionalNumberInRange(request, "opacity", path + ".action", 0.0D, 1.0D);
            }
            case "foreground_overlay" -> {
                validateAllowedMembers(
                        request,
                        path + ".action",
                        Set.of("type", "source", "durationSeconds", "opacity", "color")
                );
                validateOptionalNonNegativeNumber(request, "durationSeconds", path + ".action");
                validateOptionalNumberInRange(request, "opacity", path + ".action", 0.0D, 1.0D);
                String source = BattleScriptJson.requireString(
                        request,
                        "source",
                        path + ".action"
                );
                if ("color".equals(source)) {
                    String color = BattleScriptJson.requireString(
                            request,
                            "color",
                            path + ".action"
                    );
                    if (!color.matches("#[0-9a-fA-F]{6}")) {
                        throw new BattleScriptValidationException(
                                path + ".action.color must use #RRGGBB."
                        );
                    }
                } else if ("environment".equals(source)) {
                    if (request.has("color")) {
                        throw new BattleScriptValidationException(
                                path + ".action.color is not allowed for environment source."
                        );
                    }
                } else {
                    throw new BattleScriptValidationException(
                            path + ".action.source must be color or environment."
                    );
                }
            }
            default -> throw new BattleScriptValidationException(path + ".action.type has unsupported render action request: " + type + ".");
        }
    }

    private static void validateSmoothCamera(JsonObject action, String path) {
        validateAllowedMembers(action, path, Set.of("type", "durationSeconds", "yawDegrees", "pitchDegrees"));
        validateOptionalNonNegativeNumber(action, "durationSeconds", path);
        validateOptionalFiniteNumber(action, "yawDegrees", path);
        validateOptionalFiniteNumber(action, "pitchDegrees", path);
    }

    private static void validateViewMode(JsonObject action, String path) {
        validateAllowedMembers(action, path, Set.of(
                "type", "viewMode", "sceneMode", "viewScale", "durationSeconds"
        ));
        validateOptionalNonNegativeNumber(action, "durationSeconds", path);
        if (action.has("viewScale")) {
            double scale = action.get("viewScale").getAsDouble();
            if (!Double.isFinite(scale) || scale <= 0.0D) {
                throw new BattleScriptValidationException(path + ".viewScale must be finite and > 0.");
            }
        }
        if (action.has("sceneMode")
                && !Set.of("2d", "3d").contains(BattleScriptJson.requireString(action, "sceneMode", path))) {
            throw new BattleScriptValidationException(path + ".sceneMode must be 2d or 3d.");
        }
        JsonObject mode = BattleScriptJson.requireObject(action, "viewMode", path);
        String type = BattleScriptJson.requireString(mode, "type", path + ".viewMode");
        String sceneMode = BattleScriptJson.optionalString(action, "sceneMode")
                .orElse("ortho_2d".equals(type) ? "2d" : "3d");
        double viewScale = BattleScriptJson.optionalDouble(action, "viewScale", 1.0D);
        if ("2d".equals(sceneMode)
                && (!"ortho_2d".equals(type) || Double.compare(viewScale, 1.0D) != 0)) {
            throw new BattleScriptValidationException(
                    path + " TWO_D scene requires viewMode.type=ortho_2d and viewScale=1."
            );
        }
        switch (type) {
            case "ortho_2d" -> validateAllowedMembers(mode, path + ".viewMode", Set.of("type"));
            case "ortho_2_5d" -> {
                validateAllowedMembers(mode, path + ".viewMode", Set.of(
                        "type", "viewDirection", "cameraUp", "lockedAxis", "lockedValue",
                        "orthoHeight", "verticalCenterOffset", "framing"
                ));
                validateOptionalVector3(mode, "viewDirection", path + ".viewMode");
                validateOptionalVector3(mode, "cameraUp", path + ".viewMode");
                String axis = BattleScriptJson.optionalString(mode, "lockedAxis").orElse("y");
                if (!Set.of("x", "y", "z").contains(axis)) {
                    throw new BattleScriptValidationException(path + ".viewMode.lockedAxis must be x, y, or z.");
                }
                validateOptionalFiniteNumber(mode, "lockedValue", path + ".viewMode");
                validateOptionalFiniteNumber(mode, "verticalCenterOffset", path + ".viewMode");
                if (!mode.has("orthoHeight") || mode.get("orthoHeight").getAsDouble() <= 0.0D) {
                    throw new BattleScriptValidationException(path + ".viewMode.orthoHeight must be > 0.");
                }
                validateViewFraming(mode, path + ".viewMode");
            }
            case "perspective_3d" -> {
                validateAllowedMembers(mode, path + ".viewMode", Set.of("type", "camera"));
                JsonObject camera = BattleScriptJson.requireObject(mode, "camera", path + ".viewMode");
                validateRequiredStaticVector3(camera, "position", path + ".viewMode.camera");
                validateRequiredStaticVector3(camera, "target", path + ".viewMode.camera");
                double fov = camera.get("fovDegrees").getAsDouble();
                if (!Double.isFinite(fov) || fov <= 1.0D || fov >= 179.0D) {
                    throw new BattleScriptValidationException(path + ".viewMode.camera.fovDegrees must be between 1 and 179.");
                }
            }
            default -> throw new BattleScriptValidationException(path + ".viewMode.type is unsupported: " + type + ".");
        }
    }

    private static void validateViewFraming(JsonObject mode, String path) {
        if (!mode.has("framing")) {
            return;
        }
        JsonObject framing = BattleScriptJson.requireObject(mode, "framing", path);
        validateAllowedMembers(framing, path + ".framing", Set.of("type", "min", "max", "paddingPercent"));
        String type = BattleScriptJson.requireString(framing, "type", path + ".framing");
        if (!"fit_canonical_bounds".equals(type)) {
            throw new BattleScriptValidationException(path + ".framing.type is unsupported: " + type + ".");
        }
        validateRequiredStaticVector3(framing, "min", path + ".framing");
        validateRequiredStaticVector3(framing, "max", path + ".framing");
        JsonArray min = framing.getAsJsonArray("min");
        JsonArray max = framing.getAsJsonArray("max");
        for (int i = 0; i < 3; i++) {
            if (min.get(i).getAsDouble() >= max.get(i).getAsDouble()) {
                throw new BattleScriptValidationException(path + ".framing must satisfy min < max on every axis.");
            }
        }
        if (framing.has("paddingPercent")) {
            double padding = framing.get("paddingPercent").getAsDouble();
            if (!Double.isFinite(padding) || padding < 0.0D || padding > 100.0D) {
                throw new BattleScriptValidationException(path + ".framing.paddingPercent must be between 0 and 100.");
            }
        }
    }

    private static void validateCanonicalTransform(JsonObject actor, String path) {
        JsonObject transform = BattleScriptJson.requireObject(actor, "transform", path);
        validateAllowedMembers(transform, path + ".transform", CANONICAL_TRANSFORM_FIELDS);
        requireCanonicalSpace(transform, path + ".transform");
        validateRequiredStaticVector3(transform, "position", path + ".transform");
        validateOptionalVector3(transform, "rotationDeg", path + ".transform");
        validateOptionalPositiveStaticVector3(transform, "scale", path + ".transform");
    }

    private static void validateCanonicalVelocity(JsonObject velocity, String path) {
        validateAllowedMembers(velocity, path, CANONICAL_VELOCITY_FIELDS);
        requireCanonicalSpace(velocity, path);
        validateRequiredStaticVector3(velocity, "value", path);
    }

    private static void validateSpawnAction(
            JsonObject action,
            String path,
            BattleScriptTypeContext typeContext
    ) {
        if (action.has("actorId")) {
            String actorId = BattleScriptJson.requireString(action, "actorId", path);
            if (actorId.isBlank()) {
                throw new BattleScriptValidationException(path + ".actorId must not be blank.");
            }
        }
        if (action.has("position")) {
            throw new BattleScriptValidationException(
                    path + ".position has moved to " + path + ".transform.position; use the canonical transform object."
            );
        }
        if (action.has("transform")) {
            JsonObject transform = BattleScriptJson.requireObject(action, "transform", path);
            validateAllowedMembers(transform, path + ".transform", CANONICAL_TRANSFORM_FIELDS);
            requireCanonicalSpace(transform, path + ".transform");
            if (!transform.has("position") && !transform.has("rotationDeg") && !transform.has("scale")) {
                throw new BattleScriptValidationException(
                        BattleScriptDiagnosticCode.EMPTY_SPAWN_TRANSFORM_OVERRIDE,
                        path + ".transform",
                        "spawn transform override must contain position, rotationDeg, or scale."
                );
            }
            validateOptionalValueExpr(transform, "position", path + ".transform", BattleScriptValueType.VECTOR3, typeContext);
            validateOptionalValueExpr(transform, "rotationDeg", path + ".transform", BattleScriptValueType.VECTOR3, typeContext);
            if (transform.has("scale")) {
                TYPE_CHECKER.validate(
                        transform.get("scale"),
                        BattleScriptValueType.VECTOR3,
                        typeContext,
                        path + ".transform.scale"
                );
                validatePositiveSpawnScale(transform.get("scale"), path + ".transform.scale");
            }
        }
        if (action.has("velocity")) {
            JsonElement velocityElement = action.get("velocity");
            if (isDirectVectorValueExpr(velocityElement)) {
                throw new BattleScriptValidationException(
                        path + ".velocity uses the legacy direct ValueExpr shape; wrap it as "
                                + "{space:\"battle.canonical\",value:<Vector3 ValueExpr>}."
                );
            }
            JsonObject velocity = BattleScriptJson.requireObject(velocityElement, path + ".velocity");
            validateAllowedMembers(velocity, path + ".velocity", CANONICAL_VELOCITY_FIELDS);
            requireCanonicalSpace(velocity, path + ".velocity");
            validateRequiredValueExpr(
                    velocity,
                    "value",
                    path + ".velocity",
                    BattleScriptValueType.VECTOR3,
                    typeContext
            );
        }
    }

    private static boolean isDirectVectorValueExpr(JsonElement element) {
        if (element == null || element.isJsonArray()) {
            return true;
        }
        if (!element.isJsonObject()) {
            return false;
        }
        JsonObject object = element.getAsJsonObject();
        return object.has("type") || object.has("read");
    }

    private static void validatePositiveSpawnScale(JsonElement scale, String path) {
        JsonElement[] components = staticVector3Components(scale);
        if (components == null) {
            throw new BattleScriptValidationException(
                    path + " dynamic scale is not supported; scale must have three statically provable positive components."
            );
        }
        for (JsonElement component : components) {
            if (!component.isJsonPrimitive()
                    || !component.getAsJsonPrimitive().isNumber()
                    || !Double.isFinite(component.getAsDouble())
                    || component.getAsDouble() <= 0.0D) {
                throw new BattleScriptValidationException(
                        path + " must have three statically provable positive components."
                );
            }
        }
    }

    private static JsonElement[] staticVector3Components(JsonElement vector) {
        JsonElement[] components;
        if (vector.isJsonArray() && vector.getAsJsonArray().size() == 3) {
            JsonArray array = vector.getAsJsonArray();
            components = new JsonElement[]{array.get(0), array.get(1), array.get(2)};
        } else {
            if (!vector.isJsonObject()) {
                return null;
            }
            JsonObject object = vector.getAsJsonObject();
            if (!"vector3".equals(BattleScriptJson.optionalString(object, "type").orElse(""))
                    || !object.has("x")
                    || !object.has("y")
                    || !object.has("z")) {
                return null;
            }
            components = new JsonElement[]{object.get("x"), object.get("y"), object.get("z")};
        }
        for (JsonElement component : components) {
            if (!component.isJsonPrimitive() || !component.getAsJsonPrimitive().isNumber()) {
                return null;
            }
        }
        return components;
    }

    private static void validateCollisionPolicy(JsonObject policy, String path) {
        String type = BattleScriptJson.requireString(policy, "type", path);
        if (!Set.of("volume_3d", "projected_2d", "hybrid_depth_band").contains(type)) {
            throw new BattleScriptValidationException(path + ".type is unsupported: " + type + ".");
        }
        if ("hybrid_depth_band".equals(type)) {
            double distance = BattleScriptJson.optionalDouble(policy, "maxDepthDistance", -1.0D);
            if (!Double.isFinite(distance) || distance < 0.0D) {
                throw new BattleScriptValidationException(path + ".maxDepthDistance must be finite and >= 0.");
            }
        } else if (policy.has("maxDepthDistance")) {
            throw new BattleScriptValidationException(path + ".maxDepthDistance is only valid for hybrid_depth_band.");
        }
    }

    private static void requireCanonicalSpace(JsonObject object, String path) {
        String space = BattleScriptJson.requireString(object, "space", path);
        if (!"battle.canonical".equals(space)) {
            throw new BattleScriptValidationException(path + ".space must be battle.canonical.");
        }
    }

    private static void validateRequiredStaticVector3(JsonObject object, String member, String path) {
        if (!object.has(member)) {
            throw new BattleScriptValidationException(path + "." + member + " is required.");
        }
        validateStaticVector3(object.get(member), path + "." + member);
    }

    private static void validateOptionalVector3(JsonObject object, String member, String path) {
        if (object.has(member)) {
            validateStaticVector3(object.get(member), path + "." + member);
        }
    }

    private static void validateOptionalPositiveStaticVector3(JsonObject object, String member, String path) {
        if (!object.has(member)) {
            return;
        }
        validateStaticVector3(object.get(member), path + "." + member);
        for (JsonElement component : object.getAsJsonArray(member)) {
            if (component.getAsDouble() <= 0.0D) {
                throw new BattleScriptValidationException(path + "." + member + " components must be positive.");
            }
        }
    }

    private static void validateStaticVector3(JsonElement element, String path) {
        if (!element.isJsonArray() || element.getAsJsonArray().size() != 3) {
            throw new BattleScriptValidationException(path + " must be a 3-number array.");
        }
        for (int i = 0; i < 3; i++) {
            JsonElement value = element.getAsJsonArray().get(i);
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()
                    || !Double.isFinite(value.getAsDouble())) {
                throw new BattleScriptValidationException(path + "[" + i + "] must be finite.");
            }
        }
    }

    private static void validateTarget(JsonObject action, String path) {
        if (action.has("target")) {
            JsonElement targetElement = action.get("target");
            if (targetElement.isJsonPrimitive() && targetElement.getAsJsonPrimitive().isString()) {
                String target = targetElement.getAsString();
                if (!Set.of("self", "player", "other", "source").contains(target)) {
                    throw new BattleScriptValidationException(path + ".target has unsupported keyword: " + target + ".");
                }
                return;
            }
            JsonObject target = BattleScriptJson.requireObject(targetElement, path + ".target");
            if (!target.has("id") && !target.has("actorId") && !target.has("tag")) {
                throw new BattleScriptValidationException(path + ".target must contain id, actorId, or tag.");
            }
            return;
        }
        if (!action.has("actorId") && !action.has("tag")) {
            throw new BattleScriptValidationException(path + " must contain target, actorId, or tag.");
        }
    }

    private static void validateActorTemplate(CompiledBattleDefinition definition, ActorPrefabDefinition actor, String path) {
        JsonObject root = actor.root();
        String type = BattleScriptJson.requireString(root, "type", path);
        if (!ACTOR_TEMPLATE_TYPES.contains(type)) {
            throw new BattleScriptValidationException(path + ".type has unsupported actor template type: " + type + ".");
        }
        if ("scripted_actor".equals(type)) {
            validateScriptedActorTemplate(definition, root, path);
        } else if (root.has("visual") && root.get("visual").isJsonObject()) {
            throw new BattleScriptValidationException(path + ".visual UI payload is only supported by scripted_actor templates.");
        } else {
            validateCanonicalTransform(root, path);
            if (root.has("velocity")) {
                validateCanonicalVelocity(BattleScriptJson.requireObject(root, "velocity", path), path + ".velocity");
            }
            if (root.has("collision")) {
                validateCollision(BattleScriptJson.requireObject(root, "collision", path), path + ".collision");
            }
            validateVisual(root, path + ".visual");
        }
    }

    private static void validateScriptedActorTemplate(CompiledBattleDefinition definition, JsonObject actor, String path) {
        String kind = BattleScriptJson.requireString(actor, "kind", path);
        if (ResourceLocation.tryParse(kind) == null) {
            throw new BattleScriptValidationException(path + ".kind must be a valid resource location.");
        }
        validateVisual(actor, path + ".visual");
        String role = BattleScriptJson.requireString(actor, "role", path);
        if (!Set.of("gameplay", "presentation", "hybrid").contains(role)) {
            throw new BattleScriptValidationException(path + ".role must be gameplay, presentation, or hybrid.");
        }
        if (!"presentation".equals(role) || actor.has("transform")) {
            validateCanonicalTransform(actor, path);
        }
        if (actor.has("velocity")) {
            validateCanonicalVelocity(BattleScriptJson.requireObject(actor, "velocity", path), path + ".velocity");
        }
        if (actor.has("collision")) {
            validateCollision(BattleScriptJson.requireObject(actor, "collision", path), path + ".collision");
        }
        if ("presentation".equals(role)
                && actor.has("collision")
                && !"none".equals(BattleScriptJson.optionalString(actor.getAsJsonObject("collision"), "shape").orElse("box"))) {
            throw new BattleScriptValidationException(path + ".collision must be none for presentation actors.");
        }
        if (actor.has("collisionPolicy")) {
            validateCollisionPolicy(actor.getAsJsonObject("collisionPolicy"), path + ".collisionPolicy");
        }
        Map<String, cn.jehorstudio.minetale.battle.logic.blackboard.value.BattleScriptValue.Type> actorVarTypes = new HashMap<>();
        if (actor.has("vars")) {
            JsonObject vars = BattleScriptJson.requireObject(actor, "vars", path);
            validateActorVars(definition, vars, path + ".vars");
            for (java.util.Map.Entry<String, JsonElement> entry : vars.entrySet()) {
                JsonObject var = BattleScriptJson.requireObject(entry.getValue(), path + ".vars." + entry.getKey());
                String typeText = BattleScriptJson.requireString(var, "type", path + ".vars." + entry.getKey());
                actorVarTypes.put(entry.getKey(), cn.jehorstudio.minetale.battle.logic.blackboard.value.BattleScriptValue.Type.parse(typeText, path + ".vars." + entry.getKey() + ".type"));
            }
        }
        JsonArray components = BattleScriptJson.optionalArray(actor, "components");
        for (int i = 0; i < components.size(); i++) {
            JsonObject component = BattleScriptJson.requireObject(components.get(i), path + ".components[" + i + "]");
            String componentType = BattleScriptJson.requireString(component, "type", path + ".components[" + i + "]");
            if (!ActorComponentRegistry.contains(componentType)) {
                throw new BattleScriptValidationException(path + ".components[" + i + "].type is not supported: " + componentType + ".");
            }
        }
        JsonObject events = BattleScriptJson.optionalObject(actor, "events").orElseGet(JsonObject::new);
        for (java.util.Map.Entry<String, JsonElement> entry : events.entrySet()) {
            if (!SCRIPTED_ACTOR_EVENTS.contains(entry.getKey())) {
                throw new BattleScriptValidationException(path + ".events has unsupported event: " + entry.getKey() + ".");
            }
            validateActorEvent(definition, entry.getKey(), entry.getValue(), path + ".events." + entry.getKey(), actorVarTypes);
        }
    }

    private static void validateVisual(JsonObject actor, String path) {
        if (!actor.has("visual")) {
            return;
        }
        try {
            VisualRef.fromJson(actor.get("visual"));
        } catch (IllegalArgumentException exception) {
            throw new BattleScriptValidationException(path + " is invalid: " + exception.getMessage(), exception);
        }
    }

    private static void validateActorVars(CompiledBattleDefinition definition, JsonObject vars, String path) {
        BattleScriptTypeContext typeContext = BattleScriptTypeContext.stage(definition);
        for (java.util.Map.Entry<String, JsonElement> entry : vars.entrySet()) {
            JsonObject var = BattleScriptJson.requireObject(entry.getValue(), path + "." + entry.getKey());
            String typeText = BattleScriptJson.requireString(var, "type", path + "." + entry.getKey());
            cn.jehorstudio.minetale.battle.logic.blackboard.value.BattleScriptValue.Type type =
                    cn.jehorstudio.minetale.battle.logic.blackboard.value.BattleScriptValue.Type.parse(typeText, path + "." + entry.getKey() + ".type");
            if (!var.has("initial")) {
                throw new BattleScriptValidationException(path + "." + entry.getKey() + ".initial is required.");
            }
            TYPE_CHECKER.validate(var.get("initial"), BattleScriptValueType.fromValueType(type), typeContext, path + "." + entry.getKey() + ".initial");
        }
    }

    private static void validateActorEvent(CompiledBattleDefinition definition, String event, JsonElement value, String path, Map<String, cn.jehorstudio.minetale.battle.logic.blackboard.value.BattleScriptValue.Type> actorVarTypes) {
        if ("onKeyPressed".equals(event)) {
            validateKeyEventMap(definition, value, path, actorVarTypes);
            return;
        }
        if ("onSignal".equals(event) && value.isJsonObject()) {
            for (java.util.Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) {
                validateActions(definition, BattleScriptJson.objectList(BattleScriptJson.requireArray(wrap(entry.getValue()), "actions", path + "." + entry.getKey()), path + "." + entry.getKey()), path + "." + entry.getKey(), 0, actorVarTypes);
            }
            return;
        }
        if (!value.isJsonArray()) {
            throw new BattleScriptValidationException(path + " must be an action array.");
        }
        validateActions(definition, BattleScriptJson.objectList(value.getAsJsonArray(), path), path, 0, actorVarTypes);
    }

    private static void validateKeyEventMap(
            CompiledBattleDefinition definition,
            JsonElement value,
            String path,
            Map<String, cn.jehorstudio.minetale.battle.logic.blackboard.value.BattleScriptValue.Type> actorVarTypes
    ) {
        if (!value.isJsonObject()) {
            throw new BattleScriptValidationException(path + " must map key mapping ids to action arrays.");
        }
        for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) {
            validateInputKey(entry.getKey(), path + "." + entry.getKey());
            JsonArray actions = BattleScriptJson.requireArray(wrap(entry.getValue()), "actions", path + "." + entry.getKey());
            validateActions(definition, BattleScriptJson.objectList(actions, path + "." + entry.getKey()), path + "." + entry.getKey(), 0, actorVarTypes);
        }
    }

    private static void validateCollision(JsonObject collision, String path) {
        String shape = BattleScriptJson.optionalString(collision, "shape").orElse("box");
        if ("none".equals(shape)) {
            return;
        }
        requireCanonicalSpace(collision, path);
        if (!"box".equals(shape)) {
            throw new BattleScriptValidationException(path + ".shape has unsupported collision shape: " + shape + ".");
        }
        validateVec3(collision, "halfSize", path);
    }

    private static void validateVec3(JsonObject object, String member, String path) {
        JsonArray array = BattleScriptJson.requireArray(object, member, path);
        if (array.size() != 3) {
            throw new BattleScriptValidationException(path + "." + member + " must contain exactly 3 numbers.");
        }
        for (int i = 0; i < array.size(); i++) {
            JsonElement element = array.get(i);
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
                throw new BattleScriptValidationException(path + "." + member + "[" + i + "] must be a number.");
            }
            double value = element.getAsDouble();
            if (!Double.isFinite(value)) {
                throw new BattleScriptValidationException(path + "." + member + "[" + i + "] must be finite.");
            }
        }
    }

    private static void validateOptionalNumberInRange(
            JsonObject object,
            String member,
            String path,
            double minimum,
            double maximum
    ) {
        if (!object.has(member)) {
            return;
        }
        JsonElement element = object.get(member);
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new BattleScriptValidationException(path + "." + member + " must be a number.");
        }
        double value = element.getAsDouble();
        if (!Double.isFinite(value) || value < minimum || value > maximum) {
            throw new BattleScriptValidationException(
                    path + "." + member + " must be finite and between " + minimum + " and " + maximum + "."
            );
        }
    }

    private static void validateOptionalNonNegativeNumber(JsonObject object, String member, String path) {
        if (!object.has(member)) {
            return;
        }
        validateOptionalFiniteNumber(object, member, path);
        if (object.get(member).getAsDouble() < 0.0D) {
            throw new BattleScriptValidationException(path + "." + member + " must be >= 0.");
        }
    }

    private static void validateOptionalFiniteNumber(JsonObject object, String member, String path) {
        if (!object.has(member)) {
            return;
        }
        JsonElement element = object.get(member);
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new BattleScriptValidationException(path + "." + member + " must be a number.");
        }
        if (!Double.isFinite(element.getAsDouble())) {
            throw new BattleScriptValidationException(path + "." + member + " must be finite.");
        }
    }

    private static void validateAllowedMembers(JsonObject object, String path, Set<String> allowedMembers) {
        for (String member : object.keySet()) {
            if (!allowedMembers.contains(member)) {
                throw new BattleScriptValidationException(path + " has unsupported field: " + member + ".");
            }
        }
    }

    private static JsonObject wrap(JsonElement actions) {
        JsonObject wrapper = new JsonObject();
        wrapper.add("actions", actions.deepCopy());
        return wrapper;
    }

    private static void validateBattleResult(CompiledBattleDefinition definition, JsonObject action, String path) {
        BattleScriptJson.optionalString(action, "outcome").ifPresent(outcome -> {
            if (definition.resolveOutcome(outcome).isEmpty()) {
                throw new BattleScriptValidationException(path + ".outcome references unknown outcome " + outcome + ".");
            }
        });
        if (!action.has("outcome")) {
            BattleScriptJson.optionalString(action, "result").ifPresent(result -> parseResultState(result, path + ".result"));
        }
    }

    private static BattleResultState parseResultState(String result, String path) {
        try {
            return BattleResultState.valueOf(result.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new BattleScriptValidationException(path + " has unsupported battle result: " + result, ex);
        }
    }

    private static void validateCondition(CompiledBattleDefinition definition, JsonObject condition, String path, int depth) {
        validateCondition(definition, condition, path, depth, null);
    }

    private static void validateCondition(
            CompiledBattleDefinition definition,
            JsonObject condition,
            String path,
            int depth,
            Map<String, cn.jehorstudio.minetale.battle.logic.blackboard.value.BattleScriptValue.Type> actorVarTypes
    ) {
        if (depth > definition.budgets().maxRecursionDepth()) {
            throw new BattleScriptValidationException(path + " exceeds maxRecursionDepth.");
        }
        if (condition.has("all")) {
            validateConditionArray(definition, BattleScriptJson.requireArray(condition, "all", path), path + ".all", depth + 1, actorVarTypes);
        }
        if (condition.has("any")) {
            validateConditionArray(definition, BattleScriptJson.requireArray(condition, "any", path), path + ".any", depth + 1, actorVarTypes);
        }
        if (condition.has("not")) {
            validateCondition(definition, BattleScriptJson.requireObject(condition, "not", path), path + ".not", depth + 1, actorVarTypes);
        }
        if (condition.has("signalReceived")) {
            BattleScriptJson.requireString(condition, "signalReceived", path);
        }
        if (condition.has("currentTickSignalReceived")) {
            BattleScriptJson.requireString(condition, "currentTickSignalReceived", path);
        }
        if (condition.has("keyDown")) {
            validateInputKey(BattleScriptJson.requireString(condition, "keyDown", path), path + ".keyDown");
        }
        if (condition.has("type")) {
            TYPE_CHECKER.validate(condition, BattleScriptValueType.BOOLEAN, typeContext(definition, actorVarTypes), path);
            return;
        }
        if (condition.has("read")) {
            String read = BattleScriptJson.requireString(condition, "read", path);
            if ((read.startsWith("actor.var:") || read.startsWith("actor.property:")) && actorVarTypes == null) {
                throw new BattleScriptValidationException(path + ".read requires actor event context: " + read + ".");
            }
            validateRead(definition, read, path + ".read");
            if (condition.has("right")) {
                TYPE_CHECKER.infer(condition.get("right"), typeContext(definition, actorVarTypes), path + ".right");
            }
        }
    }

    private static void validateInputKey(String key, String path) {
        if (!BattleInputKey.contains(key)) {
            throw new BattleScriptValidationException(path + " is not an exposed Minecraft/MineTale key mapping: " + key + ".");
        }
    }

    private static void validateConditionArray(
            CompiledBattleDefinition definition,
            JsonArray array,
            String path,
            int depth,
            Map<String, cn.jehorstudio.minetale.battle.logic.blackboard.value.BattleScriptValue.Type> actorVarTypes
    ) {
        for (int i = 0; i < array.size(); i++) {
            validateCondition(definition, BattleScriptJson.requireObject(array.get(i), path + "[" + i + "]"), path + "[" + i + "]", depth, actorVarTypes);
        }
    }

    private static void validateRead(CompiledBattleDefinition definition, String read, String path) {
        if (read.startsWith("script.var:")) {
            requireVariable(definition, read.substring("script.var:".length()), path);
            return;
        }
        if (read.startsWith("actor.var:") || read.startsWith("actor.property:")) {
            return;
        }
        if (read.startsWith("actor.tag_count:")) {
            return;
        }
        switch (read) {
            case "player.any.hp_percent", "player.any.hp_text",
                 "phase.elapsed_seconds", "phase.elapsed_ticks", "phase.current" -> {
            }
            default -> throw new BattleScriptValidationException(path + " is not an allowed condition read: " + read);
        }
    }

    private static void validateRequiredValueExpr(
            JsonObject object,
            String member,
            String path,
            BattleScriptValueType expected,
            BattleScriptTypeContext typeContext
    ) {
        if (!object.has(member)) {
            throw new BattleScriptValidationException(path + "." + member + " is required.");
        }
        TYPE_CHECKER.validate(object.get(member), expected, typeContext, path + "." + member);
    }

    private static void validateOptionalValueExpr(
            JsonObject object,
            String member,
            String path,
            BattleScriptValueType expected,
            BattleScriptTypeContext typeContext
    ) {
        if (!object.has(member)) {
            return;
        }
        TYPE_CHECKER.validate(object.get(member), expected, typeContext, path + "." + member);
    }

    private static BattleScriptTypeContext typeContext(
            CompiledBattleDefinition definition,
            Map<String, cn.jehorstudio.minetale.battle.logic.blackboard.value.BattleScriptValue.Type> actorVarTypes
    ) {
        BattleScriptTypeContext context = BattleScriptTypeContext.stage(definition);
        return actorVarTypes == null ? context : context.withActorVariables(actorVarTypes);
    }

    private static boolean isNumeric(cn.jehorstudio.minetale.battle.logic.blackboard.value.BattleScriptValue.Type type) {
        return type == cn.jehorstudio.minetale.battle.logic.blackboard.value.BattleScriptValue.Type.INT
                || type == cn.jehorstudio.minetale.battle.logic.blackboard.value.BattleScriptValue.Type.DOUBLE;
    }

    private static void requireVariable(CompiledBattleDefinition definition, String id, String path) {
        if (!definition.variables().containsKey(id)) {
            throw new BattleScriptValidationException(path + " references unknown variable " + id + ".");
        }
    }

    private static void requireActorPrefab(CompiledBattleDefinition definition, String ref, String path) {
        if (definition.resolveActorPrefab(ref).isEmpty()) {
            throw new BattleScriptValidationException(path + " references unknown actor prefab " + ref + ".");
        }
    }

    private static void requireRuleSet(CompiledBattleDefinition definition, String ref, String path) {
        if (definition.resolveRuleSet(ref).isEmpty()) {
            throw new BattleScriptValidationException(path + " references unknown ruleset " + ref + ".");
        }
    }

    private static void requirePattern(CompiledBattleDefinition definition, String ref, String path) {
        if (definition.resolvePattern(ref).isEmpty()) {
            throw new BattleScriptValidationException(path + " references unknown pattern " + ref + ".");
        }
    }

}
