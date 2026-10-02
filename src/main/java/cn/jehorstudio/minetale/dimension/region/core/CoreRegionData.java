package cn.jehorstudio.minetale.dimension.region.core;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.Optional;

/** 每维度保存 Core 的唯一放置坐标；重放置即覆盖，存档中只保留一份。 */
public final class CoreRegionData extends SavedData {
    private CorePlacement placement;

    public static final Codec<CoreRegionData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            CorePlacement.CODEC.optionalFieldOf("placement").forGetter(data -> Optional.ofNullable(data.placement))
    ).apply(instance, CoreRegionData::new));

    public static final SavedDataType<CoreRegionData> TYPE =
            new SavedDataType<>("minetale_core_region", CoreRegionData::new, CODEC);

    public CoreRegionData() {}

    private CoreRegionData(Optional<CorePlacement> placement) {
        this.placement = placement.orElse(null);
    }

    public CorePlacement placement() {
        return placement;
    }

    public void set(CorePlacement next) {
        placement = next;
        setDirty();
    }
}
