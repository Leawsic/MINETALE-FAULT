package cn.jehorstudio.minetale.magic.spell.gasterblaster.client;

import cn.jehorstudio.minetale.magic.Magic;
import cn.jehorstudio.minetale.magic.collision.MagicCollision;
import cn.jehorstudio.minetale.magic.spell.gasterblaster.GasterBlaster;
import cn.jehorstudio.minetale.magic.visual.vfx.MagicBeamRenderer.BeamFrame;
import cn.jehorstudio.minetale.magic.visual.vfx.MagicBeamRenderer;
import cn.jehorstudio.minetale.magic.visual.vfx.MagicCameraShake;
import cn.jehorstudio.minetale.magic.visual.vfx.MagicScreenDoor;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.ExtractLevelRenderStateEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.constant.dataticket.DataTicket;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.renderer.GeoEntityRenderer;
import software.bernie.geckolib.renderer.base.GeoRenderState;
import software.bernie.geckolib.renderer.base.RenderModelPositioner;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class GasterBlasterRenderer extends GeoEntityRenderer<GasterBlaster, GasterBlasterRenderer.State> {
    // 此坐标属于应用 shoot 后的模型空间，位于上下颌之间。平移后与表现炮口重合。
    private static final Vector3f FINAL_MUZZLE = new Vector3f(0, 18 / 16F, -8 / 16F);

    GasterBlasterRenderer(EntityRendererProvider.Context context) {
        super(context, new Model());
        shadowRadius = 0;
    }

    public static void register(IEventBus bus) {
        bus.addListener((EntityRenderersEvent.RegisterRenderers event) ->
                event.registerEntityRenderer(GasterBlaster.TYPE.get(), GasterBlasterRenderer::new));
        NeoForge.EVENT_BUS.addListener(GasterBlasterRenderer::extract);
        NeoForge.EVENT_BUS.addListener(GasterBlasterRenderer::shake);
        GasterGpuModel.register(bus);
    }

    private static void shake(RenderFrameEvent.Pre event) {
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.level == null) return;
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof GasterBlaster blaster) || blaster.beamRange() <= 0) continue;
            var timing = blaster.animationFrame(partial);
            double elapsed = timing.age() - timing.openingStart();
            if (elapsed < 0 || timing.aperture() <= 0) continue;
            // 开火包络立即取峰值，持续阶段衰减，最后随实际闭口归零
            double duration = timing.windup() + timing.beam() - timing.openingStart();
            double envelope = Math.exp(-3 * elapsed / duration) * timing.retraction();
            MagicCameraShake.submit(blaster.position(),
                    0.042 * blaster.scale() * envelope, 24 + 8 * blaster.scale());
        }
    }

    private static void extract(ExtractLevelRenderStateEvent event) {
        List<BeamFrame> frames = new ArrayList<>();
        float renderRange = net.minecraft.client.Minecraft.getInstance().options.getEffectiveRenderDistance() * 16F;
        float partial = event.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        for (Entity entity : event.getLevel().entitiesForRendering()) {
            if (!(entity instanceof GasterBlaster blaster) || blaster.beamRange() <= 0) continue;
            var timing = blaster.animationFrame(partial);
            float aperture = timing.aperture();
            float radius = blaster.radius() * aperture;
            // 表现按横截面分别贴合墙面，伤害按目标检查受光路径
            float recoil = (float) timing.recoilDistance();
            float length = renderRange * timing.extension() + recoil;
            if (radius <= 0 || length <= 0) continue;
            Vec3 origin = blaster.position().subtract(blaster.direction().scale(recoil));
            // 球心向唇缘前移 4 个模型像素；实体光核半径最多 7.5 像素，口腔容量优先于光束倍率。
            float muzzleBaseRadius = Math.min(blaster.radius() * 1.12F, blaster.scale() * (7.5F / 16));
            float muzzleRadius = muzzleBaseRadius * aperture;
            // 球心与半径必须一起收回，否则尾帧球体会离开炮口，露出光束的平端。
            // 球核与束身粗细直接复用嘴部开度，炮口始终位于球体内部，不能另加独立缓动。
            float muzzleOffset = Math.min(blaster.scale() * (4F / 16), muzzleBaseRadius * 0.5F)
                    * aperture;
            // 外围辉光不参与伤害；视锥和屏幕裁剪必须覆盖它，避免光晕被切成直边。
            var shape = new MagicCollision.Cylinder(origin, blaster.direction(), length, radius * 2.2F);
            AABB bounds = shape.bounds().minmax(new MagicCollision.Sphere(
                    origin.add(shape.direction().scale(muzzleOffset)), muzzleRadius * 1.15F).bounds());
            if (event.getFrustum().isVisible(bounds.inflate(
                    cn.jehorstudio.minetale.magic.MagicConfig.BEAM_DISTORTION_RADIUS * radius))) {
                // 不透明激光只通过口径、球核和长度收束退场，不能把旧透明度乘到 RGB 上变黑。
                frames.add(new BeamFrame(blaster.getId(), blaster.radius() * 2.2F, renderRange, origin, blaster.position(), shape.direction(), radius, length,
                        (float) ((timing.age() - timing.openingStart()) / 20), timing.retraction(), muzzleRadius, muzzleOffset, bounds));
            }
        }
        MagicBeamRenderer.submit(event, frames);
    }

    @Override public void buildRenderTask(State state, PoseStack pose, BakedGeoModel model, GeoModel<GasterBlaster> geoModel,
            OrderedSubmitNodeCollector tasks, CameraRenderState camera, RenderType type, int light, int overlay,
            int color, RenderModelPositioner<State> positioner) {
        if (type == null) return;
        int maskedColor = MagicScreenDoor.color(color, modelOpacity(state.getGeckolibData(GasterBlaster.ANIMATION_FRAME)));
        if ((maskedColor >>> 24) == 0) return;
        if (GasterGpuModel.supports(type)) {
            if (positioner != null) positioner.run(state, model);
            geoModel.handleAnimations(createAnimationState(state));
            GasterGpuModel.submit(model, pose, maskedColor, light, overlay);
            return;
        }

        // 当前 GeckoLib 的独立骨骼层提前取姿态且按子→父变换。两个 pass 都在绘制时完整采样并从根遍历。
        for (boolean eyes : new boolean[]{false, true}) {
            RenderModelPositioner<State> prepare = RenderModelPositioner.add(null, (s, m) -> {
                if (positioner != null) positioner.run(s, m);
                s.eyesPass = eyes;
            });
            super.buildRenderTask(state, pose, model, geoModel, tasks, camera,
                    eyes ? MagicScreenDoor.renderType(getTextureLocation(state), true) : type, eyes ? LightTexture.FULL_BRIGHT : light, overlay, maskedColor, prepare);
        }
    }

    @Override public void renderCubesOfBone(State state, GeoBone bone, PoseStack pose, VertexConsumer buffer,
            CameraRenderState camera, int light, int overlay, int color) {
        boolean eye = bone.getName().equals("left_eye") || bone.getName().equals("right_eye");
        if (eye != state.eyesPass) return;
        super.renderCubesOfBone(state, bone, pose, buffer, camera, light, overlay, color);
    }

    @Override public State createRenderState(GasterBlaster animatable, Void related) { return new State(); }

    @Override protected net.minecraft.world.phys.AABB getBoundingBoxForCulling(GasterBlaster entity) {
        // 实体固定在发射基准；裁剪包络还需覆盖客户端派生的后退动画。
        return entity.visualBounds();
    }

    @Override public void addRenderData(GasterBlaster entity, Void related, State state, float partialTick) {
        state.scale = entity.scale();
        state.direction = entity.direction().toVector3f();
        // 与光束提取使用相同的全局 partial Tick
        var frame = entity.animationFrame(net.minecraft.client.Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false));
        Vec3 muzzle = entity.position().subtract(entity.direction().scale(frame.recoilDistance()));
        state.x = muzzle.x;
        state.y = muzzle.y;
        state.z = muzzle.z;
        state.ageInTicks = frame.age();
        state.addGeckolibData(GasterBlaster.ANIMATION_FRAME, frame);
    }

    @Override public void scaleModelForRender(State state, float width, float height, PoseStack pose,
            BakedGeoModel model, CameraRenderState camera) {
        super.scaleModelForRender(state, width * state.scale, height * state.scale, pose, model, camera);
    }

    @Override public void adjustRenderPose(State state, PoseStack pose, BakedGeoModel model, CameraRenderState camera) {
        pose.mulPose(aimRotation(state.direction));
        pose.translate(-FINAL_MUZZLE.x, -FINAL_MUZZLE.y, -FINAL_MUZZLE.z);
    }

    static Quaternionf aimRotation(Vector3f direction) {
        float yaw = (float) Math.atan2(-direction.x, -direction.z);
        float pitch = (float) Math.atan2(direction.y, Math.hypot(direction.x, direction.z));
        // 先绕世界 Y 确定 yaw，再绕局部 X 确定 pitch；瞄准不能引入 roll。
        return new Quaternionf().rotationY(yaw).rotateX(pitch);
    }

    private static final class Model extends GeoModel<GasterBlaster> {
        private static final ResourceLocation ASSET = Magic.id("magic/gaster_blaster");
        @Override public ResourceLocation getModelResource(GeoRenderState state) { return ASSET; }
        @Override public ResourceLocation getTextureResource(GeoRenderState state) { return Magic.id("textures/magic/gaster_blaster.png"); }
        @Override public ResourceLocation getAnimationResource(GasterBlaster entity) { return ASSET; }
        @Override public RenderType getRenderType(GeoRenderState state, ResourceLocation texture) {
            // 眼窝是正反面使用不同 UV 的零厚度平面。NoCull 会同时绘制黑色正面和灰色背面，产生 Z-fighting。
            return MagicScreenDoor.renderType(texture, false);
        }
    }

    static float modelOpacity(GasterBlaster.AnimationFrame frame) {
        // 在入场移动稳定前完成渐显；按原动画时间比例缩放
        float appear = Math.clamp((float) (frame.age() / (frame.openingStart() * 0.83)), 0, 1);
        // close 后半段嘴已合拢，此时渐隐
        float disappear = Math.clamp((frame.age() - frame.windup() - frame.beam() - frame.close() * 0.5F)
                / (frame.close() * 0.5F), 0, 1);
        return appear * appear * (3 - 2 * appear) * (1 - disappear * disappear * (3 - 2 * disappear));
    }

    static final class State extends EntityRenderState implements GeoRenderState {
        private final Map<DataTicket<?>, Object> data = new HashMap<>();
        float scale;
        boolean eyesPass;
        Vector3f direction = new Vector3f(0, 0, -1);
        @Override public <D> void addGeckolibData(DataTicket<D> ticket, D value) { data.put(ticket, value); }
        @Override public boolean hasGeckolibData(DataTicket<?> ticket) { return data.containsKey(ticket); }
        @Override @SuppressWarnings("unchecked") public <D> D getGeckolibData(DataTicket<D> ticket) {
            if (!data.containsKey(ticket)) throw new IllegalArgumentException("缺失 Gaster RenderState 数据: " + ticket);
            return (D) data.get(ticket);
        }
        @Override public Map<DataTicket<?>, Object> getDataMap() { return data; }
    }
}
