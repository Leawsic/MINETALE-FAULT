package cn.jehorstudio.minetale.dimension.ebott;

import cn.jehorstudio.minetale.dimension.ebott.entrance.CaveEntranceGenerator;
import cn.jehorstudio.minetale.dimension.ebott.shaft.ShaftData;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.Optional;

// 伊伯特山与共享竖井的世界级状态始终挂载在主世界。
// 异步 worldgen 只能读取启动后发布的不可变 Snapshot
public final class EbottData extends SavedData {
    private static final PlaceManager.Place UNRESOLVED_PLACE = new PlaceManager.Place(
            0,
            0,
            0,
            0,
            0L,
            1,
            1,
            640,
            96
    );

    private static final MapCodec<PlaceStorage> PLACE_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            Codec.BOOL.fieldOf("anchor_resolved").forGetter(PlaceStorage::resolved),
            Codec.INT.fieldOf("overworld_center_x").forGetter(PlaceStorage::centerX),
            Codec.INT.fieldOf("overworld_center_z").forGetter(PlaceStorage::centerZ),
            Codec.INT.fieldOf("overworld_base_y").forGetter(PlaceStorage::baseY),
            Codec.INT.fieldOf("overworld_summit_y").forGetter(PlaceStorage::summitY),
            Codec.LONG.fieldOf("mountain_seed").forGetter(PlaceStorage::mountainSeed),
            Codec.INT.fieldOf("profile_version").forGetter(PlaceStorage::profileVersion),
            Codec.INT.fieldOf("generation_version").forGetter(PlaceStorage::generationVersion),
            Codec.INT.fieldOf("mountain_outer_radius").forGetter(PlaceStorage::mountainOuterRadius),
            Codec.INT.fieldOf("outer_blend_width").forGetter(PlaceStorage::outerBlendWidth)
    ).apply(instance, PlaceStorage::new));

    public static final Codec<EbottData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            PLACE_CODEC.forGetter(EbottData::placeStorage),
            ShaftData.CODEC.forGetter(data -> data.shaftData),
            CaveEntranceGenerator.Placement.CODEC.optionalFieldOf("cave_entrance")
                    .forGetter(data -> Optional.ofNullable(data.caveEntrance)),
            Codec.BOOL.optionalFieldOf("snowdin_target_applied", false)
                    .forGetter(data -> data.snowdinTargetApplied)
    ).apply(instance, EbottData::fromStorage));

    public static final SavedDataType<EbottData> TYPE = new SavedDataType<>(
            "minetale_ebott",
            EbottData::new,
            CODEC
    );

    private static volatile PublishedSnapshot publishedSnapshot;

    private boolean resolved;
    private PlaceManager.Place place;
    private ShaftData shaftData;
    private CaveEntranceGenerator.Placement caveEntrance;
    private boolean snowdinTargetApplied;

    public EbottData() {
        this(false, UNRESOLVED_PLACE, ShaftData.unresolved(), null, false);
    }

    private EbottData(
            boolean resolved,
            PlaceManager.Place place,
            ShaftData shaftData,
            CaveEntranceGenerator.Placement caveEntrance,
            boolean snowdinTargetApplied
    ) {
        this.resolved = resolved;
        this.place = place;
        this.shaftData = shaftData;
        this.caveEntrance = caveEntrance;
        this.snowdinTargetApplied = snowdinTargetApplied;
    }

    public static EbottData get(ServerLevel overworld) {
        return overworld.getDataStorage().computeIfAbsent(TYPE);
    }

    // 主线程首次启动世界时选址
    public boolean resolve(ServerLevel overworld) {
        boolean newlyResolved = false;
        boolean caveResolved = false;
        if (!resolved) {
            this.place = PlaceManager.select(overworld);
            newlyResolved = true;
        }
        if (caveEntrance == null) {
            this.caveEntrance = CaveEntranceGenerator.resolve(overworld, place);
            caveResolved = true;
        }
        if (!resolved) {
            this.shaftData = ShaftData.create(overworld.getSeed(), overworld.getMinY());
            this.resolved = true;
        }
        if (newlyResolved || caveResolved) {
            setDirty();
        }
        return newlyResolved;
    }

    public Optional<Snapshot> snapshot() {
        return resolved && caveEntrance != null
                ? Optional.of(new Snapshot(place, shaftData, caveEntrance))
                : Optional.empty();
    }

    public ShaftData shaftData() {
        return shaftData;
    }

    public boolean snowdinTargetApplied() {
        return snowdinTargetApplied;
    }

    public void markShaftApplied(int version, boolean snowdinTargetApplied) {
        ShaftData updated = shaftData.markApplied(version);
        if (updated != shaftData || this.snowdinTargetApplied != snowdinTargetApplied) {
            shaftData = updated;
            this.snowdinTargetApplied = snowdinTargetApplied;
            setDirty();
        }
    }

    public static void publish(MinecraftServer server, Snapshot snapshot) {
        publishedSnapshot = new PublishedSnapshot(server, snapshot);
    }

    public static Snapshot snapshotFor(MinecraftServer server) {
        PublishedSnapshot published = publishedSnapshot;
        return published != null && published.server == server ? published.snapshot : null;
    }

    public static void clear(MinecraftServer server) {
        PublishedSnapshot published = publishedSnapshot;
        if (published != null && published.server == server) {
            publishedSnapshot = null;
        }
    }

    private PlaceStorage placeStorage() {
        return new PlaceStorage(
                resolved,
                place.centerX(),
                place.centerZ(),
                place.baseY(),
                place.summitY(),
                place.mountainSeed(),
                place.profileVersion(),
                place.generationVersion(),
                place.mountainOuterRadius(),
                place.outerBlendWidth()
        );
    }

    private static EbottData fromStorage(
            PlaceStorage placeStorage,
            ShaftData shaftData,
            Optional<CaveEntranceGenerator.Placement> caveEntrance,
            boolean snowdinTargetApplied
    ) {
        PlaceManager.Place place = new PlaceManager.Place(
                placeStorage.centerX,
                placeStorage.centerZ,
                placeStorage.baseY,
                placeStorage.summitY,
                placeStorage.mountainSeed,
                placeStorage.profileVersion,
                placeStorage.generationVersion,
                placeStorage.mountainOuterRadius,
                placeStorage.outerBlendWidth
        );
        return new EbottData(
                placeStorage.resolved,
                place,
                shaftData,
                caveEntrance.orElse(null),
                snowdinTargetApplied
        );
    }

    // Snapshot 是 worldgen 线程可见的唯一入口。
    public record Snapshot(
            PlaceManager.Place place,
            ShaftData shaft,
            CaveEntranceGenerator.Placement caveEntrance
    ) {
        public Snapshot {
            if (place == null || shaft == null || caveEntrance == null) {
                throw new NullPointerException("place, shaft and caveEntrance must not be null");
            }
        }
    }

    private record PlaceStorage(
            boolean resolved,
            int centerX,
            int centerZ,
            int baseY,
            int summitY,
            long mountainSeed,
            int profileVersion,
            int generationVersion,
            int mountainOuterRadius,
            int outerBlendWidth
    ) {
    }

    private record PublishedSnapshot(MinecraftServer server, Snapshot snapshot) {
    }
}
