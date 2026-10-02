package cn.jehorstudio.minetale.magic.skill;

import cn.jehorstudio.minetale.magic.Magic;
import cn.jehorstudio.minetale.magic.MagicConfig;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

public final class MagicNetworking {
    public static final int BEGIN = 0, RELEASE = 1, CANCEL = 2, LOCK = 3;
    private MagicNetworking() {}

    static void register(IEventBus bus) {
        bus.addListener(MagicNetworking::payloads);
        NeoForge.EVENT_BUS.addListener((EntityTickEvent.Post event) -> {
            if (event.getEntity() instanceof ServerPlayer player && player.tickCount % 5 == 0) send(player);
        });
    }

    private static void payloads(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("magic_2");
        registrar.playToClient(Status.TYPE, Status.STREAM_CODEC);
        registrar.playToServer(Input.TYPE, Input.STREAM_CODEC, (payload, context) -> {
            if (!(context.player() instanceof ServerPlayer player) || !player.isAlive() || player.isSpectator()
                    || !player.level().dimension().location().equals(payload.dimension)
                    || payload.action < BEGIN || payload.action > LOCK
                    || !MagicCasting.acceptSequence(player, payload.sequence)) return;
            MagicCasting.handleInput(player, payload.action, payload.castId, payload.sequence);
            send(player);
        });
    }

    private static void send(ServerPlayer player) {
        MagicCasting.initialize(player);
        var target = MagicCasting.lockedTarget(player);
        PacketDistributor.sendToPlayer(player, new Status(target == null ? -1 : target.getId(), MagicConfig.LOCK_RANGE));
    }

    // sequence 对每个意图单调递增；castId 始终等于对应 BEGIN 的 sequence。
    public record Input(int action, long sequence, long castId, ResourceLocation dimension) implements CustomPacketPayload {
        public static final Type<Input> TYPE = new Type<>(Magic.id("magic_input"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Input> STREAM_CODEC = new StreamCodec<>() {
            @Override public Input decode(RegistryFriendlyByteBuf b) {
                return new Input(b.readVarInt(), b.readVarLong(), b.readVarLong(), b.readResourceLocation());
            }
            @Override public void encode(RegistryFriendlyByteBuf b, Input input) {
                b.writeVarInt(input.action); b.writeVarLong(input.sequence);
                b.writeVarLong(input.castId); b.writeResourceLocation(input.dimension);
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    // 只同步锁定粒子和本地输入预判需要的数据；法术表现由实体同步。
    public record Status(int lockedId, double lockRange) implements CustomPacketPayload {
        public static final Type<Status> TYPE = new Type<>(Magic.id("magic_status"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Status> STREAM_CODEC = new StreamCodec<>() {
            @Override public Status decode(RegistryFriendlyByteBuf b) {
                return new Status(b.readVarInt(), b.readDouble());
            }
            @Override public void encode(RegistryFriendlyByteBuf b, Status status) {
                b.writeVarInt(status.lockedId);
                b.writeDouble(status.lockRange);
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
}
