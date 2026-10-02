package cn.jehorstudio.minetale.dimension.worldgen.data;

import cn.jehorstudio.minetale.dimension.worldgen.RainbowCakeModel;
import cn.jehorstudio.minetale.dimension.worldgen.network.UndergroundRegion;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public final class ColumnCache {
    public static final int COLUMN_COUNT = 256;
    public static final byte REGION_UNKNOWN = 0;
    public static final byte REGION_SNOWDIN = 1;
    public static final byte REGION_WATERFALL = 2;
    public static final byte REGION_HOT_LAND = 3;
    public static final byte REGION_RUINS = 4;
    public static final byte REGION_DEEP_TUNNEL = 5;

    public static final int HAS_REGION = 1;
    public static final int HAS_FLOOR = 1 << 1;
    public static final int HAS_CEILING = 1 << 2;
    public static final int HAS_SURFACE = 1 << 3;
    public static final int HAS_RESERVED = 1 << 4;
    public static final int HAS_MAIN_PATH = 1 << 5;
    public static final int HAS_HORIZONTAL_PATH_DISTANCE = 1 << 6;
    public static final int HAS_SNOWDIN_MATERIAL_FACTS = 1 << 7;

    public final byte[] regionId = new byte[COLUMN_COUNT];
    public final RainbowCakeModel.CakeSample[] cakeSamples = new RainbowCakeModel.CakeSample[COLUMN_COUNT];

    public final double[] progress = new double[COLUMN_COUNT];
    public final double[] floorY = new double[COLUMN_COUNT];
    public final double[] ceilingY = new double[COLUMN_COUNT];
    public final double[] mainPathY = new double[COLUMN_COUNT];
    public final double[] horizontalPathDistance = new double[COLUMN_COUNT];
    public final double[] snowdinTerraceTopY = new double[COLUMN_COUNT];
    public final double[] snowdinTerraceMask = new double[COLUMN_COUNT];
    public final double[] snowdinDeepGeoCutY = new double[COLUMN_COUNT];
    public final double[] snowdinFloorSnowMacro = new double[COLUMN_COUNT];
    public final int[] snowdinMaterialFactsMinY = new int[COLUMN_COUNT];
    public final int[] snowdinMaterialFactsMaxY = new int[COLUMN_COUNT];
    public final int[] snowdinStage3SurfaceY = new int[COLUMN_COUNT];
    public final boolean[] snowdinStage4StructureOccupied = new boolean[COLUMN_COUNT];
    public int snowdinStage4StructureOccupiedCount;
    public int snowdinFirstColumnIndex;

    public final int[] flags = new int[COLUMN_COUNT];

    private final Map<ColumnDataSlot<?>, Object> extras = new HashMap<>();

    public ColumnCache() {
        clear();
    }

    public static byte regionIdOf(UndergroundRegion region) {
        return switch (region) {
            case SNOWDIN -> REGION_SNOWDIN;
            case WATERFALL -> REGION_WATERFALL;
            case HOT_LAND -> REGION_HOT_LAND;
            case RUINS -> REGION_RUINS;
            case DEEP_TUNNEL -> REGION_DEEP_TUNNEL;
        };
    }

    public void addFlags(int index, int newFlags) {
        flags[index] |= newFlags;
    }

    public boolean hasFlags(int index, int requiredFlags) {
        return (flags[index] & requiredFlags) == requiredFlags;
    }

    public <T> void putExtra(UndergroundRegion region, int index, DataKey<T> key, T value) {
        extras.put(new ColumnDataSlot<>(region, index, key), value);
    }

    @SuppressWarnings("unchecked")
    public <T> Optional<T> getExtra(UndergroundRegion region, int index, DataKey<T> key) {
        Object raw = extras.get(new ColumnDataSlot<>(region, index, key));
        if (raw == null) {
            return Optional.empty();
        }
        if (!key.type().isInstance(raw)) {
            return Optional.empty();
        }
        return Optional.of((T) raw);
    }

    public <T> void removeExtra(UndergroundRegion region, int index, DataKey<T> key) {
        extras.remove(new ColumnDataSlot<>(region, index, key));
    }

    public void clear() {
        for (int i = 0; i < COLUMN_COUNT; i++) {
            regionId[i] = REGION_UNKNOWN;
            cakeSamples[i] = null;
            progress[i] = Double.NaN;
            floorY[i] = Double.NaN;
            ceilingY[i] = Double.NaN;
            mainPathY[i] = Double.NaN;
            horizontalPathDistance[i] = Double.NaN;
            snowdinTerraceTopY[i] = Double.NaN;
            snowdinTerraceMask[i] = Double.NaN;
            snowdinDeepGeoCutY[i] = Double.NaN;
            snowdinFloorSnowMacro[i] = Double.NaN;
            snowdinMaterialFactsMinY[i] = Integer.MIN_VALUE;
            snowdinMaterialFactsMaxY[i] = Integer.MIN_VALUE;
            snowdinStage3SurfaceY[i] = Integer.MIN_VALUE;
            snowdinStage4StructureOccupied[i] = false;
            flags[i] = 0;
        }
        snowdinStage4StructureOccupiedCount = 0;
        snowdinFirstColumnIndex = Integer.MIN_VALUE;
        extras.clear();
    }
}
