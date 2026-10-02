package cn.jehorstudio.minetale.content.player.soul;

import cn.jehorstudio.minetale.MineTale;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public final class SoulRecall {
    static final double MIN_HOLD_SECONDS = 0.1D;
    static final double MAX_HOLD_SECONDS = 5.0D;
    private static final double DEFAULT_HOLD_SECONDS = 0.7D;
    private static final ModConfigSpec.DoubleValue HOLD_SECONDS;
    public static final ModConfigSpec SPEC;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        HOLD_SECONDS = builder
                .translation("minetale.configuration.soul_recall_hold_seconds")
                .comment("主副手皆为空时，长按“使用物品/放置方块”键触发 Soul 召回所需的秒数。")
                .defineInRange(
                        "soul_recall_hold_seconds",
                        DEFAULT_HOLD_SECONDS,
                        MIN_HOLD_SECONDS,
                        MAX_HOLD_SECONDS
                );
        SPEC = builder.build();
    }

    private SoulRecall() {
    }

    public static void register(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(SoulRecall::registerPayload);
        modContainer.registerConfig(ModConfig.Type.CLIENT, SPEC, "minetale-interaction-client.toml");
    }

    private static void registerPayload(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("minetale_soul_recall_1");
        registrar.playToServer(RecallRequest.TYPE, RecallRequest.STREAM_CODEC, SoulRecall::handleRequest);
    }

    static double holdSeconds() {
        return HOLD_SECONDS.getAsDouble();
    }

    private static void handleRequest(RecallRequest ignored, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            Soul.requestReturn(player);
        }
    }

    record RecallRequest() implements CustomPacketPayload {
        private static final RecallRequest INSTANCE = new RecallRequest();
        private static final Type<RecallRequest> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(MineTale.MODID, "soul_recall_request")
        );
        private static final StreamCodec<RegistryFriendlyByteBuf, RecallRequest> STREAM_CODEC =
                StreamCodec.unit(INSTANCE);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
