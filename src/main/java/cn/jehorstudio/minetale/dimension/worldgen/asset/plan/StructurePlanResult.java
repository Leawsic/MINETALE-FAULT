package cn.jehorstudio.minetale.dimension.worldgen.asset.plan;

import java.util.List;
import org.jetbrains.annotations.Nullable;

public record StructurePlanResult(
        @Nullable StructurePlan plan,
        List<String> errors
) {
    public StructurePlanResult {
        errors = List.copyOf(errors == null ? List.of() : errors);
    }

    public boolean success() {
        return this.plan != null && this.errors.isEmpty();
    }

    public static StructurePlanResult success(StructurePlan plan) {
        return new StructurePlanResult(plan, List.of());
    }

    public static StructurePlanResult failure(String error) {
        return new StructurePlanResult(null, List.of(error));
    }
}