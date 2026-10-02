package cn.jehorstudio.minetale.dimension.ebott.transition;

import cn.jehorstudio.minetale.MineTale;
import cn.jehorstudio.minetale.dimension.worldgen.registry.ModWorldgenKeys;
import cn.jehorstudio.minetale.dimension.ebott.EbottData;
import cn.jehorstudio.minetale.dimension.ebott.EbottDestination;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;

// 管理源端触发的共享预热租约
// 失败状态由 TransitionState 锁住
final class CacheManager {
    private static final int MAX_TICKETS_PER_TICK = 2;
    private static final long TICKET_BUDGET_NANOS = 1_250_000L;

    private final MinecraftServer server;
    private final ArrayDeque<FootprintLoadTask> footprintLoadTasks = new ArrayDeque<>();
    private CacheLease sourcePrewarmLease;
    private CacheLease targetPrewarmLease;

    CacheManager(MinecraftServer server) {
        this.server = server;
    }

    // 源端与目标端共用每 Tick 的 Ticket 调度预算
    void tick() {
        long deadline = System.nanoTime() + TICKET_BUDGET_NANOS;
        for (int scheduled = 0;
             scheduled < MAX_TICKETS_PER_TICK
                     && System.nanoTime() < deadline
                     && !this.footprintLoadTasks.isEmpty();
             scheduled++) {
            FootprintLoadTask task = this.footprintLoadTasks.removeFirst();
            task.scheduleNextColumn();
            if (!task.finished()) {
                this.footprintLoadTasks.addLast(task);
            }
        }
    }

    // 只有进入完整预热圈的玩家才持有源端足迹租约。
    CacheLease ensureSourcePrewarm(
            EbottData.Snapshot snapshot,
            UUID playerId
    ) {
        if (sourcePrewarmLease == null
                || sourcePrewarmLease.state() == CacheLease.State.RELEASED) {
            ServerLevel source = server.getLevel(Level.OVERWORLD);
            if (source == null) {
                return null;
            }
            var sourceOpening = snapshot.caveEntrance().shaftOpening(snapshot.place());
            TargetFootprint footprint = TargetFootprint.create(
                    sourceOpening.getX(),
                    sourceOpening.getZ(),
                    snapshot.shaft().profile().shaftEnvelopeRadius()
            );
            sourcePrewarmLease = createFootprintLease(source, footprint);
            if (sourcePrewarmLease == null) {
                return null;
            }
            sourcePrewarmLease.completionFuture().whenCompleteAsync((unused, error) -> {
                if (error != null && !isCancellation(error)) {
                    MineTale.LOGGER.error("主世界竖井足迹预热失败", error);
                } else if (error == null) {
                    MineTale.LOGGER.debug(
                            "主世界竖井足迹预热就绪，列数={}",
                            footprint.dataColumns().size()
                    );
                }
            }, server);
        }
        if (sourcePrewarmLease.state() != CacheLease.State.FAILED) {
            sourcePrewarmLease.retain(playerId);
        }
        return sourcePrewarmLease;
    }

    CacheLease sourceLease() {
        return sourcePrewarmLease;
    }

    // true 表示源端租约已无持有者并完成释放。
    boolean releaseSource(UUID playerId) {
        if (sourcePrewarmLease != null && sourcePrewarmLease.release(playerId)) {
            sourcePrewarmLease = null;
            return true;
        }
        return false;
    }

    // 每个目标足迹只创建一个租约
    // 目标不可用时不保留半成品
    CacheLease request(UUID playerId, int priorityBlockX, int priorityBlockZ) {
        ServerLevel target = server.getLevel(ModWorldgenKeys.UNDERGROUND_LEVEL);
        if (target == null) {
            return null;
        }
        if (targetPrewarmLease == null
                || targetPrewarmLease.state() == CacheLease.State.RELEASED) {
            ChunkPos center = new ChunkPos(
                    Math.floorDiv(EbottDestination.centerX(), 16),
                    Math.floorDiv(EbottDestination.centerZ(), 16)
            );
            targetPrewarmLease = createFullFootprintLease(
                    target,
                    priorityBlockX,
                    priorityBlockZ
            );
            if (targetPrewarmLease == null) {
                return null;
            }
            CompletableFuture<?> loadFuture = targetPrewarmLease.completionFuture();
            CacheLease loggedLease = targetPrewarmLease;
            loadFuture.whenCompleteAsync((unused, error) -> {
                if (error != null && !isCancellation(error)) {
                    MineTale.LOGGER.error(
                            "目标维度预热失败 center={} radius={}",
                            center,
                            loggedLease.radius(),
                            error
                    );
                } else if (error == null) {
                    MineTale.LOGGER.debug(
                            "目标维度预热就绪 center={} radius={} refs={}",
                            center,
                            loggedLease.radius(),
                            loggedLease.referenceCount()
                    );
                }
            }, server);
        }
        if (targetPrewarmLease.state() != CacheLease.State.FAILED) {
            targetPrewarmLease.retain(playerId);
        }
        return targetPrewarmLease;
    }

