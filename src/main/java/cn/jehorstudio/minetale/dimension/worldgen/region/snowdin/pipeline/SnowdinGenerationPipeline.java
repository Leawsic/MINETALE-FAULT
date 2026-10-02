package cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.pipeline;

import cn.jehorstudio.minetale.content.block.common.CommonBlocksRegistry;
import cn.jehorstudio.minetale.dimension.worldgen.RainbowCakeModel;
import cn.jehorstudio.minetale.dimension.worldgen.WorldgenSamplingContext;
import cn.jehorstudio.minetale.dimension.worldgen.data.ColumnCache;
import cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.feature.SnowdinIceLakeFeature;
import cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.feature.SnowdinSpruceClusterFeature;
import cn.jehorstudio.minetale.dimension.worldgen.network.UndergroundRegion;
import cn.jehorstudio.minetale.dimension.worldgen.region.GenerationStages;
import cn.jehorstudio.minetale.dimension.worldgen.region.RegionGenerationPipeline;
import cn.jehorstudio.minetale.dimension.worldgen.region.RegionGenerationTask;
import cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.element.pillar.SnowdinPillarMask;
import cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.element.stalactite.SnowdinStalactiteMask;
import cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.settings.SnowdinTerrainSettings;
import cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.structure.SnowtownGenerator;
import cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.terrain.SnowdinGroundProfile;
import cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.terrain.SnowdinSurfaceResolver;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

public final class SnowdinGenerationPipeline implements RegionGenerationPipeline<SnowdinContext> {
    public static final SnowdinGenerationPipeline INSTANCE = new SnowdinGenerationPipeline();

    private final List<GenerationStages.StagePass<SnowdinContext>> stage1Passes =
            List.of(this::stage1TerrainBlankPass);
    private final List<GenerationStages.StagePass<SnowdinContext>> stage2Passes =
            List.of(this::stage2MaskPass);
    private final List<GenerationStages.StagePass<SnowdinContext>> stage3Passes =
            List.of(this::materialReplacementPass, this::floorSnowBlockSurfacePass);
    private final List<GenerationStages.StagePass<SnowdinContext>> stage4Passes =
            List.of(this::structurePass);
    private final List<GenerationStages.StagePass<SnowdinContext>> stage5Passes =
            List.of(this::featureScatterPass, this::chunkFeaturePass);

    private final GenerationStages.TerrainStage<SnowdinContext> stage1 = context -> runPasses(context, stage1Passes);
    private final GenerationStages.ElementStage<SnowdinContext> stage2 = context -> runPasses(context, stage2Passes);
    private final GenerationStages.MaterialStage<SnowdinContext> stage3 = context -> runPasses(context, stage3Passes);
    private final GenerationStages.StructureStage<SnowdinContext> stage4 = context -> runPasses(context, stage4Passes);
    private final GenerationStages.FeatureStage<SnowdinContext> stage5 = context -> runPasses(context, stage5Passes);

    private SnowdinGenerationPipeline() {}

    @Override
    public RainbowCakeModel.CakeRegion cakeRegion() {
        return RainbowCakeModel.CakeRegion.SNOWDIN;
    }

    @Override
    public SnowdinContext createContext(RegionGenerationTask task) {
        return new SnowdinContext(task);
    }

    @Override
    public void runStage3(RegionGenerationTask task) {
        SnowdinContext context = createContext(task);
        if (!ensureTerrainData(context)) {
            return;
        }
        SnowdinColumnMaterialFacts facts = ensureColumnMaterialFacts(context);
        materialReplacementPass(context, facts);
        floorSnowBlockSurfacePass(context, facts);
    }

    @Override
    public void runStage4(RegionGenerationTask task) {
        if (!isFirstSnowdinTask(task)) {
            return;
        }
        SnowtownGenerator.apply(createContext(task));
    }

