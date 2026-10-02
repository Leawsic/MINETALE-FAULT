package cn.jehorstudio.minetale.content.player.soul;

import cn.jehorstudio.minetale.MineTale;
import cn.jehorstudio.minetale.lib.client.render.WorldModelRenderer;
import cn.jehorstudio.minetale.content.player.soul.SoulActionController.ActionFrame;
import cn.jehorstudio.minetale.content.player.soul.SoulActionController.Pose;
import cn.jehorstudio.minetale.content.player.soul.SoulFlight.KinematicState;
import cn.jehorstudio.minetale.content.player.soul.mixin.client.ItemInHandRendererInvoker;
import cn.jehorstudio.minetale.lib.ObjModels;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.context.ContextKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ExtractLevelRenderStateEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.RenderPlayerEvent;
import org.joml.Matrix4f;

// 客户端表现唯一所有者：统一协调空手姿态、可见 Transform 与完整模型提交。
public final class SoulClientPresentation {
    private static final float SOUL_SCALE = 0.16F;
    private static final double FIRST_PERSON_FORWARD_OFFSET = 0.30;
    private static final double FIRST_PERSON_DOWN_OFFSET = 0.46;
    private static final long CONTROLLER_HANDOFF_GRACE_FRAMES = 4L;
    private static final Vec3 WORLD_DOWN = new Vec3(0.0, -1.0, 0.0);
    private static final ContextKey<List<Pose>> WORLD_POSES = new ContextKey<>(
            ResourceLocation.fromNamespaceAndPath(MineTale.MODID, "soul/world_poses")
    );
    private static final WorldModelRenderer MODEL_RENDERER = new WorldModelRenderer(
            ObjModels.SOUL,
            SOUL_SCALE,
            1.0F,
            0.025F,
            0.045F,
            1.0F
    );
    private static final Map<UUID, ControllerEntry> CONTROLLERS = new HashMap<>();
    private static long extractionSequence;

    private SoulClientPresentation() {
    }

    private static void renderHand(RenderHandEvent event) {
        if (!Soul.isSoulItem(event.getItemStack())) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null) {
            return;
        }

