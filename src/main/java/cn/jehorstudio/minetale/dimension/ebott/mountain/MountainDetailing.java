package cn.jehorstudio.minetale.dimension.ebott.mountain;

import cn.jehorstudio.minetale.MineTale;
import cn.jehorstudio.minetale.dimension.ebott.EbottData;
import cn.jehorstudio.minetale.dimension.ebott.PlaceManager;
import cn.jehorstudio.minetale.content.block.common.CommonBlocksRegistry;
import cn.jehorstudio.minetale.dimension.worldgen.registry.ModWorldgenKeys;
import cn.jehorstudio.minetale.dimension.worldgen.math.WorldgenMath;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.features.OreFeatures;
import net.minecraft.data.worldgen.features.TreeFeatures;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.UniformFloat;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.CarvingMask;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.VerticalAnchor;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.carver.CarvingContext;
import net.minecraft.world.level.levelgen.carver.CaveCarverConfiguration;
import net.minecraft.world.level.levelgen.carver.ConfiguredWorldCarver;
import net.minecraft.world.level.levelgen.carver.WorldCarver;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.TreeFeature;
import net.minecraft.world.level.levelgen.feature.configurations.TreeConfiguration;
import net.minecraft.world.level.levelgen.heightproviders.UniformHeight;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.material.Fluids;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

// 在原版 Feature 阶段前后合成 Ebott 局部地形，并独占阶段顺序。
final class MountainDetailing {
    private static final int CHUNK_SIDE = 16;
    private static final int CHUNK_MAX_LOCAL_COORDINATE = CHUNK_SIDE - 1;
    private static final int TREE_RANDOM_CHANNEL = 0x45424F64;
    private static final int ROCK_VARIANT_CHANNEL = 0x45424F66;
    private static final int CARVER_SOURCE_RANGE = 8;
    private static final AtomicBoolean WARNED_UNSUPPORTED_CARVER = new AtomicBoolean();
    private static final VegetationBand SNOW_BAND =
            new VegetationBand(MountainDetailModel.Surface.SNOW, MountainDetailModel.VegetationZone.NONE);
    private static final List<VegetationBand> VEGETATION_BANDS = List.of(
            new VegetationBand(MountainDetailModel.Surface.SOIL, MountainDetailModel.VegetationZone.LOW_FOREST),
            new VegetationBand(MountainDetailModel.Surface.SOIL, MountainDetailModel.VegetationZone.MONTANE_FOREST),
            SNOW_BAND
    );

    private MountainDetailing() {
    }

    // 调用方只提供原版装饰回调，pre/vanilla/post 顺序由本类固定。
    static void applyBiomeDecoration(
            ChunkGenerator generator,
            WorldGenLevel level,
            ChunkAccess chunk,
            StructureManager structureManager,
            EbottData.Snapshot snapshot
    ) {
        DetailPlan plan = composeBeforeDecoration(generator, level, chunk, snapshot);
        generator.applyBiomeDecoration(level, chunk, structureManager);
        composeAfterDecoration(generator, level, chunk, snapshot, plan);
    }

    private static DetailPlan composeBeforeDecoration(
            ChunkGenerator generator,
            WorldGenLevel level,
            ChunkAccess chunk,
            EbottData.Snapshot snapshot
    ) {
        DetailPlan plan = DetailPlan.create(level, chunk, snapshot.place());
        composeMountainBody(chunk, plan);
        placeHighRockVariants(generator, level, chunk, snapshot.place(), plan);
        placeWatercourses(level, chunk, plan);
        restoreVanillaCarvingMask(chunk, plan);
        applyHighCavePass(generator, level, chunk, snapshot, plan);
        return plan;
    }

