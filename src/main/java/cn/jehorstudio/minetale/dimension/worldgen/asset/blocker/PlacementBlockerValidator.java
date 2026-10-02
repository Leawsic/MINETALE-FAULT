package cn.jehorstudio.minetale.dimension.worldgen.asset.blocker;

import cn.jehorstudio.minetale.dimension.worldgen.asset.transform.StructureAssetTransform;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.shapes.CollisionContext;

public final class PlacementBlockerValidator {
    private PlacementBlockerValidator() {
    }

    public static PlacementBlockerValidationResult validate(
            WorldGenLevel level,
            StructureTemplate template,
            BlockPos origin,
            Rotation rotation,
            Mirror mirror
    ) {
        List<ScannedPlacementBlocker> blockers = PlacementBlockerScanner.scanTemplate(template);
        List<BlockPos> failedWorldPositions = new ArrayList<>();
        List<String> failedStates = new ArrayList<>();
        for (ScannedPlacementBlocker blocker : blockers) {
            BlockPos worldPos = StructureAssetTransform.transformMarkerPos(blocker.localPos(), template.getSize(), origin, rotation, mirror);
            BlockState state = level.getBlockState(worldPos);
            String reason = failureReason(level, worldPos, state);
            if (!reason.isEmpty()) {
                failedWorldPositions.add(worldPos.immutable());
                failedStates.add(formatFailure(worldPos, state, reason));
            }
        }

        int failedCount = failedWorldPositions.size();
        boolean success = failedCount == 0;
        String summary = success
                ? "success=true blockers=" + blockers.size()
                : "success=false blockers=" + blockers.size() + " failed=" + failedCount + " first=" + failedStates.getFirst();
        return new PlacementBlockerValidationResult(
                success,
                blockers.size(),
                failedCount,
                failedWorldPositions,
                failedStates,
                summary
        );
    }

    public static boolean isAllowed(BlockState state) {
        if (state.isAir() || state.is(Blocks.STRUCTURE_VOID) || state.is(Blocks.SNOW)) {
            return true;
        }
        if (state.getFluidState().isEmpty() && !isTreeBlock(state) && state.canBeReplaced()) {
            return true;
        }
        return false;
    }

    private static String failureReason(WorldGenLevel level, BlockPos worldPos, BlockState state) {
        if (isAllowed(state)) {
            return "";
        }
        if (state.is(PlacementBlockerRegistry.PLACEMENT_BLOCKER.get())) {
            return "";
        }
        if (!state.getFluidState().isEmpty() || state.liquid()) {
            return "liquid";
        }
        if (isTreeBlock(state)) {
            return "tree_block";
        }
        if (state.blocksMotion()) {
            return "blocks_motion";
        }
        if (!state.getCollisionShape(level, worldPos, CollisionContext.empty()).isEmpty()) {
            return "collision_shape";
        }
        return "not_replaceable";
    }

    private static boolean isTreeBlock(BlockState state) {
        return state.is(BlockTags.LEAVES) || state.is(BlockTags.LOGS);
    }

    private static String formatFailure(BlockPos worldPos, BlockState state, String reason) {
        return worldPos.toShortString()
                + " state=" + BuiltInRegistries.BLOCK.getKey(state.getBlock())
                + " reason=" + reason;
    }
}
