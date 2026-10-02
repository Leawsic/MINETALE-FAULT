package cn.jehorstudio.minetale.dimension.worldgen.asset.marker;

import java.util.List;
import net.minecraft.resources.ResourceLocation;

public final class TemplateMarkerPresets {
    private TemplateMarkerPresets() {
    }

    public static TemplateMarkerData roadConnector() {
        return data(
                MarkerKind.CONNECTOR,
                "minetale:road",
                List.of("minetale:road"),
                ConnectMode.ADJACENT,
                "road_main",
                "out",
                List.of("minetale:road"),
                "{}"
        );
    }

    public static TemplateMarkerData buildingAnchor() {
        return data(
                MarkerKind.ANCHOR,
                "minetale:building_anchor",
                List.of(),
                ConnectMode.ADJACENT,
                "",
                "origin",
                List.of("minetale:building"),
                "{}"
        );
    }

    public static TemplateMarkerData buildingFront() {
        return data(
                MarkerKind.ANCHOR,
                "minetale:building_front",
                List.of(),
                ConnectMode.ADJACENT,
                "",
                "front",
                List.of("minetale:building"),
                "{}"
        );
    }

    public static TemplateMarkerData anchor() {
        return data(
                MarkerKind.ANCHOR,
                "minetale:origin",
                List.of(),
                ConnectMode.ADJACENT,
                "main",
                "origin",
                List.of("minetale:anchor"),
                "{}"
        );
    }

    public static TemplateMarkerData npcPoint() {
        return data(
                MarkerKind.ANCHOR,
                "minetale:npc_spawn",
                List.of(),
                ConnectMode.ADJACENT,
                "",
                "spawn",
                List.of("minetale:npc"),
                "{\"npc\":\"minetale:placeholder\"}"
        );
    }

    private static TemplateMarkerData data(
            MarkerKind kind,
            String id,
            List<String> accepts,
            ConnectMode connectMode,
            String group,
            String role,
            List<String> tags,
            String payload
    ) {
        return new TemplateMarkerData(
                TemplateMarkerData.CURRENT_SCHEMA,
                kind,
                ResourceLocation.parse(id),
                accepts.stream().map(ResourceLocation::parse).toList(),
                connectMode,
                group,
                role,
                TemplateMarkerData.DEFAULT_FINAL_STATE,
                0,
                tags.stream().map(ResourceLocation::parse).toList(),
                payload
        );
    }
}
