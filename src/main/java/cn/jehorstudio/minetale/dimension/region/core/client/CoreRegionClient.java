package cn.jehorstudio.minetale.dimension.region.core.client;

import cn.jehorstudio.minetale.MineTale;
import cn.jehorstudio.minetale.dimension.region.core.CorePlacement;
import cn.jehorstudio.minetale.dimension.region.core.CorePlacementRequest;
import cn.jehorstudio.minetale.dimension.region.core.CoreRegionManager;
import cn.jehorstudio.minetale.dimension.region.core.CoreStatePayload;
import cn.jehorstudio.minetale.dimension.region.core.collision.CoreCollisionBuilder;
import cn.jehorstudio.minetale.dimension.region.core.collision.CoreCollisionGrid;
import cn.jehorstudio.minetale.dimension.region.core.collision.CoreCollisionStore;
import cn.jehorstudio.minetale.voxel.scene.asset.SceneAsset;
import cn.jehorstudio.minetale.voxel.scene.runtime.SceneClient;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShapeRenderer;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import org.joml.Vector3f;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 客户端 Core 放置状态：接收服务端同步后驱动场景渲染并重建同一份碰撞占据栅格，
 * 用于本地预测碰撞与光标选择框；选择只画线框，不产生可破坏的方块目标。
 * 客户端放置指令（/mt_scene load|unload|core）在本类注册，mt_scene 其余子命令归 SceneClient。
 */
@EventBusSubscriber(modid = MineTale.MODID, value = Dist.CLIENT)
public final class CoreRegionClient {
    private static final ExecutorService WORKER =
            Executors.newSingleThreadExecutor(r -> {
                Thread thread = new Thread(r, "MineTale core collision");
                thread.setDaemon(true);
                return thread;
            });

    private static volatile CoreCollisionGrid grid;
    private static volatile Level bound;
    // 仅主线程读写；构建线程通过捕获的令牌判定失效。
    private static int generation;

    private CoreRegionClient() {}

    @SubscribeEvent
    public static void register(RegisterClientPayloadHandlersEvent event) {
        event.register(CoreStatePayload.TYPE, CoreRegionClient::handleState);
    }

    /** load/unload 请求服务端放置/清除，状态经 CoreStatePayload 回流驱动渲染。 */
    @SubscribeEvent
    public static void commands(RegisterClientCommandsEvent event) {
        // mt_scene 根节点由 SceneClient 注册；Brigadier 对同名 literal 合并子节点。
        event.getDispatcher().register(
                Commands.literal("mt_scene")
                        .then(Commands.literal("load").then(
                                Commands.argument("scene", ResourceLocationArgument.id()).then(
                                        Commands.argument("origin", Vec3Argument.vec3(false)).executes(
                                                context -> requestLoad(
                                                        ResourceLocationArgument.getId(context, "scene"),
                                                        Vec3Argument.getVec3(context, "origin"))))))
                        .then(Commands.literal("unload").executes(context -> {
                            ClientPacketDistributor.sendToServer(CorePlacementRequest.clear());
                            MineTale.LOGGER.info("已请求服务端卸载 Core");
                            return 1;
                        }))
                        .then(Commands.literal("core").executes(context -> {
                            tell(probe());
                            return 1;
                        })));
    }

    private static int requestLoad(ResourceLocation scene, Vec3 position) {
        ClientPacketDistributor.sendToServer(
                new CorePlacementRequest(
                        CorePlacement.snap(scene, position.x, position.y, position.z)));
        MineTale.LOGGER.info("已请求服务端放置 Core：{}", scene);
        return 1;
    }

    private static void handleState(CoreStatePayload payload, IPayloadContext context) {
        apply(payload);
    }

    private static void apply(CoreStatePayload payload) {
        generation++;
        grid = null;
        bound = Minecraft.getInstance().level;
        CorePlacement placement = payload.placement();
        if (placement == null) {
            SceneClient.dismiss();
            return;
        }
        SceneClient.serve(placement.scene(), new Vec3(placement.x(), placement.y(), placement.z()));
        // 单机：维度加载时服务端已同步就绪（还原或构建），同 JVM 直接复用权威栅格。
        var server = Minecraft.getInstance().getSingleplayerServer();
        if (server != null && bound != null) {
            var manager = CoreRegionManager.of(server.getLevel(bound.dimension()));
            if (manager != null && manager.grid() != null) {
                grid = manager.grid();
                MineTale.LOGGER.info(
                        "Client core collision shared from integrated server: {}", placement);
                return;
            }
        }
        build(placement);
    }

