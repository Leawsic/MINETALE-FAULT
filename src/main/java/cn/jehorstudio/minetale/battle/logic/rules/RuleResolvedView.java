package cn.jehorstudio.minetale.battle.logic.rules;

import cn.jehorstudio.minetale.battle.logic.states.AppliedRule;
import cn.jehorstudio.minetale.battle.logic.states.RuleStateSnapshot;
import cn.jehorstudio.minetale.battle.logic.coordinate.BattleCoordinateSpace;
import cn.jehorstudio.minetale.battle.logic.coordinate.BattleCoordinateStateCache;
import cn.jehorstudio.minetale.battle.logic.coordinate.BattleViewMode;
import cn.jehorstudio.minetale.battle.logic.coordinate.CanonicalVec3;
import cn.jehorstudio.minetale.battle.logic.coordinate.CollisionPolicy;
import cn.jehorstudio.minetale.battle.logic.coordinate.ControlPolicy;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Objects;

public record RuleResolvedView(
        PlayerControl playerControl,
        Damage damage,
        BattleViewMode viewMode,
        ControlPolicy controlPolicy,
        CollisionPolicy collisionPolicy
) {
    public static final double DEFAULT_RED_FREE_SPEED_PER_SECOND = 1.25D;
    public static final double DEFAULT_INVINCIBLE_SECONDS = 1.0D;

    public RuleResolvedView {
        Objects.requireNonNull(playerControl, "playerControl");
        Objects.requireNonNull(damage, "damage");
        Objects.requireNonNull(viewMode, "viewMode");
        Objects.requireNonNull(controlPolicy, "controlPolicy");
        Objects.requireNonNull(collisionPolicy, "collisionPolicy");
    }

    public static RuleResolvedView resolve(RuleStateSnapshot snapshot) {
        return resolve(snapshot, new BattleCoordinateStateCache.Snapshot(
                BattleViewMode.ORTHO_2D,
                ControlPolicy.PLANE_LOCKED_XZ,
                BattleCoordinateStateCache.SceneMode.TWO_D,
                1.0D,
                0.0D
        ));
    }

    public static RuleResolvedView resolve(
            RuleStateSnapshot snapshot,
            BattleCoordinateStateCache.Snapshot coordinates
    ) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(coordinates, "coordinates");
        PlayerControl playerControl = PlayerControl.redFreeDefault();
        Damage damage = Damage.defaultDamage();
        BattleViewMode viewMode = coordinates.viewMode();
        ControlPolicy controlPolicy = coordinates.controlPolicy();
        CollisionPolicy collisionPolicy = null;
        for (AppliedRule rule : snapshot.activeRules()) {
            if ("player_control".equals(rule.domain())) {
                playerControl = resolvePlayerControl(rule.data(), playerControl);
                controlPolicy = resolveControlPolicy(rule.data(), controlPolicy);
            } else if ("damage".equals(rule.domain())) {
                damage = resolveDamage(rule.data(), damage);
            } else if ("view_mode".equals(rule.domain())) {
                viewMode = resolveViewMode(rule.data(), viewMode);
            } else if ("collision_policy".equals(rule.domain())) {
                collisionPolicy = resolveCollisionPolicy(rule.data(), collisionPolicy);
            }
        }
        if (collisionPolicy == null) {
            collisionPolicy = viewMode.orthographic()
                    ? CollisionPolicy.PROJECTED_2D
                    : CollisionPolicy.VOLUME_3D;
        }
        return new RuleResolvedView(playerControl, damage, viewMode, controlPolicy, collisionPolicy);
    }

    private static ControlPolicy resolveControlPolicy(JsonObject rule, ControlPolicy fallback) {
        JsonElement controllerElement = rule.get("controller");
        if (controllerElement == null || !controllerElement.isJsonObject()) return fallback;
        JsonObject controller = controllerElement.getAsJsonObject();
        String type = string(controller, "type", "plane_locked");
        double speed = doubleValue(controller, "moveSpeedPerSecond", fallback.speedBuPerSecond());
        return switch (type) {
            case "red_free", "plane_locked" -> ControlPolicy.planeLocked(
                    BattleCoordinateSpace.Axis.parse(string(controller, "lockedAxis", "y"), "controller.lockedAxis"),
                    doubleValue(controller, "lockedValue", 0.0D),
                    speed
            );
            case "free_3d" -> ControlPolicy.free3d(speed);
            case "locked", "none" -> new ControlPolicy(
                    ControlPolicy.Type.LOCKED,
                    ControlPolicy.Basis.CANONICAL,
                    null,
                    0.0D,
                    0.0D,
                    null,
                    0.0D
            );
            default -> fallback;
        };
    }

    private static BattleViewMode resolveViewMode(JsonObject rule, BattleViewMode fallback) {
        String type = string(rule, "type", fallback.type().id());
        if ("ortho_2d".equals(type)) {
            return BattleViewMode.ORTHO_2D;
        }
        if ("ortho_2_5d".equals(type)) {
            return new BattleViewMode(
                    BattleViewMode.Type.ORTHO_2_5D,
                    vector(rule, "viewDirection", fallback.viewDirection()),
                    vector(rule, "cameraUp", fallback.cameraUp()),
                    BattleCoordinateSpace.Axis.parse(string(rule, "lockedAxis", "y"), "view_mode.lockedAxis"),
                    doubleValue(rule, "lockedValue", fallback.lockedValue()),
                    doubleValue(rule, "orthoHeight", fallback.orthoHeight()),
                    doubleValue(rule, "verticalCenterOffset", fallback.verticalCenterOffset()),
                    null,
                    resolveFraming(rule, fallback.framing())
            );
        }
        if ("perspective_3d".equals(type)) {
            JsonObject camera = rule.has("camera") && rule.get("camera").isJsonObject()
                    ? rule.getAsJsonObject("camera")
                    : new JsonObject();
            return BattleViewMode.perspective3d(new BattleViewMode.Camera(
                    vector(camera, "position", new CanonicalVec3(0.0D, 4.0D, 0.0D)),
                    vector(camera, "target", CanonicalVec3.ZERO),
                    doubleValue(camera, "fovDegrees", 45.0D)
            ));
        }
        return fallback;
    }

    private static BattleViewMode.Framing resolveFraming(
            JsonObject view,
            BattleViewMode.Framing fallback
    ) {
        JsonElement element = view.get("framing");
        if (element == null || !element.isJsonObject()) {
            return fallback;
        }
        JsonObject framing = element.getAsJsonObject();
        if (!"fit_canonical_bounds".equals(string(framing, "type", ""))) {
            return fallback;
        }
        return BattleViewMode.Framing.fitCanonicalBounds(
                vector(framing, "min", new CanonicalVec3(-1.0D, -1.0D, -0.1D)),
                vector(framing, "max", new CanonicalVec3(1.0D, 1.0D, 0.1D)),
                doubleValue(framing, "paddingPercent", 0.0D)
        );
    }

    private static CollisionPolicy resolveCollisionPolicy(JsonObject rule, CollisionPolicy fallback) {
        return switch (string(rule, "type", "")) {
            case "volume_3d" -> CollisionPolicy.VOLUME_3D;
            case "projected_2d" -> CollisionPolicy.PROJECTED_2D;
            case "hybrid_depth_band" -> CollisionPolicy.hybrid(doubleValue(rule, "maxDepthDistance", 0.2D));
            default -> fallback;
        };
    }

    private static CanonicalVec3 vector(JsonObject root, String member, CanonicalVec3 fallback) {
        JsonElement element = root.get(member);
        if (element == null || !element.isJsonArray() || element.getAsJsonArray().size() != 3) return fallback;
        return new CanonicalVec3(
                element.getAsJsonArray().get(0).getAsDouble(),
                element.getAsJsonArray().get(1).getAsDouble(),
                element.getAsJsonArray().get(2).getAsDouble()
        );
    }

    private static PlayerControl resolvePlayerControl(JsonObject rule, PlayerControl fallback) {
        JsonElement controllerElement = rule.get("controller");
        if (controllerElement == null || !controllerElement.isJsonObject()) {
            return fallback;
        }
        JsonObject controller = controllerElement.getAsJsonObject();
        String type = string(controller, "type", fallback.controllerType());
        return switch (type) {
            case "red_free" -> new PlayerControl(
                    type,
                    true,
                    doubleValue(controller, "moveSpeedPerSecond", fallback.moveSpeedPerSecond()),
                    booleanValue(controller, "allowVertical", fallback.allowVertical()),
                    booleanValue(controller, "clampToBattleBox", fallback.clampToBattleBox())
            );
            case "locked", "none" -> new PlayerControl(
                    type,
                    false,
                    0.0D,
                    false,
                    booleanValue(controller, "clampToBattleBox", fallback.clampToBattleBox())
            );
            default -> new PlayerControl(
                    type,
                    false,
                    0.0D,
                    false,
                    fallback.clampToBattleBox()
            );
        };
    }

    private static Damage resolveDamage(JsonObject rule, Damage fallback) {
        return new Damage(doubleValue(rule, "invincibleSeconds", fallback.invincibleSeconds()));
    }

    private static String string(JsonObject object, String member, String fallback) {
        JsonElement element = object.get(member);
        return element == null ? fallback : element.getAsString();
    }

    private static double doubleValue(JsonObject object, String member, double fallback) {
        JsonElement element = object.get(member);
        if (element == null) {
            return fallback;
        }
        double value = element.getAsDouble();
        if (!Double.isFinite(value) || value < 0.0D) {
            return fallback;
        }
        return value;
    }

    private static boolean booleanValue(JsonObject object, String member, boolean fallback) {
        JsonElement element = object.get(member);
        return element == null ? fallback : element.getAsBoolean();
    }

    public record PlayerControl(
            String controllerType,
            boolean movementEnabled,
            double moveSpeedPerSecond,
            boolean allowVertical,
            boolean clampToBattleBox
    ) {
        public PlayerControl {
            Objects.requireNonNull(controllerType, "controllerType");
            if (!Double.isFinite(moveSpeedPerSecond) || moveSpeedPerSecond < 0.0D) {
                throw new IllegalArgumentException("moveSpeedPerSecond must be finite and >= 0.");
            }
        }

        static PlayerControl redFreeDefault() {
            return new PlayerControl(
                    "red_free",
                    true,
                    DEFAULT_RED_FREE_SPEED_PER_SECOND,
                    true,
                    true
            );
        }
    }

    public record Damage(
            double invincibleSeconds
    ) {
        public Damage {
            if (!Double.isFinite(invincibleSeconds) || invincibleSeconds < 0.0D) {
                throw new IllegalArgumentException("invincibleSeconds must be finite and >= 0.");
            }
        }

        static Damage defaultDamage() {
            return new Damage(DEFAULT_INVINCIBLE_SECONDS);
        }
    }
}
