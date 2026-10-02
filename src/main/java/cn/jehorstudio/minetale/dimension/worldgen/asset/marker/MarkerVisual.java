package cn.jehorstudio.minetale.dimension.worldgen.asset.marker;

import net.minecraft.util.StringRepresentable;

public enum MarkerVisual implements StringRepresentable {
    CONNECTOR("connector"),
    ANCHOR("anchor"),
    LOT("lot"),
    POINT("point"),
    VOLUME("volume"),
    HIDDEN("hidden");

    public static final EnumCodec<MarkerVisual> CODEC = StringRepresentable.fromEnum(MarkerVisual::values);

    private final String serializedName;

    MarkerVisual(String serializedName) {
        this.serializedName = serializedName;
    }

    @Override
    public String getSerializedName() {
        return this.serializedName;
    }

    public MarkerVisual next() {
        MarkerVisual[] values = values();
        return values[(this.ordinal() + 1) % values.length];
    }

    public static MarkerVisual byName(String name) {
        MarkerVisual visual = CODEC.byName(name);
        return visual == null ? CONNECTOR : visual;
    }
}
