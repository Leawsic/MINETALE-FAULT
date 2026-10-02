package cn.jehorstudio.minetale.dimension.worldgen;

import cn.jehorstudio.minetale.dimension.worldgen.math.WorldgenMath;
import cn.jehorstudio.minetale.dimension.worldgen.network.settings.UndergroundSamplingSettings;

import java.util.Arrays;
import java.util.Objects;

public final class WorldgenSamplingContext {
    private static final int[] COMMON_CHANNEL_SALTS = {
            6201, 6202, 6203, 6211, 6212, 6221, 6222, 6223,
            6224, 6225, 6228, 6229, 6230, 6241, 6242, 6243,
            6301, 6302, 6303, 6311, 6312, 6321, 6322, 6323,
            6324, 6326, 6331, 6332, 6333, 6341, 6342, 6343,
            6344, 6345, 6346, 6347, 6348, 6351, 6352, 6353,
            6354, 6501, 7001, 7002, 7003, 7004, 7005, 7006,
            7101, 7401, 7402, 7601, 7602, 7603, 7604, 7605,
            7606, 7607, 7951, 7952, 7962, 7963, 7964, 7965,
            7966, 8241, 8242, 8300, 8301, 8400
    };
    private static final int CHANNEL_INDEX_MASK = 511;
    private static final byte MISSING_CHANNEL = -1;
    private static final byte[] COMMON_CHANNEL_INDICES = buildChannelIndices();
    private static volatile ChannelSeeds recentChannelSeeds;

    private final long worldgenSeed;
    private final UndergroundSamplingSettings samplingSettings;
    private final ChannelSeeds channelSeeds;

    public WorldgenSamplingContext(long worldgenSeed, UndergroundSamplingSettings samplingSettings) {
        this.worldgenSeed = worldgenSeed;
        this.samplingSettings = samplingSettings;
        this.channelSeeds = channelSeedsFor(worldgenSeed);
    }

    public long worldgenSeed() {
        return worldgenSeed;
    }

    public UndergroundSamplingSettings samplingSettings() {
        return samplingSettings;
    }

    public long channelSeed(int channelSalt) {
        int commonIndex = COMMON_CHANNEL_INDICES[channelIndex(channelSalt)];
        if (commonIndex >= 0 && COMMON_CHANNEL_SALTS[commonIndex] == channelSalt) {
            return channelSeeds.values[commonIndex];
        }
        return WorldgenMath.channelSeed(worldgenSeed, channelSalt);
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || other instanceof WorldgenSamplingContext that
                && worldgenSeed == that.worldgenSeed
                && Objects.equals(samplingSettings, that.samplingSettings);
    }

    @Override
    public int hashCode() {
        return 31 * Long.hashCode(worldgenSeed) + Objects.hashCode(samplingSettings);
    }

    @Override
    public String toString() {
        return "WorldgenSamplingContext[worldgenSeed=" + worldgenSeed
                + ", samplingSettings=" + samplingSettings + ']';
    }

    private static byte[] buildChannelIndices() {
        byte[] indices = new byte[CHANNEL_INDEX_MASK + 1];
        Arrays.fill(indices, MISSING_CHANNEL);
        for (int index = 0; index < COMMON_CHANNEL_SALTS.length; index++) {
            int slot = channelIndex(COMMON_CHANNEL_SALTS[index]);
            if (indices[slot] != MISSING_CHANNEL) {
                throw new IllegalStateException("duplicate worldgen channel index: " + slot);
            }
            indices[slot] = (byte) index;
        }
        return indices;
    }

    private static int channelIndex(int channelSalt) {
        return (channelSalt ^ (channelSalt >>> 11)) & CHANNEL_INDEX_MASK;
    }

    private static ChannelSeeds channelSeedsFor(long worldgenSeed) {
        ChannelSeeds cached = recentChannelSeeds;
        if (cached != null && cached.worldgenSeed == worldgenSeed) {
            return cached;
        }

        ChannelSeeds created = new ChannelSeeds(worldgenSeed);
        recentChannelSeeds = created;
        return created;
    }

    // 槽位同时校验原 salt；碰撞或未知 salt 必须精确回退
    private static final class ChannelSeeds {
        private final long worldgenSeed;
        private final long[] values = new long[COMMON_CHANNEL_SALTS.length];

        private ChannelSeeds(long worldgenSeed) {
            this.worldgenSeed = worldgenSeed;
            for (int index = 0; index < COMMON_CHANNEL_SALTS.length; index++) {
                values[index] = WorldgenMath.channelSeed(worldgenSeed, COMMON_CHANNEL_SALTS[index]);
            }
        }
    }
}
