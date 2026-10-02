package cn.jehorstudio.minetale.dimension.ebott.entrance;

import cn.jehorstudio.minetale.MineTale;
import cn.jehorstudio.minetale.dimension.ebott.entrance.campfire.MysteriousCampfireRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.CaveVines;
import net.minecraft.world.level.block.HangingMossBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;

// 在连接通道边缘放置装饰
final class CaveEntranceDecoration {
    private static final double DECORATION_START = 0.20;
    private static final double DECORATION_END = 0.88;
    static final long FLOOR_SALT = 0x6D055F4C0A45D31BL;
    static final long CEILING_SALT = 0x43DD0B51C736A8E9L;
    static final long WALL_SALT = 0x28FA84E30D991247L;
    static final int FLOOR_SPACING = 3;
    static final int CEILING_SPACING = 4;
    static final int WALL_SPACING = 5;
    static final float FLOOR_CHANCE = 0.96F;
    static final float CEILING_CHANCE = 0.92F;
    static final float WALL_CHANCE = 0.88F;
    private static final long MOUTH_SALT = 0x7C98EA0B3F2165D4L;
    private static final long CELL_SALT = 0x9E3779B97F4A7C15L;
    static final double CAMPFIRE_PREFERRED_OUTWARD = 10.0;
    static final double CAMPFIRE_MIN_OUTWARD = 5.0;
    static final double CAMPFIRE_MAX_OUTWARD = 15.0;
    static final double CAMPFIRE_MAX_LATERAL = 4.0;
    static final int CAMPFIRE_MIN_TARGET_OFFSET = -4;
    static final int CAMPFIRE_MAX_TARGET_OFFSET = 1;

    private CaveEntranceDecoration() {
    }

    static void decorate(
            ChunkAccess chunk,
            CaveEntranceGenerator.Placement placement
    ) {
        CaveEntranceGenerator.TunnelCurve curve = CaveEntranceGenerator.tunnelCurve(placement);
        long placementSeed = Mth.getSeed(placement.origin());
        scatter(curve, placementSeed ^ FLOOR_SALT, FLOOR_SPACING, FLOOR_CHANCE,
                (candidate, random) -> decorateFloor(chunk, candidate.edge(), random));
        scatter(curve, placementSeed ^ CEILING_SALT, CEILING_SPACING, CEILING_CHANCE,
                (candidate, random) -> decorateCeiling(chunk, candidate.edge(), random));
        scatter(curve, placementSeed ^ WALL_SALT, WALL_SPACING, WALL_CHANCE,
                (candidate, random) -> decorateWall(
                        chunk,
                        candidate.center(),
                        candidate.towardWall(),
                        candidate.wallDistance()
                ));
        decorateMouth(chunk, placement, curve, placementSeed ^ MOUTH_SALT);
        placeMysteriousCampfire(chunk, placement, curve);
    }

    private static void placeMysteriousCampfire(
            ChunkAccess chunk,
            CaveEntranceGenerator.Placement placement,
            CaveEntranceGenerator.TunnelCurve curve
    ) {
        OwnerChunk owner = campfireOwner(placement, curve);
        if (owner.x() != chunk.getPos().x || owner.z() != chunk.getPos().z
                || hasMysteriousCampfire(chunk, placement)) {
            return;
        }

        BlockPos target = selectCampfireTarget(
                placement,
                curve,
                candidate -> isOpenCampfireSite(chunk, candidate)
        );
        if (!isOpenCampfireSite(chunk, target)) {
            prepareGuaranteedCampfireSite(chunk, target);
            MineTale.LOGGER.warn(
                    "洞口 {} 前方没有自然露天候选，已在 {} 构造固定谜之营火位置",
                    placement.mouth(),
                    target
            );
            if (!isOpenCampfireSite(chunk, target)) {
                throw new IllegalStateException("谜之营火兜底位置未能形成露天稳固地面 " + target);
            }
        }

        BlockState campfire = MysteriousCampfireRegistry.MYSTERIOUS_CAMPFIRE.get().defaultBlockState()
                .setValue(CampfireBlock.FACING, placement.entranceFacing().getOpposite());
        chunk.removeBlockEntity(target);
        chunk.setBlockState(target, campfire);
        if (!chunk.getBlockState(target).is(MysteriousCampfireRegistry.MYSTERIOUS_CAMPFIRE.get())) {
            throw new IllegalStateException("谜之营火未能写入洞口露天位置 " + target);
        }
        if (chunk.getBlockEntity(target) == null) {
            chunk.setBlockEntity(MysteriousCampfireRegistry.MYSTERIOUS_CAMPFIRE.get().newBlockEntity(target, campfire));
        }
    }