    private static void composeMountainBody(
            ChunkAccess chunk,
            DetailPlan plan
    ) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int localZ = 0; localZ < CHUNK_SIDE; localZ++) {
            int worldZ = plan.minZ() + localZ;
            for (int localX = 0; localX < CHUNK_SIDE; localX++) {
                int worldX = plan.minX() + localX;
                int originalY = plan.originalSurfaceY(localX, localZ);
                int foundationY = plan.foundationSurfaceY(localX, localZ);
                int targetY = plan.targetSurfaceY(localX, localZ);
                MountainDetailModel.SurfaceProfile profile = plan.surfaceProfile(localX, localZ);
                boolean submerged = targetY <= originalY;
                boolean ebottSurface = plan.usesEbottSurface(localX, localZ);
                BlockState[] originalCover = ebottSurface || submerged
                        ? null
                        : captureOriginalCover(chunk, cursor, worldX, worldZ, foundationY);
                for (int y = foundationY + 1; y <= targetY; y++) {
                    int depth = targetY - y;
                    chunk.setBlockState(
                            cursor.set(worldX, y, worldZ),
                            submerged ? Blocks.STONE.defaultBlockState()
                                    : ebottSurface ? initialMaterial(depth, profile)
                                    : inheritedMaterial(depth, originalCover)
                    );
                }
            }
        }
    }

    private static void placeHighRockVariants(
            ChunkGenerator generator,
            WorldGenLevel level,
            ChunkAccess chunk,
            PlaceManager.Place place,
            DetailPlan plan
    ) {
        var configuredFeatures = level.registryAccess().lookupOrThrow(Registries.CONFIGURED_FEATURE);
        placeHighRockVariant(generator, level, chunk, place, plan, 0,
                configuredFeatures.getOrThrow(OreFeatures.ORE_GRANITE));
        placeHighRockVariant(generator, level, chunk, place, plan, 1,
                configuredFeatures.getOrThrow(OreFeatures.ORE_DIORITE));
        placeHighRockVariant(generator, level, chunk, place, plan, 2,
                configuredFeatures.getOrThrow(OreFeatures.ORE_ANDESITE));
    }

    private static void placeHighRockVariant(
            ChunkGenerator generator,
            WorldGenLevel level,
            ChunkAccess chunk,
            PlaceManager.Place place,
            DetailPlan plan,
            int variant,
            Holder<ConfiguredFeature<?, ?>> feature
    ) {
        ChunkPos chunkPos = chunk.getPos();
        WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(0L));
        long seed = WorldgenMath.channelSeed(place.mountainSeed(), ROCK_VARIANT_CHANNEL ^ variant);
        random.setLargeFeatureSeed(seed, chunkPos.x, chunkPos.z);
        if (random.nextInt(6) != 0) {
            return;
        }

        int localX = random.nextInt(CHUNK_SIDE);
        int localZ = random.nextInt(CHUNK_SIDE);
        int minY = Math.max(129, plan.originalSurfaceY(localX, localZ) + 1);
        int maxY = plan.targetSurfaceY(localX, localZ) - 4;
        if (minY > maxY) {
            return;
        }

        int worldX = plan.minX() + localX;
        int worldZ = plan.minZ() + localZ;
        int y = minY + random.nextInt(maxY - minY + 1);
        feature.value().place(level, generator, random, new BlockPos(worldX, y, worldZ));
    }

    // 水源只替换侵蚀沟谷表层，让流向继续由既有高程决定。
    private static void placeWatercourses(
            WorldGenLevel level,
            ChunkAccess chunk,
            DetailPlan plan
    ) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int localZ = 0; localZ < CHUNK_SIDE; localZ++) {
            int worldZ = plan.minZ() + localZ;
            for (int localX = 0; localX < CHUNK_SIDE; localX++) {
                int worldX = plan.minX() + localX;
                int surfaceY = plan.targetSurfaceY(localX, localZ);
                if (!plan.usesEbottSurface(localX, localZ)
                        || surfaceY <= plan.originalSurfaceY(localX, localZ)
                        || plan.protectionMask(localX, localZ) < 0.15F) {
                    continue;
                }

                BlockPos top = cursor.set(worldX, surfaceY, worldZ);
                BlockState surface = chunk.getBlockState(top);
                BlockState above = chunk.getBlockState(top.above());
                if (!MountainDetailModel.mayPlaceWatercourse(
                        plan.surfaceProfile(localX, localZ),
                        plan.relativeHeight(localX, localZ),
                        plan.slope(localX, localZ),
                        plan.ridgeMap(localX, localZ),
                        plan.downfall(localX, localZ),
                        !surface.isAir() && surface.getFluidState().isEmpty(),
                        above.isAir()
                )) {
                    continue;
                }

                chunk.setBlockState(top, Blocks.WATER.defaultBlockState());
                level.scheduleTick(top.immutable(), Fluids.WATER, 0);
            }
        }
    }

    private static void composeAfterDecoration(
            ChunkGenerator generator,
            WorldGenLevel level,
            ChunkAccess chunk,
            EbottData.Snapshot snapshot,
            DetailPlan plan
    ) {
        finishMountainSurface(level, chunk, plan);
        placeVegetation(generator, level, chunk, snapshot.place(), plan);
    }

    private static void placeVegetation(
            ChunkGenerator generator,
            WorldGenLevel level,
            ChunkAccess chunk,
            PlaceManager.Place place,
            DetailPlan plan
    ) {
        TreePalette palette = TreePalette.load(level);

        for (boolean fallbackBiome : new boolean[]{false, true}) {
            for (VegetationBand band : VEGETATION_BANDS) {
                placeVegetationGrid(generator, level, chunk, place, plan, fallbackBiome, band, palette);
            }
        }
    }

    private static void placeVegetationGrid(
            ChunkGenerator generator,
            WorldGenLevel level,
            ChunkAccess chunk,
            PlaceManager.Place place,
            DetailPlan plan,
            boolean fallbackBiome,
            VegetationBand band,
            TreePalette palette
    ) {
        MountainDetailModel.SurfaceProfile gridProfile = band.treePatternProfile();
        int grid = MountainDetailModel.treeGrid(gridProfile, fallbackBiome);
        int clusterSize = MountainDetailModel.treeClusterSize(gridProfile);
        int clusterRadius = MountainDetailModel.treeClusterRadius(gridProfile);
        int minCellX = Math.floorDiv(plan.minX() - clusterRadius, grid);
        int maxCellX = Math.floorDiv(plan.minX() + CHUNK_MAX_LOCAL_COORDINATE + clusterRadius, grid);
        int minCellZ = Math.floorDiv(plan.minZ() - clusterRadius, grid);
        int maxCellZ = Math.floorDiv(plan.minZ() + CHUNK_MAX_LOCAL_COORDINATE + clusterRadius, grid);
        int leaflessCellX = Math.floorDiv(place.centerX(), grid);
        int leaflessCellZ = Math.floorDiv(place.centerZ(), grid);
        for (int cellZ = minCellZ; cellZ <= maxCellZ; cellZ++) {
            for (int cellX = minCellX; cellX <= maxCellX; cellX++) {
                boolean leaflessSnowCluster = band.surface() == MountainDetailModel.Surface.SNOW
                        && cellX == leaflessCellX
                        && cellZ == leaflessCellZ;
                for (int member = 0; member < clusterSize; member++) {
                    MountainDetailModel.Candidate candidate = MountainDetailModel.treeClusterCandidate(
                            place.mountainSeed(), cellX, cellZ, grid, member, clusterRadius
                    );
                    if (candidate.worldX() < plan.minX()
                            || candidate.worldX() > plan.minX() + CHUNK_MAX_LOCAL_COORDINATE
                            || candidate.worldZ() < plan.minZ()
                            || candidate.worldZ() > plan.minZ() + CHUNK_MAX_LOCAL_COORDINATE) {
                        continue;
                    }
                    placeTreeCandidate(
                            generator, level, chunk, place, plan, fallbackBiome,
                            band, palette, candidate, leaflessSnowCluster
                    );
                }
            }
        }
    }

    private static void placeTreeCandidate(
            ChunkGenerator generator,
            WorldGenLevel level,
            ChunkAccess chunk,
            PlaceManager.Place place,
            DetailPlan plan,
            boolean fallbackBiome,
            VegetationBand band,
            TreePalette palette,
            MountainDetailModel.Candidate candidate,
            boolean leaflessSnowCluster
    ) {
        int localX = candidate.worldX() - plan.minX();
        int localZ = candidate.worldZ() - plan.minZ();
        if (!plan.usesEbottSurface(localX, localZ)
                || plan.fallbackBiome(localX, localZ) != fallbackBiome) {
            return;
        }
        MountainDetailModel.SurfaceProfile profile = plan.surfaceProfile(localX, localZ);
        int surfaceY = plan.targetSurfaceY(localX, localZ);
        BlockPos root = new BlockPos(candidate.worldX(), surfaceY + 1, candidate.worldZ());
        BlockState rootState = chunk.getBlockState(root);
        boolean rootCanBeReplaced = TreeFeature.validTreePos(level, root);
        boolean hasPlantableSurface = false;
        if (surfaceY > plan.originalSurfaceY(localX, localZ)) {
            if (profile.surface() == MountainDetailModel.Surface.SNOW) {
                hasPlantableSurface = chunk.getBlockState(root.below()).is(Blocks.SNOW_BLOCK)
                        && (rootCanBeReplaced || rootState.is(Blocks.SNOW));
            } else {
                hasPlantableSurface = isSoil(chunk.getBlockState(root.below())) && rootCanBeReplaced;
            }
        }
        boolean guaranteedLeaflessSnowTree = profile.surface() == MountainDetailModel.Surface.SNOW
                && leaflessSnowCluster;
        boolean acceptedByCluster = guaranteedLeaflessSnowTree || MountainDetailModel.acceptsTree(
                candidate,
                profile,
                plan.relativeHeight(localX, localZ),
                plan.temperature(localX, localZ),
                plan.downfall(localX, localZ)
        );
        if (!band.matches(profile)
                || !MountainDetailModel.mayPlaceTree(profile, plan.slope(localX, localZ), hasPlantableSurface)
                || !acceptedByCluster) {
            return;
        }

        ConfiguredFeature<?, ?> treeFeature;
        if (profile.surface() == MountainDetailModel.Surface.SNOW) {
            treeFeature = palette.snowtownSpruce();
        } else {
            double coldShare = MountainDetailModel.coldTreeShare(
                    plan.relativeHeight(localX, localZ),
                    plan.temperature(localX, localZ),
                    plan.downfall(localX, localZ)
            );
            List<ConfiguredFeature<?, ?>> biomeTrees = biomeTreeFeatures(level, root);
            List<ConfiguredFeature<?, ?>> treePool;
            if (candidate.kindRoll() < coldShare) {
                treePool = palette.coldTrees();
            } else {
                treePool = biomeTrees.isEmpty() ? palette.fallbackTrees() : biomeTrees;
            }
            int variantIndex = Math.min(
                    treePool.size() - 1,
                    (int) (candidate.variantRoll() * treePool.size())
            );
            treeFeature = treePool.get(variantIndex);
        }
        long randomSeed = WorldgenMath.channelSeed(place.mountainSeed(), TREE_RANDOM_CHANNEL)
                ^ BlockPos.asLong(candidate.worldX(), surfaceY, candidate.worldZ());
        RandomSource random = new LegacyRandomSource(randomSeed);
        if (guaranteedLeaflessSnowTree) {
            placeSnowtownSpruceLogColumn(level, root, random, palette.snowtownSpruce());
            return;
        }
        boolean removedThinSnow = rootState.is(Blocks.SNOW);
        if (removedThinSnow) {
            chunk.setBlockState(root, Blocks.AIR.defaultBlockState());
        }
        boolean placed = treeFeature.place(level, generator, random, root);
        if (!placed && removedThinSnow && chunk.getBlockState(root).isAir()) {
            chunk.setBlockState(root, rootState);
        }
        if (placed && profile.surface() == MountainDetailModel.Surface.SNOW) {
            placeSnowOnSnowtownSpruceCanopy(level, root);
        }
    }

    // 雪区树簇复用自然散布候选，但只保留树干。
    // 神秘小巧思这一块
    private static void placeSnowtownSpruceLogColumn(
            WorldGenLevel level,
            BlockPos root,
            RandomSource random,
            ConfiguredFeature<?, ?> snowtownSpruce
    ) {
        TreeConfiguration configuration = (TreeConfiguration) snowtownSpruce.config();
        int height = configuration.trunkPlacer.getTreeHeight(random);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        if (level.getBlockState(root).is(Blocks.SNOW)) {
            level.setBlock(root, Blocks.AIR.defaultBlockState(), 19);
        }
        BlockState log = CommonBlocksRegistry.SNOWTOWN_SPRUCE_LOG.get().defaultBlockState();
        for (int offset = 0; offset < height && root.getY() + offset < level.getMaxY(); offset++) {
            level.setBlock(cursor.set(root.getX(), root.getY() + offset, root.getZ()), log, 19);
        }
    }

    private static void placeSnowOnSnowtownSpruceCanopy(WorldGenLevel level, BlockPos root) {
        BlockPos.MutableBlockPos leaf = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos above = new BlockPos.MutableBlockPos();
        BlockState snow = Blocks.SNOW.defaultBlockState();
        for (int x = root.getX() - 4; x <= root.getX() + 4; x++) {
            for (int y = root.getY() + 2; y <= root.getY() + 14; y++) {
                for (int z = root.getZ() - 4; z <= root.getZ() + 4; z++) {
                    leaf.set(x, y, z);
                    if (!level.getBlockState(leaf).is(CommonBlocksRegistry.SNOWTOWN_SPRUCE_LEAVES.get())) {
                        continue;
                    }
                    above.set(x, y + 1, z);
                    if (!level.getBlockState(above).isAir() || !snow.canSurvive(level, above)) {
                        continue;
                    }
                    level.setBlock(above, snow, 2);
                }
            }
        }
    }

    // Biome 只提供树木配置；数量、海拔与耐寒演替仍由 Ebott 模型决定。
    private static List<ConfiguredFeature<?, ?>> biomeTreeFeatures(WorldGenLevel level, BlockPos root) {
        List<HolderSet<PlacedFeature>> steps =
                level.getBiome(root).value().getGenerationSettings().features();
        int vegetationStep = GenerationStep.Decoration.VEGETAL_DECORATION.ordinal();
        if (vegetationStep >= steps.size()) {
            return List.of();
        }
        return steps.get(vegetationStep).stream()
                .flatMap(holder -> holder.value().getFeatures())
                .filter(feature -> feature.feature() == Feature.TREE)
                .distinct()
                .toList();
    }

    private static boolean isSoil(BlockState state) {
        return state.is(Blocks.GRASS_BLOCK)
                || state.is(Blocks.DIRT)
                || state.is(Blocks.COARSE_DIRT)
                || state.is(Blocks.PODZOL)
                || state.is(Blocks.ROOTED_DIRT);
    }

    // 原版装饰后只解析被石质基底屏蔽的高山草甸与冻结雪区，不覆盖低山植被的合法地表改动。
    private static void finishMountainSurface(
            WorldGenLevel level,
            ChunkAccess chunk,
            DetailPlan plan
    ) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int localZ = 0; localZ < CHUNK_SIDE; localZ++) {
            int worldZ = plan.minZ() + localZ;
            for (int localX = 0; localX < CHUNK_SIDE; localX++) {
                int worldX = plan.minX() + localX;
                int surfaceY = plan.targetSurfaceY(localX, localZ);
                if (!plan.usesEbottSurface(localX, localZ)
                        || surfaceY <= plan.originalSurfaceY(localX, localZ)) {
                    continue;
                }
                MountainDetailModel.SurfaceProfile profile = plan.surfaceProfile(localX, localZ);
                BlockPos top = cursor.set(worldX, surfaceY, worldZ).immutable();
                if (chunk.getBlockState(top).is(Blocks.WATER)) {
                    continue;
                }

                if (profile.surface() == MountainDetailModel.Surface.SOIL
                        && !MountainDetailModel.allowsBiomeVegetation(profile)) {
                    int lowestCoverY = Math.max(
                            plan.originalSurfaceY(localX, localZ) + 1,
                            surfaceY - profile.coverDepth() + 1
                    );
                    for (int y = surfaceY; y >= lowestCoverY; y--) {
                        chunk.setBlockState(
                                cursor.set(worldX, y, worldZ),
                                y == surfaceY ? Blocks.GRASS_BLOCK.defaultBlockState() : Blocks.DIRT.defaultBlockState()
                        );
                    }
                    continue;
                }

                if (profile.surface() != MountainDetailModel.Surface.SNOW) {
                    continue;
                }

                int lowestSnowY = Math.max(
                        plan.originalSurfaceY(localX, localZ) + 1,
                        surfaceY - profile.coverDepth() + 1
                );
                for (int y = surfaceY; y >= lowestSnowY; y--) {
                    chunk.setBlockState(cursor.set(worldX, y, worldZ), Blocks.SNOW_BLOCK.defaultBlockState());
                }
                BlockPos snowPos = top.above();
                if (!chunk.getBlockState(snowPos).isAir()) {
                    continue;
                }
                BlockState snow = Blocks.SNOW.defaultBlockState();
                if (snow.canSurvive(level, snowPos)) {
                    chunk.setBlockState(snowPos, snow);
                }
            }
        }
    }

    private static BlockState initialMaterial(int depth, MountainDetailModel.SurfaceProfile profile) {
        if (profile.surface() == MountainDetailModel.Surface.SNOW && depth < profile.coverDepth()) {
            return Blocks.SNOW_BLOCK.defaultBlockState();
        }
        if (MountainDetailModel.allowsBiomeVegetation(profile) && depth < profile.coverDepth()) {
            return depth == 0 ? Blocks.GRASS_BLOCK.defaultBlockState() : Blocks.DIRT.defaultBlockState();
        }
        // 高山草甸在 post 阶段前保持石质，阻止原版 biome 越过林线种树。
        return Blocks.STONE.defaultBlockState();
    }

    private static BlockState[] captureOriginalCover(
            ChunkAccess chunk,
            BlockPos.MutableBlockPos cursor,
            int worldX,
            int worldZ,
            int surfaceY
    ) {
        BlockState[] cover = new BlockState[4];
        for (int depth = 0; depth < cover.length; depth++) {
            cover[depth] = chunk.getBlockState(cursor.set(worldX, surfaceY - depth, worldZ));
        }
        return cover;
    }

    private static BlockState inheritedMaterial(int depth, BlockState[] originalCover) {
        if (depth < originalCover.length) {
            BlockState inherited = originalCover[depth];
            if (!inherited.isAir() && inherited.getFluidState().isEmpty()) {
                return inherited;
            }
        }
        return Blocks.STONE.defaultBlockState();
    }

    private static void restoreVanillaCarvingMask(
            ChunkAccess chunk,
            DetailPlan plan
    ) {
        if (!(chunk instanceof ProtoChunk protoChunk)) {
            return;
        }
        CarvingMask mask = protoChunk.getCarvingMask();
        if (mask == null) {
            return;
        }
        mask.stream(chunk.getPos()).forEach(pos -> {
            int localX = pos.getX() - plan.minX();
            int localZ = pos.getZ() - plan.minZ();
            if (localX < 0 || localX >= CHUNK_SIDE || localZ < 0 || localZ >= CHUNK_SIDE) {
                return;
            }
            if (MountainDetailModel.caveMayWrite(
                    plan.originalSurfaceY(localX, localZ),
                    plan.targetSurfaceY(localX, localZ),
                    pos.getY()
            )) {
                chunk.setBlockState(pos, Blocks.CAVE_AIR.defaultBlockState());
            }
        });
    }

    private static void applyHighCavePass(
            ChunkGenerator generator,
            WorldGenLevel level,
            ChunkAccess chunk,
            EbottData.Snapshot snapshot,
            DetailPlan plan
    ) {
        if (!(generator instanceof NoiseBasedChunkGenerator noiseGenerator)
                || !(chunk instanceof ProtoChunk)) {
            warnUnsupportedCarver(generator, chunk);
            return;
        }

        PlaceManager.Place place = snapshot.place();
        int caveMinY = MountainDetailModel.caveMinY(place.baseY());
        int caveMaxY = MountainDetailModel.caveMaxY(place.summitY());
        if (caveMinY > caveMaxY) {
            return;
        }

        try {
            RandomState randomState = level.getLevel().getChunkSource().randomState();
            NoiseChunk noiseChunk = chunk.getOrCreateNoiseChunk(ignored -> {
                throw new IllegalStateException("feature stage is missing its NoiseChunk");
            });
            CarvingContext context = new CarvingContext(
                    noiseGenerator,
                    level.registryAccess(),
                    chunk.getHeightAccessorForGeneration(),
                    noiseChunk,
                    randomState,
                    noiseGenerator.generatorSettings().value().surfaceRule()
            );
            CaveCarverConfiguration config = new CaveCarverConfiguration(
                    (float) MountainDetailModel.HIGH_CAVE_PROBABILITY,
                    UniformHeight.of(VerticalAnchor.absolute(caveMinY), VerticalAnchor.absolute(caveMaxY)),
                    UniformFloat.of(0.1F, 0.9F),
                    VerticalAnchor.aboveBottom(8),
                    level.registryAccess()
                            .lookupOrThrow(Registries.BLOCK)
                            .getOrThrow(BlockTags.OVERWORLD_CARVER_REPLACEABLES),
                    UniformFloat.of(0.7F, 1.4F),
                    UniformFloat.of(0.8F, 1.3F),
                    UniformFloat.of(-1.0F, -0.4F)
            );
            ConfiguredWorldCarver<CaveCarverConfiguration> carver = WorldCarver.CAVE.configured(config);
            CarvingMask mask = restrictedCaveMask(chunk, plan);
            WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(0L));
            long caveSeed = MountainDetailModel.highCaveSeed(place.mountainSeed());
            ChunkPos target = chunk.getPos();
            for (int offsetZ = -CARVER_SOURCE_RANGE; offsetZ <= CARVER_SOURCE_RANGE; offsetZ++) {
                for (int offsetX = -CARVER_SOURCE_RANGE; offsetX <= CARVER_SOURCE_RANGE; offsetX++) {
                    ChunkPos source = new ChunkPos(target.x + offsetX, target.z + offsetZ);
                    random.setLargeFeatureSeed(caveSeed, source.x, source.z);
                    if (!carver.isStartChunk(random)) {
                        continue;
                    }
                    carver.carve(context, chunk, level::getBiome, random, noiseChunk.aquifer(), source, mask);
                }
            }
        } catch (RuntimeException exception) {
            if (WARNED_UNSUPPORTED_CARVER.compareAndSet(false, true)) {
                MineTale.LOGGER.warn(
                        "Ebott 高海拔 CaveWorldCarver pass 不可用；保留材质细化并跳过增强洞穴。generator={}, chunk={}",
                        generator.getClass().getName(),
                        chunk.getClass().getName(),
                        exception
                );
            }
        }
    }

    private static CarvingMask restrictedCaveMask(
            ChunkAccess chunk,
            DetailPlan plan
    ) {
        CarvingMask mask = new CarvingMask(chunk.getHeight(), chunk.getMinY());
        mask.setAdditionalMask((localX, y, localZ) -> {
            int columnX = localX & 15;
            int columnZ = localZ & 15;
            return !MountainDetailModel.caveMayWrite(
                    plan.originalSurfaceY(columnX, columnZ),
                    plan.targetSurfaceY(columnX, columnZ),
                    y
            );
        });
        return mask;
    }

    private static void warnUnsupportedCarver(
            ChunkGenerator generator,
            ChunkAccess chunk
    ) {
        if (WARNED_UNSUPPORTED_CARVER.compareAndSet(false, true)) {
            MineTale.LOGGER.warn(
                    "Ebott 高海拔 CaveWorldCarver pass 仅支持 NoiseBasedChunkGenerator + ProtoChunk；"
                            + "当前 generator={}, chunk={}，已安全跳过。",
                    generator.getClass().getName(),
                    chunk.getClass().getName()
            );
        }
    }

    // 单区块后期合成只读该冻结列事实。
    private static final class DetailPlan {
        private static final int COLUMNS = CHUNK_SIDE * CHUNK_SIDE;
        private static final AtomicBoolean WARNED_MISSING_SURFACE_SNAPSHOT = new AtomicBoolean();

        private final int minX;
        private final int minZ;
        private final int[] originalSurfaceY = new int[COLUMNS];
        private final int[] foundationSurfaceY = new int[COLUMNS];
        private final int[] targetSurfaceY = new int[COLUMNS];
        private final float[] relativeHeight = new float[COLUMNS];
        private final float[] slope = new float[COLUMNS];
        private final float[] ridgeMap = new float[COLUMNS];
        private final float[] protectionMask = new float[COLUMNS];
        private final boolean[] ebottSurface = new boolean[COLUMNS];
        private final float[] temperature = new float[COLUMNS];
        private final float[] downfall = new float[COLUMNS];
        private final boolean[] fallbackBiome = new boolean[COLUMNS];
        private final MountainDetailModel.SurfaceProfile[] surfaceProfile =
                new MountainDetailModel.SurfaceProfile[COLUMNS];

        private DetailPlan(int minX, int minZ) {
            this.minX = minX;
            this.minZ = minZ;
        }

        private static DetailPlan create(
                WorldGenLevel level,
                ChunkAccess chunk,
                PlaceManager.Place place
        ) {
            int minX = chunk.getPos().getMinBlockX();
            int minZ = chunk.getPos().getMinBlockZ();
            DetailPlan plan = new DetailPlan(minX, minZ);
            SurfaceSnapshot frozenSnapshot = chunk.removeData(EbottMountainAttachments.SURFACE_SNAPSHOT);
            if (frozenSnapshot == null && WARNED_MISSING_SURFACE_SNAPSHOT.compareAndSet(false, true)) {
                MineTale.LOGGER.warn(
                        "[Ebott] Feature 阶段缺少持久化的 CARVERS 地表快照；"
                                + "本次使用当前区块高度图兼容，不重算噪声列。chunk={}",
                        chunk.getPos()
                );
            }

            // CARVERS 高度不受邻区块 Feature 顺序影响；缺少附件时只读 O(1) 高度图，不重算噪声。
            for (int localZ = 0; localZ < CHUNK_SIDE; localZ++) {
                for (int localX = 0; localX < CHUNK_SIDE; localX++) {
                    int index = index(localX, localZ);
                    plan.originalSurfaceY[index] = frozenSnapshot != null
                            ? frozenSnapshot.surfaceY(localX, localZ)
                            : chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, localX, localZ);
                    plan.foundationSurfaceY[index] = frozenSnapshot != null
                            ? frozenSnapshot.oceanFloorY(localX, localZ)
                            : chunk.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, localX, localZ);
                }
            }

            MountainMath.ChunkPlan mountainPlan = MountainMath.planChunk(
                    place,
                    minX,
                    minZ,
                    level.getMaxY(),
                    (worldX, worldZ) -> plan.foundationSurfaceY(worldX - minX, worldZ - minZ)
            );
            for (int localZ = 0; localZ < CHUNK_SIDE; localZ++) {
                for (int localX = 0; localX < CHUNK_SIDE; localX++) {
                    int index = index(localX, localZ);
                    int targetY = mountainPlan.targetSurfaceY(localX, localZ);
                    plan.targetSurfaceY[index] = targetY;
                    plan.relativeHeight[index] = (float) MountainDetailModel.relativeHeight(
                            place.baseY(),
                            place.summitY(),
                            targetY
                    );
                    plan.slope[index] = mountainPlan.slope(localX, localZ);
                    plan.ridgeMap[index] = mountainPlan.ridgeMap(localX, localZ);
                    plan.protectionMask[index] = mountainPlan.protectionMask(localX, localZ);
                    float footprintInfluence = mountainPlan.footprintInfluence(localX, localZ);
                    plan.ebottSurface[index] = MountainDetailModel.usesEbottSurface(
                            place.mountainSeed(),
                            minX + localX,
                            minZ + localZ,
                            footprintInfluence
                    );
                    Holder<Biome> biome = chunk.getNoiseBiome(
                            QuartPos.fromBlock(minX + localX),
                            QuartPos.fromBlock(targetY),
                            QuartPos.fromBlock(minZ + localZ)
                    );
                    boolean fallback = biome.is(EbottMountainTags.FALLBACK_VEGETATION_BIOMES);
                    Biome.ClimateSettings settings = biome.value().getModifiedClimateSettings();
                    MountainDetailModel.Climate climate = MountainDetailModel.effectiveClimate(
                            settings.temperature(),
                            settings.downfall(),
                            fallback
                    );
                    plan.temperature[index] = (float) climate.temperature();
                    plan.downfall[index] = (float) climate.downfall();
                    plan.fallbackBiome[index] = fallback;
                    plan.surfaceProfile[index] = MountainDetailModel.classifySurface(
                            place.mountainSeed(),
                            minX + localX,
                            minZ + localZ,
                            plan.relativeHeight[index],
                            plan.slope[index],
                            plan.ridgeMap[index],
                            climate.temperature(),
                            climate.downfall()
                    );
                }
            }
            return plan;
        }

        private int minX() {
            return minX;
        }

        private int minZ() {
            return minZ;
        }

        private int originalSurfaceY(int localX, int localZ) {
            return originalSurfaceY[index(localX, localZ)];
        }

        private int foundationSurfaceY(int localX, int localZ) {
            return foundationSurfaceY[index(localX, localZ)];
        }

        private int targetSurfaceY(int localX, int localZ) {
            return targetSurfaceY[index(localX, localZ)];
        }

        private float relativeHeight(int localX, int localZ) {
            return relativeHeight[index(localX, localZ)];
        }

        private float slope(int localX, int localZ) {
            return slope[index(localX, localZ)];
        }

        private float ridgeMap(int localX, int localZ) {
            return ridgeMap[index(localX, localZ)];
        }

        private float protectionMask(int localX, int localZ) {
            return protectionMask[index(localX, localZ)];
        }

        private boolean usesEbottSurface(int localX, int localZ) {
            return ebottSurface[index(localX, localZ)];
        }

        private float temperature(int localX, int localZ) {
            return temperature[index(localX, localZ)];
        }

        private float downfall(int localX, int localZ) {
            return downfall[index(localX, localZ)];
        }

        private boolean fallbackBiome(int localX, int localZ) {
            return fallbackBiome[index(localX, localZ)];
        }

        private MountainDetailModel.SurfaceProfile surfaceProfile(int localX, int localZ) {
            return surfaceProfile[index(localX, localZ)];
        }

        private static int index(int localX, int localZ) {
            if ((localX | localZ) < 0 || localX >= CHUNK_SIDE || localZ >= CHUNK_SIDE) {
                throw new IndexOutOfBoundsException("column outside chunk: " + localX + ", " + localZ);
            }
            return localZ * CHUNK_SIDE + localX;
        }
    }

    private record VegetationBand(
            MountainDetailModel.Surface surface,
            MountainDetailModel.VegetationZone zone
    ) {
        private boolean matches(MountainDetailModel.SurfaceProfile profile) {
            return profile.surface() == surface && profile.vegetation() == zone;
        }

        private MountainDetailModel.SurfaceProfile treePatternProfile() {
            double snowLine = surface == MountainDetailModel.Surface.SNOW ? 0.78D : 1.0D;
            return new MountainDetailModel.SurfaceProfile(surface, zone, 1, snowLine);
        }
    }

    private record TreePalette(
            List<ConfiguredFeature<?, ?>> fallbackTrees,
            List<ConfiguredFeature<?, ?>> coldTrees,
            ConfiguredFeature<?, ?> snowtownSpruce
    ) {
        private static TreePalette load(WorldGenLevel level) {
            var configuredFeatures = level.registryAccess().lookupOrThrow(Registries.CONFIGURED_FEATURE);
            return new TreePalette(
                    List.of(
                            configuredFeatures.getOrThrow(TreeFeatures.OAK).value(),
                            configuredFeatures.getOrThrow(TreeFeatures.BIRCH).value(),
                            configuredFeatures.getOrThrow(TreeFeatures.FANCY_OAK).value()
                    ),
                    List.of(
                            configuredFeatures.getOrThrow(TreeFeatures.SPRUCE).value(),
                            configuredFeatures.getOrThrow(TreeFeatures.PINE).value()
                    ),
                    configuredFeatures.getOrThrow(ModWorldgenKeys.SNOWTOWN_SPRUCE).value()
            );
        }
    }
}
