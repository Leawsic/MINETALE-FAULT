package cn.jehorstudio.minetale.dimension.ebott.transition.client;

import cn.jehorstudio.minetale.dimension.ebott.transition.TargetFootprint;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

// 只追踪目标 Chunk 数据是否收齐
// 可提交不代表对应 Section mesh 已完成 GPU 编译。
final class TargetChunkStagingState {
    private final Set<Long> receivedChunks = new HashSet<>();

    private UUID sessionId;
    private TargetFootprint footprint;
    private boolean accepting;

    void begin(UUID sessionId, TargetFootprint footprint) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.footprint = Objects.requireNonNull(footprint, "footprint");
        this.receivedChunks.clear();
        this.accepting = true;
    }

    boolean accept(UUID sessionId, int chunkX, int chunkZ) {
        if (!accepting(sessionId) || !contains(chunkX, chunkZ)) {
            return false;
        }
        return this.receivedChunks.add(TargetFootprint.pack(chunkX, chunkZ));
    }

    boolean finish(UUID sessionId) {
        if (!accepting(sessionId)) {
            return false;
        }
        this.accepting = false;
        return readyToCommit();
    }

    void clear() {
        this.sessionId = null;
        this.footprint = null;
        this.receivedChunks.clear();
        this.accepting = false;
    }

    boolean accepting(UUID sessionId) {
        return this.accepting && matches(sessionId);
    }

    private boolean matches(UUID sessionId) {
        return this.sessionId != null && this.sessionId.equals(sessionId);
    }

    boolean contains(int chunkX, int chunkZ) {
        return this.footprint != null
                && this.footprint.dataColumns().contains(TargetFootprint.pack(chunkX, chunkZ));
    }

    boolean readyToCommit() {
        return this.sessionId != null
                && !this.accepting
                && this.receivedChunks.size() == expectedChunkCount();
    }

    int receivedChunkCount() {
        return this.receivedChunks.size();
    }

    int expectedChunkCount() {
        return this.footprint == null ? 0 : this.footprint.dataColumns().size();
    }
}
