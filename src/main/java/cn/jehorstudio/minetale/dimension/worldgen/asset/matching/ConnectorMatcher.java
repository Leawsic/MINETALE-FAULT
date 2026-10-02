package cn.jehorstudio.minetale.dimension.worldgen.asset.matching;

import cn.jehorstudio.minetale.dimension.worldgen.asset.catalog.StructureAssetDefinition;
import cn.jehorstudio.minetale.dimension.worldgen.asset.marker.ConnectMode;
import cn.jehorstudio.minetale.dimension.worldgen.asset.marker.MarkerKind;
import cn.jehorstudio.minetale.dimension.worldgen.asset.marker.TemplateMarkerData;
import cn.jehorstudio.minetale.dimension.worldgen.asset.placement.OccupancyMap;
import cn.jehorstudio.minetale.dimension.worldgen.asset.scanner.ScannedTemplateMarker;
import cn.jehorstudio.minetale.dimension.worldgen.asset.scanner.TemplateScanResult;
import cn.jehorstudio.minetale.dimension.worldgen.asset.transform.StructureAssetTransform;
import cn.jehorstudio.minetale.dimension.worldgen.asset.transform.TransformedAssetPlacement;
import cn.jehorstudio.minetale.dimension.worldgen.asset.transform.TransformedTemplateMarker;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;

public final class ConnectorMatcher {
    private ConnectorMatcher() {
    }

    public static ConnectorMatchResult match(ConnectorMatchRequest request) {
        List<ConnectorMatch> matches = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>();
        TransformedTemplateMarker parentMarker = request.parentMarker();

        if (!isMatchParticipant(parentMarker.data().kind())) {
            diagnostics.add("parent marker kind does not participate in connector matching: " + parentMarker.data().kind().getSerializedName());
            return new ConnectorMatchResult(List.of(), diagnostics);
        }

        for (StructureAssetDefinition candidate : request.candidates()) {
            TemplateScanResult scanResult = candidate.scanResult();
            if (scanResult == null) {
                diagnostics.add("candidate has no scan result: " + candidate.id());
                continue;
            }

            for (Rotation rotation : rotationsFor(candidate)) {
                for (Mirror mirror : mirrorsFor(candidate)) {
                    collectMatches(request, candidate, scanResult, rotation, mirror, matches, diagnostics);
                }
            }
        }

        List<ConnectorMatch> sortedMatches = matches.stream()
                .sorted(matchComparator())
                .limit(request.maxResults())
                .toList();
        diagnostics.add("candidates scanned: " + request.candidates().size());
        diagnostics.add("matches found: " + matches.size());
        return new ConnectorMatchResult(sortedMatches, diagnostics);
    }

