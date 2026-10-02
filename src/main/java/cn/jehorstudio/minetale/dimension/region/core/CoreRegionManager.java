package cn.jehorstudio.minetale.dimension.region.core;

import cn.jehorstudio.minetale.MineTale;
import cn.jehorstudio.minetale.dimension.region.core.collision.CoreCollisionBuilder;
import cn.jehorstudio.minetale.dimension.region.core.collision.CoreCollisionGrid;
import cn.jehorstudio.minetale.dimension.region.core.collision.CoreCollisionStore;
import cn.jehorstudio.minetale.voxel.scene.asset.SceneAsset;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.network.PacketDistributor;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 每个服务端维度的 Core 权威。放置坐标持久化在 {@link CoreRegionData}；
 * 生成的碰撞栅格作为地图数据永久写入维度 data 目录，维度加载时同步还原，
 * 玩家进入世界即有完整碰撞，与真实建筑一致。重放置删除旧数据重新生成。
 */
public final class CoreRegionManager {
    private static final Map<ResourceKey<Level>, CoreRegionManager> ACTIVE = new ConcurrentHashMap<>();
    private static final String COLLISION_ENTRY = "minetale_core_collision.bin";
    private static final ExecutorService WORKER =
            Executors.newSingleThreadExecutor(r -> {
                Thread thread = new Thread(r, "MineTale core collision");
                thread.setDaemon(true);
                return thread;
            });

    private final ServerLevel level;
    private final CoreRegionData data;
    private CorePlacement placement;
    private volatile CoreCollisionGrid grid;
    // 仅服务端主线程读写；构建线程通过捕获的令牌判定失效。
    private int generation;

    private CoreRegionManager(ServerLevel level) {
        this.level = level;
        this.data = level.getDataStorage().computeIfAbsent(CoreRegionData.TYPE);
        this.placement = data.placement();
        if (placement != null) {
            CoreCollisionGrid stored = loadCollision();
            if (stored != null) grid = stored;
            else build(placement);
        }
    }

    /** 服务端主线程在维度加载时建立管理器；重复调用以先建者为准。 */
    public static CoreRegionManager load(ServerLevel level) {
        return ACTIVE.computeIfAbsent(level.dimension(), ignored -> new CoreRegionManager(level));
    }

    public static void unload(ServerLevel level) {
        ACTIVE.remove(level.dimension());
    }

    public static CoreRegionManager of(ServerLevel level) {
        CoreRegionManager manager = ACTIVE.get(level.dimension());
        return manager != null ? manager : load(level);
    }

    public CorePlacement placement() {
        return placement;
    }

    /** 当前碰撞栅格（只读共享；构建完成前为 null）。 */
    public CoreCollisionGrid grid() {
        return grid;
    }

    /**
     * 唯一的放置入口：固定坐标写入存档、生成碰撞并永久写入地图数据，再广播状态。
     * 重放置会先卸载原 Core（旧碰撞立即失效并删除存档）再重新生成。
     */
    public void generateCore(ResourceLocation scene, double x, double y, double z) {
        CorePlacement next = CorePlacement.snap(scene, x, y, z);
        generation++;
        grid = null;
        placement = next;
        data.set(next);
        deleteCollision();
        build(next);
        broadcast();
    }

    /** 卸载 Core：清除权威放置与地图数据中的碰撞，并广播。 */
    public void removeCore() {
        if (placement == null) return;
        generation++;
        grid = null;
        placement = null;
        data.set(null);
        deleteCollision();
        broadcast();
    }

