package cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.entity.monsternpc.network;

import cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.entity.monsternpc.SnowtownCrowdCoordinator;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

// 只注册宏观人口同步与一次性交互请求
public final class SnowtownCrowdNetworking {
    private SnowtownCrowdNetworking() {}

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("snowtown_crowd_3");
        registrar.playToClient(
                SnowtownCrowdPopulationPayload.TYPE,
                SnowtownCrowdPopulationPayload.STREAM_CODEC
        );
        registrar.playToServer(
                SnowtownCrowdInteractPayload.TYPE,
                SnowtownCrowdInteractPayload.STREAM_CODEC,
                SnowtownCrowdNetworking::handleInteract
        );
    }

    private static void handleInteract(
            SnowtownCrowdInteractPayload payload,
            IPayloadContext context
    ) {
        if (context.player() instanceof ServerPlayer player) {
            SnowtownCrowdCoordinator.interact(player, payload);
        }
    }
}
