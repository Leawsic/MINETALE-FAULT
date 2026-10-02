package cn.jehorstudio.minetale.battle.logic.coordinate;

import java.util.Locale;

// Battle 玩法状态必须使用 canonical 坐标。
public enum BattleCoordinateSpace {
    CANONICAL("battle.canonical");

    private final String id;

    BattleCoordinateSpace(String id) {
        this.id = id;
    }

    public String id() {
        return this.id;
    }

    public static BattleCoordinateSpace require(String id, String path) {
        for (BattleCoordinateSpace value : values()) {
            if (value.id.equals(id)) {
                return value;
            }
        }
        throw new IllegalArgumentException(path + " must be battle.canonical, got " + id + ".");
    }

    public enum Axis {
        X, Y, Z;

        public static Axis parse(String value, String path) {
            try {
                return valueOf(value.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException(path + " must be x, y, or z.", exception);
            }
        }
    }
}
