package cn.jehorstudio.minetale.battle.logic.actor.component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class ActorComponentRegistry {
    private static final Map<String, ActorComponentRuntime> COMPONENTS = createComponents();

    private ActorComponentRegistry() {
    }

    public static boolean contains(String type) {
        return COMPONENTS.containsKey(Objects.requireNonNull(type, "type"));
    }

    public static ActorComponentRuntime require(String type) {
        ActorComponentRuntime runtime = COMPONENTS.get(Objects.requireNonNull(type, "type"));
        if (runtime == null) {
            throw new IllegalArgumentException("Unsupported actor component: " + type);
        }
        return runtime;
    }

    public static Set<String> supportedTypes() {
        return COMPONENTS.keySet();
    }

    private static Map<String, ActorComponentRuntime> createComponents() {
        Map<String, ActorComponentRuntime> components = new LinkedHashMap<>();
        components.put("velocity_movement", new VelocityMovementComponent());
        components.put("bounce_on_battle_box", new BounceOnBattleBoxComponent());
        components.put("spin", new SpinComponent());
        components.put("lifetime", new LifetimeComponent());
        components.put("damage_on_touch", new DamageOnTouchComponent());
        components.put("heal_on_touch", new HealOnTouchComponent());
        components.put("destroy_on_touch", new DestroyOnTouchComponent());
        components.put("emit_signal_on_touch", new EmitSignalOnTouchComponent());
        return Map.copyOf(components);
    }
}
