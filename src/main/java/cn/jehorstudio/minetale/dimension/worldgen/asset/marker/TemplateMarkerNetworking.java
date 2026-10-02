package cn.jehorstudio.minetale.dimension.worldgen.asset.marker;

import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public final class TemplateMarkerNetworking {
    private TemplateMarkerNetworking() {
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(
                TemplateMarkerUpdatePayload.TYPE,
                TemplateMarkerUpdatePayload.STREAM_CODEC,
                TemplateMarkerUpdatePayload::handle
        );
    }
}