    @Override
    public void runStage5(RegionGenerationTask task) {
        if (task.world().columnCache().snowdinStage4StructureOccupied[task.index()]) {
            return;
        }
        SnowdinContext context = createContext(task);
        featureScatterPass(context);
        if (task.world().columnCache().snowdinStage4StructureOccupiedCount == 0 && isFirstSnowdinTask(task)) {
            chunkFeaturePass(context);
        }
    }

    @Override
    public GenerationStages.TerrainStage<SnowdinContext> stage1() {
        return stage1;
    }

    @Override
    public GenerationStages.ElementStage<SnowdinContext> stage2() {
        return stage2;
    }

    @Override
    public GenerationStages.MaterialStage<SnowdinContext> stage3() {
        return stage3;
    }

    @Override
    public GenerationStages.StructureStage<SnowdinContext> stage4() {
        return stage4;
    }

    @Override
    public GenerationStages.FeatureStage<SnowdinContext> stage5() {
        return stage5;
    }

    private void runPasses(SnowdinContext context, List<GenerationStages.StagePass<SnowdinContext>> passes) {
        for (GenerationStages.StagePass<SnowdinContext> pass : passes) {
            pass.apply(context);
        }
    }

    private void stage1TerrainBlankPass(SnowdinContext context) {
        if (!populateTerrainData(context)) {
            return;
        }

        ChunkAccess chunk = context.world().chunk();
        SnowdinColumnMaterialFacts facts = ensureColumnMaterialFacts(context);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (int y = facts.activeMinY(); y <= facts.activeMaxY(); y++) {
            if (shouldCarveSnowdinCave(context, facts, y)) {
                cursor.set(context.x(), y, context.z());
                chunk.setBlockState(cursor, Blocks.AIR.defaultBlockState());
            }
        }
    }

    private void stage2MaskPass(SnowdinContext context) {
        if (!ensureTerrainData(context)) {
            return;
        }

        if (!hasColumnMaterialFacts(context)) {
            buildColumnMaterialFacts(context);
        }
    }

    private SnowdinColumnMaterialFacts buildColumnMaterialFacts(SnowdinContext context) {
        double floorY = requireFloorY(context);
        double ceilingY = requireCeilingY(context);
        double horizontalPathDistance = requireHorizontalPathDistance(context);
        double terraceTopY = requireOptional(context.terraceTopY(), "terrace_top_y", context);
        double terraceMask = requireOptional(context.terraceMask(), "terrace_mask", context);
        ColumnYRange activeRange = snowdinActiveYRange(context, floorY, ceilingY, terraceTopY);
        int minY = activeRange.minY();
        int maxY = activeRange.maxY();
        int height = maxY - minY + 1;
        WorldgenSamplingContext samplingContext = context.world().samplingContext();
        double deepGeoCutY = SnowdinTerrainMaterial.deepGeoCutY(
                samplingContext,
                context.x(),
                context.z(),
                floorY
        );
        double floorSnowMacro = SnowdinNaturalTerrainFactsKernel.floorSnowMacro(
                samplingContext,
                context.x(),
                context.z()
        );
        SnowdinPillarMask.PreparedColumn pillarColumn = SnowdinNaturalTerrainFactsKernel.preparePillarColumn(
                samplingContext,
                context.x(),
                context.z(),
                horizontalPathDistance
        );
        SnowdinStalactiteMask.PreparedColumn stalactiteColumn =
                SnowdinNaturalTerrainFactsKernel.prepareStalactiteColumn(
                        samplingContext,
                        context.x(),
                        context.z(),
                        floorY,
                        ceilingY
                );
        SnowdinGroundProfile.Terrace terrace = new SnowdinGroundProfile.Terrace(terraceTopY, terraceMask);

        double[] stalactiteBodyMask = new double[height];
        double[] pillarMask = new double[height];

        for (int y = minY; y <= maxY; y++) {
            int index = y - minY;
            stalactiteBodyMask[index] = SnowdinNaturalTerrainFactsKernel.stalactiteBodyMask(stalactiteColumn, y);
            pillarMask[index] = SnowdinNaturalTerrainFactsKernel.pillarMask(
                    pillarColumn,
                    y,
                    floorY,
                    ceilingY,
                    terrace
            );
        }

        context.putColumnMaterialFacts(
                minY,
                maxY,
                deepGeoCutY,
                floorSnowMacro,
                stalactiteBodyMask,
                pillarMask
        );

        return new SnowdinColumnMaterialFacts(
                minY,
                maxY,
                minY,
                maxY,
                floorY,
                ceilingY,
                horizontalPathDistance,
                terraceTopY,
                terraceMask,
                deepGeoCutY,
                floorSnowMacro,
                stalactiteBodyMask,
                pillarMask
        );
    }

