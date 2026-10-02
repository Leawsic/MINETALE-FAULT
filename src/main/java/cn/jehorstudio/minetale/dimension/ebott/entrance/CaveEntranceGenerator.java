package cn.jehorstudio.minetale.dimension.ebott.entrance;

import cn.jehorstudio.minetale.MineTale;
import cn.jehorstudio.minetale.dimension.ebott.EbottData;
import cn.jehorstudio.minetale.dimension.ebott.PlaceManager;
import cn.jehorstudio.minetale.dimension.ebott.mountain.MountainMath;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.ConstantFloat;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.JigsawBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.chunk.CarvingMask;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.VerticalAnchor;
import net.minecraft.world.level.levelgen.carver.CarvingContext;
import net.minecraft.world.level.levelgen.carver.CaveCarverConfiguration;
import net.minecraft.world.level.levelgen.carver.CaveWorldCarver;
import net.minecraft.world.level.levelgen.heightproviders.ConstantHeight;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.AlwaysTrueTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockMatchTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.JigsawReplacementProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.ProcessorRule;
import net.minecraft.world.level.levelgen.structure.templatesystem.RuleProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

// Placement 汇集资产锚点、连接口、旋转、埋深与山侧洞口，只在主线程解析并持久化一次
// worldgen 线程只能消费该冻结事实
public final class CaveEntranceGenerator {
    public static final ResourceLocation TEMPLATE_ID = ResourceLocation.fromNamespaceAndPath(
            MineTale.MODID,
            "cave_entrance"
    );

    private static final int CURRENT_GENERATION_VERSION = 3;
    private static final Vec3i EXPECTED_SIZE = new Vec3i(60, 44, 66);
    private static final int ASSET_COVER_DEPTH = 1;
    private static final int PLACEMENT_DEPTH_OFFSET = 70;
    private static final int MIN_TUNNEL_LENGTH = 20;
    private static final int MIN_MOUTH_ROOF_COVER = 5;
    private static final int MAX_MOUTH_ROOF_COVER = 12;
    private static final int IDEAL_MOUTH_ROOF_COVER = 6;
    static final int MOUTH_OUTWARD_LENGTH = 18;
    private static final double CONNECTOR_HORIZONTAL_RADIUS = 1.55;
    private static final double CONNECTOR_VERTICAL_RADIUS = 1.55;
    private static final double TUNNEL_HORIZONTAL_RADIUS = 4.75;
    private static final double TUNNEL_VERTICAL_RADIUS = 3.60;
    static final double MOUTH_HORIZONTAL_RADIUS = 5.75;
    static final double MOUTH_VERTICAL_RADIUS = 4.25;
    private static final AtomicBoolean WARNED_CARVER_FALLBACK = new AtomicBoolean();
    private static final double[] ROUTE_ANGLE_OFFSETS = {
            0.0,
            -StrictMath.PI / 12.0,
            StrictMath.PI / 12.0,
            -StrictMath.PI / 6.0,
            StrictMath.PI / 6.0,
            -StrictMath.PI / 4.0,
            StrictMath.PI / 4.0
    };

    private CaveEntranceGenerator() {
    }

    private static ProcessorRule markerRule(Block marker) {
        return new ProcessorRule(
                new BlockMatchTest(marker),
                AlwaysTrueTest.INSTANCE,
                Blocks.STRUCTURE_VOID.defaultBlockState()
        );
    }

    private static RuleProcessor markerProcessor() {
        return new RuleProcessor(List.of(
                markerRule(Blocks.COMMAND_BLOCK),
                markerRule(Blocks.CHAIN_COMMAND_BLOCK),
                markerRule(Blocks.REPEATING_COMMAND_BLOCK)
        ));
    }

    // 当前区块先放资产再挖通道；主世界竖井必须在该方法之后覆盖写入。
    public static void writeChunk(
            ChunkGenerator generator,
            WorldGenLevel level,
            ChunkAccess chunk,
            EbottData.Snapshot snapshot
    ) {
        Placement placement = snapshot.caveEntrance();
        BoundingBox structureBounds = structureBounds(placement);
        TunnelBounds tunnelBounds = TunnelBounds.of(placement);
        if (!intersectsChunk(structureBounds, chunk.getPos())
                && !tunnelBounds.intersects(chunk.getPos())) {
            return;
        }
        if (intersectsChunk(structureBounds, chunk.getPos())) {
            StructureTemplate template = requireTemplate(level.getLevel());
            placeTemplate(
                    level,
                    chunk,
                    template,
                    placement,
                    level.getMinY(),
                    level.getMaxY()
            );
        }

        if (!tunnelBounds.intersects(chunk.getPos())) {
            return;
        }
        if (generator instanceof NoiseBasedChunkGenerator noiseGenerator
                && chunk instanceof ProtoChunk) {
            try {
                carveVanillaTunnel(noiseGenerator, level, chunk, placement);
            } catch (RuntimeException exception) {
                warnCarverFallback(generator, chunk, exception);
            }
        } else {
            warnCarverFallback(generator, chunk, null);
        }
        // Carver 只处理可替换标签，随后强制清空同一几何，防止结构方块堵塞通路。
        forceClearTunnel(chunk, placement);
        openEndpoints(chunk, placement);
        CaveEntranceDecoration.decorate(chunk, placement);
    }

