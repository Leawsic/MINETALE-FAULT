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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

public final class StructurePlanChainBuilder {
    public static final ResourceLocation DEBUG_CHAIN_ID = ResourceLocation.fromNamespaceAndPath(MineTale.MODID, "debug_chain");
    private static final int MAX_PIECES_LIMIT = 32;
    private static final int MATCH_RESULT_LIMIT = 50;

    private StructurePlanChainBuilder() {
    }

    public static StructurePlanResult build(
            ServerLevel level,
            StructureAssetDefinition startAsset,
            BlockPos origin,
            Rotation rotation,
            Mirror mirror,
            int maxPieces,
            Collection<StructureAssetDefinition> candidates,
            ResourceLocation candidateTag,
            String chainGroup
    ) {
        int pieceLimit = Math.clamp(maxPieces, 1, MAX_PIECES_LIMIT);
        String group = chainGroup == null ? "" : chainGroup;
        TemplateScanResult startScanResult = scanResultFor(level, startAsset);
        if (startScanResult == null) {
            return StructurePlanResult.failure("scan_failed:" + startAsset.template());
        }

        List<PlannedPiece> pieces = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        OccupancyMap occupancyMap = new OccupancyMap();
        Set<PlanMarkerRef> usedMarkers = new HashSet<>();

        notes.add("candidate_tag=" + candidateTag);
        notes.add("chain_channel=" + group);

        TransformedAssetPlacement startPlacement = StructureAssetTransform.transform(startAsset, startScanResult, origin, rotation, mirror);
        PlannedPiece startPiece = PlannedPiece.from("start", startAsset, startPlacement);
        pieces.add(startPiece);
        occupancyMap.add(startPiece.assetId().toString(), startPiece.bounds());

        if (pieceLimit == 1) {
            notes.add("max_pieces reached: " + pieceLimit);
            return StructurePlanResult.success(StructurePlan.create(DEBUG_CHAIN_ID, pieces, dedupeNotes(notes)));
        }

        ChainTail tail = selectTail(0, startPlacement, group, usedMarkers, -1, "");
        if (tail == null) {
            return StructurePlanResult.failure("no_tail:" + startAsset.id());
        }

        while (pieces.size() < pieceLimit) {
            ConnectorMatch selectedMatch = selectMatch(tail, pieces, occupancyMap, candidates, group, notes);
            if (selectedMatch == null) {
                notes.add("no_more_matches");
                break;
            }

            int childIndex = pieces.size();
            PlannedPiece childPiece = PlannedPiece.from("chain_" + childIndex, selectedMatch.childAsset(), selectedMatch.childPlacement());
            pieces.add(childPiece);
            occupancyMap.add(childPiece.assetId().toString(), childPiece.bounds());

            usedMarkers.add(new PlanMarkerRef(tail.pieceIndex(), tail.markerIndex()));
            int childMarkerIndex = selectedMatch.childPlacement().markers().indexOf(selectedMatch.childMarker());
            if (childMarkerIndex >= 0) {
                usedMarkers.add(new PlanMarkerRef(childIndex, childMarkerIndex));
            }
            if (selectedMatch.collides()) {
                notes.add("selected AABB-colliding match at piece " + tail.pieceIndex()
                        + " marker " + tail.markerIndex()
                        + "; allowed for debug chain, voxel/contact rules are not implemented yet.");
            }
            notes.addAll(selectedMatch.notes());
            if (pieces.size() >= pieceLimit) {
                notes.add("max_pieces reached: " + pieceLimit);
                break;
            }

            tail = selectTail(
                    childIndex,
                    selectedMatch.childPlacement(),
                    group,
                    usedMarkers,
                    childMarkerIndex,
                    selectedMatch.childMarker().data().role()
            );
            if (tail == null) {
                notes.add("chain_end");
                break;
            }
        }

        return StructurePlanResult.success(StructurePlan.create(DEBUG_CHAIN_ID, pieces, dedupeNotes(notes)));
    }

    private static TemplateScanResult scanResultFor(ServerLevel level, StructureAssetDefinition asset) {
        TemplateScanResult scanResult = asset.scanResult();
        if (scanResult != null) {
            return scanResult;
        }
        return StructureAssetScanner.scanTemplate(level, asset.template()).orElse(null);
    }

    private static ConnectorMatch selectMatch(
            ChainTail tail,
            List<PlannedPiece> pieces,
            OccupancyMap occupancyMap,
            Collection<StructureAssetDefinition> candidates,
            String chainGroup,
            List<String> notes
    ) {
        ConnectorMatchResult matchResult = ConnectorMatcher.match(new ConnectorMatchRequest(
                pieces.get(tail.pieceIndex()).placement(),
                tail.marker(),
                candidates,
                occupancyMap,
                MATCH_RESULT_LIMIT
        ));

        for (ConnectorMatch match : matchResult.matches()) {
            if (!canUseChainMarker(match.childMarker(), chainGroup)) {
                continue;
            }
            if (hasIdenticalBounds(match.bounds(), pieces)) {
                notes.add("rejected identical bounds at piece " + tail.pieceIndex()
                        + " marker " + tail.markerIndex()
                        + " child=" + match.childAsset().id());
                continue;
            }
            return match;
        }
        return null;
    }

    private static ChainTail selectTail(
            int pieceIndex,
            TransformedAssetPlacement placement,
            String chainGroup,
            Set<PlanMarkerRef> usedMarkers,
            int excludedMarkerIndex,
            String previousRole
    ) {
        List<TransformedTemplateMarker> markers = placement.markers();
        List<IndexedMarker> candidates = new ArrayList<>();
        for (int markerIndex = 0; markerIndex < markers.size(); markerIndex++) {
            if (markerIndex == excludedMarkerIndex) {
                continue;
            }
            PlanMarkerRef markerRef = new PlanMarkerRef(pieceIndex, markerIndex);
            TransformedTemplateMarker marker = markers.get(markerIndex);
            if (!usedMarkers.contains(markerRef) && canUseChainMarker(marker, chainGroup)) {
                candidates.add(new IndexedMarker(markerIndex, marker));
            }
        }

        return candidates.stream()
                .sorted(chainTailComparator(previousRole))
                .findFirst()
                .map(candidate -> new ChainTail(pieceIndex, candidate.markerIndex(), candidate.marker()))
                .orElse(null);
    }

    private static boolean canUseChainMarker(TransformedTemplateMarker marker, String chainGroup) {
        MarkerKind kind = marker.data().kind();
        return kind == MarkerKind.CONNECTOR
                && marker.data().id() != null
                && !marker.data().accepts().isEmpty()
                && marker.data().group().equals(chainGroup);
    }

    private static Comparator<IndexedMarker> chainTailComparator(String previousRole) {
        String role = previousRole == null ? "" : previousRole;
        return Comparator
                .comparing((IndexedMarker candidate) -> !candidate.marker().data().role().equals(role), Comparator.reverseOrder())
                .thenComparing(candidate -> candidate.marker().data().priority(), Comparator.reverseOrder())
                .thenComparing(IndexedMarker::markerIndex);
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

    private record ChainTail(int pieceIndex, int markerIndex, TransformedTemplateMarker marker) {
    }

    private record IndexedMarker(int markerIndex, TransformedTemplateMarker marker) {
    }
}
