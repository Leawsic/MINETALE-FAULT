package cn.jehorstudio.minetale.content.player.soul;

import cn.jehorstudio.minetale.MineTale;
import cn.jehorstudio.minetale.content.player.soul.SoulCompanionBrain.Stimulus;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import org.jetbrains.annotations.Nullable;

// 每名玩家唯一的 Soul 权威状态；Item 与 Entity 都只是投影
public final class Soul {
    private static final int MAX_FLIGHT_DISTANCE = 32;

    private static final int ENTITY_RECOVERY_RADIUS = 64;
    private static final boolean USE_OWNER_RENDER_VELOCITY = true;
    private static final boolean KEEP_SYNCHRONIZED_RELEASE_VELOCITY = false;

    public static final MapCodec<Soul> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            State.CODEC.optionalFieldOf("state", State.ITEM).forGetter(Soul::state),
            UUIDUtil.CODEC.optionalFieldOf("entity_uuid").forGetter(Soul::storedEntityUuid)
    ).apply(instance, Soul::fromStorage));

    private State state;
    @Nullable
    private UUID entityUuid;
    private final SoulServerCoordinator serverCoordinator = new SoulServerCoordinator();
    @Nullable
    private PendingContainerRelease pendingContainerRelease;

    private Soul(State state, @Nullable UUID entityUuid) {
        this.state = state;
        this.entityUuid = state == State.ITEM ? null : entityUuid;
    }

    public static Soul create() {
        return new Soul(State.ITEM, null);
    }

    // Attachment 缺失时由注册工厂补建
    static Soul get(ServerPlayer player) {
        return player.getData(SoulRegistry.SOUL_ATTACHMENT);
    }

    static boolean requestReturn(ServerPlayer player) {
        Soul soul = get(player);
        if (soul.state != State.FLYING
                || !player.getMainHandItem().isEmpty()
                || !player.getOffhandItem().isEmpty()) {
            return false;
        }

        soul.transition(Signal.RETURN_REQUESTED);
        return true;
    }

    // 服务端 tick 顺序固定为收敛投影、推进状态、更新交互代理与动作指令。
    private static void tick(ServerPlayer player) {
        if (!player.isAlive()) {
            return;
        }

        Soul soul = get(player);
        soul.advancePendingContainerRelease(player);

        if (soul.state == State.ITEM) {
            soul.discardBoundEntity(player);
            soul.serverCoordinator.reset();
            return;
        }

        removeSoulItemProjections(player);
        SoulEntity entity = soul.ensureWorldEntity(player);
        if (entity == null) {
            if (player.tickCount % 100 == 0) {
                MineTale.LOGGER.warn("无法为玩家 {} 创建 Soul 世界投影，状态保持为 {}", player.getScoreboardName(), soul.state);
            }
            return;
        }

        if (soul.state == State.FLYING
                && entity.distanceToSqr(player) > MAX_FLIGHT_DISTANCE * MAX_FLIGHT_DISTANCE) {
            soul.transition(Signal.RETURN_REQUESTED);
        }

        boolean returnComplete = soul.serverCoordinator.advance(player, entity, soul.state);
        if (soul.state == State.RETURNING && returnComplete) {
            soul.completeReturn(player, entity);
        }
    }

    private void reconcileClosedInventory(ServerPlayer player, AbstractContainerMenu closedMenu) {
        if (this.pendingContainerRelease != null) {
            return;
        }
        if (this.state != State.ITEM) {
            removeSoulItemProjections(player, closedMenu);
            return;
        }

        SoulContainerRelease.Source releaseSource = SoulContainerRelease.findSource(player, closedMenu);
        if (releaseSource != null) {
            removeSoulItemProjections(player, closedMenu);
            this.pendingContainerRelease = new PendingContainerRelease(
                    releaseSource,
                    SoulContainerRelease.releaseTick(player.level().getGameTime())
            );
            return;
        }

        Inventory inventory = player.getInventory();
        boolean hasPlayerProjection = false;
        boolean changed = false;

        for (int slotIndex = 0; slotIndex < inventory.getContainerSize(); slotIndex++) {
            ItemStack stack = inventory.getItem(slotIndex);
            if (!isSoulItem(stack)) {
                continue;
            }
            if (!hasPlayerProjection) {
                if (stack.getCount() != 1) {
                    stack.setCount(1);
                    changed = true;
                }
                hasPlayerProjection = true;
            } else {
                inventory.setItem(slotIndex, ItemStack.EMPTY);
                changed = true;
            }
        }

        if (changed) {
            inventory.setChanged();
            player.inventoryMenu.broadcastChanges();
        }

        if (!hasPlayerProjection && !insertSoulItem(player)) {
            startMissingItemReturn(player);
        }
    }

    private boolean startDroppedProjection(ServerPlayer player) {
        if (this.state != State.ITEM) {
            return false;
        }
        Vec3 forward = normalizedOr(player.getLookAngle(), new Vec3(0.0, 0.0, 1.0));
        Vec3 chest = player.getEyePosition().add(0.0, -0.48, 0.0).add(forward.scale(0.18));
        return startDroppedProjection(
                player,
                chest,
                forward,
                player.getKnownMovement(),
                USE_OWNER_RENDER_VELOCITY
        );
    }

    private boolean startDroppedProjection(
            ServerPlayer player,
            Vec3 origin,
            Vec3 direction,
            Vec3 inheritedVelocity,
            boolean usesOwnerRenderVelocity
    ) {
        if (this.state != State.ITEM) {
            return false;
        }
        Vec3 forward = normalizedOr(direction, new Vec3(0.0, 1.0, 0.0));
        Vec3 launchVelocity = SoulServerCoordinator.releaseVelocity(inheritedVelocity, forward);
        SoulEntity entity = createEntity(player, State.FLYING, origin, launchVelocity);
        if (entity == null) {
            insertSoulItem(player);
            return false;
        }
        this.serverCoordinator.prepareRelease(player, entity, forward, usesOwnerRenderVelocity);
        if (!player.level().addFreshEntity(entity)) {
            this.serverCoordinator.reset();
            insertSoulItem(player);
            return false;
        }
        removeSoulItemProjections(player);
        this.transition(Signal.DROPPED);
        this.bindEntity(entity.getUUID());
        return true;
    }

    private void advancePendingContainerRelease(ServerPlayer player) {
        PendingContainerRelease pending = this.pendingContainerRelease;
        if (pending == null) {
            return;
        }

        long tick = player.level().getGameTime();
        if (!pending.launched) {
            if (this.state != State.ITEM) {
                if (pending.animationStarted) {
                    pending.source.closeAnimation();
                }
                this.pendingContainerRelease = null;
                return;
            }
            if (!pending.animationStarted) {
                if (tick < pending.animationStartTick) {
                    return;
                }
                pending.source.openAnimation();
                pending.animationStarted = true;
                pending.launchTick = tick + pending.source.safeLaunchDelayTicks();
            }
            if (tick < pending.launchTick) {
                return;
            }

            SoulContainerRelease.Source source = pending.source;
            if (!startDroppedProjection(
                    player,
                    source.origin(),
                    source.direction(),
                    source.inheritedVelocity(),
                    KEEP_SYNCHRONIZED_RELEASE_VELOCITY
            )) {
                source.closeAnimation();
                this.pendingContainerRelease = null;
                return;
            }
            pending.launched = true;
            pending.closeAnimationTick = tick + SoulContainerRelease.OPEN_ANIMATION_TICKS;
            return;
        }

        if (tick >= pending.closeAnimationTick) {
            pending.source.closeAnimation();
            this.pendingContainerRelease = null;
        }
    }

    private void cancelPendingContainerRelease(ServerPlayer player, boolean restoreItem) {
        PendingContainerRelease pending = this.pendingContainerRelease;
        if (pending == null) {
            return;
        }
        if (pending.animationStarted) {
            pending.source.closeAnimation();
        }
        this.pendingContainerRelease = null;
        if (restoreItem && this.state == State.ITEM && !insertSoulItem(player)) {
            startMissingItemReturn(player);
        }
    }

    private boolean startMissingItemReturn(ServerPlayer player) {
        if (this.state != State.ITEM) {
            return false;
        }
        SoulEntity entity = spawnEntity(
                player,
                State.RETURNING,
                player.getEyePosition().add(0.0, 1.5, 0.0)
        );
        if (entity == null) {
            return false;
        }

        this.transition(Signal.DROPPED);
        this.transition(Signal.RETURN_REQUESTED);
        this.serverCoordinator.reset();
        this.bindEntity(entity.getUUID());
        return true;
    }

    private void completeReturn(ServerPlayer player, SoulEntity entity) {
        if (insertSoulItem(player)) {
            UUID completedEntityUuid = entity.getUUID();
            this.transition(Signal.REACHED_OWNER_WITH_SPACE);
            entity.discard();
            this.clearEntity(completedEntityUuid);
            this.serverCoordinator.reset();
        } else {
            this.transition(Signal.REACHED_OWNER_FULL);
            entity.setSoulState(this.state);
            this.serverCoordinator.stimulate(Stimulus.INVENTORY_REJECTED);
        }
    }

    @Nullable
    private SoulEntity ensureWorldEntity(ServerPlayer player) {
        ServerLevel level = player.level();
        if (this.entityUuid != null) {
            Entity stored = level.getEntityInAnyDimension(this.entityUuid);
            if (stored instanceof SoulEntity soulEntity
                    && player.getUUID().equals(soulEntity.ownerUuid())
                    && soulEntity.level() == level) {
                return soulEntity;
            }
            if (stored instanceof SoulEntity staleSoulEntity
                    && player.getUUID().equals(staleSoulEntity.ownerUuid())) {
                staleSoulEntity.discard();
            }
            this.entityUuid = null;
        }

        AABB recoveryArea = player.getBoundingBox().inflate(ENTITY_RECOVERY_RADIUS);
        List<SoulEntity> candidates = level.getEntitiesOfClass(
                SoulEntity.class,
                recoveryArea,
                entity -> player.getUUID().equals(entity.ownerUuid())
        );
        if (!candidates.isEmpty()) {
            SoulEntity recovered = candidates.get(0);
            for (int index = 1; index < candidates.size(); index++) {
                candidates.get(index).discard();
            }
            this.bindEntity(recovered.getUUID());
            return recovered;
        }

        SoulEntity spawned = spawnEntity(
                player,
                this.state,
                player.getEyePosition().add(0.8, 0.25, 0.0)
        );
        if (spawned != null) {
            this.bindEntity(spawned.getUUID());
        }
        return spawned;
    }

    @Nullable
    private static SoulEntity spawnEntity(ServerPlayer player, State state, Vec3 position) {
        return spawnEntity(player, state, position, Vec3.ZERO);
    }

    @Nullable
    private static SoulEntity spawnEntity(ServerPlayer player, State state, Vec3 position, Vec3 velocity) {
        SoulEntity entity = createEntity(player, state, position, velocity);
        return entity != null && player.level().addFreshEntity(entity) ? entity : null;
    }

    @Nullable
    private static SoulEntity createEntity(ServerPlayer player, State state, Vec3 position, Vec3 velocity) {
        SoulEntity entity = SoulRegistry.SOUL.get().create(player.level(), EntitySpawnReason.EVENT);
        if (entity == null) {
            return null;
        }
        entity.initialize(player, state, position, velocity);
        return entity;
    }

    private void discardBoundEntity(ServerPlayer player) {
        if (this.entityUuid == null) {
            return;
        }
        UUID discardedUuid = this.entityUuid;
        Entity entity = player.level().getEntityInAnyDimension(discardedUuid);
        if (entity instanceof SoulEntity soulEntity
                && player.getUUID().equals(soulEntity.ownerUuid())) {
            soulEntity.discard();
        }
        this.clearEntity(discardedUuid);
        this.serverCoordinator.reset();
    }

    private static boolean insertSoulItem(ServerPlayer player) {
        Inventory inventory = player.getInventory();
        int selectedSlot = inventory.getSelectedSlot();
        int freeSlot = inventory.getItem(selectedSlot).isEmpty()
                ? selectedSlot
                : inventory.getFreeSlot();
        if (freeSlot < 0) {
            return false;
        }
        inventory.setItem(freeSlot, oneSoulItem());
        inventory.setChanged();
        player.containerMenu.broadcastChanges();
        return true;
    }

    private static void removeSoulItemProjections(ServerPlayer player) {
        removeSoulItemProjections(player, player.containerMenu);
    }

    private static void removeSoulItemProjections(ServerPlayer player, AbstractContainerMenu menu) {
        Inventory inventory = player.getInventory();
        boolean changed = false;
        for (int slotIndex = 0; slotIndex < inventory.getContainerSize(); slotIndex++) {
            if (isSoulItem(inventory.getItem(slotIndex))) {
                inventory.setItem(slotIndex, ItemStack.EMPTY);
                changed = true;
            }
        }
        if (isSoulItem(menu.getCarried())) {
            menu.setCarried(ItemStack.EMPTY);
            changed = true;
        }
        for (Slot slot : menu.slots) {
            if (slot.container != inventory && isSoulItem(slot.getItem())) {
                slot.set(ItemStack.EMPTY);
                changed = true;
            }
        }
        if (changed) {
            inventory.setChanged();
            menu.broadcastChanges();
            player.inventoryMenu.broadcastChanges();
        }
    }

    private static ItemStack oneSoulItem() {
        return new ItemStack(SoulRegistry.SOUL_ITEM.get());
    }

    static boolean isSoulItem(ItemStack stack) {
        return stack.is(SoulRegistry.SOUL_ITEM.get());
    }

    private static Vec3 normalizedOr(Vec3 value, Vec3 fallback) {
        return value == null || value.lengthSqr() < 1.0E-8 ? fallback : value.normalize();
    }

    private static Soul fromStorage(State state, Optional<UUID> entityUuid) {
        return new Soul(state, entityUuid.orElse(null));
    }

    State state() {
        return this.state;
    }

    @Nullable
    UUID entityUuid() {
        return this.entityUuid;
    }

    void bindEntity(UUID entityUuid) {
        if (this.state == State.ITEM) {
            throw new IllegalStateException("ITEM 状态不能绑定世界实体");
        }
        this.entityUuid = entityUuid;
    }

    void clearEntity(@Nullable UUID expectedUuid) {
        if (expectedUuid == null || expectedUuid.equals(this.entityUuid)) {
            this.entityUuid = null;
        }
    }

    void transition(Signal signal) {
        State next = nextState(this.state, signal);
        if (next == this.state) {
            return;
        }

        this.state = next;
        if (next == State.ITEM) {
            this.entityUuid = null;
            this.serverCoordinator.reset();
        }
    }

    static State nextState(State current, Signal signal) {
        return switch (signal) {
            case DROPPED -> current == State.ITEM ? State.FLYING : current;
            case RETURN_REQUESTED -> current == State.FLYING ? State.RETURNING : current;
            case FORCE_RETURN -> current == State.ITEM ? State.ITEM : State.RETURNING;
            case REACHED_OWNER_WITH_SPACE -> current == State.RETURNING ? State.ITEM : current;
            case REACHED_OWNER_FULL -> current == State.RETURNING ? State.FLYING : current;
        };
    }

    private Optional<UUID> storedEntityUuid() {
        return Optional.ofNullable(this.entityUuid);
    }

    enum State implements StringRepresentable {
        ITEM("item"),
        FLYING("flying"),
        RETURNING("returning");

        static final EnumCodec<State> CODEC = StringRepresentable.fromEnum(State::values);

        private final String serializedName;

        State(String serializedName) {
            this.serializedName = serializedName;
        }

        @Override
        public String getSerializedName() {
            return this.serializedName;
        }
    }

    enum Signal {
        DROPPED,
        RETURN_REQUESTED,
        FORCE_RETURN,
        REACHED_OWNER_WITH_SPACE,
        REACHED_OWNER_FULL
    }

    // 下列方法只适配 NeoForge 事件
    @EventBusSubscriber(modid = MineTale.MODID)
    public static final class Events {
        private Events() {
        }

        @SubscribeEvent
        public static void onPlayerTick(PlayerTickEvent.Post event) {
            if (event.getEntity() instanceof ServerPlayer player) {
                tick(player);
            }
        }

        @SubscribeEvent
        public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
            if (event.getEntity() instanceof ServerPlayer player) {
                get(player).reconcileClosedInventory(player, player.inventoryMenu);
                tick(player);
            }
        }

        @SubscribeEvent
        public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
            if (event.getEntity() instanceof ServerPlayer player) {
                get(player).reconcileClosedInventory(player, player.inventoryMenu);
            }
        }

        @SubscribeEvent
        public static void onContainerClosed(PlayerContainerEvent.Close event) {
            if (event.getEntity() instanceof ServerPlayer player) {
                get(player).reconcileClosedInventory(player, event.getContainer());
            }
        }

        @SubscribeEvent
        public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
            if (event.getEntity() instanceof ServerPlayer player) {
                Soul soul = get(player);
                soul.cancelPendingContainerRelease(player, false);
                soul.discardBoundEntity(player);
            }
        }

        @SubscribeEvent
        public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
            if (event.getEntity() instanceof ServerPlayer player) {
                Soul soul = get(player);
                soul.cancelPendingContainerRelease(player, true);
                soul.discardBoundEntity(player);
                soul.transition(Signal.FORCE_RETURN);
            }
        }

        @SubscribeEvent
        public static void onPlayerDeath(LivingDeathEvent event) {
            if (event.getEntity() instanceof ServerPlayer player) {
                Soul soul = get(player);
                soul.cancelPendingContainerRelease(player, false);
                soul.discardBoundEntity(player);
                soul.transition(Signal.FORCE_RETURN);
            }
        }

        @SubscribeEvent
        public static void onPlayerDrops(LivingDropsEvent event) {
            if (event.getEntity() instanceof ServerPlayer) {
                event.getDrops().removeIf(drop -> isSoulItem(drop.getItem()));
            }
        }

        @SubscribeEvent
        public static void onItemToss(ItemTossEvent event) {
            ItemEntity itemEntity = event.getEntity();
            if (!isSoulItem(itemEntity.getItem())) {
                return;
            }

            // 取消 ItemTossEvent 不会回滚背包，必须在此直接提交世界投影。
            event.setCanceled(true);
            if (event.getPlayer() instanceof ServerPlayer player) {
                Soul soul = get(player);
                if (soul.state == State.ITEM) {
                    soul.startDroppedProjection(player);
                }
            }
        }
    }

    private static final class PendingContainerRelease {
        private final SoulContainerRelease.Source source;
        private final long animationStartTick;
        private boolean animationStarted;
        private long launchTick = Long.MAX_VALUE;
        private boolean launched;
        private long closeAnimationTick = Long.MAX_VALUE;

        private PendingContainerRelease(SoulContainerRelease.Source source, long animationStartTick) {
            this.source = source;
            this.animationStartTick = animationStartTick;
        }
    }
}
