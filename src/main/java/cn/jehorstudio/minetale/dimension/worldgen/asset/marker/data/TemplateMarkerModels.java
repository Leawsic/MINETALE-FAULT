package cn.jehorstudio.minetale.dimension.worldgen.asset.marker.data;

import cn.jehorstudio.minetale.MineTale;
import cn.jehorstudio.minetale.dimension.worldgen.asset.marker.MarkerVisual;
import cn.jehorstudio.minetale.dimension.worldgen.asset.marker.TemplateMarkerBlock;
import cn.jehorstudio.minetale.dimension.worldgen.asset.marker.TemplateMarkerRegistry;
import net.minecraft.client.data.models.BlockModelGenerators;
import net.minecraft.client.data.models.blockstates.MultiVariantGenerator;
import net.minecraft.client.data.models.blockstates.PropertyDispatch;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;

// 生成 Template Marker 的 blockstate 与模型数据。
public final class TemplateMarkerModels {
    public static void register(BlockModelGenerators blockModels) {
        var marker = PropertyDispatch.initial(TemplateMarkerBlock.FACING, TemplateMarkerBlock.VISUAL);
        for (Direction facing : Direction.values()) {
            for (MarkerVisual visual : MarkerVisual.values()) {
                ResourceLocation model = ResourceLocation.fromNamespaceAndPath(
                        MineTale.MODID,
                        "block/template_marker_" + visual.getSerializedName()
                );
                var variant = BlockModelGenerators.plainVariant(model);
                variant = switch (facing) {
                    case EAST -> variant.with(BlockModelGenerators.Y_ROT_90);
                    case SOUTH -> variant.with(BlockModelGenerators.Y_ROT_180);
                    case WEST -> variant.with(BlockModelGenerators.Y_ROT_270);
                    case UP -> variant.with(BlockModelGenerators.X_ROT_270);
                    case DOWN -> variant.with(BlockModelGenerators.X_ROT_90);
                    default -> variant;
                };
                marker.select(facing, visual, variant);
            }
        }
        blockModels.blockStateOutput.accept(
                MultiVariantGenerator.dispatch(TemplateMarkerRegistry.TEMPLATE_MARKER.get()).with(marker)
        );
    }

    private TemplateMarkerModels() {}
}
