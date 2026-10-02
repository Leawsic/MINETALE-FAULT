package cn.jehorstudio.minetale.dimension.worldgen.asset.catalog;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.List;

public record StructureAssetFootprint(
        int width,
        int height,
        int depth,
        int foundationDepth,
        int maxSlope,
        boolean allowLiquid,
        boolean requireSurface
) {
    public static final StructureAssetFootprint DEFAULT = new StructureAssetFootprint(1, 1, 1, 0, 0, false, false);

    public static final Codec<StructureAssetFootprint> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.optionalFieldOf("width", DEFAULT.width()).forGetter(StructureAssetFootprint::width),
            Codec.INT.optionalFieldOf("height", DEFAULT.height()).forGetter(StructureAssetFootprint::height),
            Codec.INT.optionalFieldOf("depth", DEFAULT.depth()).forGetter(StructureAssetFootprint::depth),
            Codec.INT.optionalFieldOf("foundation_depth", DEFAULT.foundationDepth()).forGetter(StructureAssetFootprint::foundationDepth),
            Codec.INT.optionalFieldOf("max_slope", DEFAULT.maxSlope()).forGetter(StructureAssetFootprint::maxSlope),
            Codec.BOOL.optionalFieldOf("allow_liquid", DEFAULT.allowLiquid()).forGetter(StructureAssetFootprint::allowLiquid),
            Codec.BOOL.optionalFieldOf("require_surface", DEFAULT.requireSurface()).forGetter(StructureAssetFootprint::requireSurface)
    ).apply(instance, StructureAssetFootprint::new));

    public List<String> validate() {
        List<String> errors = new ArrayList<>();
        if (this.width <= 0) {
            errors.add("footprint.width must be > 0");
        }
        if (this.height <= 0) {
            errors.add("footprint.height must be > 0");
        }
        if (this.depth <= 0) {
            errors.add("footprint.depth must be > 0");
        }
        if (this.foundationDepth < 0) {
            errors.add("footprint.foundation_depth must be >= 0");
        }
        if (this.maxSlope < 0) {
            errors.add("footprint.max_slope must be >= 0");
        }
        return errors;
    }

    public String describe() {
        return "width=" + this.width
                + " height=" + this.height
                + " depth=" + this.depth
                + " foundation_depth=" + this.foundationDepth
                + " max_slope=" + this.maxSlope
                + " allow_liquid=" + this.allowLiquid
                + " require_surface=" + this.requireSurface;
    }
}