    private static OwnerChunk campfireOwner(
            CaveEntranceGenerator.Placement placement,
            CaveEntranceGenerator.TunnelCurve curve
    ) {
        int x = Mth.floor(placement.mouthX() + 0.5
                + curve.lateralZ() * CAMPFIRE_PREFERRED_OUTWARD);
        int z = Mth.floor(placement.mouthZ() + 0.5
                - curve.lateralX() * CAMPFIRE_PREFERRED_OUTWARD);
        return new OwnerChunk(Math.floorDiv(x, 16), Math.floorDiv(z, 16));
    }

    // 营火候选优先选择洞口外侧高位、露天且接近视觉中心的石台。
    static BlockPos selectCampfireTarget(
            CaveEntranceGenerator.Placement placement,
            CaveEntranceGenerator.TunnelCurve curve,
            CampfireSiteTester siteTester
    ) {
        OwnerChunk owner = campfireOwner(placement, curve);
        BlockPos best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        int reach = Mth.ceil(CAMPFIRE_MAX_OUTWARD + CAMPFIRE_MAX_LATERAL);
        for (int x = placement.mouthX() - reach; x <= placement.mouthX() + reach; x++) {
            for (int z = placement.mouthZ() - reach; z <= placement.mouthZ() + reach; z++) {
                if (Math.floorDiv(x, 16) != owner.x() || Math.floorDiv(z, 16) != owner.z()) {
                    continue;
                }
                double deltaX = x - placement.mouthX();
                double deltaZ = z - placement.mouthZ();
                double outward = deltaX * curve.lateralZ() - deltaZ * curve.lateralX();
                double lateral = deltaX * curve.lateralX() + deltaZ * curve.lateralZ();
                if (outward < CAMPFIRE_MIN_OUTWARD || outward > CAMPFIRE_MAX_OUTWARD
                        || StrictMath.abs(lateral) > CAMPFIRE_MAX_LATERAL) {
                    continue;
                }
                for (int y = placement.mouthY() + CAMPFIRE_MAX_TARGET_OFFSET;
                     y >= placement.mouthY() + CAMPFIRE_MIN_TARGET_OFFSET;
                     y--) {
                    BlockPos candidate = new BlockPos(x, y, z);
                    if (!siteTester.isSuitable(candidate)) {
                        continue;
                    }
                    double score = StrictMath.abs(outward - CAMPFIRE_PREFERRED_OUTWARD) * 4.0
                            + StrictMath.abs(lateral) * 2.0
                            + StrictMath.abs(y - (placement.mouthY() - 3));
                    if (score < bestScore || score == bestScore && earlier(candidate, best)) {
                        best = candidate;
                        bestScore = score;
                    }
                    break;
                }
            }
        }
        return best != null ? best : fallbackCampfireTarget(placement, curve);
    }

    private static BlockPos fallbackCampfireTarget(
            CaveEntranceGenerator.Placement placement,
            CaveEntranceGenerator.TunnelCurve curve
    ) {
        int x = Mth.floor(placement.mouthX() + 0.5
                + curve.lateralZ() * CAMPFIRE_PREFERRED_OUTWARD);
        int z = Mth.floor(placement.mouthZ() + 0.5
                - curve.lateralX() * CAMPFIRE_PREFERRED_OUTWARD);
        return new BlockPos(x, placement.mouthY() + CAMPFIRE_MIN_TARGET_OFFSET, z);
    }