    // 主线程修复强制加载覆盖区块，并按同一 Placement 幂等重写。
    public static int repairGenerated(ServerLevel level, EbottData.Snapshot snapshot) {
        Placement placement = snapshot.caveEntrance();
        StructureTemplate template = requireTemplate(level);
        BoundingBox structureBounds = structureBounds(placement);
        TunnelBounds tunnelBounds = TunnelBounds.of(placement);
        int minChunkX = Math.floorDiv(Math.min(structureBounds.minX(), tunnelBounds.minX()), 16);
        int maxChunkX = Math.floorDiv(Math.max(structureBounds.maxX(), tunnelBounds.maxX()), 16);
        int minChunkZ = Math.floorDiv(Math.min(structureBounds.minZ(), tunnelBounds.minZ()), 16);
        int maxChunkZ = Math.floorDiv(Math.max(structureBounds.maxZ(), tunnelBounds.maxZ()), 16);
        int repaired = 0;
        for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                ChunkAccess chunk = level.getChunk(chunkX, chunkZ);
                if (intersectsChunk(structureBounds, chunk.getPos())) {
                    placeTemplate(
                            level,
                            chunk,
                            template,
                            placement,
                            level.getMinY(),
                            level.getMaxY()
                    );
                }
                if (tunnelBounds.intersects(chunk.getPos())) {
                    forceClearTunnel(chunk, placement);
                    openEndpoints(chunk, placement);
                    CaveEntranceDecoration.decorate(chunk, placement);
                }
                repaired++;
            }
        }
        return repaired;
    }

    private static StructureTemplate requireTemplate(ServerLevel level) {
        StructureTemplate template = level.getStructureManager()
                .get(TEMPLATE_ID)
                .orElseThrow(() -> new IllegalStateException("missing structure " + TEMPLATE_ID));
        if (!template.getSize().equals(EXPECTED_SIZE)) {
            throw new IllegalStateException(
                    "invalid cave entrance size: expected=" + EXPECTED_SIZE
                            + ", actual=" + template.getSize()
            );
        }
        return template;
    }

    private static BoundingBox structureBounds(Placement placement) {
        BlockPos first = placement.origin().offset(transform(BlockPos.ZERO, placement.rotation()));
        BlockPos opposite = placement.origin().offset(transform(
                new BlockPos(
                        EXPECTED_SIZE.getX() - 1,
                        EXPECTED_SIZE.getY() - 1,
                        EXPECTED_SIZE.getZ() - 1
                ),
                placement.rotation()
        ));
        return BoundingBox.fromCorners(first, opposite);
    }

    private static void placeTemplate(
            ServerLevelAccessor level,
            ChunkAccess chunk,
            StructureTemplate template,
            Placement placement,
            int minY,
            int maxY
    ) {
        ChunkPos chunkPos = chunk.getPos();
        StructurePlaceSettings settings = new StructurePlaceSettings()
                .setRotation(placement.rotation())
                .setIgnoreEntities(true)
                .setKnownShape(true)
                .setBoundingBox(new BoundingBox(
                        chunkPos.getMinBlockX(),
                        minY,
                        chunkPos.getMinBlockZ(),
                        chunkPos.getMaxBlockX(),
                        maxY,
                        chunkPos.getMaxBlockZ()
                ))
                .addProcessor(markerProcessor())
                .addProcessor(JigsawReplacementProcessor.INSTANCE);
        boolean placed = template.placeInWorld(
                level,
                placement.origin(),
                placement.origin(),
                settings,
                RandomSource.create(Mth.getSeed(placement.origin())),
                Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE
        );
        if (!placed) {
            throw new IllegalStateException("洞窟入口无法放入区块 " + chunkPos);
        }
    }

    private static void carveVanillaTunnel(
            NoiseBasedChunkGenerator generator,
            WorldGenLevel level,
            ChunkAccess chunk,
            Placement placement
    ) {
        RandomState randomState = level.getLevel().getChunkSource().randomState();
        NoiseChunk noiseChunk = chunk.getOrCreateNoiseChunk(ignored -> {
            throw new IllegalStateException("Feature 阶段缺少 NoiseChunk");
        });
        CarvingContext context = new CarvingContext(
                generator,
                level.registryAccess(),
                chunk.getHeightAccessorForGeneration(),
                noiseChunk,
                randomState,
                generator.generatorSettings().value().surfaceRule()
        );
        CaveCarverConfiguration configuration = new CaveCarverConfiguration(
                1.0F,
                ConstantHeight.of(VerticalAnchor.absolute(placement.connectorY())),
                ConstantFloat.of(1.0F),
                VerticalAnchor.bottom(),
                level.registryAccess()
                        .lookupOrThrow(Registries.BLOCK)
                        .getOrThrow(BlockTags.OVERWORLD_CARVER_REPLACEABLES),
                ConstantFloat.of(1.0F),
                ConstantFloat.of(1.0F),
                ConstantFloat.of(-1.0F)
        );
        CarvingMask mask = new CarvingMask(chunk.getHeight(), chunk.getMinY());
        TunnelCurve curve = tunnelCurve(placement);
        for (int step = 0; step <= curve.steps(); step++) {
            double progress = (double) step / curve.steps();
            CurvePoint point = curve.sample(progress);
            double horizontalRadius = tunnelHorizontalRadius(progress);
            double verticalRadius = tunnelVerticalRadius(progress);
            ConnectorCarverHolder.INSTANCE.carvePoint(
                    context,
                    configuration,
                    chunk,
                    level::getBiome,
                    noiseChunk.aquifer(),
                    point,
                    horizontalRadius,
                    verticalRadius,
                    mask
            );
        }
    }

    private static void forceClearTunnel(ChunkAccess chunk, Placement placement) {
        TunnelCurve curve = tunnelCurve(placement);
        for (int step = 0; step <= curve.steps(); step++) {
            double progress = (double) step / curve.steps();
            CurvePoint point = curve.sample(progress);
            clearTunnelEllipsoid(
                    chunk,
                    point,
                    tunnelHorizontalRadius(progress),
                    tunnelVerticalRadius(progress)
            );
        }
    }

    private static void clearTunnelEllipsoid(
            ChunkAccess chunk,
            CurvePoint center,
            double horizontalRadius,
            double verticalRadius
    ) {
        ChunkPos chunkPos = chunk.getPos();
        int minX = Math.max(chunkPos.getMinBlockX(), Mth.floor(center.x() - horizontalRadius) - 1);
        int maxX = Math.min(chunkPos.getMaxBlockX(), Mth.floor(center.x() + horizontalRadius) + 1);
        int minY = Math.max(chunk.getMinY(), Mth.floor(center.y() - verticalRadius) - 1);
        int maxY = Math.min(chunk.getMaxY(), Mth.floor(center.y() + verticalRadius) + 1);
        int minZ = Math.max(chunkPos.getMinBlockZ(), Mth.floor(center.z() - horizontalRadius) - 1);
        int maxZ = Math.min(chunkPos.getMaxBlockZ(), Mth.floor(center.z() + horizontalRadius) + 1);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = minX; x <= maxX; x++) {
            double relativeX = (x + 0.5 - center.x()) / horizontalRadius;
            for (int z = minZ; z <= maxZ; z++) {
                double relativeZ = (z + 0.5 - center.z()) / horizontalRadius;
                if (relativeX * relativeX + relativeZ * relativeZ >= 1.0) {
                    continue;
                }
                for (int y = minY; y <= maxY; y++) {
                    double relativeY = (y - 0.5 - center.y()) / verticalRadius;
                    if (relativeX * relativeX + relativeY * relativeY
                            + relativeZ * relativeZ >= 1.0) {
                        continue;
                    }
                    cursor.set(x, y, z);
                    chunk.removeBlockEntity(cursor);
                    chunk.setBlockState(cursor, Blocks.CAVE_AIR.defaultBlockState());
                }
            }
        }
    }

    private static void openEndpoints(ChunkAccess chunk, Placement placement) {
        Direction facing = placement.entranceFacing();
        Direction lateral = facing.getClockWise();
        for (int depth = 0; depth <= 3; depth++) {
            BlockPos center = placement.connector().relative(facing, depth);
            openPlane(chunk, center, lateral);
        }

        CaveEntranceMouth.open(chunk, placement);
    }

    private static void openPlane(
            ChunkAccess chunk,
            BlockPos center,
            Direction lateral
    ) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int side = -1; side <= 1; side++) {
            for (int vertical = -1; vertical <= 1; vertical++) {
                setCaveAirIfInside(
                        chunk,
                        cursor.set(
                                center.getX() + lateral.getStepX() * side,
                                center.getY() + vertical,
                                center.getZ() + lateral.getStepZ() * side
                        )
                );
            }
        }
    }

    private static void setCaveAirIfInside(ChunkAccess chunk, BlockPos position) {
        ChunkPos chunkPos = chunk.getPos();
        if (position.getX() >= chunkPos.getMinBlockX()
                && position.getX() <= chunkPos.getMaxBlockX()
                && position.getZ() >= chunkPos.getMinBlockZ()
                && position.getZ() <= chunkPos.getMaxBlockZ()
                && position.getY() >= chunk.getMinY()
                && position.getY() <= chunk.getMaxY()) {
            chunk.removeBlockEntity(position);
            chunk.setBlockState(position, Blocks.CAVE_AIR.defaultBlockState());
        }
    }

    static double tunnelHorizontalRadius(double progress) {
        double connectorBlend = smoothStep(Mth.clamp(progress / 0.18, 0.0, 1.0));
        double mouthBlend = smoothStep(Mth.clamp((progress - 0.82) / 0.18, 0.0, 1.0));
        return Mth.lerp(connectorBlend, CONNECTOR_HORIZONTAL_RADIUS, TUNNEL_HORIZONTAL_RADIUS)
                + mouthBlend * (MOUTH_HORIZONTAL_RADIUS - TUNNEL_HORIZONTAL_RADIUS);
    }

    static double tunnelVerticalRadius(double progress) {
        double connectorBlend = smoothStep(Mth.clamp(progress / 0.18, 0.0, 1.0));
        double mouthBlend = smoothStep(Mth.clamp((progress - 0.82) / 0.18, 0.0, 1.0));
        return Mth.lerp(connectorBlend, CONNECTOR_VERTICAL_RADIUS, TUNNEL_VERTICAL_RADIUS)
                + mouthBlend * (MOUTH_VERTICAL_RADIUS - TUNNEL_VERTICAL_RADIUS);
    }

    private static double smoothStep(double value) {
        return value * value * (3.0 - 2.0 * value);
    }

    private static boolean intersectsChunk(BoundingBox bounds, ChunkPos chunk) {
        return bounds.maxX() >= chunk.getMinBlockX()
                && bounds.minX() <= chunk.getMaxBlockX()
                && bounds.maxZ() >= chunk.getMinBlockZ()
                && bounds.minZ() <= chunk.getMaxBlockZ();
    }

    private static void warnCarverFallback(
            ChunkGenerator generator,
            ChunkAccess chunk,
            RuntimeException exception
    ) {
        if (!WARNED_CARVER_FALLBACK.compareAndSet(false, true)) {
            return;
        }
        if (exception == null) {
            MineTale.LOGGER.warn(
                    "Ebott 洞窟连接通道无法使用 CaveWorldCarver；改用强制椭球清障。generator={}, chunk={}",
                    generator.getClass().getName(),
                    chunk.getClass().getName()
            );
        } else {
            MineTale.LOGGER.warn(
                    "Ebott 洞窟连接通道的 CaveWorldCarver 执行失败；改用强制椭球清障。generator={}, chunk={}",
                    generator.getClass().getName(),
                    chunk.getClass().getName(),
                    exception
            );
        }
    }

    // 仅主线程解析一次
    public static Placement resolve(ServerLevel level, PlaceManager.Place place) {
        StructureTemplate template = level.getStructureManager()
                .get(TEMPLATE_ID)
                .orElseThrow(() -> new IllegalStateException("missing structure " + TEMPLATE_ID));
        AssetMetadata asset = AssetMetadata.read(template);
        return select(
                place,
                asset,
                level.getMinY(),
                level.getMaxY(),
                selectionSurface(place, level.getMaxY())
        );
    }

    static SurfaceSampler selectionSurface(PlaceManager.Place place, int maxY) {
        Map<Long, Integer> surfaceCache = new HashMap<>();
        return (worldX, worldZ) -> surfaceCache.computeIfAbsent(
                columnKey(worldX, worldZ),
                ignored -> MountainMath.sample(
                        place,
                        worldX,
                        worldZ,
                        place.baseY(),
                        maxY
                ).targetSurfaceY()
        );
    }

    static Placement select(
            PlaceManager.Place place,
            AssetMetadata asset,
            int minY,
            int maxY,
            SurfaceSampler surface
    ) {
        Objects.requireNonNull(place, "place");
        Objects.requireNonNull(asset, "asset");
        Objects.requireNonNull(surface, "surface");
        if (maxY < minY) {
            throw new IllegalArgumentException("maxY 不能低于 minY");
        }

        List<RotatedAsset> rotations = new ArrayList<>(4);
        int highestOriginY = Integer.MIN_VALUE;
        for (int quarterTurns = 0; quarterTurns < 4; quarterTurns++) {
            Rotation rotation = rotation(quarterTurns);
            BlockPos transformedCenter = transform(asset.shaftCenter(), rotation);
            int originX = place.centerX() - transformedCenter.getX();
            int originZ = place.centerZ() - transformedCenter.getZ();
            int maximumOriginY = maxY - asset.size().getY() + 1;
            for (ColumnTop column : asset.columnTops()) {
                BlockPos transformed = transform(new BlockPos(column.localX(), 0, column.localZ()), rotation);
                int targetSurfaceY = surface.surfaceY(
                        originX + transformed.getX(),
                        originZ + transformed.getZ()
                );
                maximumOriginY = Math.min(
                        maximumOriginY,
                        targetSurfaceY - ASSET_COVER_DEPTH - column.topY()
                );
            }
            rotations.add(new RotatedAsset(quarterTurns, originX, originZ, maximumOriginY));
            highestOriginY = Math.max(highestOriginY, maximumOriginY);
        }

        int originY = highestOriginY - PLACEMENT_DEPTH_OFFSET;
        if (originY < minY) {
            throw new IllegalStateException("洞窟入口下沉 70 格后超出世界构建高度");
        }
        List<PlacementCandidate> candidates = new ArrayList<>();
        for (RotatedAsset rotated : rotations) {
            if (originY > rotated.maximumOriginY()) {
                continue;
            }
            Rotation rotation = rotation(rotated.quarterTurns());
            BlockPos origin = new BlockPos(rotated.originX(), originY, rotated.originZ());
            BlockPos connector = origin.offset(transform(asset.connector(), rotation));
            Direction facing = rotation.rotate(asset.connectorFacing());
            int maxTunnelLength = place.mountainOuterRadius() + Mth.ceil(StrictMath.hypot(
                    connector.getX() - place.centerX(),
                    connector.getZ() - place.centerZ()
            ));
            Route route = selectRoute(connector, facing, maxTunnelLength, surface);
            if (route != null) {
                candidates.add(new PlacementCandidate(
                        rotated.quarterTurns(), origin, connector, facing, route
                ));
            }
        }
        if (!candidates.isEmpty()) {
            PlacementCandidate chosen = candidates.stream()
                    .min(Comparator.comparingDouble(candidate -> candidate.route().score()))
                    .orElseThrow();
            int shaftOpeningY = originY + asset.shaftCenter().getY();
            return new Placement(
                    CURRENT_GENERATION_VERSION,
                    chosen.origin().getX(), chosen.origin().getY(), chosen.origin().getZ(),
                    chosen.quarterTurns(), shaftOpeningY,
                    chosen.connector().getX(), chosen.connector().getY(), chosen.connector().getZ(),
                    chosen.facing().get2DDataValue(),
                    chosen.route().mouth().getX(), chosen.route().mouth().getY(), chosen.route().mouth().getZ()
            );
        }
        throw new IllegalStateException("Ebott 山体内找不到完整埋藏且可连通的洞窟入口位置");
    }

    private static Route selectRoute(
            BlockPos connector,
            Direction facing,
            int maxTunnelLength,
            SurfaceSampler surface
    ) {
        double facingAngle = StrictMath.atan2(facing.getStepZ(), facing.getStepX());
        Route best = null;
        for (double angleOffset : ROUTE_ANGLE_OFFSETS) {
            double angle = facingAngle + angleOffset;
            double directionX = StrictMath.cos(angle);
            double directionZ = StrictMath.sin(angle);
            for (int distance = MIN_TUNNEL_LENGTH; distance <= maxTunnelLength; distance++) {
                int mouthX = connector.getX() + (int) StrictMath.round(directionX * distance);
                int mouthZ = connector.getZ() + (int) StrictMath.round(directionZ * distance);
                int mouthSurfaceY = surface.surfaceY(mouthX, mouthZ);
                int roofCover = mouthSurfaceY - connector.getY();
                if (roofCover < MIN_MOUTH_ROOF_COVER || roofCover > MAX_MOUTH_ROOF_COVER) {
                    continue;
                }
                if (!hasStableMouth(
                        mouthX,
                        mouthZ,
                        connector.getY(),
                        directionX,
                        directionZ,
                        surface
                )) {
                    continue;
                }
                BlockPos mouth = new BlockPos(mouthX, connector.getY(), mouthZ);
                if (!pathRemainsBuried(connector, facing, mouth, surface)) {
                    continue;
                }
                double score = distance
                        + StrictMath.abs(roofCover - IDEAL_MOUTH_ROOF_COVER) * 12.0
                        + StrictMath.abs(angleOffset) * 6.0;
                Route candidate = new Route(mouth, score);
                if (best == null || candidate.score() < best.score()) {
                    best = candidate;
                }
                break;
            }
        }
        return best;
    }

    private static boolean hasStableMouth(
            int mouthX,
            int mouthZ,
            int tunnelY,
            double directionX,
            double directionZ,
            SurfaceSampler surface
    ) {
        double lateralX = -directionZ;
        double lateralZ = directionX;
        for (int lateral = -5; lateral <= 5; lateral++) {
            int x = mouthX + (int) StrictMath.round(lateralX * lateral);
            int z = mouthZ + (int) StrictMath.round(lateralZ * lateral);
            int surfaceY = surface.surfaceY(x, z);
            if (surfaceY < tunnelY + MIN_MOUTH_ROOF_COVER - 1
                    || surfaceY > tunnelY + MAX_MOUTH_ROOF_COVER) {
                return false;
            }
        }
        for (int outward = MOUTH_OUTWARD_LENGTH; outward <= MOUTH_OUTWARD_LENGTH + 6; outward += 3) {
            int x = mouthX + (int) StrictMath.round(directionX * outward);
            int z = mouthZ + (int) StrictMath.round(directionZ * outward);
            if (surface.surfaceY(x, z) > tunnelY - 1) {
                return false;
            }
        }
        return true;
    }

    private static boolean pathRemainsBuried(
            BlockPos connector,
            Direction facing,
            BlockPos mouth,
            SurfaceSampler surface
    ) {
        TunnelCurve curve = TunnelCurve.windingBetween(connector, facing, mouth);
        for (double progress = 0.05; progress <= 0.78; progress += 0.05) {
            CurvePoint point = curve.sample(progress);
            CurvePoint before = curve.sample(Math.max(0.0, progress - 0.01));
            CurvePoint after = curve.sample(Math.min(1.0, progress + 0.01));
            double tangentX = after.x() - before.x();
            double tangentZ = after.z() - before.z();
            double tangentLength = StrictMath.hypot(tangentX, tangentZ);
            if (tangentLength < 1.0E-9) {
                tangentX = facing.getStepX();
                tangentZ = facing.getStepZ();
                tangentLength = 1.0;
            }
            double lateralX = -tangentZ / tangentLength;
            double lateralZ = tangentX / tangentLength;
            int lateralRadius = Mth.ceil(tunnelHorizontalRadius(progress));
            int requiredSurfaceY = connector.getY()
                    + Mth.ceil(tunnelVerticalRadius(progress)) + 1;
            for (int side = -1; side <= 1; side++) {
                if (surface.surfaceY(
                        (int) StrictMath.round(point.x() + lateralX * lateralRadius * side),
                        (int) StrictMath.round(point.z() + lateralZ * lateralRadius * side)
                ) < requiredSurfaceY) {
                    return false;
                }
            }
        }
        return true;
    }

    static TunnelCurve tunnelCurve(Placement placement) {
        return placement.generationVersion() >= 3
                ? TunnelCurve.windingBetween(
                placement.connector(),
                placement.entranceFacing(),
                placement.mouth()
        )
                : TunnelCurve.straightBetween(
                placement.connector(),
                placement.entranceFacing(),
                placement.mouth()
        );
    }

    private static BlockPos transform(BlockPos position, Rotation rotation) {
        return StructureTemplate.transform(position, Mirror.NONE, rotation, BlockPos.ZERO);
    }

    private static Rotation rotation(int quarterTurns) {
        return switch (quarterTurns) {
            case 0 -> Rotation.NONE;
            case 1 -> Rotation.CLOCKWISE_90;
            case 2 -> Rotation.CLOCKWISE_180;
            case 3 -> Rotation.COUNTERCLOCKWISE_90;
            default -> throw new IllegalArgumentException("quarterTurns 必须位于 [0, 3]");
        };
    }

    private static long columnKey(int x, int z) {
        return (long) x << 32 ^ z & 0xFFFFFFFFL;
    }

    // 该记录是持久化的唯一洞窟放置事实。
    public record Placement(
            int generationVersion,
            int originX,
            int originY,
            int originZ,
            int quarterTurns,
            int shaftOpeningY,
            int connectorX,
            int connectorY,
            int connectorZ,
            int entranceFacing2D,
            int mouthX,
            int mouthY,
            int mouthZ
    ) {
        public static final Codec<Placement> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.INT.fieldOf("generation_version").forGetter(Placement::generationVersion),
                Codec.INT.fieldOf("origin_x").forGetter(Placement::originX),
                Codec.INT.fieldOf("origin_y").forGetter(Placement::originY),
                Codec.INT.fieldOf("origin_z").forGetter(Placement::originZ),
                Codec.INT.fieldOf("quarter_turns").forGetter(Placement::quarterTurns),
                Codec.INT.fieldOf("shaft_opening_y").forGetter(Placement::shaftOpeningY),
                Codec.INT.fieldOf("connector_x").forGetter(Placement::connectorX),
                Codec.INT.fieldOf("connector_y").forGetter(Placement::connectorY),
                Codec.INT.fieldOf("connector_z").forGetter(Placement::connectorZ),
                Codec.INT.fieldOf("entrance_facing_2d").forGetter(Placement::entranceFacing2D),
                Codec.INT.fieldOf("mouth_x").forGetter(Placement::mouthX),
                Codec.INT.fieldOf("mouth_y").forGetter(Placement::mouthY),
                Codec.INT.fieldOf("mouth_z").forGetter(Placement::mouthZ)
        ).apply(instance, Placement::new));

        public Placement {
            if (generationVersion < 1 || generationVersion > CURRENT_GENERATION_VERSION) {
                throw new IllegalArgumentException("不支持的洞窟入口生成版本");
            }
            if (quarterTurns < 0 || quarterTurns > 3
                    || entranceFacing2D < 0 || entranceFacing2D > 3) {
                throw new IllegalArgumentException("洞窟入口旋转值无效");
            }
            if (mouthY != connectorY) {
                throw new IllegalArgumentException("山侧洞口和拼图连接口必须位于同一高度截面");
            }
            if (mouthX == connectorX && mouthZ == connectorZ) {
                throw new IllegalArgumentException("山侧洞口不能与拼图连接口重合");
            }
        }

        public BlockPos origin() {
            return new BlockPos(originX, originY, originZ);
        }

        public Rotation rotation() {
            return CaveEntranceGenerator.rotation(quarterTurns);
        }

        public BlockPos shaftOpening(PlaceManager.Place place) {
            return new BlockPos(place.centerX(), shaftOpeningY, place.centerZ());
        }

        public BlockPos connector() {
            return new BlockPos(connectorX, connectorY, connectorZ);
        }

        public Direction entranceFacing() {
            return Direction.from2DDataValue(entranceFacing2D);
        }

        public BlockPos mouth() {
            return new BlockPos(mouthX, mouthY, mouthZ);
        }
    }

    static record AssetMetadata(
            Vec3i size,
            BlockPos shaftCenter,
            BlockPos connector,
            Direction connectorFacing,
            List<ColumnTop> columnTops
    ) {
        AssetMetadata {
            Objects.requireNonNull(size, "size");
            Objects.requireNonNull(shaftCenter, "shaftCenter");
            Objects.requireNonNull(connector, "connector");
            Objects.requireNonNull(connectorFacing, "connectorFacing");
            columnTops = List.copyOf(columnTops);
            if (!size.equals(EXPECTED_SIZE) || !connectorFacing.getAxis().isHorizontal()) {
                throw new IllegalArgumentException("洞窟入口资产元数据无效");
            }
        }

        static AssetMetadata read(StructureTemplate template) {
            if (!template.getSize().equals(EXPECTED_SIZE)) {
                throw new IllegalStateException(
                        "invalid cave entrance size: expected=" + EXPECTED_SIZE
                                + ", actual=" + template.getSize()
                );
            }
            StructurePlaceSettings settings = new StructurePlaceSettings();
            List<StructureTemplate.StructureBlockInfo> markers = new ArrayList<>();
            markers.addAll(template.filterBlocks(BlockPos.ZERO, settings, Blocks.COMMAND_BLOCK));
            markers.addAll(template.filterBlocks(BlockPos.ZERO, settings, Blocks.CHAIN_COMMAND_BLOCK));
            markers.addAll(template.filterBlocks(BlockPos.ZERO, settings, Blocks.REPEATING_COMMAND_BLOCK));
            if (markers.size() != 2 || markers.get(0).pos().getY() != markers.get(1).pos().getY()) {
                throw new IllegalStateException("洞窟入口必须包含两个同高的命令方块标记");
            }
            BlockPos first = markers.get(0).pos();
            BlockPos second = markers.get(1).pos();
            BlockPos shaftCenter = new BlockPos(
                    Math.floorDiv(first.getX() + second.getX(), 2),
                    first.getY(),
                    Math.floorDiv(first.getZ() + second.getZ(), 2)
            );

            List<StructureTemplate.JigsawBlockInfo> jigsaws = template.getJigsaws(BlockPos.ZERO, Rotation.NONE);
            if (jigsaws.size() != 1) {
                throw new IllegalStateException("洞窟入口必须包含且只包含一个拼图连接口");
            }
            StructureTemplate.StructureBlockInfo jigsaw = jigsaws.getFirst().info();
            if (jigsaw.nbt() == null
                    || !"minecraft:air".equals(jigsaw.nbt().getStringOr("final_state", ""))) {
                throw new IllegalStateException("洞窟入口拼图方块的 final_state 必须是 minecraft:air");
            }
            Direction connectorFacing = JigsawBlock.getFrontFacing(jigsaw.state());
            if (!connectorFacing.getAxis().isHorizontal()) {
                throw new IllegalStateException("洞窟入口拼图方块必须水平朝向");
            }

            CompoundTag saved = template.save(new CompoundTag());
            ListTag palette = saved.getListOrEmpty("palette");
            boolean hasPlaceholder = palette.compoundStream().anyMatch(state ->
                    "minetale:ruin_brick".equals(state.getStringOr("Name", ""))
                            || "minecraft:structure_void".equals(state.getStringOr("Name", ""))
            );
            if (hasPlaceholder) {
                throw new IllegalStateException("洞窟入口资产仍包含占位方块");
            }
            Map<Long, Integer> topByColumn = new HashMap<>();
            saved.getListOrEmpty("blocks").compoundStream().forEach(block -> {
                ListTag position = block.getListOrEmpty("pos");
                int x = position.getIntOr(0, 0);
                int y = position.getIntOr(1, 0);
                int z = position.getIntOr(2, 0);
                topByColumn.merge(columnKey(x, z), y, Math::max);
            });
            List<ColumnTop> columns = topByColumn.entrySet().stream()
                    .map(entry -> new ColumnTop(
                            (int) (entry.getKey() >> 32),
                            (int) (long) entry.getKey(),
                            entry.getValue()
                    ))
                    .toList();
            if (columns.isEmpty()) {
                throw new IllegalStateException("洞窟入口资产不包含任何方块");
            }
            return new AssetMetadata(
                    template.getSize(),
                    shaftCenter,
                    jigsaw.pos(),
                    connectorFacing,
                    columns
            );
        }
    }

    static record ColumnTop(int localX, int localZ, int topY) {
    }

    static record TunnelCurve(
            CurvePoint start,
            CurvePoint firstControl,
            CurvePoint secondControl,
            CurvePoint end,
            int steps,
            double lateralX,
            double lateralZ,
            double windingAmplitude
    ) {
        static TunnelCurve straightBetween(BlockPos connector, Direction facing, BlockPos mouth) {
            return between(connector, facing, mouth, false);
        }

        static TunnelCurve windingBetween(BlockPos connector, Direction facing, BlockPos mouth) {
            return between(connector, facing, mouth, true);
        }

        private static TunnelCurve between(
                BlockPos connector,
                Direction facing,
                BlockPos mouth,
                boolean winding
        ) {
            CurvePoint start = CurvePoint.centerOf(connector);
            CurvePoint end = CurvePoint.centerOf(mouth);
            double deltaX = end.x() - start.x();
            double deltaZ = end.z() - start.z();
            double distance = StrictMath.hypot(deltaX, deltaZ);
            double directionX = distance < 1.0E-9 ? facing.getStepX() : deltaX / distance;
            double directionZ = distance < 1.0E-9 ? facing.getStepZ() : deltaZ / distance;
            double departure = Math.min(18.0, distance * 0.25);
            double approach = Math.min(24.0, distance * 0.30);
            long windingSeed = Mth.getSeed(connector) ^ Long.rotateLeft(Mth.getSeed(mouth), 21);
            double windingSign = (windingSeed & 1L) == 0L ? 1.0 : -1.0;
            double windingAmplitude = winding
                    ? windingSign * Math.min(24.0, Math.max(10.0, distance * 0.16))
                    : 0.0;
            return new TunnelCurve(
                    start,
                    new CurvePoint(
                            start.x() + facing.getStepX() * departure,
                            start.y(),
                            start.z() + facing.getStepZ() * departure
                    ),
                    new CurvePoint(
                            end.x() - directionX * approach,
                            end.y(),
                            end.z() - directionZ * approach
                    ),
                    end,
                    Math.max(1, (int) StrictMath.ceil(distance * (winding ? 1.35 : 1.15))),
                    -directionZ,
                    directionX,
                    windingAmplitude
            );
        }

        CurvePoint sample(double progress) {
            double inverse = 1.0 - progress;
            double firstWeight = inverse * inverse * inverse;
            double secondWeight = 3.0 * inverse * inverse * progress;
            double thirdWeight = 3.0 * inverse * progress * progress;
            double fourthWeight = progress * progress * progress;
            double envelope = StrictMath.sin(StrictMath.PI * progress);
            envelope *= envelope;
            double winding = windingAmplitude * envelope
                    * (0.35 + StrictMath.sin(StrictMath.PI * 2.0 * progress));
            return new CurvePoint(
                    firstWeight * start.x() + secondWeight * firstControl.x()
                            + thirdWeight * secondControl.x() + fourthWeight * end.x()
                            + lateralX * winding,
                    firstWeight * start.y() + secondWeight * firstControl.y()
                            + thirdWeight * secondControl.y() + fourthWeight * end.y(),
                    firstWeight * start.z() + secondWeight * firstControl.z()
                            + thirdWeight * secondControl.z() + fourthWeight * end.z()
                            + lateralZ * winding
            );
        }
    }

    static record CurvePoint(double x, double y, double z) {
        static CurvePoint centerOf(BlockPos position) {
            return new CurvePoint(
                    position.getX() + 0.5,
                    position.getY() + 0.5,
                    position.getZ() + 0.5
            );
        }
    }

    private static final class ConnectorCarver extends CaveWorldCarver {
        private ConnectorCarver() {
            super(CaveCarverConfiguration.CODEC);
        }

        private boolean carvePoint(
                CarvingContext context,
                CaveCarverConfiguration configuration,
                ChunkAccess chunk,
                Function<BlockPos, Holder<Biome>> biomeAccessor,
                Aquifer aquifer,
                CurvePoint point,
                double horizontalRadius,
                double verticalRadius,
                CarvingMask mask
        ) {
            return carveEllipsoid(
                    context,
                    configuration,
                    chunk,
                    biomeAccessor,
                    aquifer,
                    point.x(),
                    point.y(),
                    point.z(),
                    horizontalRadius,
                    verticalRadius,
                    mask,
                    (ignoredContext, relativeX, relativeY, relativeZ, ignoredY) ->
                            relativeX * relativeX + relativeY * relativeY
                                    + relativeZ * relativeZ >= 1.0
            );
        }
    }

    // 延迟到 worldgen 使用时初始化，使选址与 Codec 不依赖 registry bootstrap。
    private static final class ConnectorCarverHolder {
        private static final ConnectorCarver INSTANCE = new ConnectorCarver();
    }

    private record TunnelBounds(int minX, int minZ, int maxX, int maxZ) {
        private static TunnelBounds of(Placement placement) {
            TunnelCurve curve = tunnelCurve(placement);
            double minX = Math.min(Math.min(curve.start().x(), curve.firstControl().x()),
                    Math.min(curve.secondControl().x(), curve.end().x()));
            double minZ = Math.min(Math.min(curve.start().z(), curve.firstControl().z()),
                    Math.min(curve.secondControl().z(), curve.end().z()));
            double maxX = Math.max(Math.max(curve.start().x(), curve.firstControl().x()),
                    Math.max(curve.secondControl().x(), curve.end().x()));
            double maxZ = Math.max(Math.max(curve.start().z(), curve.firstControl().z()),
                    Math.max(curve.secondControl().z(), curve.end().z()));
            double mouthOutX = curve.end().x() + curve.lateralZ() * MOUTH_OUTWARD_LENGTH;
            double mouthOutZ = curve.end().z() - curve.lateralX() * MOUTH_OUTWARD_LENGTH;
            minX = Math.min(minX, mouthOutX);
            minZ = Math.min(minZ, mouthOutZ);
            maxX = Math.max(maxX, mouthOutX);
            maxZ = Math.max(maxZ, mouthOutZ);
            double windingMargin = StrictMath.abs(curve.windingAmplitude()) * 1.35;
            int marginX = Mth.ceil(MOUTH_HORIZONTAL_RADIUS
                    + StrictMath.abs(curve.lateralX()) * windingMargin) + 2;
            int marginZ = Mth.ceil(MOUTH_HORIZONTAL_RADIUS
                    + StrictMath.abs(curve.lateralZ()) * windingMargin) + 2;
            return new TunnelBounds(
                    Mth.floor(minX) - marginX,
                    Mth.floor(minZ) - marginZ,
                    Mth.floor(maxX) + marginX,
                    Mth.floor(maxZ) + marginZ
            );
        }

        private boolean intersects(ChunkPos chunk) {
            return maxX >= chunk.getMinBlockX()
                    && minX <= chunk.getMaxBlockX()
                    && maxZ >= chunk.getMinBlockZ()
                    && minZ <= chunk.getMaxBlockZ();
        }
    }

    @FunctionalInterface
    interface SurfaceSampler {
        int surfaceY(int worldX, int worldZ);
    }

    private record RotatedAsset(int quarterTurns, int originX, int originZ, int maximumOriginY) {
    }

    private record Route(BlockPos mouth, double score) {
    }

    private record PlacementCandidate(
            int quarterTurns,
            BlockPos origin,
            BlockPos connector,
            Direction facing,
            Route route
    ) {
    }
}