        event.setCanceled(true);
        ((ItemInHandRendererInvoker) minecraft.gameRenderer.itemInHandRenderer).minetale$renderArmWithItem(
                player,
                event.getPartialTick(),
                event.getInterpolatedPitch(),
                event.getHand(),
                event.getSwingProgress(),
                ItemStack.EMPTY,
                event.getEquipProgress(),
                event.getPoseStack(),
                event.getSubmitNodeCollector(),
                event.getPackedLight()
        );
    }

    private static void preparePlayerRender(RenderPlayerEvent.Pre<?> event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null
                || !(minecraft.level.getEntity(event.getRenderState().id) instanceof Player player)) {
            return;
        }

        AvatarRenderState renderState = event.getRenderState();
        if (Soul.isSoulItem(player.getItemHeldByArm(HumanoidArm.RIGHT))) {
            renderState.rightHandItem.clear();
            renderState.rightArmPose = HumanoidModel.ArmPose.EMPTY;
        }
        if (Soul.isSoulItem(player.getItemHeldByArm(HumanoidArm.LEFT))) {
            renderState.leftHandItem.clear();
            renderState.leftArmPose = HumanoidModel.ArmPose.EMPTY;
        }
    }

    private static void extractLevelRenderState(ExtractLevelRenderStateEvent event) {
        List<Pose> poses = extractPoses(event.getLevel(), event.getCamera());
        if (!poses.isEmpty()) {
            event.getRenderState().setRenderData(WORLD_POSES, poses);
        }
    }

    private static void render(RenderLevelStageEvent.AfterEntities event) {
        if (event.getLevelRenderer() != Minecraft.getInstance().levelRenderer) {
            return;
        }
        List<Pose> poses = event.getLevelRenderState().getRenderData(WORLD_POSES);
        if (poses == null) {
            return;
        }
        for (Pose pose : poses) {
            MODEL_RENDERER.render(event, pose.position(), modelOrientation(pose));
        }
    }

    private static void clear() {
        CONTROLLERS.clear();
        extractionSequence = 0L;
    }

    private static List<Pose> extractPoses(ClientLevel level, Camera camera) {
        if (!camera.isInitialized()) {
            return List.of();
        }

        float partialTick = camera.getPartialTickTime();
        Vec3 cameraPosition = camera.getPosition();
        Vec3 cameraHorizontalForward = Vec3.directionFromRotation(0.0F, camera.getYRot()).normalize();
        List<Pose> poses = new ArrayList<>();
        List<Player> heldPlayers = new ArrayList<>();
        Set<UUID> projectedOwners = new HashSet<>();
        long sequence = ++extractionSequence;
        double sampleTick = level.getGameTime() + partialTick;

        for (Entity entity : level.entitiesForRendering()) {
            if (entity instanceof SoulEntity soulEntity && soulEntity.soulState() != Soul.State.ITEM) {
                UUID ownerUuid = soulEntity.ownerUuid();
                if (ownerUuid == null) {
                    continue;
                }
                poses.add(samplePose(
                        ownerUuid,
                        sampleTick,
                        soulEntity.visualActionFrame(sampleTick, partialTick),
                        sequence
                ));
                projectedOwners.add(ownerUuid);
            } else if (entity instanceof Player player && holdsSoul(player)) {
                heldPlayers.add(player);
            }
        }

        Minecraft minecraft = Minecraft.getInstance();
        for (Player player : heldPlayers) {
            UUID ownerUuid = player.getUUID();
            if (projectedOwners.contains(ownerUuid)) {
                continue;
            }
            boolean firstPersonOwner = player == minecraft.player
                    && minecraft.options.getCameraType().isFirstPerson();
            poses.add(samplePose(
                    ownerUuid,
                    sampleTick,
                    heldActionFrame(
                            player,
                            partialTick,
                            firstPersonOwner,
                            cameraPosition,
                            cameraHorizontalForward
                    ),
                    sequence
            ));
            projectedOwners.add(ownerUuid);
        }

        CONTROLLERS.entrySet().removeIf(entry -> !projectedOwners.contains(entry.getKey())
                && sequence - entry.getValue().lastSeenSequence > CONTROLLER_HANDOFF_GRACE_FRAMES);
        poses.sort(Comparator.comparingDouble(
                (Pose pose) -> pose.position().distanceToSqr(cameraPosition)
        ).reversed());
        return List.copyOf(poses);
    }

    private static Pose samplePose(UUID ownerUuid, double sampleTick, ActionFrame action, long sequence) {
        ControllerEntry entry = CONTROLLERS.computeIfAbsent(
                ownerUuid,
                ignored -> new ControllerEntry(new SoulActionController())
        );
        entry.lastSeenSequence = sequence;
        return entry.controller.sample(sampleTick, action);
    }

    private static ActionFrame heldActionFrame(
            Player player,
            float partialTick,
            boolean firstPersonOwner,
            Vec3 cameraPosition,
            Vec3 cameraHorizontalForward
    ) {
        Vec3 chestForward = SoulPresentationAnchors.chestForward(player, partialTick);
        Vec3 targetPosition = firstPersonOwner
                ? cameraPosition
                        .add(cameraHorizontalForward.scale(FIRST_PERSON_FORWARD_OFFSET))
                        .add(WORLD_DOWN.scale(FIRST_PERSON_DOWN_OFFSET))
                : SoulPresentationAnchors.chestAnchor(player, partialTick);
        Vec3 targetVelocity = player.getPosition(1.0F).subtract(player.getPosition(0.0F));
        KinematicState target = new KinematicState(targetPosition, targetVelocity, Vec3.ZERO);
        return firstPersonOwner
                ? ActionFrame.held(target, WORLD_DOWN, cameraHorizontalForward)
                : ActionFrame.attached(target, WORLD_DOWN, chestForward);
    }

    private static boolean holdsSoul(Player player) {
        return Soul.isSoulItem(player.getMainHandItem()) || Soul.isSoulItem(player.getOffhandItem());
    }

    // soul.obj 局部 -Y、X、Z 分别对应尖端轴、正面法线与心形宽度轴。
    static Matrix4f modelOrientation(Pose pose) {
        Vec3 tipAxis = pose.tipAxis();
        Vec3 faceNormal = pose.faceNormal();
        Vec3 radialRight = pose.radialRight();
        return new Matrix4f(
                (float) faceNormal.x, (float) faceNormal.y, (float) faceNormal.z, 0.0F,
                (float) -tipAxis.x, (float) -tipAxis.y, (float) -tipAxis.z, 0.0F,
                (float) -radialRight.x, (float) -radialRight.y, (float) -radialRight.z, 0.0F,
                0.0F, 0.0F, 0.0F, 1.0F
        );
    }

    // 下列方法只适配 NeoForge 客户端事件
    @EventBusSubscriber(modid = MineTale.MODID, value = Dist.CLIENT)
    public static final class ClientEvents {
        private ClientEvents() {
        }

        @SubscribeEvent
        public static void onRenderHand(RenderHandEvent event) {
            renderHand(event);
        }

        @SubscribeEvent
        public static void onRenderPlayer(RenderPlayerEvent.Pre<?> event) {
            preparePlayerRender(event);
        }

        @SubscribeEvent
        public static void onExtractLevelRenderState(ExtractLevelRenderStateEvent event) {
            extractLevelRenderState(event);
        }

        @SubscribeEvent
        public static void onAfterEntities(RenderLevelStageEvent.AfterEntities event) {
            render(event);
        }

        @SubscribeEvent
        public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
            clear();
        }
    }

    private static final class ControllerEntry {
        private final SoulActionController controller;
        private long lastSeenSequence;

        private ControllerEntry(SoulActionController controller) {
            this.controller = controller;
        }
    }
}
