package cn.jehorstudio.minetale.lib.client.particle.gpu;

import cn.jehorstudio.minetale.MineTale;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ExtractLevelRenderStateEvent;
import net.neoforged.neoforge.client.event.FrameGraphSetupEvent;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

// GPU 粒子的注册与帧事件所有者
// 领域只提供 ParticleAction，调用方按 ID 控制生命周期。
public final class GpuParticleSystem {
    private static final GpuParticleRenderer RENDERER = new GpuParticleRenderer();
    private static final Map<ResourceLocation, ManagedParticleAction> ACTIONS = new LinkedHashMap<>();
    private static final Matrix4f PROJECTION_MATRIX = new Matrix4f();
    private static boolean hasProjection;

    private GpuParticleSystem() {
    }

    // 重复 start 不重置动作实例或时间线
    public static boolean start(ResourceLocation actionId) {
        return action(actionId).start(Minecraft.getInstance());
    }

    public static void stop(ResourceLocation actionId) {
        action(actionId).stop();
    }

    // 返回切换后的运行状态
    public static boolean toggle(ResourceLocation actionId) {
        return action(actionId).toggle(Minecraft.getInstance());
    }

    public static boolean isRunning(ResourceLocation actionId) {
        return action(actionId).isRunning();
    }

    // 动作必须在 RenderPipeline 注册事件前完成注册。
    public static void register(ParticleAction action) {
        ManagedParticleAction managedAction = new ManagedParticleAction(
                Objects.requireNonNull(action, "action")
        );
        ManagedParticleAction previous = ACTIONS.putIfAbsent(managedAction.id(), managedAction);
        if (previous != null) {
            throw new IllegalArgumentException("ParticleAction ID 重复: " + managedAction.id());
        }
    }

    private static void stopAll() {
        for (ManagedParticleAction action : ACTIONS.values()) {
            action.stop();
        }
    }

    private static void registerRenderPipelines(RegisterRenderPipelinesEvent event) {
        RENDERER.registerPipeline(event);
        for (ManagedParticleAction action : ACTIONS.values()) {
            action.registerPipeline(event);
        }
    }

    private static void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            stopAll();
            return;
        }
        for (ManagedParticleAction action : ACTIONS.values()) {
            action.tick(minecraft);
        }
    }

    private static void extractLevelRenderState(ExtractLevelRenderStateEvent event) {
        for (ManagedParticleAction action : ACTIONS.values()) {
            action.extractFrame(event);
        }
    }

    private static void captureProjection(FrameGraphSetupEvent event) {
        PROJECTION_MATRIX.set(event.getProjectionMatrix());
        hasProjection = true;
    }

    private static void render(RenderLevelStageEvent.AfterEntities event) {
        if (event.getLevelRenderer() != Minecraft.getInstance().levelRenderer) {
            return;
        }
        RENDERER.beginFrame();
        for (ManagedParticleAction action : ACTIONS.values()) {
            action.renderFrame(event, RENDERER, PROJECTION_MATRIX, hasProjection);
        }
        if (hasProjection) {
            RENDERER.endFrame(event, PROJECTION_MATRIX);
        }
    }

    private static void closeActions() {
        stopAll();
        RENDERER.close();
        hasProjection = false;
    }

    private static ManagedParticleAction action(ResourceLocation actionId) {
        ManagedParticleAction action = ACTIONS.get(Objects.requireNonNull(actionId, "actionId"));
        if (action == null) {
            throw new IllegalArgumentException("未知 ParticleAction: " + actionId);
        }
        return action;
    }

    // NeoForge 事件适配器不属于领域调用接口。
    @EventBusSubscriber(modid = MineTale.MODID, value = Dist.CLIENT)
    public static final class ClientEvents {
        private ClientEvents() {
        }

        @SubscribeEvent
        public static void onRegisterRenderPipelines(RegisterRenderPipelinesEvent event) {
            registerRenderPipelines(event);
        }

        @SubscribeEvent
        public static void onClientTick(ClientTickEvent.Post event) {
            tick();
        }

        @SubscribeEvent
        public static void onExtractLevelRenderState(ExtractLevelRenderStateEvent event) {
            extractLevelRenderState(event);
        }

        @SubscribeEvent
        public static void onFrameGraphSetup(FrameGraphSetupEvent event) {
            captureProjection(event);
        }

        @SubscribeEvent
        public static void onAfterEntities(RenderLevelStageEvent.AfterEntities event) {
            render(event);
        }

        @SubscribeEvent
        public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
            closeActions();
        }
    }
}