    void releasePlayer(UUID playerId) {
        if (targetPrewarmLease != null && targetPrewarmLease.release(playerId)) {
            targetPrewarmLease = null;
        }
    }

    private CacheLease createFullFootprintLease(
            ServerLevel target,
            int priorityBlockX,
            int priorityBlockZ
    ) {
        EbottData.Snapshot snapshot = EbottData.snapshotFor(server);
        if (snapshot == null) {
            return null;
        }
        TargetFootprint footprint = TargetFootprint.create(
                EbottDestination.centerX(),
                EbottDestination.centerZ(),
                EbottDestination.targetPreviewRadius(snapshot.shaft().profile()),
                priorityBlockX,
                priorityBlockZ
        );
        return createFootprintLease(target, footprint);
    }

    // Ticket 按水平列申请，单列已经覆盖该坐标的全部垂直 Section。
    private CacheLease createFootprintLease(
            ServerLevel level,
            TargetFootprint footprint
    ) {
        FootprintLoadTask task = new FootprintLoadTask(level, footprint);
        CacheLease lease = new CacheLease(
                footprint.dataChunkRadius(),
                task.completionFuture(),
                task::release,
                server
        );
        if (!task.finished()) {
            this.footprintLoadTasks.addLast(task);
        }
        return lease;
    }

    private static void releaseTickets(ServerLevel level, List<ChunkPos> ticketColumns) {
        for (ChunkPos chunk : ticketColumns) {
            level.getChunkSource().removeTicketWithRadius(
                    TransitionTicketTypes.PREWARM.value(), chunk, 0);
        }
    }

    void shutdown() {
        if (targetPrewarmLease != null) {
            targetPrewarmLease.forceRelease();
            targetPrewarmLease = null;
        }
        if (sourcePrewarmLease != null) {
            sourcePrewarmLease.forceRelease();
            sourcePrewarmLease = null;
        }
        for (FootprintLoadTask task : this.footprintLoadTasks) {
            task.release();
        }
        this.footprintLoadTasks.clear();
    }

    private static boolean isCancellation(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof CancellationException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    // 足迹按预算增量发放 Ticket
    private static final class FootprintLoadTask {
        private final ServerLevel level;
        private final long[] pendingColumns;
        private final List<ChunkPos> ticketColumns;
        private final List<CompletableFuture<?>> loadFutures;
        private final CompletableFuture<Void> completionFuture = new CompletableFuture<>();
        private int cursor;
        private boolean released;
        private boolean allScheduled;

        private FootprintLoadTask(ServerLevel level, TargetFootprint footprint) {
            this.level = level;
            this.pendingColumns = footprint.dataColumns().stream().mapToLong(Long::longValue).toArray();
            this.ticketColumns = new ArrayList<>(this.pendingColumns.length);
            this.loadFutures = new ArrayList<>(this.pendingColumns.length);
            if (this.pendingColumns.length == 0) {
                this.allScheduled = true;
                this.completionFuture.complete(null);
            }
        }

        private void scheduleNextColumn() {
            if (finished()) {
                return;
            }
            long packed = this.pendingColumns[this.cursor++];
            ChunkPos chunk = new ChunkPos(
                    TargetFootprint.chunkX(packed),
                    TargetFootprint.chunkZ(packed)
            );
            try {
                this.ticketColumns.add(chunk);
                this.loadFutures.add(this.level.getChunkSource().addTicketAndLoadWithRadius(
                        TransitionTicketTypes.PREWARM.value(), chunk, 0));
            } catch (RuntimeException error) {
                releaseTickets(this.level, this.ticketColumns);
                this.released = true;
                this.completionFuture.completeExceptionally(error);
                return;
            }
            if (this.cursor == this.pendingColumns.length) {
                this.allScheduled = true;
                CompletableFuture.allOf(this.loadFutures.toArray(CompletableFuture[]::new))
                        .whenComplete((unused, error) -> {
                            if (this.released) {
                                return;
                            }
                            if (error == null) {
                                this.completionFuture.complete(null);
                            } else {
                                this.completionFuture.completeExceptionally(error);
                            }
                        });
            }
        }

        private CompletableFuture<Void> completionFuture() {
            return completionFuture;
        }

        private boolean finished() {
            return this.released || this.allScheduled;
        }

        private void release() {
            if (this.released) {
                return;
            }
            this.released = true;
            releaseTickets(this.level, this.ticketColumns);
            this.ticketColumns.clear();
            if (!this.completionFuture.isDone()) {
                this.completionFuture.completeExceptionally(
                        new CancellationException("transition prewarm released"));
            }
        }
    }

}
