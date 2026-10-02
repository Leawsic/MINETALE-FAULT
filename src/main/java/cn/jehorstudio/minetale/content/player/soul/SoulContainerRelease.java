package cn.jehorstudio.minetale.content.player.soul;

import cn.jehorstudio.minetale.content.player.soul.mixin.common.CompoundContainerAccessor;
import cn.jehorstudio.minetale.content.player.soul.mixin.common.HorseInventoryMenuAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.HorseInventoryMenu;
import net.minecraft.world.inventory.PlayerEnderChestContainer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

// 解析菜单背后真实容器的中心、可通行开口与原版开合行为。
final class SoulContainerRelease {
    static final int RELEASE_DELAY_TICKS = 20;
    static final int OPEN_ANIMATION_TICKS = 12;
    private static final int LID_ANIMATION_TICKS = 10;
    // 方块事件与实体生成包有序，但客户端可能在相邻 world tick 才分别收到。
    private static final int CLIENT_ANIMATION_ALIGNMENT_TICKS = 1;
    private static final double SOUL_MODEL_HALF_WIDTH = 0.8;
    private static final double SOUL_WORLD_SCALE = 0.2;
    private static final double SOUL_CLEARANCE_RADIUS = SOUL_MODEL_HALF_WIDTH * SOUL_WORLD_SCALE;
    private static final double CHEST_HINGE_TO_CENTER = 7.0 / 16.0;
    private static final double CHEST_LID_LENGTH = 14.0 / 16.0;
    private static final double SHULKER_MAX_LID_TRAVEL = 0.5;
    private static final int CHEST_SAFE_LAUNCH_DELAY_TICKS = chestSafeLaunchDelayTicks();
    private static final int SHULKER_SAFE_LAUNCH_DELAY_TICKS = shulkerSafeLaunchDelayTicks();
    private static final int BLOCK_CONTAINER_SEARCH_RADIUS = 8;

    private SoulContainerRelease() {
    }

    @Nullable
    static Source findSource(ServerPlayer player, AbstractContainerMenu menu) {
        Inventory inventory = player.getInventory();
        for (Slot slot : menu.slots) {
            if (slot.container == inventory || !Soul.isSoulItem(slot.getItem())) {
                continue;
            }
            return sourceFor(player, menu, slot.container, slot.index);
        }
        return null;
    }

    static long releaseTick(long operationEndTick) {
        return operationEndTick + RELEASE_DELAY_TICKS;
    }

