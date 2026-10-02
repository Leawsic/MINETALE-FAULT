package cn.jehorstudio.minetale.dimension.worldgen.network;

public enum UndergroundRegion {
    RUINS(0),
    SNOWDIN(1),
    WATERFALL(2),
    HOT_LAND(3),
    DEEP_TUNNEL(-1);

    private static final UndergroundRegion[] STORY_ORDER = {
            RUINS,
            SNOWDIN,
            WATERFALL,
            HOT_LAND
    };

    private final int storyIndex;

    UndergroundRegion(int storyIndex) {
        this.storyIndex = storyIndex;
    }

    public static UndergroundRegion storyRegionOrDeepTunnel(int rawStoryIndex) {
        if (rawStoryIndex < 0 || rawStoryIndex >= STORY_ORDER.length) {
            return DEEP_TUNNEL;
        }

        return STORY_ORDER[rawStoryIndex];
    }

    public boolean hasPreviousStoryRegion() {
        return storyIndex > 0;
    }

    public boolean hasNextStoryRegion() {
        return storyIndex >= 0 && storyIndex < STORY_ORDER.length - 1;
    }

    public boolean isDeepTunnel() {
        return this == DEEP_TUNNEL;
    }
}
