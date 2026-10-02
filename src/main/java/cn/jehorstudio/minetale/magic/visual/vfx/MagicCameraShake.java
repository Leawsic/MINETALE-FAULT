package cn.jehorstudio.minetale.magic.visual.vfx;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.client.gui.screens.options.AccessibilityOptionsScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/** 客户端逐帧镜头震动；调用方拥有震源时序，本类负责距离衰减、叠加、上限及辅助功能倍率。 */
public final class MagicCameraShake {
    private static final double MAX_DISPLACEMENT = 0.12;
    private static final List<Source> SOURCES = new ArrayList<>();

    private MagicCameraShake() {}

    public static void register() {
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, (RenderFrameEvent.Pre event) -> SOURCES.clear());
        NeoForge.EVENT_BUS.addListener(MagicCameraShake::addOption);
        // 拖动只改内存，关闭页面后保存，避免逐帧写盘与配置文件重载竞争。
        NeoForge.EVENT_BUS.addListener((ScreenEvent.Closing event) -> {
            if (event.getScreen() instanceof AccessibilityOptionsScreen) CameraShakeConfig.save();
        });
    }

    /**
     * 在 RenderFrameEvent.Pre 的 NORMAL 或更低优先级中提交当前帧震源，仅限客户端渲染线程。
     * 下一帧自动清空；离开世界或调用方停止提交后不保留震动。
     * @param position 震源世界坐标
     * @param strength 当前包络强度，单位为方块，必须有限且非负；时序衰减由调用方提供
     * @param range 有限正数，距离达到此方块数时完全衰减
     */
    public static void submit(Vec3 position, double strength, double range) {
        if (position == null || !Double.isFinite(position.x) || !Double.isFinite(position.y)
                || !Double.isFinite(position.z) || !Double.isFinite(strength) || strength < 0
                || !Double.isFinite(range) || range <= 0) {
            throw new IllegalArgumentException("镜头震源必须具有有限坐标、非负强度和正作用距离");
        }
        if (strength > 0) SOURCES.add(new Source(position, strength, range));
    }

    // Camera.setup TAIL 调用：此时第三人称、睡眠、矿车分支均已算好真实镜头位置。
    // 只改主相机，不修改玩家朝向或辅助场景捕获相机，也不累积上一帧位移。
    public static Vec3 displacement(Camera camera, float partialTick) {
        var mc = Minecraft.getInstance();
        if (mc.level == null || camera != mc.gameRenderer.getMainCamera() || SOURCES.isEmpty()) return Vec3.ZERO;
        double strength = 0;
        for (Source source : SOURCES) {
            double attenuation = Math.max(0, 1 - camera.getPosition().distanceTo(source.position) / source.range);
            strength += source.strength * attenuation * attenuation;
        }
        // 先限制总量再乘用户倍率，避免大量炮击抵消用户降低震动的设置。
        strength = Math.min(MAX_DISPLACEMENT, strength) * CameraShakeConfig.scale();
        if (strength == 0) return Vec3.ZERO;
        double time = (mc.level.getGameTime() + (double) partialTick) / 20.0;
        // 在镜头局部坐标中平移震颤；暂停时沿用冻结的游戏时间。
        Vector3f local = new Vector3f((float) (wave(time, 15.7, 21.7) * strength),
                (float) (wave(time, 17.3, 23.1) * strength),
                (float) (wave(time, 18.9, 24.7) * strength * 0.1));
        Vec3 offset = new Vec3(local.rotate(camera.rotation()));
        double length = offset.length();
        if (length == 0) return Vec3.ZERO;
        // 沿用原版相机的 0.1 方块八角采样，限制整个位移，避免靠墙震动穿入可见方块。
        double fraction = 1;
        for (int i = 0; i < 8; i++) {
            Vec3 start = camera.getPosition().add(((i & 1) * 2 - 1) * 0.1,
                    (((i >> 1) & 1) * 2 - 1) * 0.1, (((i >> 2) & 1) * 2 - 1) * 0.1);
            var hit = mc.level.clip(new ClipContext(start, start.add(offset),
                    ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, camera.getEntity()));
            if (hit.getType() != HitResult.Type.MISS) {
                fraction = Math.min(fraction, Math.max(0, (start.distanceTo(hit.getLocation()) - 0.001) / length));
            }
        }
        return offset.scale(fraction);
    }
    private static double wave(double time, double first, double second) {
        return (Math.sin(time * Math.PI * 2 * first) + 0.35 * Math.sin(time * Math.PI * 2 * second)) / 1.35;
    }

    private static void addOption(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof AccessibilityOptionsScreen)) return;
        for (var listener : event.getListenersList()) {
            if (listener instanceof OptionsList list) {
                list.addBig(new OptionInstance<>("minetale.options.camera_shake",
                        OptionInstance.cachedConstantTooltip(Component.translatable("minetale.options.camera_shake.tooltip")),
                        (caption, value) -> Component.translatable("options.percent_value", caption, Math.round(value * 100)),
                        OptionInstance.UnitDouble.INSTANCE, CameraShakeConfig.scale(), CameraShakeConfig::setScale));
                return;
            }
        }
    }

    private record Source(Vec3 position, double strength, double range) {}
}