    private void materialReplacementPass(SnowdinContext context) {
        if (!ensureTerrainData(context)) {
            return;
        }

        SnowdinColumnMaterialFacts facts = ensureColumnMaterialFacts(context);
        materialReplacementPass(context, facts);
    }

    private void materialReplacementPass(SnowdinContext context, SnowdinColumnMaterialFacts facts) {
        ChunkAccess chunk = context.world().chunk();
        SnowdinTerrainPalette palette = SnowdinTerrainPalette.defaults();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (int y = facts.activeMinY(); y <= facts.activeMaxY(); y++) {
            int factsIndex = facts.indexForY(y);
            cursor.set(context.x(), y, context.z());
            BlockState current = chunk.getBlockState(cursor);
            if (!isSnowdinStone(current)) {
                continue;
            }

            BlockState target = SnowdinTerrainMaterial.terrainBlockState(
                    context.world().samplingContext(),
                    context.x(),
                    y,
                    context.z(),
                    facts.floorY(),
                    facts.ceilingY(),
                    facts.deepGeoCutY(),
                    facts.stalactiteBodyMask()[factsIndex],
                    facts.pillarMask()[factsIndex],
                    palette
            );
            if (target != current) {
                chunk.setBlockState(cursor, target);
            }
        }
    }

    private void floorSnowBlockSurfacePass(SnowdinContext context) {
        if (!ensureTerrainData(context)) {
            return;
        }

        SnowdinColumnMaterialFacts facts = ensureColumnMaterialFacts(context);
        floorSnowBlockSurfacePass(context, facts);
    }

    private void floorSnowBlockSurfacePass(SnowdinContext context, SnowdinColumnMaterialFacts facts) {
        ChunkAccess chunk = context.world().chunk();
        SnowdinTerrainPalette palette = SnowdinTerrainPalette.defaults();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        double preferredGroundY = SnowdinSurfaceResolver.preferredGroundY(facts.floorY(), facts.terraceTopY(), facts.terraceMask());
        OptionalInt supportY = SnowdinSurfaceResolver.resolveFloorAnchorY(
                preferredGroundY,
                facts.ceilingY(),
                facts.activeMinY(),
                facts.activeMaxY(),
                y -> {
                    cursor.set(context.x(), y, context.z());
                    return chunk.getBlockState(cursor);
                },
                SnowdinSurfaceResolver.FloorSurfaceType.SNOW_BLOCK_REPLACEMENT
        );
        if (supportY.isEmpty()) {
            return;
        }

        int y = supportY.getAsInt();
        context.putStage3SurfaceY(y);

        if (!SnowdinTerrainMaterial.hasSnowBlockSurface(facts.floorSnowMacro())) {
            return;
        }

        cursor.set(context.x(), y, context.z());
        chunk.setBlockState(cursor, palette.snowBlock());
    }

    private void structurePass(SnowdinContext context) {
        if (!isFirstSnowdinColumn(context)) {
            return;
        }
        SnowtownGenerator.apply(context);
    }

