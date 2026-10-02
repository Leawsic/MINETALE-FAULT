package cn.jehorstudio.minetale.narrative.runtime;

import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

// 对话规则的最小目标快照，使无 Entity 的NPC居民也能参与规则匹配。
final class DialogueTargetContext {
    private final UUID id;
    private final ResourceLocation entityTypeId;
    private final ResourceKey<Level> dimension;
    private final BlockPos fixedPosition;
    private final Entity entity;

    private DialogueTargetContext(
            UUID id,
            ResourceLocation entityTypeId,
            ResourceKey<Level> dimension,
            BlockPos fixedPosition,
            Entity entity
    ) {
        this.id = Objects.requireNonNull(id, "id");
        this.entityTypeId = Objects.requireNonNull(entityTypeId, "entityTypeId");
        this.dimension = Objects.requireNonNull(dimension, "dimension");
        this.fixedPosition = Objects.requireNonNull(fixedPosition, "fixedPosition").immutable();
        this.entity = entity;
    }

    static DialogueTargetContext entity(Entity entity) {
        Objects.requireNonNull(entity, "entity");
        return new DialogueTargetContext(
                entity.getUUID(),
                BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()),
                entity.level().dimension(),
                entity.blockPosition(),
                entity
        );
    }

    static DialogueTargetContext virtual(
            UUID id,
            BlockPos position,
            ResourceLocation entityTypeId,
            ResourceKey<Level> dimension
    ) {
        return new DialogueTargetContext(id, entityTypeId, dimension, position, null);
    }

    UUID id() {
        return this.id;
    }

    ResourceLocation entityTypeId() {
        return this.entityTypeId;
    }

    BlockPos blockPosition() {
        return this.entity == null ? this.fixedPosition : this.entity.blockPosition();
    }

    boolean reservable() {
        return this.entity != null;
    }

    boolean available(ServerLevel level) {
        return level.dimension().equals(this.dimension)
                && (this.entity == null
                || (!this.entity.isRemoved() && this.entity.level() == level));
    }
}
