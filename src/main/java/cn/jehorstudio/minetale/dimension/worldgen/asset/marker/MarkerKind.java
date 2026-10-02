package cn.jehorstudio.minetale.dimension.worldgen.asset.marker;

import net.minecraft.util.StringRepresentable;

public enum MarkerKind implements StringRepresentable {
    CONNECTOR("connector"),
    ANCHOR("anchor"),
    SLOT("slot"),
    LOT("lot"),
    POINT("point"),
    VOLUME("volume");

    public static final EnumCodec<MarkerKind> CODEC = StringRepresentable.fromEnum(MarkerKind::values);
    private static final MarkerKind[] VISIBLE_VALUES = {CONNECTOR, ANCHOR, SLOT, VOLUME};

    private final String serializedName;

    MarkerKind(String serializedName) {
        this.serializedName = serializedName;
    }

    @Override
    public String getSerializedName() {
        return this.serializedName;
    }

    public MarkerKind next() {
        for (int i = 0; i < VISIBLE_VALUES.length; i++) {
            if (VISIBLE_VALUES[i] == this) {
                return VISIBLE_VALUES[(i + 1) % VISIBLE_VALUES.length];
            }
        }
        return CONNECTOR;
    }

    public static MarkerKind byName(String name) {
        if ("lot".equals(name)) {
            return SLOT;
        }
        if ("point".equals(name)) {
            return ANCHOR;
        }
        MarkerKind kind = CODEC.byName(name);
        return kind == null ? CONNECTOR : kind;
    }
}