    private void featureScatterPass(SnowdinContext context) {
        if (context.isStage4StructureOccupied()) {
            return;
        }
        if (!ensureTerrainData(context)) {
            return;
        }

        SnowdinColumnMaterialFacts facts = ensureColumnMaterialFacts(context);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos aboveCursor = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos belowCursor = new BlockPos.MutableBlockPos();
        SnowdinFeatureScatter.apply(
                context.world().chunk(),
                context.world().samplingContext(),
                context.x(),
                context.z(),
                facts.floorY(),
                facts.ceilingY(),
                facts.terraceTopY(),
                facts.terraceMask(),
                facts.floorSnowMacro(),
                facts.stalactiteBodyMask(),
                facts.activeMinY(),
                facts.activeMaxY(),
                context.stage3SurfaceY().orElse(Integer.MIN_VALUE),
                cursor,
                aboveCursor,
                belowCursor
        );
    }

    // Feature 放置器可能触及邻区块；本入口只保证每个 Chunk 由其首个 Snowdin 列触发一次。
    private void chunkFeaturePass(SnowdinContext context) {
        if (!isFirstSnowdinColumn(context) || context.world().level().isEmpty() || context.world().generator().isEmpty()) {
            return;
        }
        if (context.hasAnyStage4StructureOccupied()) {
            return;
        }

        SnowdinSpruceClusterFeature.placeChunk(
                context.world().level().get(),
                context.world().generator().get(),
                context.world().chunk().getPos(),
                context.world().samplingContext()
        );
        SnowdinIceLakeFeature.placeChunk(
                context.world().level().get(),
                context.world().chunk().getPos(),
                context.world().samplingContext()
        );
    }

    private boolean isFirstSnowdinColumn(SnowdinContext context) {
        int firstIndex = context.columnCache().snowdinFirstColumnIndex;
        if (firstIndex == Integer.MIN_VALUE) {
            firstIndex = findFirstSnowdinColumn(context.columnCache());
            context.columnCache().snowdinFirstColumnIndex = firstIndex;
        }
        return context.index() == firstIndex;
    }

    private static boolean isFirstSnowdinTask(RegionGenerationTask task) {
        int firstIndex = task.world().columnCache().snowdinFirstColumnIndex;
        if (firstIndex == Integer.MIN_VALUE) {
            firstIndex = findFirstSnowdinColumn(task.world().columnCache());
            task.world().columnCache().snowdinFirstColumnIndex = firstIndex;
        }
        return task.index() == firstIndex;
    }

    private static int findFirstSnowdinColumn(ColumnCache columnCache) {
        for (int index = 0; index < ColumnCache.COLUMN_COUNT; index++) {
            if (columnCache.regionId[index] == ColumnCache.REGION_SNOWDIN) {
                return index;
            }
        }
        return -1;
    }

    // 列缓存共享采样结果
    // 各 Stage 只执行自己的写入职责。
    private SnowdinColumnMaterialFacts ensureColumnMaterialFacts(SnowdinContext context) {
        return readColumnMaterialFacts(context);
    }

    private boolean hasColumnMaterialFacts(SnowdinContext context) {
        return context.columnCache().hasFlags(context.index(), ColumnCache.HAS_SNOWDIN_MATERIAL_FACTS);
    }

