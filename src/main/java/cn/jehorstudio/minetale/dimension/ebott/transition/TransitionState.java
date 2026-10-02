package cn.jehorstudio.minetale.dimension.ebott.transition;

// 单次准备的单向权威状态；仅 PASSABLE 解除碰撞，FAILED 保持玩家运动不变。
enum TransitionState {
    PREWARMING,
    PASSABLE,
    CROSSING,
    COMPLETE,
    FAILED;

    boolean barrierPassable() {
        return this == PASSABLE;
    }

    boolean isTerminal() {
        return this == COMPLETE || this == FAILED;
    }

    boolean canAdvanceTo(TransitionState next) {
        if (next == FAILED) {
            return !isTerminal();
        }
        return switch (this) {
            case PREWARMING -> next == PASSABLE;
            case PASSABLE -> next == CROSSING;
            case CROSSING -> next == COMPLETE;
            case COMPLETE, FAILED -> false;
        };
    }
}
