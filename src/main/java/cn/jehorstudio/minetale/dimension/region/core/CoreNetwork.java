package cn.jehorstudio.minetale.dimension.region.core;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** Core 放置状态与放置请求的 payload 注册；客户端状态处理在 CoreRegionClient。 */
public final class CoreNetwork {
    private CoreNetwork() {}

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("minetale_core_1");
        registrar.playToClient(CoreStatePayload.TYPE, CoreStatePayload.STREAM_CODEC);
        registrar.playToServer(CorePlacementRequest.TYPE, CorePlacementRequest.STREAM_CODEC,
                CoreNetwork::handlePlacementRequest);
    }

    private static void handlePlacementRequest(CorePlacementRequest payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        if (!player.hasPermissions(2)) {
            player.sendSystemMessage(Component.literal("Core 放置需要权限等级 2。"));
            return;
        }
        CoreRegionManager manager = CoreRegionManager.of(player.level());
        if (payload.placement() == null) {
            if (manager.placement() == null) {
                player.sendSystemMessage(Component.literal("当前维度没有已放置的 Core。"));
                return;
            }
            manager.removeCore();
            player.sendSystemMessage(Component.literal("Core 已卸载，碰撞与地图数据已清除。"));
        } else {
            boolean replaced = manager.placement() != null;
            var next = payload.placement();
            manager.generateCore(next.scene(), next.x(), next.y(), next.z());
            player.sendSystemMessage(Component.literal(
                    (replaced ? "已卸载原 Core 并重新生成：" : "Core 已生成：")
                            + manager.placement()
                            + "，碰撞生成中并写入地图数据"));
        }
    }
}
