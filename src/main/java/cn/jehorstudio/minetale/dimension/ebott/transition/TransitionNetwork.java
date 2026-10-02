package cn.jehorstudio.minetale.dimension.ebott.transition;

import cn.jehorstudio.minetale.dimension.ebott.transition.payload.AbortTransitionPayload;
import cn.jehorstudio.minetale.dimension.ebott.transition.payload.BarrierStatePayload;
import cn.jehorstudio.minetale.dimension.ebott.transition.payload.TargetChunkStreamFinishedPayload;
import cn.jehorstudio.minetale.dimension.ebott.transition.payload.TargetPrewarmReadyPayload;
import cn.jehorstudio.minetale.dimension.ebott.transition.payload.TransitionBeginPayload;
import cn.jehorstudio.minetale.dimension.ebott.transition.payload.VisualCommitPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

// 只拥有换维 payload 注册与协议转发，权威状态变更统一交给 TransitionManager。
public final class TransitionNetwork {
    private TransitionNetwork() {
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("minetale_transition_5");
        registrar.playToClient(TransitionBeginPayload.TYPE, TransitionBeginPayload.STREAM_CODEC);
        registrar.playToClient(
                TargetChunkStreamFinishedPayload.TYPE,
                TargetChunkStreamFinishedPayload.STREAM_CODEC
        );
        registrar.playToClient(VisualCommitPayload.TYPE, VisualCommitPayload.STREAM_CODEC);
        registrar.playToClient(AbortTransitionPayload.TYPE, AbortTransitionPayload.STREAM_CODEC);
        registrar.playToClient(BarrierStatePayload.TYPE, BarrierStatePayload.STREAM_CODEC);
        registrar.playToServer(
                TargetPrewarmReadyPayload.TYPE,
                TargetPrewarmReadyPayload.STREAM_CODEC,
                TransitionNetwork::handleTargetPrewarmReady
        );
    }

    private static void handleTargetPrewarmReady(TargetPrewarmReadyPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            TransitionManager.acknowledgeTargetPrewarmReady(player, payload.sessionId());
        }
    }
}
