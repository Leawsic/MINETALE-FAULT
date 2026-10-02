package cn.jehorstudio.minetale.dimension.ebott.transition;

import cn.jehorstudio.minetale.dimension.ebott.transition.seam.DimensionSeam;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.portal.TeleportTransition;

import java.util.Set;

final class DimensionCommitter {
    private DimensionCommitter() {
    }

    static Result commit(
            ServerPlayer player,
            ServerLevel target,
            DimensionSeam seam,
            TransitionSession session
    ) {
        if (target == null) {
            return Result.TARGET_MISSING;
        }
        CrossingSnapshot snapshot = session.crossingSnapshot();
        ServerPlayer teleported = player.teleport(new TeleportTransition(
                target,
                snapshot.targetBasePosition(seam.transform()),
                seam.transform().sourceToTargetVelocity(snapshot.velocity()),
                snapshot.yaw(),
                snapshot.pitch(),
                Set.of(),
                TeleportTransition.DO_NOTHING
        ));
        if (teleported == null) {
            return Result.REJECTED;
        }
        session.complete();
        return Result.COMMITTED;
    }

    enum Result {
        COMMITTED(null),
        TARGET_MISSING("target_dimension_missing"),
        REJECTED("dimension_commit_rejected");

        private final String abortReason;

        Result(String abortReason) {
            this.abortReason = abortReason;
        }

        String abortReason() {
            return abortReason;
        }
    }
}
