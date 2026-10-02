package cn.jehorstudio.minetale.dimension.worldgen.asset.plan;

import cn.jehorstudio.minetale.MineTale;
import cn.jehorstudio.minetale.dimension.worldgen.asset.catalog.StructureAssetDefinition;
import cn.jehorstudio.minetale.dimension.worldgen.asset.marker.MarkerKind;
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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

public final class StructurePlanExtender {
    public static final ResourceLocation DEBUG_EXTEND_ID = ResourceLocation.fromNamespaceAndPath(MineTale.MODID, "debug_extend");
    private static final int MAX_PIECES_LIMIT = 32;
    private static final int MATCH_RESULT_LIMIT = 50;

    private StructurePlanExtender() {
    }

    public static StructurePlanResult extend(
            ServerLevel level,
            StructureAssetDefinition startAsset,
            BlockPos origin,
            Rotation rotation,
            Mirror mirror,
            int maxPieces,
            Collection<StructureAssetDefinition> candidates
    ) {
        int pieceLimit = Math.clamp(maxPieces, 1, MAX_PIECES_LIMIT);
        TemplateScanResult startScanResult = scanResultFor(level, startAsset);
        if (startScanResult == null) {
            return StructurePlanResult.failure("scan_failed:" + startAsset.template());
        }

        List<PlannedPiece> pieces = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        OccupancyMap occupancyMap = new OccupancyMap();
        Queue<PlanFrontierEntry> frontier = new ArrayDeque<>();
        Set<PlanMarkerRef> usedMarkers = new HashSet<>();

        TransformedAssetPlacement startPlacement = StructureAssetTransform.transform(startAsset, startScanResult, origin, rotation, mirror);
        PlannedPiece startPiece = PlannedPiece.from("start", startAsset, startPlacement);
        pieces.add(startPiece);
        occupancyMap.add(startPiece.assetId().toString(), startPiece.bounds());
        addFrontierEntries(0, startPlacement, usedMarkers, frontier);

        while (pieces.size() < pieceLimit && !frontier.isEmpty()) {
            PlanFrontierEntry entry = frontier.remove();
            PlanMarkerRef parentRef = new PlanMarkerRef(entry.pieceIndex(), entry.markerIndex());
            if (usedMarkers.contains(parentRef)) {
                continue;
            }

            ConnectorMatch selectedMatch = selectMatch(entry, pieces, occupancyMap, candidates, notes);
            if (selectedMatch == null) {
                usedMarkers.add(parentRef);
                continue;
            }

            int childIndex = pieces.size();
            PlannedPiece childPiece = PlannedPiece.from("child_" + childIndex, selectedMatch.childAsset(), selectedMatch.childPlacement());
            pieces.add(childPiece);
            occupancyMap.add(childPiece.assetId().toString(), childPiece.bounds());

            usedMarkers.add(parentRef);
            int childMarkerIndex = selectedMatch.childPlacement().markers().indexOf(selectedMatch.childMarker());
            if (childMarkerIndex >= 0) {
                usedMarkers.add(new PlanMarkerRef(childIndex, childMarkerIndex));
            }
            if (selectedMatch.collides()) {
                notes.add("selected AABB-colliding match at piece " + entry.pieceIndex()
                        + " marker " + entry.markerIndex()
                        + "; allowed for debug extension, voxel/contact rules are not implemented yet.");
            }
            notes.addAll(selectedMatch.notes());
            addFrontierEntries(childIndex, selectedMatch.childPlacement(), usedMarkers, frontier);
        }

        if (pieces.size() >= pieceLimit) {
            notes.add("max_pieces reached: " + pieceLimit);
        } else if (frontier.isEmpty()) {
            notes.add("frontier_empty");
        } else {
            notes.add("no_more_matches");
        }

        return StructurePlanResult.success(StructurePlan.create(DEBUG_EXTEND_ID, pieces, dedupeNotes(notes)));
    }

    private static TemplateScanResult scanResultFor(ServerLevel level, StructureAssetDefinition asset) {
        TemplateScanResult scanResult = asset.scanResult();
        if (scanResult != null) {
            return scanResult;
        }
        return StructureAssetScanner.scanTemplate(level, asset.template()).orElse(null);
    }

    private static ConnectorMatch selectMatch(
            PlanFrontierEntry entry,
            List<PlannedPiece> pieces,
            OccupancyMap occupancyMap,
            Collection<StructureAssetDefinition> candidates,
            List<String> notes
    ) {
        ConnectorMatchResult matchResult = ConnectorMatcher.match(new ConnectorMatchRequest(
                pieces.get(entry.pieceIndex()).placement(),
                entry.marker(),
                candidates,
                occupancyMap,
                MATCH_RESULT_LIMIT
        ));

        if (matchResult.matches().isEmpty()) {
            notes.add("no_more_matches");
            return null;
        }

        for (ConnectorMatch match : matchResult.matches()) {
            if (hasIdenticalBounds(match.bounds(), pieces)) {
                notes.add("rejected identical bounds at piece " + entry.pieceIndex()
                        + " marker " + entry.markerIndex()
                        + " child=" + match.childAsset().id());
                continue;
            }
            return match;
        }

        notes.add("no_more_matches");
        return null;
    }

    private static void addFrontierEntries(
            int pieceIndex,
            TransformedAssetPlacement placement,
            Set<PlanMarkerRef> usedMarkers,
            Queue<PlanFrontierEntry> frontier
    ) {
        List<TransformedTemplateMarker> markers = placement.markers();
        for (int markerIndex = 0; markerIndex < markers.size(); markerIndex++) {
            PlanMarkerRef markerRef = new PlanMarkerRef(pieceIndex, markerIndex);
            if (!usedMarkers.contains(markerRef) && canEnterFrontier(markers.get(markerIndex))) {
                frontier.add(new PlanFrontierEntry(pieceIndex, markerIndex, markers.get(markerIndex)));
            }
        }
    }

    private static boolean canEnterFrontier(TransformedTemplateMarker marker) {
        MarkerKind kind = marker.data().kind();
        return kind == MarkerKind.CONNECTOR
                && marker.data().id() != null
                && !marker.data().accepts().isEmpty();
    }

    private static boolean hasIdenticalBounds(BoundingBox candidate, List<PlannedPiece> pieces) {
        return pieces.stream().anyMatch(piece -> identicalBounds(candidate, piece.bounds()));
    }

    private static boolean identicalBounds(BoundingBox left, BoundingBox right) {
        return left.minX() == right.minX()
                && left.minY() == right.minY()
                && left.minZ() == right.minZ()
                && left.maxX() == right.maxX()
                && left.maxY() == right.maxY()
                && left.maxZ() == right.maxZ();
    }

    private static List<String> dedupeNotes(List<String> notes) {
        return new ArrayList<>(new LinkedHashSet<>(notes));
    }
}
