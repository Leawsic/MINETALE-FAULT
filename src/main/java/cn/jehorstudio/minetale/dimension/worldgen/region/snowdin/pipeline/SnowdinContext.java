package cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.pipeline;

import cn.jehorstudio.minetale.dimension.worldgen.WorldgenContext;
import cn.jehorstudio.minetale.dimension.worldgen.data.ColumnCache;
import cn.jehorstudio.minetale.dimension.worldgen.data.DataKey;
import cn.jehorstudio.minetale.dimension.worldgen.network.UndergroundRegion;
import cn.jehorstudio.minetale.dimension.worldgen.region.RegionContext;
import cn.jehorstudio.minetale.dimension.worldgen.region.RegionGenerationTask;

import java.util.Optional;

public record SnowdinContext(RegionGenerationTask task) implements RegionContext {
    public static final DataKey<Double> TERRACE_BASE_Y_KEY =
            DataKey.of("minetale:snowdin/terrace_base_y", Double.class);
    public static final DataKey<Double> PLATEAU_PRESENCE_KEY =
            DataKey.of("minetale:snowdin/plateau_presence", Double.class);
    public static final DataKey<double[]> STALACTITE_BODY_MASK_KEY =
            DataKey.of("minetale:snowdin/stage2_stalactite_body_mask", double[].class);
    public static final DataKey<double[]> PILLAR_MASK_KEY =
            DataKey.of("minetale:snowdin/stage2_pillar_mask", double[].class);

    public void putColumnMaterialFacts(
            int minY,
            int maxY,
            double deepGeoCutY,
            double floorSnowMacro,
            double[] stalactiteBodyMask,
            double[] pillarMask
    ) {
        columnCache().snowdinMaterialFactsMinY[index()] = minY;
        columnCache().snowdinMaterialFactsMaxY[index()] = maxY;
        columnCache().snowdinDeepGeoCutY[index()] = deepGeoCutY;
        columnCache().snowdinFloorSnowMacro[index()] = floorSnowMacro;
        columnCache().putExtra(region(), index(), STALACTITE_BODY_MASK_KEY, stalactiteBodyMask);
        columnCache().putExtra(region(), index(), PILLAR_MASK_KEY, pillarMask);
        columnCache().addFlags(index(), ColumnCache.HAS_SNOWDIN_MATERIAL_FACTS);
    }

    public Optional<Integer> maskMinY() {
        int value = columnCache().snowdinMaterialFactsMinY[index()];
        return value == Integer.MIN_VALUE ? Optional.empty() : Optional.of(value);
    }

    public Optional<Integer> maskMaxY() {
        int value = columnCache().snowdinMaterialFactsMaxY[index()];
        return value == Integer.MIN_VALUE ? Optional.empty() : Optional.of(value);
    }

    public Optional<Double> deepGeoCutY() {
        double value = columnCache().snowdinDeepGeoCutY[index()];
        return Double.isNaN(value) ? Optional.empty() : Optional.of(value);
    }

    public Optional<Double> floorSnowMacro() {
        double value = columnCache().snowdinFloorSnowMacro[index()];
        return Double.isNaN(value) ? Optional.empty() : Optional.of(value);
    }

    public Optional<double[]> stalactiteBodyMask() {
        return columnCache().getExtra(region(), index(), STALACTITE_BODY_MASK_KEY);
    }

    public Optional<double[]> pillarMask() {
        return columnCache().getExtra(region(), index(), PILLAR_MASK_KEY);
    }

    public void putStage3SurfaceY(int surfaceY) {
        columnCache().snowdinStage3SurfaceY[index()] = surfaceY;
        columnCache().addFlags(index(), ColumnCache.HAS_SURFACE);
    }

    public Optional<Integer> stage3SurfaceY() {
        int value = columnCache().snowdinStage3SurfaceY[index()];
        return value == Integer.MIN_VALUE ? Optional.empty() : Optional.of(value);
    }

    public void putStage4StructureOccupied(int columnIndex) {
        if (!columnCache().snowdinStage4StructureOccupied[columnIndex]) {
            columnCache().snowdinStage4StructureOccupiedCount++;
        }
        columnCache().snowdinStage4StructureOccupied[columnIndex] = true;
    }

    public boolean isStage4StructureOccupied() {
        return columnCache().snowdinStage4StructureOccupied[index()];
    }

    public boolean isStage4StructureOccupied(int columnIndex) {
        return columnCache().snowdinStage4StructureOccupied[columnIndex];
    }

    public boolean hasAnyStage4StructureOccupied() {
        return columnCache().snowdinStage4StructureOccupiedCount > 0;
    }

    public void putTerrainData(
            double progress,
            double floorY,
            double terraceBaseY,
            double ceilingY,
            double mainPathY,
            double horizontalPathDistance,
            double terraceTopY,
            double terraceMask,
            double plateauPresence
    ) {
        columnCache().progress[index()] = progress;
        columnCache().floorY[index()] = floorY;
        columnCache().ceilingY[index()] = ceilingY;
        columnCache().mainPathY[index()] = mainPathY;
        columnCache().horizontalPathDistance[index()] = horizontalPathDistance;
        columnCache().snowdinTerraceTopY[index()] = terraceTopY;
        columnCache().snowdinTerraceMask[index()] = terraceMask;
        columnCache().putExtra(region(), index(), TERRACE_BASE_Y_KEY, terraceBaseY);
        columnCache().putExtra(region(), index(), PLATEAU_PRESENCE_KEY, plateauPresence);
    }

    public Optional<Double> terraceBaseY() {
        return columnCache().getExtra(region(), index(), TERRACE_BASE_Y_KEY);
    }

    public Optional<Double> terraceTopY() {
        double value = columnCache().snowdinTerraceTopY[index()];
        return Double.isNaN(value) ? Optional.empty() : Optional.of(value);
    }

    public Optional<Double> terraceMask() {
        double value = columnCache().snowdinTerraceMask[index()];
        return Double.isNaN(value) ? Optional.empty() : Optional.of(value);
    }

    public Optional<Double> plateauPresence() {
        return columnCache().getExtra(region(), index(), PLATEAU_PRESENCE_KEY);
    }

    @Override
    public WorldgenContext world() {
        return task.world();
    }

    @Override
    public UndergroundRegion region() {
        return task.region();
    }

    @Override
    public int x() {
        return task.x();
    }

    @Override
    public int z() {
        return task.z();
    }

    @Override
    public int localX() {
        return task.localX();
    }

    @Override
    public int localZ() {
        return task.localZ();
    }

    @Override
    public int index() {
        return task.index();
    }
}
