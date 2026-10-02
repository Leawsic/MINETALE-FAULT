package cn.jehorstudio.minetale.dimension.worldgen.asset.marker;

import net.minecraft.util.StringRepresentable;

public enum ConnectMode implements StringRepresentable {
    ADJACENT("adjacent"),
    OVERLAP("overlap");

    public static final EnumCodec<ConnectMode> CODEC = StringRepresentable.fromEnum(ConnectMode::values);

    private final String serializedName;

    ConnectMode(String serializedName) {
        this.serializedName = serializedName;
    }

    @Override
    public String getSerializedName() {
        return this.serializedName;
    }

    public ConnectMode next() {
        return this == ADJACENT ? OVERLAP : ADJACENT;
    }

    public static ConnectMode byName(String name) {
        ConnectMode mode = CODEC.byName(name);
        return mode == null ? ADJACENT : mode;
    }

    public static ConnectMode readLegacy(String name) {
        ConnectMode mode = CODEC.byName(name);
        return mode == null ? OVERLAP : mode;
    }
}