    private static void build(CorePlacement target) {
        int token = generation;
        CompletableFuture.supplyAsync(
                        () -> {
                            try (SceneAsset asset = CoreCollisionBuilder.openAsset(target.scene())) {
                                long started = System.nanoTime();
                                // 本地缓存（键 = 资产摘要 + 放置坐标）：命中免重建，进场预测碰撞即时就绪。
                                Path cache = CoreCollisionStore.clientCache(
                                        HexFormat.of().formatHex(asset.contentDigest(), 0, 8),
                                        target.x(), target.y(), target.z());
                                if (Files.isRegularFile(cache)) {
                                    try {
                                        CoreCollisionGrid stored =
                                                CoreCollisionStore.read(
                                                        cache, target.x(), target.y(), target.z());
                                        return new CoreCollisionBuilder.Result(
                                                stored, 0, 0, (System.nanoTime() - started) / 1_000_000);
                                    } catch (IOException | RuntimeException invalid) {
                                        MineTale.LOGGER.warn(
                                                "Client core collision cache rejected", invalid);
                                    }
                                }
                                CoreCollisionBuilder.Result built =
                                        CoreCollisionBuilder.build(asset, target, () -> token != generation);
                                writeClientCache(cache, built.grid());
                                return built;
                            } catch (IOException failure) {
                                throw new UncheckedIOException(failure);
                            }
                        },
                        WORKER)
                .whenComplete(
                        (result, error) ->
                                Minecraft.getInstance()
                                        .execute(
                                                () -> {
                                                    if (token != generation) return;
                                                    if (error != null) {
                                                        MineTale.LOGGER.error(
                                                                "Client core collision build failed for {}",
                                                                target,
                                                                error);
                                                        return;
                                                    }
                                                    grid = result.grid();
                                                    MineTale.LOGGER.info(
                                                            "Client core collision ready: {} cells={} shells={} sections={} triangles={} snapped={} {}ms",
                                                            target,
                                                            result.grid().cells(),
                                                            result.grid().shells(),
                                                            result.grid().sections(),
                                                            result.triangles(),
                                                            result.snapped(),
                                                            result.millis());
                                                }));
    }

    private static void writeClientCache(Path cache, CoreCollisionGrid built) {
        try {
            CoreCollisionStore.write(cache, "col-", built, StandardCopyOption.REPLACE_EXISTING);
            CoreCollisionStore.pruneClientSiblings(cache);
        } catch (IOException failure) {
            MineTale.LOGGER.warn("Client core collision cache write failed", failure);
        }
    }

    /**
     * 原版 clip 接入：Core 命中比原版结果更近时返回等价的 BlockHitResult（空气位置的假想方块），
     * 使放置、右键使用、射线类交互都以选择框为落点；破坏因位置为空气而天然无效。
     */
    public static BlockHitResult clipCore(ClipContext context, BlockHitResult vanilla) {
        CoreCollisionGrid current = grid;
        Minecraft minecraft = Minecraft.getInstance();
        if (current == null || minecraft.level == null || minecraft.level != bound) return null;
        Vec3 from = context.getFrom();
        Vec3 delta = context.getTo().subtract(from);
        double length = delta.length();
        if (length < 1e-6) return null;
        Vec3 direction = delta.scale(1 / length);
        double limit = length;
        if (vanilla.getType() != HitResult.Type.MISS) {
            double vanillaDistance = from.distanceTo(vanilla.getLocation());
            if (vanillaDistance <= 1e-6) return null;
            limit = Math.min(limit, vanillaDistance);
        }
        CoreCollisionGrid.Hit hit = current.raycastWorld(
                from.x, from.y, from.z, direction.x, direction.y, direction.z, limit);
        if (hit == null) return null;
        Vec3 location = from.add(direction.scale(hit.distance()));
        BlockPos pos = BlockPos.containing(hit.box().minX, hit.box().minY, hit.box().minZ);
        // 与原版对不完整碰撞体的处理一致：占据块按整块对待，落点沿入射面推进到
        // 碰撞体积外的第一个整块，避免放进去再被服务端裁决取消（吞方块）。
        List<AABB> probe = new ArrayList<>(4);
        BlockPos next = pos.relative(hit.face());
        for (int step = 0; step < 64 && blockOccupied(current, next, probe); step++) {
            pos = next;
            next = pos.relative(hit.face());
        }
        return new BlockHitResult(location, hit.face(), pos, false);
    }

