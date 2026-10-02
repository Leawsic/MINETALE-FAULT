package cn.jehorstudio.minetale.dimension.ebott.transition.client;

import cn.jehorstudio.minetale.dimension.ebott.transition.payload.AbortTransitionPayload;
import cn.jehorstudio.minetale.dimension.ebott.transition.payload.BarrierStatePayload;
import cn.jehorstudio.minetale.dimension.ebott.transition.payload.TargetChunkStreamFinishedPayload;
import cn.jehorstudio.minetale.dimension.ebott.transition.payload.TransitionBeginPayload;
import cn.jehorstudio.minetale.dimension.ebott.transition.payload.VisualCommitPayload;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

// 网络入口只推进换维会话，不创建或控制 Screen。
public final class TransitionClientNetworkHandlers {
    private TransitionClientNetworkHandlers() {
    }

    public static void register(RegisterClientPayloadHandlersEvent event) {
        event.register(TransitionBeginPayload.TYPE, TransitionClientNetworkHandlers::handleBegin);
        event.register(
                TargetChunkStreamFinishedPayload.TYPE,
                TransitionClientNetworkHandlers::handleChunkStreamFinished
        );
        event.register(VisualCommitPayload.TYPE, TransitionClientNetworkHandlers::handleVisualCommit);
        event.register(BarrierStatePayload.TYPE, TransitionClientNetworkHandlers::handleBarrierState);
        event.register(AbortTransitionPayload.TYPE, TransitionClientNetworkHandlers::handleAbort);
    }

    private static void handleVisualCommit(VisualCommitPayload payload, IPayloadContext context) {
        TransitionClient.INSTANCE.authorizeVisualCommit(payload);
    }

    private static void handleBarrierState(BarrierStatePayload payload, IPayloadContext context) {
        TransitionClient.INSTANCE.applyBarrierState(payload);
    }

    private static void handleChunkStreamFinished(
            TargetChunkStreamFinishedPayload payload,
            IPayloadContext context
    ) {
        TransitionClient.INSTANCE.finishTargetChunkStream(payload);
    }

    private static void handleBegin(TransitionBeginPayload payload, IPayloadContext context) {
        TransitionClient.INSTANCE.begin(payload);
    }

    private static void handleAbort(AbortTransitionPayload payload, IPayloadContext context) {
        TransitionClient.INSTANCE.abort(payload);
    }
}
