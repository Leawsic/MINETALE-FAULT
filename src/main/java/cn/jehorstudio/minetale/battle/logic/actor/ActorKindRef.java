package cn.jehorstudio.minetale.battle.logic.actor;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

// 数据驱动的玩法类别 ID（如 minetale:blue_bone），独立于 ActorType 的运行时形态。
public record ActorKindRef(ResourceLocation id) {
    public ActorKindRef {
        Objects.requireNonNull(id, "id");
    }

    public static ActorKindRef of(String value) {
        ResourceLocation id = ResourceLocation.tryParse(Objects.requireNonNull(value, "value"));
        if (id == null) {
            throw new IllegalArgumentException("Actor kind must be a valid resource location: " + value);
        }
        return new ActorKindRef(id);
    }

    @Override
    public String toString() {
        return this.id.toString();
    }
}