    private static boolean blockOccupied(CoreCollisionGrid grid, BlockPos pos, List<AABB> probe) {
        AABB cube = new AABB(
                pos.getX(), pos.getY(), pos.getZ(),
                pos.getX() + 1.0, pos.getY() + 1.0, pos.getZ() + 1.0);
        if (!grid.intersectsWorld(cube)) return false;
        probe.clear();
        return grid.collectWorld(cube, probe) > 0;
    }

    /** 探针：视线命中、脚下占据与栅格统计（mt_scene core）。 */
    public static String probe() {
        CoreCollisionGrid current = grid;
        Minecraft minecraft = Minecraft.getInstance();
        if (current == null || minecraft.level != bound) return "Core 碰撞栅格未就绪";
        var camera = minecraft.gameRenderer.getMainCamera();
        Vec3 from = camera.getPosition();
        Vector3f look = camera.getLookVector();
        CoreCollisionGrid.Hit hit = current.raycastWorld(from.x, from.y, from.z, look.x, look.y, look.z, 64);
        LocalPlayer player = minecraft.player;
        boolean feet = false;
        if (player != null)
            feet = current.solidScene(
                    player.getX() - current.originX(),
                    player.getY() - current.originY(),
                    player.getZ() - current.originZ());
        return "Core 碰撞 "
                + current.describe()
                + (feet ? " 脚下=占据" : " 脚下=空")
                + (hit == null
                        ? " 视线=未命中"
                        : String.format(
                                Locale.ROOT,
                                " 视线=命中 world(%.2f %.2f %.2f) 距离 %.2f",
                                hit.box().minX,
                                hit.box().minY,
                                hit.box().minZ,
                                hit.distance()));
    }

    /** 客户端碰撞注入入口；由 ClientCoreCollisionMixin 调用，仅作用于绑定的客户端世界。 */
    public static List<VoxelShape> appendCollision(Level level, AABB box, List<VoxelShape> original) {
        CoreCollisionGrid current = grid;
        if (current == null || level != bound) return original;
        return current.appendShapes(box, original);
    }

    @SubscribeEvent
    public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        generation++;
        grid = null;
        bound = null;
    }

    /** 光标选择框：视线取最近命中（占据格 DDA + 面壳），比原版目标更近时在本地画线框。 */
    @SubscribeEvent
    public static void outline(RenderLevelStageEvent.AfterEntities event) {
        CoreCollisionGrid current = grid;
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (current == null || player == null || minecraft.level != bound) return;
        var camera = minecraft.gameRenderer.getMainCamera();
        Vec3 from = camera.getPosition();
        Vector3f look = camera.getLookVector();
        CoreCollisionGrid.Hit hit = current.raycastWorld(
                from.x, from.y, from.z, look.x, look.y, look.z, player.blockInteractionRange());
        if (hit == null) return;
        // 原版先命中实体或更近方块时，让位给原版选择框。
        HitResult vanilla = minecraft.hitResult;
        if (vanilla != null
                && vanilla.getType() != HitResult.Type.MISS
                && hit.distance() > from.distanceTo(vanilla.getLocation())) return;
        Vec3 cameraPos = event.getLevelRenderState().cameraRenderState.pos;
        PoseStack poseStack = event.getPoseStack();
        VertexConsumer consumer = minecraft.renderBuffers().bufferSource().getBuffer(RenderType.lines());
        poseStack.pushPose();
        poseStack.translate(hit.box().minX - cameraPos.x, hit.box().minY - cameraPos.y,
                hit.box().minZ - cameraPos.z);
        // 与原版方块选择框同款黑线（alpha 102/255）；命中盒为占据格方块或面壳本身。
        ShapeRenderer.renderLineBox(
                poseStack.last(), consumer, 0, 0, 0,
                hit.box().maxX - hit.box().minX,
                hit.box().maxY - hit.box().minY,
                hit.box().maxZ - hit.box().minZ,
                0F, 0F, 0F, 102F / 255F);
        poseStack.popPose();
    }

    private static void tell(String message) {
        MineTale.LOGGER.info(message);
        var player = Minecraft.getInstance().player;
        if (player != null) player.displayClientMessage(Component.literal(message), false);
    }
}
