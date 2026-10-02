package cn.jehorstudio.minetale.dimension.ebott.transition.mixin.client.network;

import cn.jehorstudio.minetale.dimension.ebott.transition.client.TransitionClient;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.LevelLoadTracker;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.Holder;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.dimension.DimensionType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// 拦截主世界到地下的预热 Respawn，使真实 Tracker 将预送 Chunk 写入 prepared world。
@Mixin(ClientPacketListener.class)
abstract class PreparedTargetPacketListenerMixin {
    @Shadow
    private LevelLoadTracker levelLoadTracker;

    @Shadow
    private ClientLevel level;

    @Shadow
    private ClientLevel.ClientLevelData levelData;

    @Shadow
    private int serverChunkRadius;

    @Shadow
    private int serverSimulationDistance;

    @Shadow
    private void updateLevelChunk(int x, int z, ClientboundLevelChunkPacketData data) {
        throw new AssertionError();
    }

    @Shadow
    private void applyLightData(int x, int z, ClientboundLightUpdatePacketData data, boolean update) {
        throw new AssertionError();
    }

    @Shadow
    private void enableChunkLight(LevelChunk chunk, int x, int z) {
        throw new AssertionError();
    }

    @Inject(
            method = "handleRespawn",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/network/protocol/game/ClientboundRespawnPacket;commonPlayerSpawnInfo()Lnet/minecraft/network/protocol/game/CommonPlayerSpawnInfo;"
            ),
            cancellable = true
    )
    private void minetale$prepareTargetWorld(
            ClientboundRespawnPacket packet,
            CallbackInfo callback
    ) {
        TransitionClient transition = TransitionClient.INSTANCE;
        if (!transition.shouldInterceptPreparationRespawn(packet.commonPlayerSpawnInfo())) {
            transition.markActualRespawnStarted();
            return;
        }
        transition.prepareTargetWorld(
                (ClientPacketListener) (Object) this,
                packet.commonPlayerSpawnInfo(),
                this.levelData,
                this.serverChunkRadius,
                this.serverSimulationDistance
        );
        callback.cancel();
    }

    @Inject(
            method = "handleLevelChunkWithLight",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/network/protocol/game/ClientboundLevelChunkWithLightPacket;getX()I"
            ),
            cancellable = true
    )
    private void minetale$interceptTargetChunk(
            ClientboundLevelChunkWithLightPacket packet,
            CallbackInfo callback
    ) {
        TransitionClient transition = TransitionClient.INSTANCE;
        if (!transition.interceptTargetChunk(packet)) {
            return;
        }
        ClientLevel preparedLevel = transition.preparedTargetLevel();
        if (preparedLevel == null || transition.preparedTargetRenderer() == null) {
            return;
        }
        transition.enqueueTargetChunkInstall(
                () -> minetale$installTargetChunkIntoPreparedWorld(packet, preparedLevel, transition)
        );
        callback.cancel();
    }

    private void minetale$installTargetChunkIntoPreparedWorld(
            ClientboundLevelChunkWithLightPacket packet,
            ClientLevel preparedLevel,
            TransitionClient transition
    ) {
        long started = System.nanoTime();
        ClientLevel sourceLevel = this.level;
        try {
            this.level = preparedLevel;
            int chunkX = packet.getX();
            int chunkZ = packet.getZ();
            updateLevelChunk(chunkX, chunkZ, packet.getChunkData());
            applyLightData(chunkX, chunkZ, packet.getLightData(), false);
            LevelChunk chunk = preparedLevel.getChunkSource().getChunk(chunkX, chunkZ, false);
            if (chunk != null) {
                enableChunkLight(chunk, chunkX, chunkZ);
                transition.preparedTargetRenderer().onChunkReadyToRender(chunk.getPos());
            }
            transition.markTargetChunkInstalled(packet, System.nanoTime() - started);
        } finally {
            this.level = sourceLevel;
        }
    }

    @WrapOperation(
            method = "handleRespawn",
            at = @At(
                    value = "NEW",
                    target = "(Lnet/minecraft/client/multiplayer/ClientPacketListener;Lnet/minecraft/client/multiplayer/ClientLevel$ClientLevelData;Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/core/Holder;IILnet/minecraft/client/renderer/LevelRenderer;ZJI)Lnet/minecraft/client/multiplayer/ClientLevel;"
            )
    )
    private ClientLevel minetale$adoptPreparedTargetWorld(
            ClientPacketListener connection,
            ClientLevel.ClientLevelData levelData,
            ResourceKey<Level> dimension,
            Holder<DimensionType> dimensionType,
            int viewDistance,
            int simulationDistance,
            LevelRenderer renderer,
            boolean debug,
            long seed,
            int seaLevel,
            Operation<ClientLevel> original
    ) {
        ClientLevel prepared = TransitionClient.INSTANCE.adoptPreparedTargetWorld(dimension);
        if (prepared != null) {
            this.levelData = prepared.getLevelData();
            return prepared;
        }
        return original.call(
                connection,
                levelData,
                dimension,
                dimensionType,
                viewDistance,
                simulationDistance,
                renderer,
                debug,
                seed,
                seaLevel
        );
    }

    @Inject(
            method = "handleRespawn",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/Minecraft;setLevel(Lnet/minecraft/client/multiplayer/ClientLevel;)V"
            )
    )
    private void minetale$activatePreparedTargetRenderer(
            ClientboundRespawnPacket packet,
            CallbackInfo callback
    ) {
        TransitionClient.INSTANCE.activatePreparedTargetWorld(
                this.level,
                Minecraft.getInstance()
        );
    }

    @Inject(
            method = "startWaitingForNewLevel(Lnet/minecraft/client/player/LocalPlayer;Lnet/minecraft/client/multiplayer/ClientLevel;Lnet/minecraft/client/gui/screens/LevelLoadingScreen$Reason;Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/resources/ResourceKey;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/multiplayer/LevelLoadTracker;startClientLoad(Lnet/minecraft/client/player/LocalPlayer;Lnet/minecraft/client/multiplayer/ClientLevel;Lnet/minecraft/client/renderer/LevelRenderer;)V",
                    shift = At.Shift.AFTER
            ),
            cancellable = true
    )
    private void minetale$suppressTransitionScreenSelection(
            LocalPlayer player,
            ClientLevel level,
            net.minecraft.client.gui.screens.LevelLoadingScreen.Reason reason,
            ResourceKey<Level> toDimension,
            ResourceKey<Level> fromDimension,
            CallbackInfo callback
    ) {
        if (TransitionClient.INSTANCE.beginTargetLoad(
                toDimension,
                fromDimension,
                this.levelLoadTracker,
                Minecraft.getInstance()
        )) {
            callback.cancel();
        }
    }
}
