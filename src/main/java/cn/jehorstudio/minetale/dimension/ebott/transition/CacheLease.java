package cn.jehorstudio.minetale.dimension.ebott.transition;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

// 同一预热足迹由玩家共享
// 最后一个持有者负责触发 Chunk Ticket 释放
final class CacheLease {
    enum State {
        LOADING,
        READY,
        FAILED,
        RELEASED
    }

    private final int radius;
    private final CompletableFuture<?> completionFuture;
    private final Runnable ticketRelease;
    private final Set<UUID> holders = new HashSet<>();
    private final AtomicBoolean ticketReleased = new AtomicBoolean();
    private volatile State state = State.LOADING;

    CacheLease(
            int radius,
            CompletableFuture<?> completionFuture,
            Runnable ticketRelease,
            Executor completionExecutor
    ) {
        if (radius < 0) {
            throw new IllegalArgumentException("radius must be non-negative");
        }
        this.radius = radius;
        this.completionFuture = Objects.requireNonNull(completionFuture, "completionFuture");
        this.ticketRelease = Objects.requireNonNull(ticketRelease, "ticketRelease");
        completionFuture.whenCompleteAsync((unused, error) -> {
            if (this.state == State.RELEASED) {
                return;
            }
            if (error == null) {
                this.state = State.READY;
            } else {
                this.state = State.FAILED;
            }
        }, Objects.requireNonNull(completionExecutor, "completionExecutor"));
    }

    synchronized void retain(UUID playerId) {
        if (state == State.RELEASED) {
            throw new IllegalStateException("cannot retain a released cache lease");
        }
        holders.add(Objects.requireNonNull(playerId, "playerId"));
    }

    // 返回 true 表示此次释放清空了持有者并移除了 Ticket
    synchronized boolean release(UUID playerId) {
        if (!holders.remove(playerId)) {
            return false;
        }
        if (!holders.isEmpty()) {
            return false;
        }
        releaseTicket();
        return true;
    }

    synchronized void forceRelease() {
        holders.clear();
        releaseTicket();
    }

    private void releaseTicket() {
        if (ticketReleased.compareAndSet(false, true)) {
            ticketRelease.run();
            state = State.RELEASED;
        }
    }

    int radius() {
        return radius;
    }

    CompletableFuture<?> completionFuture() {
        return completionFuture;
    }

    State state() {
        return state;
    }

    synchronized int referenceCount() {
        return holders.size();
    }
}
