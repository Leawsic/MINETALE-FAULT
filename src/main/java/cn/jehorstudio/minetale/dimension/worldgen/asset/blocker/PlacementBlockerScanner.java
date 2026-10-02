package cn.jehorstudio.minetale.dimension.worldgen.asset.blocker;

import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

public final class PlacementBlockerScanner {
    private PlacementBlockerScanner() {
    }

    public static Optional<List<ScannedPlacementBlocker>> scanTemplate(ServerLevel level, ResourceLocation templateId) {
        StructureTemplateManager structureManager = level.getStructureManager();
        return structureManager.get(templateId).map(PlacementBlockerScanner::scanTemplate);
    }

    public static List<ScannedPlacementBlocker> scanTemplate(StructureTemplate template) {
        return template
                .filterBlocks(BlockPos.ZERO, new StructurePlaceSettings(), PlacementBlockerRegistry.PLACEMENT_BLOCKER.get(), false)
                .stream()
                .map(blockInfo -> new ScannedPlacementBlocker(blockInfo.pos()))
                .toList();
    }
}
