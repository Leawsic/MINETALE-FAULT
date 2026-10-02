package cn.jehorstudio.minetale.dimension.worldgen.asset.plan;

import cn.jehorstudio.minetale.dimension.worldgen.asset.placement.PlacementBlockerCleanupProcessor;
import cn.jehorstudio.minetale.dimension.worldgen.asset.placement.TemplateMarkerCleanupProcessor;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

public final class StructurePlanDebugPlacer {
    private StructurePlanDebugPlacer() {
    }

    public static Result place(ServerLevel level, StructurePlan plan, boolean keepMarkers) {
        List<PieceResult> results = new ArrayList<>();
        for (PlannedPiece piece : plan.pieces()) {
            StructureTemplate template = level.getStructureManager().get(piece.templateId()).orElse(null);
            if (template == null) {
                results.add(new PieceResult(piece.roleInPlan(), piece.assetId().toString(), false, "template missing: " + piece.templateId()));
                continue;
            }

            StructurePlaceSettings settings = new StructurePlaceSettings()
                    .setRotation(piece.rotation())
                    .setMirror(piece.mirror())
                    .setKnownShape(false);
            settings.addProcessor(PlacementBlockerCleanupProcessor.INSTANCE);
            if (!keepMarkers) {
                settings.addProcessor(TemplateMarkerCleanupProcessor.INSTANCE);
            }

            boolean placed = template.placeInWorld(level, piece.origin(), piece.origin(), settings, RandomSource.create(), 2);
            results.add(new PieceResult(piece.roleInPlan(), piece.assetId().toString(), placed, placed ? "" : "placeInWorld returned false"));
        }
        return new Result(results);
    }

    public record Result(List<PieceResult> pieces) {
        public Result {
            pieces = List.copyOf(pieces == null ? List.of() : pieces);
        }

        public boolean success() {
            return this.pieces.stream().allMatch(PieceResult::placed);
        }

        public boolean placed(String roleInPlan) {
            return this.pieces.stream()
                    .filter(piece -> piece.roleInPlan().equals(roleInPlan))
                    .findFirst()
                    .map(PieceResult::placed)
                    .orElse(false);
        }
    }

    public record PieceResult(String roleInPlan, String assetId, boolean placed, String error) {
    }
}