    static Direction openingDirection(BlockState state, Vec3 viewerOffset) {
        if (state.getBlock() instanceof ShulkerBoxBlock) {
            return lateralOpeningDirection(state.getValue(ShulkerBoxBlock.FACING), viewerOffset);
        }
        if (state.getBlock() instanceof ChestBlock
                || state.getBlock() instanceof EnderChestBlock
                || state.getBlock() instanceof HopperBlock) {
            return Direction.UP;
        }
        if (state.hasProperty(BlockStateProperties.ORIENTATION)) {
            return state.getValue(BlockStateProperties.ORIENTATION).front();
        }
        if (state.hasProperty(BlockStateProperties.FACING)) {
            return state.getValue(BlockStateProperties.FACING);
        }
        if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            return state.getValue(BlockStateProperties.HORIZONTAL_FACING);
        }
        return Direction.UP;
    }

    // 潜影盒盖沿自身轴移动，可通行开口从垂直于该轴的四个侧面选择。
    static Direction lateralOpeningDirection(Direction lidDirection, Vec3 viewerOffset) {
        Direction fallback = switch (lidDirection.getAxis()) {
            case X -> Direction.SOUTH;
            case Y -> Direction.SOUTH;
            case Z -> Direction.EAST;
        };
        Direction best = fallback;
        double bestScore = directionScore(fallback, viewerOffset);
        for (Direction candidate : Direction.values()) {
            if (candidate.getAxis() == lidDirection.getAxis()) {
                continue;
            }
            double score = directionScore(candidate, viewerOffset);
            if (score > bestScore) {
                best = candidate;
                bestScore = score;
            }
        }
        return best;
    }

    private static double directionScore(Direction direction, Vec3 offset) {
        return direction.getStepX() * offset.x
                + direction.getStepY() * offset.y
                + direction.getStepZ() * offset.z;
    }

    static int chestSafeLaunchDelayTicks() {
        double clearForwardExtent = CHEST_HINGE_TO_CENTER - SOUL_CLEARANCE_RADIUS;
        double requiredAngle = Math.acos(clearForwardExtent / CHEST_LID_LENGTH);
        double easedOpenness = requiredAngle / (Math.PI * 0.5);
        double rawOpenness = 1.0 - Math.cbrt(1.0 - easedOpenness);
        return (int) Math.ceil(rawOpenness * LID_ANIMATION_TICKS)
                + CLIENT_ANIMATION_ALIGNMENT_TICKS;
    }

    static int shulkerSafeLaunchDelayTicks() {
        double requiredOpenness = (SOUL_CLEARANCE_RADIUS * 2.0) / SHULKER_MAX_LID_TRAVEL;
        return (int) Math.ceil(requiredOpenness * LID_ANIMATION_TICKS)
                + CLIENT_ANIMATION_ALIGNMENT_TICKS;
    }

    private static Source sourceFor(
            ServerPlayer player,
            AbstractContainerMenu menu,
            Container menuContainer,
            int slotIndex
    ) {
        Container physicalContainer = unwrap(menuContainer, slotIndex);
        if (physicalContainer instanceof BlockEntity blockEntity) {
            return Source.atBlock(blockEntity, player.getEyePosition());
        }
        if (physicalContainer instanceof Entity entity) {
            return Source.onEntity(entity);
        }
        if (menu instanceof HorseInventoryMenu) {
            AbstractHorse horse = ((HorseInventoryMenuAccessor) menu).minetale$getHorse();
            return Source.onEntity(horse);
        }
        if (physicalContainer instanceof PlayerEnderChestContainer) {
            EnderChestBlockEntity enderChest = nearestEnderChest(player);
            if (enderChest != null) {
                return Source.atBlock(enderChest, player.getEyePosition());
            }
        }

        Vec3 fallbackOrigin = player.getBoundingBox().getCenter();
        return Source.fallback(fallbackOrigin, Direction.UP);
    }

    private static Container unwrap(Container container, int slotIndex) {
        if (!(container instanceof CompoundContainer compound)) {
            return container;
        }
        CompoundContainerAccessor containers = (CompoundContainerAccessor) compound;
        Container first = containers.minetale$getFirstContainer();
        return slotIndex < first.getContainerSize()
                ? first
                : containers.minetale$getSecondContainer();
    }

    @Nullable
    private static EnderChestBlockEntity nearestEnderChest(ServerPlayer player) {
        BlockPos center = player.blockPosition();
        EnderChestBlockEntity nearest = null;
        double nearestDistance = Double.POSITIVE_INFINITY;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = -BLOCK_CONTAINER_SEARCH_RADIUS; x <= BLOCK_CONTAINER_SEARCH_RADIUS; x++) {
            for (int y = -BLOCK_CONTAINER_SEARCH_RADIUS; y <= BLOCK_CONTAINER_SEARCH_RADIUS; y++) {
                for (int z = -BLOCK_CONTAINER_SEARCH_RADIUS; z <= BLOCK_CONTAINER_SEARCH_RADIUS; z++) {
                    cursor.setWithOffset(center, x, y, z);
                    if (!(player.level().getBlockEntity(cursor) instanceof EnderChestBlockEntity candidate)) {
                        continue;
                    }
                    double distance = candidate.getBlockPos().distToCenterSqr(player.position());
                    if (distance < nearestDistance) {
                        nearest = candidate;
                        nearestDistance = distance;
                    }
                }
            }
        }
        return nearest;
    }

    static final class Source {
        @Nullable
        private final BlockEntity blockEntity;
        @Nullable
        private final Entity carrierEntity;
        private final Vec3 fallbackOrigin;
        private final Direction fallbackDirection;

        private Source(
                @Nullable BlockEntity blockEntity,
                @Nullable Entity carrierEntity,
                Vec3 fallbackOrigin,
                Direction fallbackDirection
        ) {
            this.blockEntity = blockEntity;
            this.carrierEntity = carrierEntity;
            this.fallbackOrigin = fallbackOrigin;
            this.fallbackDirection = fallbackDirection;
        }

        static Source atBlock(BlockEntity blockEntity, Vec3 viewerPosition) {
            Vec3 origin = Vec3.atCenterOf(blockEntity.getBlockPos());
            return new Source(
                    blockEntity,
                    null,
                    origin,
                    openingDirection(blockEntity.getBlockState(), viewerPosition.subtract(origin))
            );
        }

        static Source onEntity(Entity carrierEntity) {
            return new Source(
                    null,
                    carrierEntity,
                    carrierEntity.getBoundingBox().getCenter(),
                    Direction.UP
            );
        }

        static Source fallback(Vec3 origin, Direction direction) {
            return new Source(null, null, origin, direction);
        }

        Vec3 origin() {
            if (this.blockEntity != null) {
                return Vec3.atCenterOf(this.blockEntity.getBlockPos());
            }
            return this.carrierEntity == null
                    ? this.fallbackOrigin
                    : this.carrierEntity.getBoundingBox().getCenter();
        }

        Vec3 direction() {
            Direction direction = this.fallbackDirection;
            return new Vec3(direction.getStepX(), direction.getStepY(), direction.getStepZ());
        }

        int safeLaunchDelayTicks() {
            if (this.blockEntity instanceof ShulkerBoxBlockEntity) {
                return SHULKER_SAFE_LAUNCH_DELAY_TICKS;
            }
            if (this.blockEntity instanceof ChestBlockEntity
                    || this.blockEntity instanceof EnderChestBlockEntity) {
                return CHEST_SAFE_LAUNCH_DELAY_TICKS;
            }
            return 0;
        }

        Vec3 inheritedVelocity() {
            return this.carrierEntity == null ? Vec3.ZERO : this.carrierEntity.getDeltaMovement();
        }

        void openAnimation() {
            setAnimationOpen(true);
        }

        void closeAnimation() {
            setAnimationOpen(false);
        }

        // 只广播原版动画事件
        private void setAnimationOpen(boolean open) {
            if (!hasLidAnimation(this.blockEntity)
                    || this.blockEntity.isRemoved()
                    || this.blockEntity.getLevel() == null) {
                return;
            }
            this.blockEntity.getLevel().blockEvent(
                    this.blockEntity.getBlockPos(),
                    this.blockEntity.getBlockState().getBlock(),
                    1,
                    open ? 1 : 0
            );
        }
    }

    private static boolean hasLidAnimation(@Nullable BlockEntity blockEntity) {
        return blockEntity instanceof ChestBlockEntity
                || blockEntity instanceof EnderChestBlockEntity
                || blockEntity instanceof ShulkerBoxBlockEntity;
    }
}
