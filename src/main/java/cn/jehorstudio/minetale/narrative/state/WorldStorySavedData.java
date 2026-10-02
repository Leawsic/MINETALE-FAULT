package cn.jehorstudio.minetale.narrative.state;

import com.mojang.serialization.Codec;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

final class WorldStorySavedData extends SavedData {
    static final Codec<WorldStorySavedData> CODEC =
            CompoundTag.CODEC.xmap(WorldStorySavedData::new, data -> data.values);
    static final SavedDataType<WorldStorySavedData> TYPE = new SavedDataType<>(
            "minetale_narrative_story",
            WorldStorySavedData::new,
            CODEC
    );

    private final CompoundTag values;

    private WorldStorySavedData() {
        this(new CompoundTag());
    }

    private WorldStorySavedData(CompoundTag values) {
        this.values = values;
    }

    CompoundTag values() {
        return this.values;
    }
}
