package cn.jehorstudio.minetale.battle.network.payload;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record BattleStartSharedSection(
        UUID battleId,
        ResourceLocation battleDefinitionId,
        String definitionHash,
        int schemaVersion,
        long startBattleTick,
        long seed,
        List<BattleParticipant> finalParticipants
) {
    public BattleStartSharedSection {
        Objects.requireNonNull(battleId, "battleId");
        Objects.requireNonNull(battleDefinitionId, "battleDefinitionId");
        if (definitionHash == null || definitionHash.isBlank()) {
            throw new IllegalArgumentException("definitionHash must not be blank.");
        }
        if (schemaVersion <= 0) {
            throw new IllegalArgumentException("schemaVersion must be positive.");
        }
        finalParticipants = List.copyOf(finalParticipants);
        if (finalParticipants.isEmpty()) {
            throw new IllegalArgumentException("finalParticipants must not be empty.");
        }
        Set<UUID> participantIds = new HashSet<>();
        for (BattleParticipant participant : finalParticipants) {
            if (!participantIds.add(participant.playerId())) {
                throw new IllegalArgumentException("finalParticipants must not contain duplicates.");
            }
        }
    }

    static void write(RegistryFriendlyByteBuf buf, BattleStartSharedSection value) {
        buf.writeUUID(value.battleId);
        buf.writeResourceLocation(value.battleDefinitionId);
        buf.writeUtf(value.definitionHash);
        buf.writeVarInt(value.schemaVersion);
        buf.writeVarLong(value.startBattleTick);
        buf.writeLong(value.seed);
        BattlePayloadCodecs.writeParticipants(buf, value.finalParticipants);
    }

    static BattleStartSharedSection read(RegistryFriendlyByteBuf buf) {
        return new BattleStartSharedSection(
                buf.readUUID(),
                buf.readResourceLocation(),
                buf.readUtf(),
                buf.readVarInt(),
                buf.readVarLong(),
                buf.readLong(),
                BattlePayloadCodecs.readParticipants(buf)
        );
    }
}
