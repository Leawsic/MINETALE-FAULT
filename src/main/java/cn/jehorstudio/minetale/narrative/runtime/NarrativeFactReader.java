package cn.jehorstudio.minetale.narrative.runtime;

import cn.jehorstudio.minetale.narrative.data.NarrativeValidationException;
import cn.jehorstudio.minetale.narrative.state.StoryStateStore;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import static cn.jehorstudio.minetale.narrative.data.NarrativeModel.BooleanValue;
import static cn.jehorstudio.minetale.narrative.data.NarrativeModel.ConditionSource;
import static cn.jehorstudio.minetale.narrative.data.NarrativeModel.IdValue;
import static cn.jehorstudio.minetale.narrative.data.NarrativeModel.IntValue;
import static cn.jehorstudio.minetale.narrative.data.NarrativeModel.StoryValue;

final class NarrativeFactReader {
    private NarrativeFactReader() {
    }

    static StoryValue read(
            ConditionSource source,
            ServerPlayer interactor,
            DialogueTargetContext target,
            ServerLevel level
    ) {
        if (source.storyState()) {
            String text = source.key();
            if (!text.startsWith("story:")) {
                throw new NarrativeValidationException("Malformed story state source " + text + ".");
            }
            ResourceLocation id = ResourceLocation.tryParse(text.substring("story:".length()));
            if (id == null) {
                throw new NarrativeValidationException("Malformed story state id in " + text + ".");
            }
            return StoryStateStore.get(interactor, id);
        }

        return switch (source.key()) {
            case "interactor.biome" -> new IdValue(biomeId(level, interactor));
            case "interactor.sneaking" -> new BooleanValue(interactor.isShiftKeyDown());
            case "target.biome" -> new IdValue(biomeId(level, target.blockPosition()));
            case "target.entity_type" -> new IdValue(target.entityTypeId());
            case "level.dimension" -> new IdValue(level.dimension().location());
            case "level.time_of_day" -> new IntValue((int) Math.floorMod(level.getDayTime(), 24000L));
            case "level.raining" -> new BooleanValue(level.isRaining());
            case "level.thundering" -> new BooleanValue(level.isThundering());
            default -> throw new NarrativeValidationException("Unknown narrative read key " + source.key() + ".");
        };
    }

    private static ResourceLocation biomeId(ServerLevel level, Entity entity) {
        return biomeId(level, entity.blockPosition());
    }

    private static ResourceLocation biomeId(ServerLevel level, BlockPos position) {
        return level.getBiome(position).unwrapKey()
                .orElseThrow(() -> new NarrativeValidationException(
                        "Biome at " + position + " has no registry key."
                ))
                .location();
    }
}
