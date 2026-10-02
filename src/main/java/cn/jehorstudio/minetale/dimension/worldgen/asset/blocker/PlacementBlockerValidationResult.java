package cn.jehorstudio.minetale.dimension.worldgen.asset.blocker;

import java.util.List;
import net.minecraft.core.BlockPos;

public record PlacementBlockerValidationResult(
        boolean success,
        int blockerCount,
        int failedCount,
        List<BlockPos> failedWorldPositions,
        List<String> failedStates,
        String summary
) {
    public PlacementBlockerValidationResult {
        failedWorldPositions = List.copyOf(failedWorldPositions == null ? List.of() : failedWorldPositions);
        failedStates = List.copyOf(failedStates == null ? List.of() : failedStates);
    }
}
