package cn.jehorstudio.minetale.dimension.region.core;

import cn.jehorstudio.minetale.MineTale;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** 服务端生命周期：维度加载即恢复放置，玩家进入或换维即同步状态。 */
@EventBusSubscriber(modid = MineTale.MODID)
public final class CoreEvents {
    private CoreEvents() {}

    @SubscribeEvent
    public static void levelLoad(LevelEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level) CoreRegionManager.load(level);
    }

    @SubscribeEvent
    public static void levelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) CoreRegionManager.unload(level);
    }

    @SubscribeEvent
    public static void loggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player)
            CoreRegionManager.of(player.level()).sync(player);
    }

    @SubscribeEvent
    public static void changedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player)
            CoreRegionManager.of(player.level()).sync(player);
    }

    @SubscribeEvent
    public static void respawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player)
            CoreRegionManager.of(player.level()).sync(player);
    }

    @SubscribeEvent
    public static void blockPlaced(BlockEvent.EntityPlaceEvent event) {
        if (event.getLevel() instanceof ServerLevel level
                && CoreRegionManager.placementBlocked(level, event.getPos(), event.getPlacedBlock()))
            event.setCanceled(true);
    }

    @SubscribeEvent
    public static void commands(RegisterCommandsEvent event) {
        CoreCommands.register(event);
    }

    @SubscribeEvent
    public static void payloads(RegisterPayloadHandlersEvent event) {
        CoreNetwork.register(event);
    }
}
