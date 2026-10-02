package cn.jehorstudio.minetale.dimension.ebott.transition;

import cn.jehorstudio.minetale.MineTale;
import cn.jehorstudio.minetale.dimension.worldgen.registry.ModWorldgenKeys;
import cn.jehorstudio.minetale.dimension.ebott.EbottData;
import cn.jehorstudio.minetale.dimension.ebott.EbottDestination;
import cn.jehorstudio.minetale.dimension.ebott.PlaceManager;
import cn.jehorstudio.minetale.dimension.ebott.shaft.ShaftData;
import cn.jehorstudio.minetale.dimension.ebott.transition.payload.AbortTransitionPayload;
import cn.jehorstudio.minetale.dimension.ebott.transition.payload.BarrierStatePayload;
import cn.jehorstudio.minetale.dimension.ebott.transition.payload.TargetChunkStreamFinishedPayload;
import cn.jehorstudio.minetale.dimension.ebott.transition.payload.TransitionBeginPayload;
import cn.jehorstudio.minetale.dimension.ebott.transition.payload.VisualCommitPayload;
import cn.jehorstudio.minetale.dimension.ebott.transition.seam.DimensionSeam;
import cn.jehorstudio.minetale.dimension.ebott.transition.seam.SeamCrossingDetector;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;

// 服务端唯一拥有结界会话与通行状态
public final class TransitionManager {
    // 预热在玩家接近山体外缘时启动
    private static final int PREWARM_MARGIN = 512;
    // 与 ClientChunkCache 的 max(2, configuredViewDistance) + 3 保持一致。
    private static final int MIN_CLIENT_CHUNK_STORAGE_RADIUS = 5;
    private static final int MAX_CHUNK_PACKETS_PER_TICK = 2;
    private static final long CHUNK_PACKET_BUDGET_NANOS = 1_250_000L;
    // 伤害产生时实体已被解算到膜面，微量扩张用于重新命中刚好相切的结界。
    private static final double BARRIER_CONTACT_EPSILON = 1.0E-3;
    private static final Map<MinecraftServer, TransitionManager> MANAGERS = new IdentityHashMap<>();

    private final MinecraftServer server;
    private final CacheManager cacheManager;
    private final Map<UUID, TransitionSession> sessions = new HashMap<>();
    private final Map<UUID, TargetChunkPreload> targetChunkPreloads = new HashMap<>();
    private DimensionSeam currentSeam;

    private TransitionManager(MinecraftServer server) {
        this.server = server;
        this.cacheManager = new CacheManager(server);
    }

    public static void register(IEventBus eventBus) {
        eventBus.addListener(TransitionManager::onServerTick);
        eventBus.addListener(TransitionManager::onPlayerLogout);
        eventBus.addListener(TransitionManager::onLivingDeath);
        eventBus.addListener(TransitionManager::onIncomingDamage);
        eventBus.addListener(TransitionManager::onServerStopping);
    }

    // 只接受“目标数据已安装”回执
    public static boolean acknowledgeTargetPrewarmReady(ServerPlayer player, UUID sessionId) {
        TransitionManager manager = MANAGERS.get(player.level().getServer());
        TransitionSession session = manager == null ? null : manager.sessions.get(player.getUUID());
        if (session == null || !session.matches(player.getUUID(), sessionId)) {
            return false;
        }
        return manager.completePrewarm(player, session);
    }

    // 返回空形状即授权该玩家穿越
    public static VoxelShape barrierCollisionShape(ServerPlayer player, AABB queryBox) {
        boolean sourceSide = player.level().dimension().equals(Level.OVERWORLD);
        boolean targetSide = player.level().dimension().equals(ModWorldgenKeys.UNDERGROUND_LEVEL);
        if (!sourceSide && !targetSide) {
            return Shapes.empty();
        }
        MinecraftServer server = player.level().getServer();
        TransitionManager manager = MANAGERS.get(server);
        TransitionSession session = manager == null ? null : manager.sessions.get(player.getUUID());
        if (sourceSide && session != null && session.state().barrierPassable()) {
            return Shapes.empty();
        }
        DimensionSeam seam = manager == null ? null : manager.currentSeam;
        if (seam == null) {
            EbottData.Snapshot snapshot = EbottData.snapshotFor(server);
            seam = snapshot == null ? null : resolveSeam(server, snapshot);
            if (manager != null) {
                manager.currentSeam = seam;
            }
        }
        if (seam == null) {
            return Shapes.empty();
        }
        if (sourceSide) {
            return TransitionBarrier.collisionShape(seam, queryBox);
        }
        DimensionSeam.CircularSeamAperture aperture = seam.aperture();
        return TransitionBarrier.collisionShape(
                seam.targetCenterX(),
                seam.targetCenterZ(),
                seam.targetPlaneY(),
                aperture.centerOffsetX(),
                aperture.centerOffsetZ(),
                aperture.radius(),
                queryBox
        );
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        MANAGERS.computeIfAbsent(event.getServer(), TransitionManager::new).tick();
    }

