package cn.jehorstudio.minetale.dimension.worldgen.asset.importer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.ResourceLocation;

public record StructureAssetJsonDraft(
        ResourceLocation templateId,
        String role,
        ResourceLocation category,
        Vec3i size
) {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public String toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("template", this.templateId.toString());
        root.addProperty("role", this.role);
        root.addProperty("category", this.category.toString());
        root.addProperty("weight", 1);

        JsonArray tags = new JsonArray();
        tags.add(this.category.toString());
        root.add("tags", tags);

        JsonObject footprint = new JsonObject();
        footprint.addProperty("width", this.size.getX());
        footprint.addProperty("height", this.size.getY());
        footprint.addProperty("depth", this.size.getZ());
        footprint.addProperty("foundation_depth", 0);
        footprint.addProperty("max_slope", 0);
        footprint.addProperty("allow_liquid", false);
        footprint.addProperty("require_surface", false);
        root.add("footprint", footprint);

        JsonObject placement = new JsonObject();
        placement.addProperty("can_rotate", true);
        placement.addProperty("can_mirror", false);
        root.add("placement", placement);
        root.addProperty("scan_markers", true);
        return GSON.toJson(root) + System.lineSeparator();
    }
}