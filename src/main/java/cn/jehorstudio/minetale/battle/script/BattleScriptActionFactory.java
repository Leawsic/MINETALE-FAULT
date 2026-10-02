package cn.jehorstudio.minetale.battle.script;

import cn.jehorstudio.minetale.battle.logic.action.BattleAction;
import cn.jehorstudio.minetale.battle.logic.action.BattleRenderRequest;
import cn.jehorstudio.minetale.battle.logic.action.BattleRenderRequestPayload;
import cn.jehorstudio.minetale.battle.logic.action.actions.RequestRenderAction;
import cn.jehorstudio.minetale.battle.logic.action.actions.RequestSmoothCameraAction;
import cn.jehorstudio.minetale.battle.logic.action.actions.WaitSecondsAction;
import cn.jehorstudio.minetale.battle.logic.action.actions.WaitTicksAction;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class BattleScriptActionFactory {
    private final BattleScriptRuntime runtime;

    BattleScriptActionFactory(BattleScriptRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    BattleAction create(JsonObject action) {
        String type = BattleScriptJson.requireString(action, "type", "action");
        return switch (type) {
            case "wait" -> waitAction(action);
            case "wait_until" -> new BattleScriptWaitUntilAction(this.runtime, action);
            case "spawn_actor", "spawn_bullet" -> new BattleScriptSpawnActorAction(this.runtime, action);
            case "destroy_actor" -> new BattleScriptDestroyActorAction(this.runtime, action);
            case "set_var" -> new BattleScriptSetVariableAction(this.runtime, action);
            case "modify_var" -> new BattleScriptModifyVariableAction(this.runtime, action);
            case "set_actor_var" -> new BattleScriptSetActorVariableAction(this.runtime, action);
            case "modify_actor_var" -> new BattleScriptModifyActorVariableAction(this.runtime, action);
            case "push_ruleset" -> new BattleScriptPushRulesetAction(this.runtime, action);
            case "pop_ruleset" -> new BattleScriptPopRulesetAction(action);
            case "call_pattern" -> new BattleScriptSequenceAction(this, patternActions(action));
            case "sequence" -> new BattleScriptSequenceAction(this, nestedActions(action));
            case "parallel" -> new BattleScriptParallelAction(this, nestedActions(action));
            case "random_one" -> new BattleScriptRandomOneAction(this.runtime, this, randomOptions(action));
            case "repeat" -> new BattleScriptRepeatAction(this.runtime, this, action, nestedActions(action));
            case "until" -> new BattleScriptUntilAction(this.runtime, this, action, nestedActions(action));
            case "if", "if_else" -> new BattleScriptIfAction(this.runtime, this, action);
            case "cleanup_tag" -> new BattleScriptCleanupTagAction(this.runtime, action);
            case "end_phase" -> new BattleScriptEndPhaseAction(this.runtime, action);
            case "end_battle" -> new BattleScriptEndBattleAction(this.runtime, action);
            case "request_render_action" -> new RequestRenderAction(renderRequest(action));
            case "play_sound" -> new BattleScriptPlaySoundAction(this.runtime, action);
            case "smooth_camera" -> new RequestSmoothCameraAction(
                    BattleScriptJson.optionalDouble(action, "durationSeconds", 0.4D),
                    BattleScriptJson.optionalDouble(action, "yawDegrees", 0.0D),
                    BattleScriptJson.optionalDouble(action, "pitchDegrees", 0.0D)
            );
            case "set_view_mode" -> new BattleScriptSetViewModeAction(this.runtime, action);
            case "set_collision_policy" -> new BattleScriptSetCollisionPolicyAction(this.runtime, action);
            case "emit_signal" -> new BattleScriptEmitSignalAction(this.runtime, action);
            case "set_actor_property" -> new BattleScriptSetActorPropertyAction(this.runtime, action);
            case "destroy_self" -> new BattleScriptDestroySelfAction(this.runtime);
            case "damage_player" -> new BattleScriptDamagePlayerAction(this.runtime, action);
            case "heal_player" -> new BattleScriptHealPlayerAction(this.runtime, action);
            case "set_self_velocity" -> new BattleScriptSetSelfVelocityAction(this.runtime, action);
            case "set_self_position" -> new BattleScriptSetSelfPositionAction(this.runtime, action);
            case "move_self_by_velocity" -> new BattleScriptMoveSelfByVelocityAction(this.runtime);
            case "set_actor_image_texture", "set_actor_appearance_property", "set_actor_text", "type_actor_text", "insert_actor_text", "delete_actor_text",
                 "set_actor_progress_min", "set_actor_progress_max", "set_actor_progress_value" ->
                    new BattleScriptAppearanceAction(this.runtime, action);
            default -> throw new IllegalArgumentException("Unsupported BattleScript action type: " + type);
        };
    }

    private static BattleAction waitAction(JsonObject action) {
        if (action.has("ticks")) {
            return new WaitTicksAction(action.get("ticks").getAsLong());
        }
        return new WaitSecondsAction(BattleScriptJson.optionalDouble(action, "seconds", 0.0D));
    }

    private static BattleRenderRequest renderRequest(JsonObject action) {
        JsonObject request = BattleScriptJson.requireObject(action, "action", "request_render_action");
        String type = BattleScriptJson.requireString(request, "type", "request_render_action.action");
        return switch (type) {
            case "scene_transition" -> BattleRenderRequest.sceneTransition(
                    BattleScriptJson.optionalDouble(request, "durationSeconds", 0.35D)
            );
            case "screen_shake" -> BattleRenderRequest.screenShake(
                    BattleScriptJson.optionalDouble(request, "durationSeconds", 0.25D),
                    BattleScriptJson.optionalDouble(request, "intensity", 0.5D)
            );
            case "screen_flash" -> BattleRenderRequest.screenFlash(
                    BattleScriptJson.optionalDouble(request, "durationSeconds", 0.15D),
                    BattleScriptJson.optionalDouble(request, "intensity", 1.0D),
                    parseRgb(BattleScriptJson.optionalString(request, "color").orElse("#FFFFFF"))
            );
            case "environment_background_opacity" -> BattleRenderRequest.environmentBackgroundOpacity(
                    BattleScriptJson.optionalDouble(request, "durationSeconds", 0.0D),
                    BattleScriptJson.optionalDouble(request, "opacity", 1.0D)
            );
            case "foreground_overlay" -> foregroundOverlay(request);
            default -> throw new IllegalArgumentException("Unsupported render action request type: " + type);
        };
    }

    private static BattleRenderRequest foregroundOverlay(JsonObject request) {
        String source = BattleScriptJson.requireString(
                request,
                "source",
                "request_render_action.action"
        );
        BattleRenderRequestPayload.ForegroundOverlaySource parsedSource = switch (source) {
            case "color" -> BattleRenderRequestPayload.ForegroundOverlaySource.COLOR;
            case "environment" -> BattleRenderRequestPayload.ForegroundOverlaySource.ENVIRONMENT;
            default -> throw new IllegalArgumentException(
                    "Foreground overlay source must be color or environment."
            );
        };
        int rgb = parsedSource == BattleRenderRequestPayload.ForegroundOverlaySource.COLOR
                ? parseRgb(BattleScriptJson.requireString(
                        request,
                        "color",
                        "request_render_action.action"
                ))
                : 0;
        return BattleRenderRequest.foregroundOverlay(
                BattleScriptJson.optionalDouble(request, "durationSeconds", 0.0D),
                BattleScriptJson.optionalDouble(request, "opacity", 1.0D),
                parsedSource,
                rgb
        );
    }

    private static int parseRgb(String color) {
        if (!color.matches("#[0-9a-fA-F]{6}")) {
            throw new IllegalArgumentException("Render action color must use #RRGGBB.");
        }
        return Integer.parseInt(color.substring(1), 16);
    }

    private List<JsonObject> patternActions(JsonObject action) {
        String id = BattleScriptJson.requireString(action, "id", "action");
        PatternDefinition pattern = this.runtime.definition().compiled().resolvePattern(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown BattleScript pattern: " + id));
        return pattern.actions();
    }

    static List<JsonObject> nestedActions(JsonObject action) {
        if (action.has("actions")) {
            return BattleScriptJson.objectList(action.getAsJsonArray("actions"), "action.actions");
        }
        if (action.has("action")) {
            return List.of(BattleScriptJson.requireObject(action, "action", "action").deepCopy());
        }
        return List.of();
    }

    private static List<List<JsonObject>> randomOptions(JsonObject action) {
        JsonArray source = action.has("options") ? action.getAsJsonArray("options") : action.getAsJsonArray("actions");
        List<List<JsonObject>> options = new ArrayList<>();
        for (int i = 0; i < source.size(); i++) {
            if (source.get(i).isJsonArray()) {
                options.add(BattleScriptJson.objectList(source.get(i).getAsJsonArray(), "action.options[" + i + "]"));
            } else {
                options.add(List.of(BattleScriptJson.requireObject(source.get(i), "action.options[" + i + "]").deepCopy()));
            }
        }
        return List.copyOf(options);
    }
}
