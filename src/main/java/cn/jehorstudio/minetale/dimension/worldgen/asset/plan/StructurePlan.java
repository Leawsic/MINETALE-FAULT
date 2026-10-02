package cn.jehorstudio.minetale.dimension.worldgen.asset.plan;

import java.util.List;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

public record StructurePlan(
        ResourceLocation id,
        List<PlannedPiece> pieces,
        BoundingBox bounds,
        List<String> notes
) {
    public StructurePlan {
        pieces = List.copyOf(pieces == null ? List.of() : pieces);
        notes = List.copyOf(notes == null ? List.of() : notes);
    }

    public static StructurePlan create(ResourceLocation id, List<PlannedPiece> pieces, List<String> notes) {
        return new StructurePlan(id, pieces, computeBounds(pieces), notes);
    }

    private static BoundingBox computeBounds(List<PlannedPiece> pieces) {
        if (pieces == null || pieces.isEmpty()) {
            return new BoundingBox(0, 0, 0, 0, 0, 0);
        }
        BoundingBox bounds = pieces.getFirst().bounds();
        for (int i = 1; i < pieces.size(); i++) {
            bounds = BoundingBox.encapsulating(bounds, pieces.get(i).bounds());
        }
        return bounds;
    }
}