    private SnowdinColumnMaterialFacts readColumnMaterialFacts(SnowdinContext context) {
        Optional<Integer> maybeMinY = context.maskMinY();
        Optional<Integer> maybeMaxY = context.maskMaxY();
        if (maybeMinY.isEmpty() || maybeMaxY.isEmpty()) {
            return rebuildMissingColumnMaterialFacts(context);
        }

        int minY = maybeMinY.get();
        int maxY = maybeMaxY.get();
        int expectedLength = maxY - minY + 1;

        if (minY < context.world().minY() || maxY > context.world().maxY() || minY > maxY) {
            throw new IllegalStateException(
                    "Snowdin material facts 数据高度范围与当前 chunk 不一致: expected [%d, %d], actual [%d, %d]".formatted(
                            context.world().minY(),
                            context.world().maxY(),
                            minY,
                            maxY
                    )
            );
        }

        Optional<Double> maybeDeepGeoCutY = context.deepGeoCutY();
        Optional<Double> maybeFloorSnowMacro = context.floorSnowMacro();
        Optional<double[]> maybeStalactiteBodyMask = context.stalactiteBodyMask();
        Optional<double[]> maybePillarMask = context.pillarMask();
        if (maybeDeepGeoCutY.isEmpty()
                || maybeFloorSnowMacro.isEmpty()
                || maybeStalactiteBodyMask.isEmpty()
                || maybePillarMask.isEmpty()) {
            return rebuildMissingColumnMaterialFacts(context);
        }

        double floorY = requireFloorY(context);
        double ceilingY = requireCeilingY(context);
        double horizontalPathDistance = requireHorizontalPathDistance(context);
        double terraceTopY = requireOptional(context.terraceTopY(), "terrace_top_y", context);
        double terraceMask = requireOptional(context.terraceMask(), "terrace_mask", context);
        ColumnYRange activeRange = snowdinActiveYRange(context, floorY, ceilingY, terraceTopY);
        if (minY != activeRange.minY() || maxY != activeRange.maxY()) {
            throw new IllegalStateException(
                    "Snowdin material facts active range 与地形事实不一致: expected [%d, %d], actual [%d, %d]".formatted(
                            activeRange.minY(),
                            activeRange.maxY(),
                            minY,
                            maxY
                    )
            );
        }

        return new SnowdinColumnMaterialFacts(
                minY,
                maxY,
                minY,
                maxY,
                floorY,
                ceilingY,
                horizontalPathDistance,
                terraceTopY,
                terraceMask,
                maybeDeepGeoCutY.get(),
                maybeFloorSnowMacro.get(),
                requireColumnMaterialArray(maybeStalactiteBodyMask.get(), expectedLength, "stalactite_body_mask"),
                requireColumnMaterialArray(maybePillarMask.get(), expectedLength, "pillar_mask")
        );
    }

    private SnowdinColumnMaterialFacts rebuildMissingColumnMaterialFacts(SnowdinContext context) {
        if (!ensureTerrainData(context)) {
            throw new IllegalStateException(
                    "Snowdin material facts 无法构建: column missing at (%d, %d)".formatted(context.x(), context.z())
            );
        }
        return buildColumnMaterialFacts(context);
    }

    private static double[] requireColumnMaterialArray(double[] values, int expectedLength, String key) {
        if (values.length != expectedLength) {
            throw new IllegalStateException(
                    "Snowdin material facts 数据长度不正确: %s expected %d but was %d".formatted(key, expectedLength, values.length)
            );
        }
        return values;
    }

    private static boolean isSnowdinStone(BlockState state) {
        return state.is(CommonBlocksRegistry.SNOW_ROCK.get())
                || state.is(CommonBlocksRegistry.GEO_ROCK.get());
    }

    private boolean ensureTerrainData(SnowdinContext context) {
        if (context.columnCache().hasFlags(
                context.index(),
                ColumnCache.HAS_REGION | ColumnCache.HAS_FLOOR | ColumnCache.HAS_CEILING
        )) {
            return true;
        }
        return populateTerrainData(context);
    }

    private boolean populateTerrainData(SnowdinContext context) {
        WorldgenSamplingContext samplingContext = context.world().samplingContext();
        RainbowCakeModel.CakeSample sample = context.task().cakeSample();
        SnowdinSurfaceResolver.NoiseSurface surface = SnowdinSurfaceResolver.computeNoiseSurface(
                samplingContext,
                context.x(),
                context.z(),
                sample
        );
        if (surface == null) {
            return false;
        }

        context.columnCache().regionId[context.index()] = ColumnCache.regionIdOf(UndergroundRegion.SNOWDIN);
        rememberFirstSnowdinColumn(context);
        context.putTerrainData(
                surface.progress(),
                surface.floorY(),
                surface.terraceBaseY(),
                surface.ceilingY(),
                surface.mainPathY(),
                surface.horizontalPathDistance(),
                surface.terraceTopY(),
                surface.terraceMask(),
                surface.plateauPresence()
        );
        context.columnCache().addFlags(
                context.index(),
                ColumnCache.HAS_REGION
                        | ColumnCache.HAS_FLOOR
                        | ColumnCache.HAS_CEILING
                        | ColumnCache.HAS_MAIN_PATH
                        | ColumnCache.HAS_HORIZONTAL_PATH_DISTANCE
        );
        return true;
    }

