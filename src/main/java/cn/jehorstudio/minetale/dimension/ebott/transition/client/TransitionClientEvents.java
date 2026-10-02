package cn.jehorstudio.minetale.dimension.ebott.transition.client;

import cn.jehorstudio.minetale.MineTale;
import cn.jehorstudio.minetale.dimension.ebott.transition.client.particle.BarrierVortexParticleAction;
import cn.jehorstudio.minetale.dimension.ebott.transition.client.render.BarrierRender;
import cn.jehorstudio.minetale.dimension.ebott.transition.client.render.TargetDimensionRender;
import cn.jehorstudio.minetale.lib.client.particle.gpu.GpuParticleSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

// 驱动无 Screen 换维的 Tracker、断线清理与目标视窗；普通 Level unload 不终止活跃会话。
@EventBusSubscriber(modid = MineTale.MODID, value = Dist.CLIENT)
public final class TransitionClientEvents {
    private static final int TRACE_FRAMES_AFTER_DIMENSION_CHANGE = 20;
    private static ResourceKey<Level> lastRenderedDimension;
    private static int traceFramesRemaining;
    private static long renderedFrame;
    private static boolean opaqueStageSeen;

    private TransitionClientEvents() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        TransitionClient.INSTANCE.tick();
        Minecraft minecraft = Minecraft.getInstance();
        boolean barrierAvailable = minecraft.level != null
                && Level.OVERWORLD.equals(minecraft.level.dimension())
                && TransitionClient.INSTANCE.renderableBarrier() != null;
        if (barrierAvailable && !GpuParticleSystem.isRunning(BarrierVortexParticleAction.ID)) {
            GpuParticleSystem.start(BarrierVortexParticleAction.ID);
        } else if (!barrierAvailable && GpuParticleSystem.isRunning(BarrierVortexParticleAction.ID)) {
            GpuParticleSystem.stop(BarrierVortexParticleAction.ID);
        }
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        TransitionClient.INSTANCE.shutdown();
        TargetDimensionRender.INSTANCE.close();
        BarrierRender.INSTANCE.close();
    }

    // 目标 Section mesh 与源世界 opaque pass 共用深度关系。
    @SubscribeEvent
    public static void onAfterOpaqueBlocks(RenderLevelStageEvent.AfterOpaqueBlocks event) {
        opaqueStageSeen = true;
        TargetDimensionRender.INSTANCE.render(event);
    }

    // 在实体后绘制结界，保留既有世界深度对 emissive 材质的遮挡。
    @SubscribeEvent
    public static void onAfterEntities(RenderLevelStageEvent.AfterEntities event) {
        BarrierRender.INSTANCE.render(event);
    }

    @SubscribeEvent
    public static void onRenderFramePre(RenderFrameEvent.Pre event) {
        Minecraft minecraft = Minecraft.getInstance();
        renderedFrame++;
        opaqueStageSeen = false;
        if (minecraft.level != null) {
            ResourceKey<Level> dimension = minecraft.level.dimension();
            if (lastRenderedDimension != null && !lastRenderedDimension.equals(dimension)) {
                traceFramesRemaining = TRACE_FRAMES_AFTER_DIMENSION_CHANGE;
            }
            lastRenderedDimension = dimension;
        }
    }

    @SubscribeEvent
    public static void onRenderFramePost(RenderFrameEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (traceFramesRemaining <= 0 || minecraft.level == null) {
            return;
        }
        MineTale.LOGGER.info(
                "[DEBUG-transition-flicker] frame={} dimension={} renderer={} opaqueStage={} camera={} player={} renderedSections={} stats={} target={}",
                renderedFrame,
                minecraft.level.dimension().location(),
                Integer.toHexString(System.identityHashCode(minecraft.levelRenderer)),
                opaqueStageSeen,
                minecraft.gameRenderer.getMainCamera().getPosition(),
                minecraft.player == null ? "null" : minecraft.player.position(),
                minecraft.levelRenderer.countRenderedSections(),
                minecraft.levelRenderer.getSectionStatistics(),
                TargetDimensionRender.INSTANCE.debugLine());
        traceFramesRemaining--;
    }

    // 诊断文本仅进入已开启的 F3 overlay。
    @SubscribeEvent
    public static void onRenderDebug(RenderGuiEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.getDebugOverlay().showDebugScreen()) {
            return;
        }
        int y = event.getGuiGraphics().guiHeight() / 2;
        event.getGuiGraphics().drawString(
                minecraft.font, TransitionClient.INSTANCE.debugLine(), 2, y, 0xFFA8E6C2, true);
        y += 10;
        event.getGuiGraphics().drawString(
                minecraft.font, TargetDimensionRender.INSTANCE.debugLine(), 2, y, 0xFFA8E6C2, true);
    }
}