    private static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            TransitionManager manager = MANAGERS.get(player.level().getServer());
            if (manager != null) {
                manager.clearSession(player, "player_logout", false);
            }
        }
    }

    private static void onLivingDeath(LivingDeathEvent event) {
        if (!event.isCanceled() && event.getEntity() instanceof ServerPlayer player) {
            TransitionManager manager = MANAGERS.get(player.level().getServer());
            if (manager != null) {
                manager.clearSession(player, "player_death", true);
            }
        }
    }

    private static void onIncomingDamage(LivingIncomingDamageEvent event) {
        if (event.getEntity() instanceof ServerPlayer player
                && isBarrierCollisionDamage(event.getSource())
                && isTouchingClosedBarrier(player)) {
            event.setCanceled(true);
        }
    }

    private static boolean isBarrierCollisionDamage(DamageSource source) {
        return source.is(DamageTypes.FALL) || source.is(DamageTypes.FLY_INTO_WALL);
    }

    private static boolean isTouchingClosedBarrier(ServerPlayer player) {
        if (!player.level().dimension().equals(Level.OVERWORLD)) {
            return false;
        }
        return !barrierCollisionShape(
                player,
                player.getBoundingBox().inflate(BARRIER_CONTACT_EPSILON)
        ).isEmpty();
    }

    private static void onServerStopping(ServerStoppingEvent event) {
        TransitionManager manager = MANAGERS.remove(event.getServer());
        if (manager != null) {
            manager.shutdown();
        }
    }

    private void tick() {
        long tick = this.server.getTickCount();
        this.cacheManager.tick();
        EbottData.Snapshot anchor = EbottData.snapshotFor(this.server);
        if (anchor == null) {
            return;
        }
        DimensionSeam seam = this.currentSeam;
        if (seam == null) {
            seam = resolveSeam(this.server, anchor);
            this.currentSeam = seam;
        }
        for (ServerPlayer player : this.server.getPlayerList().getPlayers()) {
            tickPlayer(player, anchor, seam, tick);
        }
    }

    private void tickPlayer(
            ServerPlayer player,
            EbottData.Snapshot anchor,
            DimensionSeam seam,
            long tick
    ) {
        if (!player.level().dimension().equals(Level.OVERWORLD)
                || !isWithinPrewarmRange(player, anchor)) {
            clearSession(player, player.level().dimension().equals(Level.OVERWORLD)
                    ? "left_prewarm_range" : "left_overworld", true);
            return;
        }

        TransitionSession session = this.sessions.get(player.getUUID());
        if (session == null) {
            session = new TransitionSession(UUID.randomUUID(), player.getUUID(), tick);
            this.sessions.put(player.getUUID(), session);
            sendPreparationBegin(player, anchor, seam, session.sessionId());
        }
        if (session.state() == TransitionState.FAILED) {
            return;
        }

        CacheLease sourceLease = this.cacheManager.ensureSourcePrewarm(
                anchor, player.getUUID());
        CacheLease targetLease = session.prewarmLease();
        if (targetLease == null) {
            Vec3 targetPriority = targetPriority(player, seam);
            targetLease = this.cacheManager.request(
                    player.getUUID(),
                    (int) Math.floor(targetPriority.x),
                    (int) Math.floor(targetPriority.z)
            );
            if (targetLease != null) {
                session.attachPrewarmLease(targetLease);
            }
        }
        if (sourceLease == null || targetLease == null) {
            if (this.server.getLevel(ModWorldgenKeys.UNDERGROUND_LEVEL) == null) {
                failSession(player, session, "target_dimension_missing", tick);
            }
            return;
        }
        if (failed(sourceLease) || failed(targetLease)) {
            failSession(player, session, "prewarm_failed", tick);
            return;
        }

        if (session.state() == TransitionState.PREWARMING
                && sourceLease.state() == CacheLease.State.READY
                && targetLease.state() == CacheLease.State.READY) {
            ensureTargetChunkStream(player, anchor, seam, targetLease, session.sessionId());
            // 完整数据流安装后的客户端回执才推进状态
        }

        TargetChunkPreload preload = this.targetChunkPreloads.get(player.getUUID());
        if (preload != null) {
            tickChunkStreams(player, anchor, preload);
        }
        if (session.state() == TransitionState.PASSABLE) {
            tickPassableCrossing(player, seam, session, tick);
        }
    }

    private boolean completePrewarm(ServerPlayer player, TransitionSession session) {
        if (session.state() == TransitionState.PASSABLE) {
            return true;
        }
        TargetChunkPreload preload = this.targetChunkPreloads.get(player.getUUID());
        if (session.state() != TransitionState.PREWARMING || this.currentSeam == null
                || preload == null || !preload.sessionId().equals(session.sessionId())
                || !preload.targetStreamFinished()) {
            return false;
        }
        session.completePrewarm(
                crossingProbe(player), this.currentSeam.sourcePlaneY());
        sendBarrierState(player, session.sessionId(), true);
        return true;
    }

    private void tickPassableCrossing(
            ServerPlayer player,
            DimensionSeam seam,
            TransitionSession session,
            long tick
    ) {
        double boundingRadius = Math.max(
                player.getBoundingBox().getXsize(),
                player.getBoundingBox().getZsize()
        ) * 0.5;
        SeamCrossingDetector.Observation observation = session.observeCrossing(
                crossingProbe(player),
                seam.sourcePlaneY(),
                seam.sourceCenterX(),
                seam.sourceCenterZ(),
                seam.aperture(),
                boundingRadius
        );
        if (!observation.crossed()) {
            return;
        }
        CrossingSnapshot snapshot = CrossingSnapshot.capture(
                player.getEyePosition(),
                player.position(),
                seam.sourcePlaneY(),
                player.getKnownMovement(),
                player.getYRot(),
                player.getXRot(),
                seam.sourceCenterX(),
                seam.sourceCenterZ(),
                player.getEyeHeight()
        );
        if (!session.beginCrossing(observation, snapshot)) {
            return;
        }
        PacketDistributor.sendToPlayer(player, new VisualCommitPayload(session.sessionId()));
        DimensionCommitter.Result result = DimensionCommitter.commit(
                player,
                this.server.getLevel(ModWorldgenKeys.UNDERGROUND_LEVEL),
                seam,
                session
        );
        if (result == DimensionCommitter.Result.COMMITTED) {
            finishSession(player, session);
        } else {
            failSession(player, session, result.abortReason(), tick);
        }
    }

    private static Vec3 crossingProbe(ServerPlayer player) {
        return new Vec3(player.getX(), player.getBoundingBox().minY, player.getZ());
    }

    private static DimensionSeam resolveSeam(
            MinecraftServer server,
            EbottData.Snapshot snapshot
    ) {
        ShaftData.Profile profile = snapshot.shaft().profile();
        ServerLevel target = server.getLevel(ModWorldgenKeys.UNDERGROUND_LEVEL);
        double targetPlaneY = target == null
                ? profile.targetSeamY()
                : EbottDestination.targetSeamY(
                        target,
                        target.getChunkSource().getGenerator(),
                        profile
                );
        return DimensionSeam.from(snapshot, targetPlaneY);
    }

    private static boolean failed(CacheLease lease) {
        return lease.state() == CacheLease.State.FAILED
                || lease.state() == CacheLease.State.RELEASED;
    }

    private static boolean isWithinPrewarmRange(
            ServerPlayer player,
            EbottData.Snapshot anchor
    ) {
        long dx = (long) Math.floor(player.getX()) - anchor.place().centerX();
        long dz = (long) Math.floor(player.getZ()) - anchor.place().centerZ();
        int radius = anchor.place().mountainOuterRadius() + PREWARM_MARGIN;
        return dx * dx + dz * dz <= (long) radius * radius;
    }

    private void sendPreparationBegin(
            ServerPlayer player,
            EbottData.Snapshot anchor,
            DimensionSeam seam,
            UUID sessionId
    ) {
        ShaftData.Profile profile = anchor.shaft().profile();
        PlaceManager.Place place = anchor.place();
        BlockPos sourceOpening = anchor.caveEntrance().shaftOpening(place);
        DimensionSeam.CircularSeamAperture aperture = seam.aperture();
        int targetPreviewRadius = EbottDestination.targetPreviewRadius(profile);
        PacketDistributor.sendToPlayer(player, new TransitionBeginPayload(
                sessionId,
                EbottDestination.centerX(),
                EbottDestination.centerZ(),
                profile.shaftEnvelopeRadius(),
                profile.fallbackTargetArrivalY(),
                sourceOpening.getX(),
                sourceOpening.getZ(),
                seam.sourcePlaneY(),
                seam.targetPlaneY(),
                seam.geometryVersion(),
                aperture.centerOffsetX(),
                aperture.centerOffsetZ(),
                aperture.radius(),
                targetPreviewRadius
        ));
    }

    private TargetChunkPreload ensureTargetChunkStream(
            ServerPlayer player,
            EbottData.Snapshot anchor,
            DimensionSeam seam,
            CacheLease lease,
            UUID sessionId
    ) {
        TargetChunkPreload existing = this.targetChunkPreloads.get(player.getUUID());
        if (existing != null) {
            return existing;
        }
        ServerLevel target = this.server.getLevel(ModWorldgenKeys.UNDERGROUND_LEVEL);
        if (target == null) {
            return null;
        }
        Vec3 targetPriority = targetPriority(player, seam);
        TargetFootprint footprint = TargetFootprint.create(
                EbottDestination.centerX(),
                EbottDestination.centerZ(),
                EbottDestination.targetPreviewRadius(anchor.shaft().profile()),
                (int) Math.floor(targetPriority.x),
                (int) Math.floor(targetPriority.z)
        );
        if (footprint.dataChunkRadius() > lease.radius()) {
            MineTale.LOGGER.error(
                    "目标足迹半径 {} 超过预热租约半径 {}",
                    footprint.dataChunkRadius(), lease.radius());
            return null;
        }
        LevelChunk[] targetChunks = collectLoadedChunks(target, footprint);
        if (targetChunks == null) {
            return null;
        }
        TargetChunkPreload preload = new TargetChunkPreload(sessionId, target, targetChunks);
        this.targetChunkPreloads.put(player.getUUID(), preload);
        player.connection.send(new ClientboundRespawnPacket(
                player.createCommonSpawnInfo(target),
                ClientboundRespawnPacket.KEEP_ALL_DATA
        ));
        return preload;
    }

    private void tickChunkStreams(
            ServerPlayer player,
            EbottData.Snapshot anchor,
            TargetChunkPreload preload
    ) {
        long deadline = System.nanoTime() + CHUNK_PACKET_BUDGET_NANOS;
        int sent = 0;
        while (sent < MAX_CHUNK_PACKETS_PER_TICK
                && System.nanoTime() < deadline
                && preload.hasTargetPacket()) {
            sendChunkPacket(player, preload.targetLevel(), preload.nextTargetChunk());
            sent++;
        }
        if (!preload.hasTargetPacket() && !preload.targetStreamFinished()) {
            preload.markTargetStreamFinished();
            PacketDistributor.sendToPlayer(
                    player, new TargetChunkStreamFinishedPayload(preload.sessionId()));
        }
        if (!preload.targetStreamFinished()) {
            return;
        }
        ensureSourceChunkStream(player, anchor, preload);
        while (sent < MAX_CHUNK_PACKETS_PER_TICK
                && System.nanoTime() < deadline
                && preload.hasSourcePacket()) {
            sendChunkPacket(player, preload.sourceLevel(), preload.nextSourceChunk());
            sent++;
        }
    }

    private void ensureSourceChunkStream(
            ServerPlayer player,
            EbottData.Snapshot anchor,
            TargetChunkPreload preload
    ) {
        if (preload.sourceStreamInitialized()
                || this.cacheManager.sourceLease() == null
                || this.cacheManager.sourceLease().state() != CacheLease.State.READY) {
            return;
        }
        BlockPos sourceOpening = anchor.caveEntrance().shaftOpening(anchor.place());
        TargetFootprint footprint = TargetFootprint.create(
                sourceOpening.getX(),
                sourceOpening.getZ(),
                anchor.shaft().profile().shaftEnvelopeRadius()
        );
        if (!footprint.fitsWithinChunkRadius(
                player.chunkPosition().x,
                player.chunkPosition().z,
                MIN_CLIENT_CHUNK_STORAGE_RADIUS
        )) {
            return;
        }
        ServerLevel source = this.server.getLevel(Level.OVERWORLD);
        LevelChunk[] chunks = source == null ? null : collectLoadedChunks(source, footprint);
        if (chunks != null) {
            preload.initializeSourceStream(source, chunks);
        }
    }

    private static LevelChunk[] collectLoadedChunks(ServerLevel level, TargetFootprint footprint) {
        LevelChunk[] chunks = new LevelChunk[footprint.dataColumns().size()];
        int index = 0;
        for (long packed : footprint.dataColumns()) {
            LevelChunk chunk = level.getChunkSource().getChunkNow(
                    TargetFootprint.chunkX(packed),
                    TargetFootprint.chunkZ(packed)
            );
            if (chunk == null) {
                return null;
            }
            chunks[index++] = chunk;
        }
        return chunks;
    }

    private static void sendChunkPacket(ServerPlayer player, ServerLevel level, LevelChunk chunk) {
        player.connection.send(new ClientboundLevelChunkWithLightPacket(
                chunk,
                level.getLightEngine(),
                null,
                null
        ));
    }

    // 映射后的目标 XZ 只影响预热顺序
    private static Vec3 targetPriority(ServerPlayer player, DimensionSeam seam) {
        return seam.transform().sourceToTargetProbe(player.position());
    }

    private void sendBarrierState(ServerPlayer player, UUID sessionId, boolean passable) {
        if (player.connection != null) {
            PacketDistributor.sendToPlayer(player, new BarrierStatePayload(sessionId, passable));
        }
    }

    private void failSession(
            ServerPlayer player,
            TransitionSession session,
            String reason,
            long tick
    ) {
        TransitionState failedState = session.state();
        session.fail();
        this.targetChunkPreloads.remove(player.getUUID());
        releaseAllFor(player.getUUID());
        sendBarrierState(player, session.sessionId(), false);
        MineTale.LOGGER.warn(
                "Transition session {} blocked for {}: reason={}, state={}, elapsedTicks={}",
                session.sessionId(), player.getUUID(), reason, failedState, tick - session.createdTick());
    }

    private void finishSession(ServerPlayer player, TransitionSession session) {
        this.sessions.remove(player.getUUID(), session);
        this.targetChunkPreloads.remove(player.getUUID());
        releaseAllFor(player.getUUID());
        MineTale.LOGGER.debug("Completed transition session {}", session.sessionId());
    }

    private void clearSession(ServerPlayer player, String reason, boolean notifyClient) {
        TransitionSession session = this.sessions.remove(player.getUUID());
        this.targetChunkPreloads.remove(player.getUUID());
        releaseAllFor(player.getUUID());
        if (session != null && notifyClient && player.connection != null) {
            PacketDistributor.sendToPlayer(
                    player, new AbortTransitionPayload(session.sessionId(), reason));
        }
    }

    private void releaseAllFor(UUID playerId) {
        this.cacheManager.releaseSource(playerId);
        this.cacheManager.releasePlayer(playerId);
    }

    private void shutdown() {
        for (TransitionSession session : this.sessions.values().toArray(TransitionSession[]::new)) {
            ServerPlayer player = this.server.getPlayerList().getPlayer(session.playerId());
            if (player != null) {
                clearSession(player, "server_stopping", false);
            }
        }
        this.sessions.clear();
        this.targetChunkPreloads.clear();
        this.cacheManager.shutdown();
        this.currentSeam = null;
    }

    private static final class TargetChunkPreload {
        private final UUID sessionId;
        private final ServerLevel targetLevel;
        private final LevelChunk[] targetChunks;
        private int targetCursor;
        private boolean targetStreamFinished;
        private ServerLevel sourceLevel;
        private LevelChunk[] sourceChunks;
        private int sourceCursor;

        private TargetChunkPreload(
                UUID sessionId,
                ServerLevel targetLevel,
                LevelChunk[] targetChunks
        ) {
            this.sessionId = sessionId;
            this.targetLevel = targetLevel;
            this.targetChunks = targetChunks;
        }

        private UUID sessionId() {
            return sessionId;
        }

        private ServerLevel targetLevel() {
            return targetLevel;
        }

        private boolean hasTargetPacket() {
            return targetCursor < targetChunks.length;
        }

        private LevelChunk nextTargetChunk() {
            return targetChunks[targetCursor++];
        }

        private boolean targetStreamFinished() {
            return targetStreamFinished;
        }

        private void markTargetStreamFinished() {
            this.targetStreamFinished = true;
        }

        private boolean sourceStreamInitialized() {
            return sourceChunks != null;
        }

        private void initializeSourceStream(ServerLevel level, LevelChunk[] chunks) {
            this.sourceLevel = level;
            this.sourceChunks = chunks;
        }

        private boolean hasSourcePacket() {
            return sourceChunks != null && sourceCursor < sourceChunks.length;
        }

        private ServerLevel sourceLevel() {
            return sourceLevel;
        }

        private LevelChunk nextSourceChunk() {
            return sourceChunks[sourceCursor++];
        }
    }
}