    public void sync(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, state());
    }

    private CoreStatePayload state() {
        return placement != null ? new CoreStatePayload(placement) : CoreStatePayload.empty();
    }

    private void broadcast() {
        PacketDistributor.sendToPlayersInDimension(level, state());
    }

    private void build(CorePlacement target) {
        int token = generation;
        CompletableFuture.supplyAsync(
                        () -> {
                            try (SceneAsset asset = CoreCollisionBuilder.openAsset(target.scene())) {
                                return CoreCollisionBuilder.build(asset, target, () -> token != generation);
                            } catch (IOException failure) {
                                throw new UncheckedIOException(failure);
                            }
                        },
                        WORKER)
                .whenComplete(
                        (result, error) ->
                                level.getServer().execute(
                                        () -> {
                                            if (token != generation) return;
                                            if (error != null) {
                                                MineTale.LOGGER.error(
                                                        "Core collision build failed for {}", target, error);
                                                return;
                                            }
                                            grid = result.grid();
                                            storeCollision(result.grid());
                                            MineTale.LOGGER.info(
                                                    "Core collision ready: {} cells={} shells={} sections={} triangles={} snapped={} {}ms",
                                                    target,
                                                    result.grid().cells(),
                                                    result.grid().shells(),
                                                    result.grid().sections(),
                                                    result.triangles(),
                                                    result.snapped(),
                                                    result.millis());
                                        }));
    }

    /** 维度 data 目录下的碰撞存档；原版布局（主世界 data/，其它维度 dimensions/&lt;ns&gt;/&lt;path&gt;/data/）。 */
    private Path collisionFile() {
        Path root = level.getServer().getWorldPath(LevelResource.ROOT);
        ResourceLocation key = level.dimension().location();
        Path data = Level.OVERWORLD.location().equals(key)
                ? root.resolve("data")
                : root.resolve("dimensions").resolve(key.getNamespace()).resolve(key.getPath()).resolve("data");
        return data.resolve(COLLISION_ENTRY);
    }

    private void storeCollision(CoreCollisionGrid built) {
        try {
            CoreCollisionStore.write(collisionFile(), "core-collision-", built,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException failure) {
            MineTale.LOGGER.warn("Core collision store failed; will rebuild on next load", failure);
        }
    }

    private CoreCollisionGrid loadCollision() {
        Path file = collisionFile();
        if (!Files.isRegularFile(file) || placement == null) return null;
        try {
            CoreCollisionGrid stored =
                    CoreCollisionStore.read(file, placement.x(), placement.y(), placement.z());
            MineTale.LOGGER.info(
                    "Core collision restored from world data: {} cells={} shells={}",
                    placement, stored.cells(), stored.shells());
            return stored;
        } catch (IOException | RuntimeException invalid) {
            MineTale.LOGGER.warn("Core collision data rejected; rebuilding", invalid);
            return null;
        }
    }

    private void deleteCollision() {
        try {
            Files.deleteIfExists(collisionFile());
        } catch (IOException failure) {
            MineTale.LOGGER.warn("Core collision data delete failed", failure);
        }
    }

    /** 放置的方块碰撞与 Core 碰撞相交时禁止放置（EntityPlaceEvent 服务端裁决）。 */
    static boolean placementBlocked(ServerLevel level, BlockPos pos, BlockState state) {
        CoreRegionManager manager = ACTIVE.get(level.dimension());
        if (manager == null) return false;
        CoreCollisionGrid grid = manager.grid;
        if (grid == null) return false;
        for (AABB box : state.getCollisionShape(level, pos).toAabbs()) {
            AABB world = box.move(pos.getX(), pos.getY(), pos.getZ());
            if (grid.intersectsWorld(world)) {
                List<AABB> found = new ArrayList<>(4);
                if (grid.collectWorld(world, found) > 0) return true;
            }
        }
        return false;
    }

    /** 服务端碰撞注入入口；由 ServerCoreCollisionMixin 调用，可在任意线程。 */
    public static List<VoxelShape> appendCollision(Level level, AABB box, List<VoxelShape> original) {
        if (!(level instanceof ServerLevel server)) return original;
        CoreRegionManager manager = ACTIVE.get(server.dimension());
        if (manager == null) return original;
        CoreCollisionGrid grid = manager.grid;
        return grid == null ? original : grid.appendShapes(box, original);
    }
}
