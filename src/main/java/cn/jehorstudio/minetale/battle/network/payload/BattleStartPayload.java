package cn.jehorstudio.minetale.battle.network.payload;

import cn.jehorstudio.minetale.MineTale;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record BattleStartPayload(
        BattleStartSharedSection shared,
        Optional<PlayerStateInitialSnapshot> recipientPrivatePlayerState
) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<BattleStartPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(MineTale.MODID, "battle_start"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BattleStartPayload> STREAM_CODEC =
            StreamCodec.ofMember(BattleStartPayload::write, BattleStartPayload::read);

    public BattleStartPayload {
        Objects.requireNonNull(shared, "shared");
        recipientPrivatePlayerState = Objects.requireNonNull(recipientPrivatePlayerState, "recipientPrivatePlayerState");
        recipientPrivatePlayerState.ifPresent(privateState -> {
            boolean included = shared.finalParticipants().stream()
                    .anyMatch(participant -> participant.playerId().equals(privateState.recipientPlayerId()));
            if (!included) {
                throw new IllegalArgumentException("recipient private player state must belong to a final participant.");
            }
        });
    }

    public UUID battleId() {
        return this.shared.battleId();
    }

    public List<BattleParticipant> finalParticipants() {
        return this.shared.finalParticipants();
    }

    private void write(RegistryFriendlyByteBuf buf) {
        BattleStartSharedSection.write(buf, this.shared);
        BattlePayloadCodecs.writeOptionalPlayerStateInitialSnapshot(buf, this.recipientPrivatePlayerState);
    }

    private static BattleStartPayload read(RegistryFriendlyByteBuf buf) {
        return new BattleStartPayload(
                BattleStartSharedSection.read(buf),
                BattlePayloadCodecs.readOptionalPlayerStateInitialSnapshot(buf)
        );
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
