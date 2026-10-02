package cn.jehorstudio.minetale.voxel.scene.runtime;

import cn.jehorstudio.minetale.MineTale;
import cn.jehorstudio.minetale.voxel.scene.geometry.SceneLayout;
import cn.jehorstudio.minetale.voxel.scene.geometry.SceneVoxels;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.context.ContextKey;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.AddClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ExtractLevelRenderStateEvent;
import net.neoforged.neoforge.client.event.FrameGraphSetupEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import org.joml.Matrix4f;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// 场景客户端运行时与渲染事件入口；Core 放置经 dimension.region.core 驱动（serve/dismiss），
// 资源解码在专用线程执行。
@EventBusSubscriber(modid = MineTale.MODID, value = Dist.CLIENT)
public final class SceneClient {
    private static final ExecutorService WORKER =
            Executors.newSingleThreadExecutor(
                    r -> {
                        Thread thread = new Thread(r, "MineTale scene reader");
                        thread.setDaemon(true);
                        return thread;
                    });
    private static final ExecutorService GENERATORS = Executors.newFixedThreadPool(
            SceneRuntime.WORKERS,
            Thread.ofPlatform().daemon().name("MineTale scene generator-", 0).factory());
    private static final ContextKey<Frame> FRAME =
            new ContextKey<>(ResourceLocation.fromNamespaceAndPath(MineTale.MODID, "voxel_scene"));
    private static final Matrix4f PROJECTION = new Matrix4f();
    private static SceneRuntime runtime;
    private static ClientLevel level;
    private static ResourceLocation scene;
    private static Vec3 origin = Vec3.ZERO;
    private static SceneRuntime.Mode mode = SceneRuntime.Mode.PAGED;
    private static boolean culling = true, loading;
    private static volatile long generation;

    private SceneClient() {}

    @SubscribeEvent
    public static void commands(RegisterClientCommandsEvent event) {
        var command = Commands.literal("mt_scene");
        command.then(
                Commands.literal("origin")
                        .then(
                                Commands.argument("position", Vec3Argument.vec3(false))
                                        .executes(
                                                ctx -> {
                                                    origin = Vec3Argument.getVec3(ctx, "position");
                                                    return 1;
                                                })));
        command.then(
                Commands.literal("culling")
                        .then(
                                Commands.argument("enabled", BoolArgumentType.bool())
                                        .executes(
                                                ctx -> {
                                                    culling =
                                                            BoolArgumentType.getBool(
                                                                    ctx, "enabled");
                                                    resetMetrics();
                                                    tell(stats());
                                                    return 1;
                                                })));
        command.then(
                Commands.literal("stats")
                        .executes(
                                ctx -> {
                                    tell(stats());
                                    return 1;
                                }));
        command.then(
                Commands.literal("greedy")
                        .then(
                                Commands.argument("enabled", BoolArgumentType.bool())
                                        .executes(
                                                ctx -> {
                                                    if (runtime != null)
                                                        runtime.setGreedy(
                                                                BoolArgumentType.getBool(
                                                                        ctx, "enabled"));
                                                    tell(stats());
                                                    return 1;
                                                })));
        command.then(
                Commands.literal("reset_metrics")
                        .executes(
                                ctx -> {
                                    resetMetrics();
                                    tell("采样计数已清零");
                                    return 1;
                                }));
        var modes = Commands.literal("mode");
        for (SceneRuntime.Mode value : SceneRuntime.Mode.values()) {
            modes.then(
                    Commands.literal(value.name().toLowerCase(java.util.Locale.ROOT))
                            .executes(ctx -> selectMode(value)));
        }
        var z =
                Commands.argument("z", IntegerArgumentType.integer())
                        .then(precisionArguments(true));
        var y = Commands.argument("y", IntegerArgumentType.integer()).then(z);
        var x = Commands.argument("x", IntegerArgumentType.integer()).then(y);
        var precision =
                Commands.literal("precision")
                        .executes(
                                ctx -> {
                                    if (runtime != null) tell(runtime.precisionDescription());
                                    return 1;
                                })
                        .then(precisionArguments(false))
                        .then(Commands.literal("brick").then(x));
        event.getDispatcher().register(command.then(modes).then(precision));
    }

