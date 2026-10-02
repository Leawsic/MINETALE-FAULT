package cn.jehorstudio.minetale.dimension.worldgen.asset.placement;

import cn.jehorstudio.minetale.MineTale;
import cn.jehorstudio.minetale.dimension.worldgen.asset.marker.TemplateMarkerRegistry;
import cn.jehorstudio.minetale.dimension.worldgen.registry.ModStructureProcessors;
import cn.jehorstudio.minetale.dimension.worldgen.asset.marker.TemplateMarkerBlockEntity;
import cn.jehorstudio.minetale.dimension.worldgen.asset.marker.TemplateMarkerData;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.serialization.MapCodec;
import javax.annotation.Nullable;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.storage.TagValueInput;

public final class TemplateMarkerCleanupProcessor extends StructureProcessor {
    public static final TemplateMarkerCleanupProcessor INSTANCE = new TemplateMarkerCleanupProcessor();
    public static final MapCodec<TemplateMarkerCleanupProcessor> CODEC = MapCodec.unit(() -> INSTANCE);

    private TemplateMarkerCleanupProcessor() {
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
        if (!relativeBlockInfo.state().is(TemplateMarkerRegistry.TEMPLATE_MARKER.get())) {
            return relativeBlockInfo;
        }

        TemplateMarkerData data = readMarkerData(level, relativeBlockInfo);
        BlockState replacement = parseFinalState(level, data.finalState(), relativeBlockInfo.pos());
        return new StructureTemplate.StructureBlockInfo(relativeBlockInfo.pos(), replacement, null);
    }

    @Override
    protected StructureProcessorType<?> getType() {
        return ModStructureProcessors.TEMPLATE_MARKER_CLEANUP.get();
    }

    private static TemplateMarkerData readMarkerData(LevelReader level, StructureTemplate.StructureBlockInfo blockInfo) {
        CompoundTag nbt = blockInfo.nbt();
        if (nbt == null) {
            return TemplateMarkerData.defaults();
        }

        TemplateMarkerBlockEntity markerBlockEntity = new TemplateMarkerBlockEntity(blockInfo.pos(), blockInfo.state());
        markerBlockEntity.loadCustomOnly(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), nbt));
        return markerBlockEntity.getData().copy();
    }

    private static BlockState parseFinalState(LevelReader level, String finalState, BlockPos pos) {
        String stateText = finalState == null || finalState.isBlank() ? TemplateMarkerData.DEFAULT_FINAL_STATE : finalState.trim();
        try {
            return BlockStateParser.parseForBlock(level.holderLookup(Registries.BLOCK), stateText, true).blockState();
        } catch (CommandSyntaxException ex) {
            MineTale.LOGGER.warn("Invalid Template Marker final_state '{}' at {}; using minecraft:air", stateText, pos, ex);
            return Blocks.AIR.defaultBlockState();
        }
    }
}
