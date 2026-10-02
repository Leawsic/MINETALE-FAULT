package cn.jehorstudio.minetale.dimension.worldgen.preview;

public enum PreviewVisualKind {
    SITE("site"),
    CENTER("center"),
    ROAD("road"),
    ROAD_CANDIDATE("road_candidate"),
    LOT("lot"),
    LOT_CANDIDATE("lot_candidate"),
    LARGE_LOT("large_lot"),
    FOOD_NODE("food_node"),
    BUILDING_NODE("building_node");

    private final String serializedName;

    PreviewVisualKind(String serializedName) {
        this.serializedName = serializedName;
    }

    public String getSerializedName() {
        return this.serializedName;
    }

    public static PreviewVisualKind byName(String name) {
        for (PreviewVisualKind kind : values()) {
            if (kind.serializedName.equals(name)) {
                return kind;
            }
        }
        return SITE;
    }
}