    private static int selectMode(SceneRuntime.Mode selected) {
        if (selected == SceneRuntime.Mode.RAW && runtime != null && !runtime.asset.rawAvailable()) {
            tell("该场景仅包含 Low；请使用 low 或 paged 模式");
            return 0;
        }
        if (selected == SceneRuntime.Mode.VOXEL
                && runtime != null
                && !runtime.asset.sourceAvailable()) {
            tell("该场景没有精细控制网格来源");
            return 0;
        }
        mode = selected;
        resetMetrics();
        tell(stats());
        return 1;
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<
                    net.minecraft.commands.CommandSourceStack, Float>
            precisionArguments(boolean brick) {
        var normal =
                Commands.argument("normal", FloatArgumentType.floatArg(.5F, 8))
                        .executes(ctx -> selectPrecision(ctx, brick));
        var pbr = Commands.argument("pbr", FloatArgumentType.floatArg(.5F, 8)).then(normal);
        var color = Commands.argument("color", FloatArgumentType.floatArg(.5F, 8)).then(pbr);
        return Commands.argument("geometry", FloatArgumentType.floatArg(.5F, 8)).then(color);
    }

    private static int selectPrecision(
            com.mojang.brigadier.context.CommandContext<net.minecraft.commands.CommandSourceStack>
                    ctx,
            boolean brick) {
        if (runtime == null) {
            tell("请先加载场景");
            return 0;
        }
        try {
            float geometry = FloatArgumentType.getFloat(ctx, "geometry");
            float color = FloatArgumentType.getFloat(ctx, "color");
            float pbr = FloatArgumentType.getFloat(ctx, "pbr");
            float normal = FloatArgumentType.getFloat(ctx, "normal");
            var selected = new SceneVoxels.Precision(geometry, color, pbr, normal);
            if (brick)
                runtime.setPrecision(
                        new SceneLayout.Cell(
                                IntegerArgumentType.getInteger(ctx, "x"),
                                IntegerArgumentType.getInteger(ctx, "y"),
                                IntegerArgumentType.getInteger(ctx, "z")),
                        selected);
            else runtime.setPrecision(selected);
            tell(runtime.precisionDescription());
            return 1;
        } catch (IllegalArgumentException invalid) {
            tell(invalid.getMessage());
            return 0;
        }
    }

    /** 供 Core 放置状态同步驱动；占用唯一场景运行时槽位，后到者取代先到者。 */
    public static void serve(ResourceLocation source, Vec3 position) {
        load(source, position, Minecraft.getInstance().getResourceManager());
    }

    public static void dismiss() {
        stop();
    }

    private static void load(ResourceLocation source, Vec3 position, ResourceManager resources) {
        stop();
        scene = source;
        origin = position;
        level = Minecraft.getInstance().level;
        long token = generation;
        boolean output = SceneTerrain.shaderPackInUse();
        loading = true;
        ResourceLocation requested =
                ResourceLocation.fromNamespaceAndPath(
                        source.getNamespace(), "scene/" + source.getPath() + ".mtscene");
        MineTale.LOGGER.info("正在读取场景：{}", source);
        CompletableFuture.supplyAsync(
                        () -> SceneLoader.load(requested, resources, output, () -> token != generation),
                        WORKER)
                .whenComplete(
                        (loaded, error) ->
                                Minecraft.getInstance()
                                        .execute(
                                                () -> {
                                                    if (token != generation) {
                                                        if (loaded != null)
                                                            try {
                                                                loaded.asset().close();
                                                            } catch (IOException ignored) {
                                                            }
                                                        return;
                                                    }
                                                    loading = false;
                                                    if (error != null) {
                                                        MineTale.LOGGER.error(
                                                                "Scene asset load failed", error);
                                                        tell("场景读取失败：" + error.getMessage());
                                                        return;
                                                    }
                                                    runtime =
                                                            new SceneRuntime(
                                                                    loaded.asset(),
                                                                    loaded.low(),
                                                                    loaded.textures(),
                                                                    loaded.seams(),
                                                                    loaded.index(),
                                                                    loaded.surface(),
                                                                    GENERATORS, output);
                                                    if (loaded.warning() != null) {
                                                        mode = SceneRuntime.Mode.LOW;
                                                        MineTale.LOGGER.warn(loaded.warning());
                                                    }
                                                    if (!loaded.asset().rawAvailable()
                                                            && mode == SceneRuntime.Mode.RAW)
                                                        mode = SceneRuntime.Mode.LOW;
                                                    if (!loaded.asset().sourceAvailable()
                                                            && mode == SceneRuntime.Mode.VOXEL) {
                                                        mode = SceneRuntime.Mode.LOW;
                                                        MineTale.LOGGER.info("该场景没有精细控制网格来源，已选择 Low");
                                                    }
                                                    MineTale.LOGGER.info(
                                                            "场景目录已读取：{} 页，开始上传",
                                                            loaded.asset().pages().size());
                                                }));
    }

    @SubscribeEvent
    public static void extract(ExtractLevelRenderStateEvent event) {
        if (level != null && event.getLevel() != level) {
            stop();
            return;
        }
        if (runtime != null)
            event.getRenderState().setRenderData(FRAME, new Frame(runtime, origin, mode, culling));
    }

    @SubscribeEvent
    public static void projection(FrameGraphSetupEvent event) {
        PROJECTION.set(event.getProjectionMatrix());
    }

    @SubscribeEvent
    public static void render(RenderLevelStageEvent.AfterOpaqueBlocks event) {
        SceneGpu.collectRetired();
        if (event.getLevelRenderer() != Minecraft.getInstance().levelRenderer) return;
        Frame frame = event.getLevelRenderState().getRenderData(FRAME);
        if (frame == null) return;
        try {
            frame.runtime.render(event, PROJECTION, frame.origin, frame.mode, frame.culling);
        } catch (Exception error) {
            MineTale.LOGGER.error("Scene rendering failed", error);
            stop();
            tell("场景渲染已停止：" + error.getMessage());
        }
    }

    @SubscribeEvent
    public static void retire(net.neoforged.neoforge.client.event.RenderFrameEvent.Post event) {
        SceneGpu.collectRetired();
    }

    @SubscribeEvent
    public static void reload(AddClientReloadListenersEvent event) {
        event.addListener(
                ResourceLocation.fromNamespaceAndPath(MineTale.MODID, "voxel_scene"),
                (shared, prepare, barrier, apply) ->
                        CompletableFuture.completedFuture(true)
                                .thenCompose(barrier::wait)
                                .thenRunAsync(
                                        () -> {
                                            if (scene != null && level != null)
                                                load(scene, origin, shared.resourceManager());
                                        },
                                        apply));
    }

    @SubscribeEvent
    public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        stop();
    }

    private static void stop() {
        generation++;
        if (runtime != null) runtime.close();
        runtime = null;
        level = null;
        scene = null;
        loading = false;
    }

    private static void resetMetrics() {
        if (runtime != null) runtime.resetMetrics();
    }

    private static String stats() {
        return "Scene mode="
                + mode
                + " origin="
                + origin
                + " camera="
                + Minecraft.getInstance().gameRenderer.getMainCamera().getPosition()
                + " culling="
                + culling
                + " "
                + (loading ? "loading" : runtime == null ? "unloaded" : runtime.stats());
    }

    private static void tell(String message) {
        MineTale.LOGGER.info(message);
        var player = Minecraft.getInstance().player;
        if (player != null) player.displayClientMessage(Component.literal(message), false);
    }

    private record Frame(
            SceneRuntime runtime, Vec3 origin, SceneRuntime.Mode mode, boolean culling) {}
}
