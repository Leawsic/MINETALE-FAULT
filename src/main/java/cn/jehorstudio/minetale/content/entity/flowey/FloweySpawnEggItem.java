package cn.jehorstudio.minetale.content.entity.flowey;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.Spawner;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.function.Consumer;

public final class FloweySpawnEggItem extends SpawnEggItem {
    public FloweySpawnEggItem(Item.Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        if (!(level instanceof ServerLevel serverLevel)) {
            return InteractionResult.SUCCESS;
        }

        ItemStack stack = context.getItemInHand();
        BlockPos clickedPos = context.getClickedPos();
        Direction clickedFace = context.getClickedFace();
        BlockState clickedState = level.getBlockState(clickedPos);

        if (level.getBlockEntity(clickedPos) instanceof Spawner spawner) {
            EntityType<?> entityType = this.getType(stack);
            if (entityType == null) {
                return InteractionResult.FAIL;
            }

            if (!serverLevel.getServer().isSpawnerBlockEnabled()) {
                if (context.getPlayer() instanceof ServerPlayer serverPlayer) {
                    serverPlayer.sendSystemMessage(Component.translatable("advMode.notEnabled.spawner"));
                }
                return InteractionResult.FAIL;
            }

            spawner.setEntityId(entityType, level.getRandom());
            level.sendBlockUpdated(clickedPos, clickedState, clickedState, 3);
            level.gameEvent(context.getPlayer(), GameEvent.BLOCK_CHANGE, clickedPos);
            stack.shrink(1);
            return InteractionResult.SUCCESS;
        }

        BlockPos spawnPos = clickedState.getCollisionShape(level, clickedPos).isEmpty()
                ? clickedPos
                : clickedPos.relative(clickedFace);
        boolean offsetYMore = !Objects.equals(clickedPos, spawnPos) && clickedFace == Direction.UP;
        return spawnMob(context.getPlayer(), stack, serverLevel, spawnPos, true, offsetYMore);
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        BlockHitResult hit = getPlayerPOVHitResult(level, player, ClipContext.Fluid.SOURCE_ONLY);
        if (hit.getType() != HitResult.Type.BLOCK) {
            return InteractionResult.PASS;
        }

        if (!(level instanceof ServerLevel serverLevel)) {
            return InteractionResult.SUCCESS;
        }

        BlockPos pos = hit.getBlockPos();
        if (!(level.getBlockState(pos).getBlock() instanceof LiquidBlock)) {
            return InteractionResult.PASS;
        }

        if (!level.mayInteract(player, pos) || !player.mayUseItemAt(pos, hit.getDirection(), stack)) {
            return InteractionResult.FAIL;
        }

        InteractionResult result = spawnMob(player, stack, serverLevel, pos, false, false);
        if (result == InteractionResult.SUCCESS) {
            player.awardStat(Stats.ITEM_USED.get(this));
        }
        return result;
    }

    private InteractionResult spawnMob(
            @Nullable LivingEntity source,
            ItemStack stack,
            ServerLevel level,
            BlockPos pos,
            boolean offsetY,
            boolean offsetYMore
    ) {
        EntityType<?> entityType = this.getType(stack);
        if (entityType == null) {
            return InteractionResult.FAIL;
        }

        if (!entityType.isAllowedInPeaceful() && level.getDifficulty() == Difficulty.PEACEFUL) {
            return InteractionResult.FAIL;
        }

        Entity spawned = spawnWithFacing(entityType, level, stack, source, pos, offsetY, offsetYMore);
        if (spawned != null) {
            stack.consume(1, source);
            level.gameEvent(source, GameEvent.ENTITY_PLACE, pos);
        }
        return InteractionResult.SUCCESS;
    }

    @SuppressWarnings("unchecked")
    @Nullable
    private static Entity spawnWithFacing(
            EntityType<?> entityType,
            ServerLevel level,
            ItemStack stack,
            @Nullable LivingEntity source,
            BlockPos pos,
            boolean offsetY,
            boolean offsetYMore
    ) {
        EntityType<Entity> typedEntityType = (EntityType<Entity>)entityType;
        Consumer<Entity> configuration = EntityType.createDefaultStackConfig(level, stack, source);
        if (source != null) {
            configuration = configuration.andThen(entity -> {
                if (entity instanceof Flowey flowey) {
                    flowey.setRootFacingTowards(source);
                }
            });
        }

        return typedEntityType.spawn(
                level,
                configuration,
                pos,
                EntitySpawnReason.SPAWN_ITEM_USE,
                offsetY,
                offsetYMore
        );
    }
}
