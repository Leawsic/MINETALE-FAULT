package cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.particles;

import cn.jehorstudio.minetale.MineTale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.ArrayList;
import java.util.List;

@EventBusSubscriber(modid = MineTale.MODID, value = Dist.CLIENT)
public final class SnowdinAmbientParticles {
    private static final ResourceKey<Biome> SNOWDIN = ResourceKey.create(
            Registries.BIOME,
            ResourceLocation.fromNamespaceAndPath(MineTale.MODID, "snowdin")
    );

    private static final int SNOW_PILLAR_GRID_SIZE = 48;
    private static final int SNOW_PILLAR_GRID_RADIUS = 4;
    private static final int SNOW_PILLAR_REFRESH_INTERVAL = 40;
    private static final int SNOW_PILLAR_MAX_COUNT = 16;

    private static final List<SnowPillar> ACTIVE_SNOW_PILLARS = new ArrayList<>();
    private static long lastSnowPillarRefreshTick = -9999L;

    private SnowdinAmbientParticles() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        LocalPlayer player = minecraft.player;

        if (level == null || player == null || minecraft.isPaused()) {
            return;
        }

        if (!level.getBiome(player.blockPosition()).is(SNOWDIN)) {
            ACTIVE_SNOW_PILLARS.clear();
            return;
        }

        RandomSource random = level.random;
        long gameTime = level.getGameTime();