    private static void rememberFirstSnowdinColumn(SnowdinContext context) {
        int firstIndex = context.columnCache().snowdinFirstColumnIndex;
        if (firstIndex == Integer.MIN_VALUE || firstIndex < 0 || context.index() < firstIndex) {
            context.columnCache().snowdinFirstColumnIndex = context.index();
        }
    }

    private boolean shouldCarveSnowdinCave(SnowdinContext context, SnowdinColumnMaterialFacts facts, int y) {
        int index = facts.indexForY(y);
        return SnowdinNaturalTerrainFactsKernel.shouldCarveSnowdinCave(
                context.world().samplingContext(),
                context.x(),
                y,
                context.z(),
                facts.floorY(),
                facts.ceilingY(),
                facts.terraceTopY(),
                facts.terraceMask(),
                facts.stalactiteBodyMask()[index],
                facts.pillarMask()[index]
        );
    }

    private static ColumnYRange snowdinActiveYRange(
            SnowdinContext context,
            double floorY,
            double ceilingY,
            double terraceTopY
    ) {
        double lowerMargin = Math.max(
                SnowdinTerrainSettings.INSIDE_FLOOR_MARGIN,
                SnowdinTerrainSettings.FLOOR_CARVE_OFFSET + SnowdinTerrainSettings.FLOOR_FEATHER + 4.0
        );
        double upperMargin = Math.max(
                SnowdinTerrainSettings.INSIDE_CEILING_MARGIN,
                SnowdinTerrainSettings.CEILING_FEATHER + 4.0
        );
        int minY = Math.max(context.world().minY(), (int) Math.floor(Math.min(floorY, terraceTopY) - lowerMargin));
        int maxY = Math.min(context.world().maxY(), (int) Math.ceil(Math.max(ceilingY, terraceTopY) + upperMargin));
        return new ColumnYRange(minY, Math.max(minY, maxY));
    }

    private static double requireProgress(SnowdinContext context) {
        double value = context.columnCache().progress[context.index()];
        if (Double.isNaN(value)) {
            throw missing("progress", context);
        }
        return value;
    }

    private static double requireFloorY(SnowdinContext context) {
        double value = context.columnCache().floorY[context.index()];
        if (Double.isNaN(value)) {
            throw missing("floor_y", context);
        }
        return value;
    }

    private static double requireCeilingY(SnowdinContext context) {
        double value = context.columnCache().ceilingY[context.index()];
        if (Double.isNaN(value)) {
            throw missing("ceiling_y", context);
        }
        return value;
    }

    private static double requireHorizontalPathDistance(SnowdinContext context) {
        double value = context.columnCache().horizontalPathDistance[context.index()];
        if (Double.isNaN(value)) {
            throw missing("horizontal_path_distance", context);
        }
        return value;
    }

    private static double requireOptional(Optional<Double> value, String key, SnowdinContext context) {
        return value.orElseThrow(() -> missing(key, context));
    }

    private static IllegalStateException missing(String key, SnowdinContext context) {
        return new IllegalStateException(
                "Snowdin column cache missing %s at (%d, %d)".formatted(key, context.x(), context.z())
        );
    }

    private record SnowdinColumnMaterialFacts(
            int minY,
            int maxY,
            int activeMinY,
            int activeMaxY,
            double floorY,
            double ceilingY,
            double horizontalPathDistance,
            double terraceTopY,
            double terraceMask,
            double deepGeoCutY,
            double floorSnowMacro,
            double[] stalactiteBodyMask,
            double[] pillarMask
    ) {
        private int indexForY(int y) {
            if (y < minY || y > maxY) {
                throw new IllegalArgumentException("Snowdin material facts y out of range: " + y);
            }
            return y - minY;
        }
    }

    private record ColumnYRange(int minY, int maxY) {}
}
