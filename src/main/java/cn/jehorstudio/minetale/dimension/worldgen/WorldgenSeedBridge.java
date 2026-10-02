package cn.jehorstudio.minetale.dimension.worldgen;

import java.util.OptionalLong;
import java.util.function.Supplier;

public final class WorldgenSeedBridge {
    private static final ThreadLocal<Long> CURRENT_SEED = new ThreadLocal<>();
    private static volatile long boundLevelSeed = 0L;

    private WorldgenSeedBridge() {}

    public static <T> T withSeed(long seed, Supplier<T> action) {
        Long previous = CURRENT_SEED.get();
        CURRENT_SEED.set(seed);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                CURRENT_SEED.remove();
            } else {
                CURRENT_SEED.set(previous);
            }
        }
    }

    public static void withSeed(long seed, Runnable action) {
        withSeed(seed, () -> {
            action.run();
            return null;
        });
    }

    public static OptionalLong currentSeed() {
        Long seed = CURRENT_SEED.get();
        return seed == null ? OptionalLong.empty() : OptionalLong.of(seed);
    }

    public static long currentSeedOrDefault() {
        return currentSeed().orElse(boundLevelSeed);
    }

    public static void bindLevelSeed(long seed) {
        boundLevelSeed = seed;
    }
}