    private static void prepareGuaranteedCampfireSite(ChunkAccess chunk, BlockPos target) {
        BlockPos support = target.below();
        chunk.removeBlockEntity(support);
        chunk.setBlockState(support, Blocks.MOSS_BLOCK.defaultBlockState());

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = target.getY(); y <= chunk.getMaxY(); y++) {
            cursor.set(target.getX(), y, target.getZ());
            chunk.removeBlockEntity(cursor);
            chunk.setBlockState(cursor, Blocks.AIR.defaultBlockState());
        }
    }

    private static boolean earlier(BlockPos candidate, BlockPos current) {
        return current == null
                || candidate.getX() < current.getX()
                || candidate.getX() == current.getX() && candidate.getZ() < current.getZ()
                || candidate.getX() == current.getX() && candidate.getZ() == current.getZ()
                && candidate.getY() < current.getY();
    }

    private static boolean hasMysteriousCampfire(
            ChunkAccess chunk,
            CaveEntranceGenerator.Placement placement
    ) {
        ChunkPos chunkPos = chunk.getPos();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = chunkPos.getMinBlockX(); x <= chunkPos.getMaxBlockX(); x++) {
            for (int z = chunkPos.getMinBlockZ(); z <= chunkPos.getMaxBlockZ(); z++) {
                for (int y = placement.mouthY() + CAMPFIRE_MIN_TARGET_OFFSET;
                     y <= placement.mouthY() + CAMPFIRE_MAX_TARGET_OFFSET;
                     y++) {
                    if (chunk.getBlockState(cursor.set(x, y, z))
                            .is(MysteriousCampfireRegistry.MYSTERIOUS_CAMPFIRE.get())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean isOpenCampfireSite(ChunkAccess chunk, BlockPos target) {
        ChunkPos chunkPos = chunk.getPos();
        if (target.getX() < chunkPos.getMinBlockX() || target.getX() > chunkPos.getMaxBlockX()
                || target.getZ() < chunkPos.getMinBlockZ() || target.getZ() > chunkPos.getMaxBlockZ()
                || target.getY() <= chunk.getMinY() || target.getY() >= chunk.getMaxY()) {
            return false;
        }
        BlockState targetState = chunk.getBlockState(target);
        BlockPos support = target.below();
        if (!targetState.canBeReplaced() || !targetState.getFluidState().isEmpty()
                || !chunk.getBlockState(support).isFaceSturdy(chunk, support, Direction.UP)) {
            return false;
        }
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = target.getY() + 1; y <= chunk.getMaxY(); y++) {
            if (!chunk.getBlockState(cursor.set(target.getX(), y, target.getZ())).isAir()) {
                return false;
            }
        }
        return true;
    }

    // 每个通道单元只产生一个带确定性相位与横向偏移的候选，避免逐格随机聚集。
    static void scatter(
            CaveEntranceGenerator.TunnelCurve curve,
            long seed,
            int spacing,
            float chance,
            ScatterAction action
    ) {
        int firstCell = Mth.floor(curve.steps() * DECORATION_START / spacing);
        int lastCell = Mth.ceil(curve.steps() * DECORATION_END / spacing);
        for (int cell = firstCell; cell <= lastCell; cell++) {
            RandomSource random = RandomSource.create(seed ^ CELL_SALT * cell);
            if (random.nextFloat() >= chance) {
                continue;
            }
            double step = (cell + random.nextDouble()) * spacing;
            double progress = step / curve.steps();
            if (progress < DECORATION_START || progress > DECORATION_END) {
                continue;
            }
            CaveEntranceGenerator.CurvePoint point = curve.sample(progress);
            HorizontalFrame frame = horizontalFrame(curve, progress);
            int maximumOffset = Math.max(
                    2,
                    Mth.floor(CaveEntranceGenerator.tunnelHorizontalRadius(progress) - 1.0)
            );
            int lateralOffset = 2 + random.nextInt(maximumOffset - 1);
            if (random.nextBoolean()) {
                lateralOffset = -lateralOffset;
            }
            int x = Mth.floor(point.x() + frame.lateralX() * lateralOffset);
            int z = Mth.floor(point.z() + frame.lateralZ() * lateralOffset);
            BlockPos center = new BlockPos(Mth.floor(point.x()), Mth.floor(point.y()), Mth.floor(point.z()));
            Direction towardWall = cardinalDirection(
                    frame.lateralX() * lateralOffset,
                    frame.lateralZ() * lateralOffset
            );
            action.apply(
                    new ScatterCandidate(
                            center,
                            new BlockPos(x, center.getY(), z),
                            towardWall,
                            Mth.ceil(CaveEntranceGenerator.tunnelHorizontalRadius(progress)) + 2
                    ),
                    random
            );
        }
    }

    private static HorizontalFrame horizontalFrame(
            CaveEntranceGenerator.TunnelCurve curve,
            double progress
    ) {
        double before = Math.max(0.0, progress - 0.01);
        double after = Math.min(1.0, progress + 0.01);
        CaveEntranceGenerator.CurvePoint first = curve.sample(before);
        CaveEntranceGenerator.CurvePoint second = curve.sample(after);
        double tangentX = second.x() - first.x();
        double tangentZ = second.z() - first.z();
        double length = StrictMath.hypot(tangentX, tangentZ);
        if (length < 1.0E-9) {
            return new HorizontalFrame(1.0, 0.0, 0.0, 1.0);
        }
        tangentX /= length;
        tangentZ /= length;
        return new HorizontalFrame(tangentX, tangentZ, -tangentZ, tangentX);
    }

    private static Direction cardinalDirection(double x, double z) {
        if (StrictMath.abs(x) >= StrictMath.abs(z)) {
            return x >= 0.0 ? Direction.EAST : Direction.WEST;
        }
        return z >= 0.0 ? Direction.SOUTH : Direction.NORTH;
    }

    private static void decorateFloor(
            ChunkAccess chunk,
            BlockPos column,
            RandomSource random
    ) {
        BlockPos air = findFloorAir(chunk, column);
        if (air == null) {
            return;
        }
        BlockPos floor = air.below();
        BlockState moss = random.nextBoolean()
                ? Blocks.MOSS_BLOCK.defaultBlockState()
                : Blocks.PALE_MOSS_BLOCK.defaultBlockState();
        chunk.removeBlockEntity(floor);
        chunk.setBlockState(floor, moss);

        int decoration = random.nextInt(100);
        if (decoration < 14) {
            chunk.setBlockState(floor, Blocks.MOSS_BLOCK.defaultBlockState());
            chunk.setBlockState(air, Blocks.FIREFLY_BUSH.defaultBlockState());
        } else if (decoration < 48) {
            chunk.setBlockState(
                    air,
                    random.nextBoolean()
                            ? Blocks.MOSS_CARPET.defaultBlockState()
                            : Blocks.PALE_MOSS_CARPET.defaultBlockState()
            );
        }
    }

    private static void decorateCeiling(
            ChunkAccess chunk,
            BlockPos column,
            RandomSource random
    ) {
        BlockPos air = findCeilingAir(chunk, column);
        if (air == null) {
            return;
        }
        int length = 1 + random.nextInt(3);
        if (random.nextInt(4) != 0) {
            placeCaveVines(chunk, air, length, random);
        } else {
            placePaleHangingMoss(chunk, air, length);
        }
    }

    private static void placeCaveVines(
            ChunkAccess chunk,
            BlockPos start,
            int requestedLength,
            RandomSource random
    ) {
        int length = availableDownwardAir(chunk, start, requestedLength);
        if (length == 0) {
            return;
        }
        for (int offset = 0; offset < length; offset++) {
            boolean tip = offset == length - 1;
            BlockState state = (tip ? Blocks.CAVE_VINES : Blocks.CAVE_VINES_PLANT)
                    .defaultBlockState()
                    .setValue(CaveVines.BERRIES, tip || random.nextInt(4) == 0);
            chunk.setBlockState(start.below(offset), state);
        }
    }

    private static void placePaleHangingMoss(
            ChunkAccess chunk,
            BlockPos start,
            int requestedLength
    ) {
        int length = availableDownwardAir(chunk, start, requestedLength);
        for (int offset = 0; offset < length; offset++) {
            chunk.setBlockState(
                    start.below(offset),
                    Blocks.PALE_HANGING_MOSS.defaultBlockState()
                            .setValue(HangingMossBlock.TIP, offset == length - 1)
            );
        }
    }

    private static void decorateWall(
            ChunkAccess chunk,
            BlockPos center,
            Direction towardWall,
            int maximumDistance
    ) {
        for (int distance = 3; distance <= maximumDistance; distance++) {
            BlockPos air = center.relative(towardWall, distance);
            BlockPos support = air.relative(towardWall);
            if (!insideChunk(chunk, air) || !insideChunk(chunk, support)) {
                return;
            }
            if (chunk.getBlockState(air).isAir()
                    && chunk.getBlockState(support).isFaceSturdy(
                    chunk,
                    support,
                    towardWall.getOpposite()
            )) {
                chunk.setBlockState(
                        air,
                        Blocks.VINE.defaultBlockState()
                                .setValue(VineBlock.getPropertyForFace(towardWall), true)
                );
                return;
            }
        }
    }

    private static void decorateMouth(
            ChunkAccess chunk,
            CaveEntranceGenerator.Placement placement,
            CaveEntranceGenerator.TunnelCurve curve,
            long seed
    ) {
        scatterMouthSurface(chunk, placement, horizontalFrame(curve, 0.99), seed);
        decorateMouthRim(chunk, placement, seed);
    }

    private static void scatterMouthSurface(
            ChunkAccess chunk,
            CaveEntranceGenerator.Placement placement,
            HorizontalFrame frame,
            long seed
    ) {
        for (int candidate = 0; candidate < 42; candidate++) {
            RandomSource random = RandomSource.create(seed ^ CELL_SALT * candidate);
            double outward = -2.0
                    + random.nextDouble() * (CaveEntranceGenerator.MOUTH_OUTWARD_LENGTH + 10.0);
            double lateral = (random.nextDouble() * 2.0 - 1.0) * 10.0;
            int x = Mth.floor(placement.mouthX() + 0.5
                    + frame.tangentX() * outward + frame.lateralX() * lateral);
            int z = Mth.floor(placement.mouthZ() + 0.5
                    + frame.tangentZ() * outward + frame.lateralZ() * lateral);
            placeSurfaceMoss(chunk, x, z, placement.mouthY(), random);
        }
    }

    private static void placeSurfaceMoss(
            ChunkAccess chunk,
            int x,
            int z,
            int centerY,
            RandomSource random
    ) {
        ChunkPos chunkPos = chunk.getPos();
        if (x < chunkPos.getMinBlockX() || x > chunkPos.getMaxBlockX()
                || z < chunkPos.getMinBlockZ() || z > chunkPos.getMaxBlockZ()) {
            return;
        }
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = Math.min(chunk.getMaxY(), centerY + 9);
             y >= Math.max(chunk.getMinY(), centerY - 7);
             y--) {
            cursor.set(x, y, z);
            BlockState state = chunk.getBlockState(cursor);
            if (state.isAir() || state.is(Blocks.SNOW)) {
                continue;
            }
            if (!mayBecomeMoss(state)) {
                continue;
            }
            BlockPos above = cursor.above();
            BlockState aboveState = chunk.getBlockState(above);
            if (!aboveState.isAir() && !aboveState.is(Blocks.SNOW)) {
                continue;
            }
            if (aboveState.is(Blocks.SNOW)) {
                chunk.setBlockState(above, Blocks.AIR.defaultBlockState());
            }
            chunk.removeBlockEntity(cursor);
            chunk.setBlockState(cursor, Blocks.MOSS_BLOCK.defaultBlockState());
            if (random.nextInt(4) == 0 && chunk.getBlockState(above).isAir()) {
                chunk.setBlockState(above, Blocks.MOSS_CARPET.defaultBlockState());
            }
            return;
        }
    }

    // 只替换部分空气边界原生岩石，以打散规则椭圆而不改变开口尺寸。
    private static void decorateMouthRim(
            ChunkAccess chunk,
            CaveEntranceGenerator.Placement placement,
            long seed
    ) {
        ChunkPos chunkPos = chunk.getPos();
        int minX = Math.max(chunkPos.getMinBlockX(), placement.mouthX() - 10);
        int maxX = Math.min(chunkPos.getMaxBlockX(), placement.mouthX() + 10);
        int minZ = Math.max(chunkPos.getMinBlockZ(), placement.mouthZ() - 10);
        int maxZ = Math.min(chunkPos.getMaxBlockZ(), placement.mouthZ() + 10);
        int minY = Math.max(chunk.getMinY(), placement.mouthY() - 6);
        int maxY = Math.min(chunk.getMaxY(), placement.mouthY() + 6);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = minY; y <= maxY; y++) {
                    cursor.set(x, y, z);
                    BlockState state = chunk.getBlockState(cursor);
                    if (!mayBecomeMoss(state)
                            || !touchesCaveAir(chunk, cursor)
                            || !selected(seed, cursor, 100, 24)) {
                        continue;
                    }
                    chunk.removeBlockEntity(cursor);
                    chunk.setBlockState(cursor, Blocks.MOSS_BLOCK.defaultBlockState());
                }
            }
        }
    }

    private static boolean mayBecomeMoss(BlockState state) {
        return state.is(BlockTags.BASE_STONE_OVERWORLD)
                || state.is(Blocks.DIRT)
                || state.is(Blocks.GRASS_BLOCK)
                || state.is(Blocks.SNOW_BLOCK);
    }

    private static boolean touchesCaveAir(ChunkAccess chunk, BlockPos position) {
        for (Direction direction : Direction.values()) {
            BlockPos neighbor = position.relative(direction);
            if (insideChunk(chunk, neighbor) && chunk.getBlockState(neighbor).is(Blocks.CAVE_AIR)) {
                return true;
            }
        }
        return false;
    }

    private static boolean selected(long seed, BlockPos position, int bound, int threshold) {
        long mixed = Mth.getSeed(position) ^ seed;
        mixed ^= mixed >>> 33;
        mixed *= 0xFF51AFD7ED558CCDL;
        mixed ^= mixed >>> 33;
        return Long.remainderUnsigned(mixed, bound) < threshold;
    }

    private static BlockPos findFloorAir(ChunkAccess chunk, BlockPos column) {
        for (int offset = 1; offset <= 6; offset++) {
            BlockPos air = column.below(offset);
            BlockPos floor = air.below();
            if (!insideChunk(chunk, air) || !insideChunk(chunk, floor)) {
                return null;
            }
            if (chunk.getBlockState(air).isAir()
                    && chunk.getBlockState(floor).isFaceSturdy(chunk, floor, Direction.UP)) {
                return air;
            }
        }
        return null;
    }

    private static BlockPos findCeilingAir(ChunkAccess chunk, BlockPos column) {
        for (int offset = 1; offset <= 6; offset++) {
            BlockPos air = column.above(offset);
            BlockPos ceiling = air.above();
            if (!insideChunk(chunk, air) || !insideChunk(chunk, ceiling)) {
                return null;
            }
            if (chunk.getBlockState(air).isAir()
                    && chunk.getBlockState(ceiling).isFaceSturdy(chunk, ceiling, Direction.DOWN)) {
                return air;
            }
        }
        return null;
    }

    private static int availableDownwardAir(
            ChunkAccess chunk,
            BlockPos start,
            int requestedLength
    ) {
        int length = 0;
        while (length < requestedLength) {
            BlockPos position = start.below(length);
            if (!insideChunk(chunk, position) || !chunk.getBlockState(position).isAir()) {
                break;
            }
            length++;
        }
        return length;
    }

    private static boolean insideChunk(ChunkAccess chunk, BlockPos position) {
        ChunkPos chunkPos = chunk.getPos();
        return position.getX() > chunkPos.getMinBlockX()
                && position.getX() < chunkPos.getMaxBlockX()
                && position.getZ() > chunkPos.getMinBlockZ()
                && position.getZ() < chunkPos.getMaxBlockZ()
                && position.getY() >= chunk.getMinY()
                && position.getY() <= chunk.getMaxY();
    }

    @FunctionalInterface
    interface ScatterAction {
        void apply(ScatterCandidate candidate, RandomSource random);
    }

    @FunctionalInterface
    interface CampfireSiteTester {
        boolean isSuitable(BlockPos target);
    }

    record ScatterCandidate(
            BlockPos center,
            BlockPos edge,
            Direction towardWall,
            int wallDistance
    ) {
    }

    private record HorizontalFrame(
            double tangentX,
            double tangentZ,
            double lateralX,
            double lateralZ
    ) {
    }

    private record OwnerChunk(int x, int z) {
    }
}
