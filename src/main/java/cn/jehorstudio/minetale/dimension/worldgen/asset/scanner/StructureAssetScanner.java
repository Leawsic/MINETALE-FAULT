package cn.jehorstudio.minetale.dimension.worldgen.asset.scanner;

import cn.jehorstudio.minetale.dimension.worldgen.asset.marker.TemplateMarkerRegistry;
import cn.jehorstudio.minetale.dimension.worldgen.asset.marker.MarkerVisual;
import cn.jehorstudio.minetale.dimension.worldgen.asset.marker.TemplateMarkerBlock;
import cn.jehorstudio.minetale.dimension.worldgen.asset.marker.TemplateMarkerBlockEntity;
import cn.jehorstudio.minetale.dimension.worldgen.asset.marker.TemplateMarkerData;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.storage.TagValueInput;
import org.jetbrains.annotations.Nullable;

public final class StructureAssetScanner {
    private StructureAssetScanner() {
    }

    public static Optional<TemplateScanResult> scanTemplate(ServerLevel level, ResourceLocation templateId) {
        StructureTemplateManager structureManager = level.getStructureManager();
        return structureManager.get(templateId).map(template -> scanTemplate(level.registryAccess(), templateId, template));
    }

    public static TemplateScanResult scanTemplate(HolderLookup.Provider registries, ResourceLocation templateId, StructureTemplate template) {
        List<ScannedTemplateMarker> markers = template
                .filterBlocks(BlockPos.ZERO, new StructurePlaceSettings(), TemplateMarkerRegistry.TEMPLATE_MARKER.get(), false)
                .stream()
                .map(blockInfo -> scanMarker(registries, blockInfo))
                .toList();
        return new TemplateScanResult(templateId, template.getSize(), markers);
    }

    private static ScannedTemplateMarker scanMarker(HolderLookup.Provider registries, StructureTemplate.StructureBlockInfo blockInfo) {
        BlockState state = blockInfo.state();
        Direction facing = state.getValue(TemplateMarkerBlock.FACING);
        MarkerVisual visual = state.getValue(TemplateMarkerBlock.VISUAL);
        TemplateMarkerData data = readMarkerData(registries, blockInfo.pos(), state, blockInfo.nbt());
        return new ScannedTemplateMarker(blockInfo.pos(), facing, visual, data);
    }

    private static TemplateMarkerData readMarkerData(
            HolderLookup.Provider registries,
            BlockPos localPos,
            BlockState state,
            @Nullable CompoundTag nbt
    ) {
        if (nbt == null) {
            return TemplateMarkerData.defaults();
        }

        TemplateMarkerBlockEntity markerBlockEntity = new TemplateMarkerBlockEntity(localPos, state);
        markerBlockEntity.loadCustomOnly(TagValueInput.create(ProblemReporter.DISCARDING, registries, nbt));
        return markerBlockEntity.getData().copy();
    }
}
