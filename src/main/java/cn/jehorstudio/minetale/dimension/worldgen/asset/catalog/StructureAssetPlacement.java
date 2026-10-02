package cn.jehorstudio.minetale.dimension.worldgen.asset.catalog;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

public record StructureAssetPlacement(
        boolean canRotate,
        boolean canMirror
) {
    public static final StructureAssetPlacement DEFAULT = new StructureAssetPlacement(true, false);

    public static final Codec<StructureAssetPlacement> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.BOOL.optionalFieldOf("can_rotate", DEFAULT.canRotate()).forGetter(StructureAssetPlacement::canRotate),
            Codec.BOOL.optionalFieldOf("can_mirror", DEFAULT.canMirror()).forGetter(StructureAssetPlacement::canMirror)
    ).apply(instance, StructureAssetPlacement::new));

    public String describe() {
        return "can_rotate=" + this.canRotate + " can_mirror=" + this.canMirror;
    }
}