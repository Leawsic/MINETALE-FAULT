package cn.jehorstudio.minetale.dimension.worldgen.asset.placement;

import cn.jehorstudio.minetale.dimension.worldgen.asset.blocker.PlacementBlockerRegistry;
import cn.jehorstudio.minetale.dimension.worldgen.registry.ModStructureProcessors;
import com.mojang.serialization.MapCodec;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

public final class PlacementBlockerCleanupProcessor extends StructureProcessor {
    public static final PlacementBlockerCleanupProcessor INSTANCE = new PlacementBlockerCleanupProcessor();
    public static final MapCodec<PlacementBlockerCleanupProcessor> CODEC = MapCodec.unit(() -> INSTANCE);

    private PlacementBlockerCleanupProcessor() {
    }

    @Nullable
    @Override
    public StructureTemplate.StructureBlockInfo processBlock(
            LevelReader level,
            BlockPos offset,
            BlockPos pos,
            StructureTemplate.StructureBlockInfo blockInfo,
            StructureTemplate.StructureBlockInfo relativeBlockInfo,
            StructurePlaceSettings settings
    ) {
        if (!relativeBlockInfo.state().is(PlacementBlockerRegistry.PLACEMENT_BLOCKER.get())) {
            return relativeBlockInfo;
        }
        return new StructureTemplate.StructureBlockInfo(relativeBlockInfo.pos(), Blocks.AIR.defaultBlockState(), null);
    }

    @Override
    protected StructureProcessorType<?> getType() {
        return ModStructureProcessors.PLACEMENT_BLOCKER_CLEANUP.get();
    }
}