        spawnSnowCurtain(level, player, random, gameTime);
        spawnIceSparkles(level, player, random);
        spawnColdMist(level, player, random, gameTime);
        spawnCeilingSnowPillars(level, player, random, gameTime);
    }

    private static void spawnSnowCurtain(ClientLevel level, LocalPlayer player, RandomSource random, long gameTime) {
        // 近场雪从玩家上方生成，保证下落过程始终处于可见范围。
        int count = 2;

        if ((gameTime / 80L) % 5L == 0L) {
            count += 2;
        }

        double windX = Math.sin(gameTime * 0.015) * 0.018;
        double windZ = Math.cos(gameTime * 0.011) * 0.018;

        for (int i = 0; i < count; i++) {
            double x = player.getX() + (random.nextDouble() - 0.5) * 28.0;
            double y = player.getY() + 3.0 + random.nextDouble() * 10.0;
            double z = player.getZ() + (random.nextDouble() - 0.5) * 28.0;

            BlockPos pos = BlockPos.containing(x, y, z);
            if (!level.getBlockState(pos).isAir()) {
                continue;
            }

            double xd = windX + (random.nextDouble() - 0.5) * 0.01;
            double yd = -0.015 - random.nextDouble() * 0.025;
            double zd = windZ + (random.nextDouble() - 0.5) * 0.01;

            level.addParticle(ParticleTypes.SNOWFLAKE, x, y, z, xd, yd, zd);
        }
    }

    private static void spawnIceSparkles(ClientLevel level, LocalPlayer player, RandomSource random) {
        // 冰晶闪光仅在冰面附近低频出现。
        if (random.nextFloat() > 0.18F) {
            return;
        }

        double x = player.getX() + (random.nextDouble() - 0.5) * 18.0;
        double y = player.getY() + random.nextDouble() * 4.0;
        double z = player.getZ() + (random.nextDouble() - 0.5) * 18.0;

        BlockPos pos = BlockPos.containing(x, y, z);
        BlockPos below = pos.below();

        if (!level.getBlockState(pos).isAir()) {
            return;
        }

        if (!isIceLike(level.getBlockState(below).getBlock())) {
            return;
        }

        level.addParticle(
                ParticleTypes.END_ROD,
                x,
                below.getY() + 1.08,
                z,
                0.0,
                0.01 + random.nextDouble() * 0.015,
                0.0
        );
    }

    private static void spawnColdMist(ClientLevel level, LocalPlayer player, RandomSource random, long gameTime) {
        // 冷雾锚定低处地表，避免漂入洞顶空间。
        if (gameTime % 3L != 0L) {
            return;
        }

        if (random.nextFloat() > 0.35F) {
            return;
        }

        double x = player.getX() + (random.nextDouble() - 0.5) * 20.0;
        double y = player.getY() + 0.2 + random.nextDouble() * 1.8;
        double z = player.getZ() + (random.nextDouble() - 0.5) * 20.0;

        BlockPos pos = BlockPos.containing(x, y, z);
        if (!level.getBlockState(pos).isAir()) {
            return;
        }

        double xd = (random.nextDouble() - 0.5) * 0.01;
        double yd = 0.002;
        double zd = (random.nextDouble() - 0.5) * 0.01;

        level.addParticle(ParticleTypes.CLOUD, x, y, z, xd, yd, zd);
    }

    private static void spawnCeilingSnowPillars(ClientLevel level, LocalPlayer player, RandomSource random, long gameTime) {
        if (gameTime - lastSnowPillarRefreshTick >= SNOW_PILLAR_REFRESH_INTERVAL) {
            rebuildSnowPillars(level, player, gameTime);
        }

        double windX = Math.sin(gameTime * 0.012) * 0.025;
        double windZ = Math.cos(gameTime * 0.009) * 0.025;

        for (SnowPillar pillar : ACTIVE_SNOW_PILLARS) {
            double dx = pillar.x - player.getX();
            double dz = pillar.z - player.getZ();
            double distanceSqr = dx * dx + dz * dz;

            // 远环不生成真实粒子，以固定粒子数量上限。
            if (distanceSqr > 150.0 * 150.0) {
                continue;
            }

            // 密度随距离衰减，把预算优先留给近处雪柱。
            int snowflakeCount = distanceSqr > 90.0 * 90.0 ? 2 : 5;

            for (int i = 0; i < snowflakeCount; i++) {
                spawnSnowPillarFlake(level, random, pillar, windX, windZ);
            }

            // 白雾连接离散雪点，形成连续雪柱轮廓。
            if (gameTime % 4L == 0L && random.nextFloat() < 0.45F) {
                spawnSnowPillarMist(level, random, pillar, windX, windZ);
            }

            // 冰晶光作为雪柱内的稀疏高亮。
            if (random.nextFloat() < 0.035F) {
                spawnSnowPillarGlint(level, random, pillar);
            }
        }
    }

    private static void rebuildSnowPillars(ClientLevel level, LocalPlayer player, long gameTime) {
        ACTIVE_SNOW_PILLARS.clear();
        lastSnowPillarRefreshTick = gameTime;

        int centerCellX = Math.floorDiv(player.blockPosition().getX(), SNOW_PILLAR_GRID_SIZE);
        int centerCellZ = Math.floorDiv(player.blockPosition().getZ(), SNOW_PILLAR_GRID_SIZE);

        for (int cellX = centerCellX - SNOW_PILLAR_GRID_RADIUS; cellX <= centerCellX + SNOW_PILLAR_GRID_RADIUS; cellX++) {
            for (int cellZ = centerCellZ - SNOW_PILLAR_GRID_RADIUS; cellZ <= centerCellZ + SNOW_PILLAR_GRID_RADIUS; cellZ++) {
                if (ACTIVE_SNOW_PILLARS.size() >= SNOW_PILLAR_MAX_COUNT) {
                    return;
                }

                long seed = snowPillarSeed(cellX, cellZ);

                // 稳定 hash 稀疏网格，防止每个格点都形成雪柱。
                if (unitFromSeed(seed, 0) > 0.22) {
                    continue;
                }

                double offsetX = 8.0 + unitFromSeed(seed, 16) * (SNOW_PILLAR_GRID_SIZE - 16.0);
                double offsetZ = 8.0 + unitFromSeed(seed, 32) * (SNOW_PILLAR_GRID_SIZE - 16.0);

                double x = cellX * SNOW_PILLAR_GRID_SIZE + offsetX;
                double z = cellZ * SNOW_PILLAR_GRID_SIZE + offsetZ;

                SnowPillar pillar = findSnowPillar(level, x, z, seed);
                if (pillar != null) {
                    ACTIVE_SNOW_PILLARS.add(pillar);
                }
            }
        }
    }

    private static SnowPillar findSnowPillar(ClientLevel level, double x, double z, long seed) {
        int blockX = (int) Math.floor(x);
        int blockZ = (int) Math.floor(z);

        int minY = level.dimensionType().minY();
        int maxY = minY + level.dimensionType().height() - 1;

        BlockPos anchor = BlockPos.containing(x, minY, z);
        if (!level.hasChunkAt(anchor)) {
            return null;
        }

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        int topY = Integer.MIN_VALUE;

        // 自上而下寻找下方为空气的实体块，即穹顶内表面。
        for (int y = maxY; y > minY + 8; y--) {
            pos.set(blockX, y, blockZ);

            if (level.getBlockState(pos).isAir()) {
                continue;
            }

            pos.set(blockX, y - 1, blockZ);
            if (level.getBlockState(pos).isAir()) {
                topY = y - 1;
                break;
            }
        }

        if (topY == Integer.MIN_VALUE) {
            return null;
        }

        int bottomY = Math.max(minY + 4, topY - 80);

        // 从穹顶向下解析地面，使雪柱终止于地面或台地而非贯穿世界。
        for (int y = topY - 2; y > minY + 1; y--) {
            pos.set(blockX, y, blockZ);

            if (!level.getBlockState(pos).isAir()) {
                bottomY = y + 2;
                break;
            }
        }

        int height = topY - bottomY;
        if (height < 24) {
            return null;
        }

        double radius = 2.2 + unitFromSeed(seed, 48) * 2.6;
        return new SnowPillar(x, z, topY, bottomY, radius);
    }

    private static void spawnSnowPillarFlake(
            ClientLevel level,
            RandomSource random,
            SnowPillar pillar,
            double windX,
            double windZ
    ) {
        double angle = random.nextDouble() * Math.PI * 2.0;
        double radius = Math.sqrt(random.nextDouble()) * pillar.radius;

        double x = pillar.x + Math.cos(angle) * radius;
        double y = pillar.bottomY + random.nextDouble() * (pillar.topY - pillar.bottomY);
        double z = pillar.z + Math.sin(angle) * radius;

        double xd = windX + (random.nextDouble() - 0.5) * 0.018;
        double yd = -0.025 - random.nextDouble() * 0.035;
        double zd = windZ + (random.nextDouble() - 0.5) * 0.018;

        level.addAlwaysVisibleParticle(ParticleTypes.SNOWFLAKE, true, x, y, z, xd, yd, zd);
    }

    private static void spawnSnowPillarMist(
            ClientLevel level,
            RandomSource random,
            SnowPillar pillar,
            double windX,
            double windZ
    ) {
        double angle = random.nextDouble() * Math.PI * 2.0;
        double radius = Math.sqrt(random.nextDouble()) * pillar.radius * 0.75;

        double x = pillar.x + Math.cos(angle) * radius;
        double y = pillar.bottomY + random.nextDouble() * (pillar.topY - pillar.bottomY);
        double z = pillar.z + Math.sin(angle) * radius;

        double xd = windX * 0.35 + (random.nextDouble() - 0.5) * 0.006;
        double yd = -0.003 - random.nextDouble() * 0.006;
        double zd = windZ * 0.35 + (random.nextDouble() - 0.5) * 0.006;

        level.addAlwaysVisibleParticle(ParticleTypes.CLOUD, true, x, y, z, xd, yd, zd);
    }

    private static void spawnSnowPillarGlint(ClientLevel level, RandomSource random, SnowPillar pillar) {
        double angle = random.nextDouble() * Math.PI * 2.0;
        double radius = Math.sqrt(random.nextDouble()) * pillar.radius * 0.6;

        double x = pillar.x + Math.cos(angle) * radius;
        double y = pillar.bottomY + random.nextDouble() * (pillar.topY - pillar.bottomY);
        double z = pillar.z + Math.sin(angle) * radius;

        level.addAlwaysVisibleParticle(
                ParticleTypes.END_ROD,
                true,
                x,
                y,
                z,
                0.0,
                0.008 + random.nextDouble() * 0.012,
                0.0
        );
    }

    private static boolean isIceLike(net.minecraft.world.level.block.Block block) {
        return block == Blocks.ICE
                || block == Blocks.PACKED_ICE
                || block == Blocks.BLUE_ICE
                || block == Blocks.FROSTED_ICE
                || block == Blocks.SNOW_BLOCK
                || block == Blocks.POWDER_SNOW;
    }

    private static long snowPillarSeed(int cellX, int cellZ) {
        long x = cellX * 341873128712L;
        long z = cellZ * 132897987541L;
        long seed = x ^ z ^ 0x6D1B54A32D192ED3L;

        seed ^= seed >>> 33;
        seed *= 0xff51afd7ed558ccdL;
        seed ^= seed >>> 33;
        seed *= 0xc4ceb9fe1a85ec53L;
        seed ^= seed >>> 33;

        return seed;
    }

    private static double unitFromSeed(long seed, int shift) {
        return ((seed >>> shift) & 0xFFFFL) / 65535.0;
    }

    private record SnowPillar(
            double x,
            double z,
            int topY,
            int bottomY,
            double radius
    ) {
    }
}
