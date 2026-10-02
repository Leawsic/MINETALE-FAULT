package cn.jehorstudio.minetale.dimension.worldgen.asset.plan;

import cn.jehorstudio.minetale.MineTale;
import cn.jehorstudio.minetale.dimension.worldgen.asset.catalog.StructureAssetDefinition;
import cn.jehorstudio.minetale.dimension.worldgen.asset.matching.ConnectorMatch;
import cn.jehorstudio.minetale.dimension.worldgen.asset.matching.ConnectorMatchRequest;
import cn.jehorstudio.minetale.dimension.worldgen.asset.matching.ConnectorMatchResult;
import cn.jehorstudio.minetale.dimension.worldgen.asset.matching.ConnectorMatcher;
import cn.jehorstudio.minetale.dimension.worldgen.asset.placement.OccupancyMap;
import cn.jehorstudio.minetale.dimension.worldgen.asset.scanner.StructureAssetScanner;
import cn.jehorstudio.minetale.dimension.worldgen.asset.scanner.TemplateScanResult;
import cn.jehorstudio.minetale.dimension.worldgen.asset.transform.StructureAssetTransform;
import cn.jehorstudio.minetale.dimension.worldgen.asset.transform.TransformedAssetPlacement;
import cn.jehorstudio.minetale.dimension.worldgen.asset.transform.TransformedTemplateMarker;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;

public final class StructurePlanPairBuilder {
    public static final ResourceLocation DEBUG_PAIR_ID = ResourceLocation.fromNamespaceAndPath(MineTale.MODID, "debug_pair");

    private StructurePlanPairBuilder() {
    }

    public static StructurePlanResult buildPair(
            ServerLevel level,
            StructureAssetDefinition parentAsset,
            BlockPos parentOrigin,
            Rotation parentRotation,
            Mirror parentMirror,
            int parentMarkerIndex,
            Collection<StructureAssetDefinition> candidates
    ) {
        TemplateScanResult parentScanResult = scanResultFor(level, parentAsset);
        if (parentScanResult == null) {
            return StructurePlanResult.failure("scan_failed:" + parentAsset.template());
        }

        TransformedAssetPlacement parentPlacement = StructureAssetTransform.transform(parentAsset, parentScanResult, parentOrigin, parentRotation, parentMirror);
        if (parentMarkerIndex < 0 || parentMarkerIndex >= parentPlacement.markers().size()) {
            return StructurePlanResult.failure("marker_index_out_of_range:" + parentMarkerIndex + ":" + parentPlacement.markers().size());
        }

        TransformedTemplateMarker parentMarker = parentPlacement.markers().get(parentMarkerIndex);
        OccupancyMap occupancyMap = new OccupancyMap();
        occupancyMap.add(parentAsset.id().toString(), parentPlacement.bounds());
        ConnectorMatchResult matchResult = ConnectorMatcher.match(new ConnectorMatchRequest(
                parentPlacement,
                parentMarker,
                candidates,
                occupancyMap,
                50
        ));
        if (matchResult.matches().isEmpty()) {
            return StructurePlanResult.failure("no_match");
        }

        ConnectorMatch selectedMatch = selectMatch(matchResult.matches());
        List<String> notes = new ArrayList<>();
        notes.addAll(matchResult.diagnostics());
        notes.addAll(selectedMatch.notes());
        if (selectedMatch.collides()) {
            notes.add("selected match collides=true; allowed for debug pair.");
        }

        PlannedPiece parentPiece = PlannedPiece.from("parent", parentAsset, parentPlacement);
        PlannedPiece childPiece = PlannedPiece.from("child", selectedMatch.childAsset(), selectedMatch.childPlacement());
        StructurePlan plan = StructurePlan.create(DEBUG_PAIR_ID, List.of(parentPiece, childPiece), notes);
        return StructurePlanResult.success(plan);
    }

    private static TemplateScanResult scanResultFor(ServerLevel level, StructureAssetDefinition asset) {
        TemplateScanResult scanResult = asset.scanResult();
        if (scanResult != null) {
            return scanResult;
        }
        return StructureAssetScanner.scanTemplate(level, asset.template()).orElse(null);
    }

    private static ConnectorMatch selectMatch(List<ConnectorMatch> matches) {
        return matches.stream().filter(match -> !match.collides()).findFirst().orElse(matches.getFirst());
    }
}
