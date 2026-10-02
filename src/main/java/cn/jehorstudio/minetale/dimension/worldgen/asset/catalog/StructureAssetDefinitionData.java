package cn.jehorstudio.minetale.dimension.worldgen.asset.catalog;

import cn.jehorstudio.minetale.MineTale;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.resources.ResourceLocation;

public record StructureAssetDefinitionData(
        ResourceLocation template,
        String role,
        ResourceLocation category,
        int weight,
        List<ResourceLocation> tags,
        StructureAssetFootprint footprint,
        StructureAssetPlacement placement,
        boolean scanMarkers
) {
    public static final ResourceLocation DEFAULT_CATEGORY = ResourceLocation.fromNamespaceAndPath(MineTale.MODID, "generic");

    public static final Codec<StructureAssetDefinitionData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            ResourceLocation.CODEC.fieldOf("template").forGetter(StructureAssetDefinitionData::template),
            Codec.STRING.fieldOf("role").forGetter(StructureAssetDefinitionData::role),
            ResourceLocation.CODEC.optionalFieldOf("category", DEFAULT_CATEGORY).forGetter(StructureAssetDefinitionData::category),
            Codec.INT.optionalFieldOf("weight", 1).forGetter(StructureAssetDefinitionData::weight),
            ResourceLocation.CODEC.listOf().optionalFieldOf("tags", List.of()).forGetter(StructureAssetDefinitionData::tags),
            StructureAssetFootprint.CODEC.optionalFieldOf("footprint", StructureAssetFootprint.DEFAULT).forGetter(StructureAssetDefinitionData::footprint),
            StructureAssetPlacement.CODEC.optionalFieldOf("placement", StructureAssetPlacement.DEFAULT).forGetter(StructureAssetDefinitionData::placement),
            Codec.BOOL.optionalFieldOf("scan_markers", true).forGetter(StructureAssetDefinitionData::scanMarkers)
    ).apply(instance, StructureAssetDefinitionData::new));

    public StructureAssetDefinitionData {
        if (role == null) {
            role = "";
        }
        tags = List.copyOf(tags == null ? List.of() : tags);
        if (category == null) {
            category = DEFAULT_CATEGORY;
        }
        if (footprint == null) {
            footprint = StructureAssetFootprint.DEFAULT;
        }
        if (placement == null) {
            placement = StructureAssetPlacement.DEFAULT;
        }
    }

    public List<String> validate() {
        List<String> errors = new ArrayList<>();
        if (this.template == null) {
            errors.add("template is required");
        }
        if (this.role.isBlank()) {
            errors.add("role must not be empty");
        }
        if (this.category == null) {
            errors.add("category is required");
        }
        if (this.weight < 1) {
            errors.add("weight must be >= 1");
        }
        errors.addAll(this.footprint.validate());
        return errors;
    }
}