    private static void collectMatches(
            ConnectorMatchRequest request,
            StructureAssetDefinition candidate,
            TemplateScanResult scanResult,
            Rotation rotation,
            Mirror mirror,
            List<ConnectorMatch> matches,
            List<String> diagnostics
    ) {
        for (int index = 0; index < scanResult.markers().size(); index++) {
            ScannedTemplateMarker childSourceMarker = scanResult.markers().get(index);
            TemplateMarkerData childData = childSourceMarker.data();
            if (!canMatch(request.parentMarker().data(), childData)) {
                continue;
            }

            BlockPos transformedChildLocal = StructureAssetTransform.transformMarkerPos(
                    childSourceMarker.localPos(),
                    scanResult.size(),
                    BlockPos.ZERO,
                    rotation,
                    mirror
            );
            BlockPos targetWorldPos = targetWorldPos(request.parentMarker());
            BlockPos childOrigin = targetWorldPos.subtract(transformedChildLocal);
            TransformedAssetPlacement childPlacement = StructureAssetTransform.transform(candidate, scanResult, childOrigin, rotation, mirror);
            TransformedTemplateMarker childMarker = childPlacement.markers().get(index);
            if (!childMarker.worldPos().equals(targetWorldPos)) {
                diagnostics.add("child marker did not land on target_world_pos: child=" + childMarker.worldPos().toShortString() + " target=" + targetWorldPos.toShortString());
                continue;
            }
            if (request.parentMarker().data().connectMode() == ConnectMode.ADJACENT && request.parentMarker().worldPos().equals(childMarker.worldPos())) {
                diagnostics.add("adjacent match rejected because parent and child marker overlap: " + request.parentMarker().worldPos().toShortString());
                continue;
            }
            if (request.parentMarker().data().connectMode() == ConnectMode.OVERLAP && !request.parentMarker().worldPos().equals(childMarker.worldPos())) {
                diagnostics.add("overlap match rejected because parent and child marker differ: parent=" + request.parentMarker().worldPos().toShortString() + " child=" + childMarker.worldPos().toShortString());
                continue;
            }
            if (request.parentMarker().worldFacing() != childMarker.worldFacing().getOpposite()) {
                continue;
            }

            OccupancyMap occupancyMap = request.occupancyMap();
            boolean collides = occupancyMap != null && occupancyMap.collides(childPlacement.bounds());
            matches.add(new ConnectorMatch(
                    candidate,
                    childPlacement,
                    request.parentMarker(),
                    childMarker,
                    rotation,
                    mirror,
                    childOrigin,
                    childPlacement.bounds(),
                    collides,
                    List.of(
                            "target_mode=" + request.parentMarker().data().connectMode().getSerializedName(),
                            "parent_connect_mode=" + request.parentMarker().data().connectMode().getSerializedName(),
                            "child_connect_mode=" + childData.connectMode().getSerializedName(),
                            "target_world_pos=" + targetWorldPos.toShortString(),
                            "parent_marker_pos=" + request.parentMarker().worldPos().toShortString(),
                            "child_marker_pos=" + childMarker.worldPos().toShortString(),
                            "channel=" + childData.group(),
                            "endpoint=" + childData.role()
                    )
            ));
        }
    }

    private static BlockPos targetWorldPos(TransformedTemplateMarker parentMarker) {
        ConnectMode connectMode = parentMarker.data().connectMode();
        if (connectMode == ConnectMode.ADJACENT) {
            return parentMarker.worldPos().relative(parentMarker.worldFacing());
        }
        return parentMarker.worldPos();
    }

    public static boolean canMatch(TemplateMarkerData parentData, TemplateMarkerData childData) {
        return kindPairAllowed(parentData.kind(), childData.kind())
                && (parentData.accepts().contains(childData.id()) || childData.accepts().contains(parentData.id()));
    }

    private static boolean kindPairAllowed(MarkerKind parentKind, MarkerKind childKind) {
        if (!isMatchParticipant(parentKind) || !isMatchParticipant(childKind)) {
            return false;
        }
        return true;
    }

    private static boolean isMatchParticipant(MarkerKind kind) {
        return kind == MarkerKind.CONNECTOR;
    }

    private static List<Rotation> rotationsFor(StructureAssetDefinition asset) {
        if (!asset.placement().canRotate()) {
            return List.of(Rotation.NONE);
        }
        return List.of(Rotation.NONE, Rotation.CLOCKWISE_90, Rotation.CLOCKWISE_180, Rotation.COUNTERCLOCKWISE_90);
    }

    private static List<Mirror> mirrorsFor(StructureAssetDefinition asset) {
        if (!asset.placement().canMirror()) {
            return List.of(Mirror.NONE);
        }
        return List.of(Mirror.NONE, Mirror.LEFT_RIGHT, Mirror.FRONT_BACK);
    }

    private static Comparator<ConnectorMatch> matchComparator() {
        return Comparator
                .comparing(ConnectorMatch::collides)
                .thenComparing((ConnectorMatch match) -> match.childMarker().data().priority(), Comparator.reverseOrder())
                .thenComparing((ConnectorMatch match) -> match.childAsset().weight(), Comparator.reverseOrder())
                .thenComparing(match -> match.childAsset().id().toString());
    }
}
