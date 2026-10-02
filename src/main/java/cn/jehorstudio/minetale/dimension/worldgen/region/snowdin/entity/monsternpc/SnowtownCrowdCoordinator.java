package cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.entity.monsternpc;

import cn.jehorstudio.minetale.MineTale;
import cn.jehorstudio.minetale.content.entity.monster_npc.MonsterNpcAppearance;
import cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.entity.monsternpc.network.SnowtownCrowdInteractPayload;
import cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.entity.monsternpc.network.SnowtownCrowdPopulationPayload;
import cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.entity.monsternpc.network.SnowtownCrowdPopulationPayload.SurfacePopulation;
import cn.jehorstudio.minetale.narrative.runtime.DialogueSessionManager;
import cn.jehorstudio.minetale.dimension.worldgen.generator.UndergroundNoiseGenerator;
import cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.structure.SnowtownResidentNavigationSource;
import cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.structure.SnowtownResidentNavigationSource.AreaKey;
import cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.structure.SnowtownResidentNavigationSource.AreaSnapshot;
import cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.structure.SnowtownResidentNavigationSource.TownKey;
import cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.structure.SnowtownResidentNavigationSource.TownSnapshot;
import cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.settings.SnowtownSettings;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

// Snowtown 建筑人口指令与 GPU 环境居民交互的服务端唯一协调
public final class SnowtownCrowdCoordinator {
    private static final int UPDATE_INTERVAL_TICKS = 2;
    private static final int INTEREST_REFRESH_TICKS = 20;
    private static final int INACTIVE_RETENTION_TICKS = 200;
    private static final int MAX_CACHED_AREAS = 64;
    private static final double MIN_VISUAL_INTEREST_RANGE = 128.0D;
    private static final double MAX_VISUAL_INTEREST_RANGE = 512.0D;
    private static final double INTERACTION_VALIDATION_MARGIN = 0.75D;
    private static final double INTERACTION_VISUAL_BOUNDS_MARGIN = 0.75D;
    private static final double MAX_SURFACE_HEIGHT_DELTA = 18.0D;
    private static final ResourceLocation VIRTUAL_RESIDENT_TYPE = ResourceLocation.fromNamespaceAndPath(
            MineTale.MODID,
            "monster_npc"
    );
    private static final ResourceLocation VIRTUAL_RESIDENT_DIALOGUE = ResourceLocation.fromNamespaceAndPath(
            MineTale.MODID,
            "monster_npc"
    );
    private static final long SLOW_UPDATE_NANOS = 4_000_000L;
    private static final Map<MinecraftServer, SnowtownCrowdCoordinator> COORDINATORS =
            new IdentityHashMap<>();

    private final MinecraftServer server;
    private final Map<ServerLevel, LevelCrowd> levels = new IdentityHashMap<>();

    private SnowtownCrowdCoordinator(MinecraftServer server) {
        this.server = server;
    }

    public static void register(IEventBus eventBus) {
        eventBus.addListener(SnowtownCrowdCoordinator::onServerTick);
        eventBus.addListener(SnowtownCrowdCoordinator::onServerStopping);
    }

    public static void interact(
            ServerPlayer player,
            SnowtownCrowdInteractPayload payload
    ) {
        ServerLevel level = player.level();
        MinecraftServer server = level.getServer();
        SnowtownCrowdCoordinator coordinator = COORDINATORS.get(server);
        if (coordinator == null) {
            return;
        }
        LevelCrowd crowd = coordinator.levels.get(level);
        if (crowd != null) {
            crowd.interact(player, payload);
        }
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % UPDATE_INTERVAL_TICKS != 0) {
            return;
        }
        COORDINATORS.computeIfAbsent(
                event.getServer(),
                SnowtownCrowdCoordinator::new
        ).tick();
    }

    private static void onServerStopping(ServerStoppingEvent event) {
        SnowtownCrowdCoordinator coordinator = COORDINATORS.remove(event.getServer());
        if (coordinator != null) {
            coordinator.shutdown();
        }
    }

    private void tick() {
        long gameTime = this.server.getTickCount();
        for (ServerLevel level : this.server.getAllLevels()) {
            if (!(level.getChunkSource().getGenerator() instanceof UndergroundNoiseGenerator)) {
                continue;
            }
            long startedAt = System.nanoTime();
            LevelCrowd crowd = this.levels.computeIfAbsent(level, LevelCrowd::new);
            crowd.tick(gameTime);
            long elapsed = System.nanoTime() - startedAt;
            crowd.recordTiming(elapsed, gameTime);
        }
    }

    private void shutdown() {
        for (LevelCrowd crowd : this.levels.values()) {
            crowd.shutdown();
        }
        this.levels.clear();
    }

    private static final class LevelCrowd {
        private final ServerLevel level;
        private final Map<AreaKey, AreaSnapshot> areas = new HashMap<>();
        private final Map<TownKey, SnowtownPedestrianMap> maps = new HashMap<>();
        private final Map<TownKey, TownPopulation> populations = new HashMap<>();
        private final Map<UUID, CrowdDirective> directivesByPlayer = new HashMap<>();
        private long navigationRevision = Long.MIN_VALUE;
        private long timingNanos;
        private long timingSamples;
        private long maxTimingNanos;
        private long lastSlowWarningTick = Long.MIN_VALUE;

        private LevelCrowd(ServerLevel level) {
            this.level = level;
        }

        private void tick(long gameTime) {
            if (gameTime % INTEREST_REFRESH_TICKS == 0) {
                refreshInterest(gameTime);
            }

            Iterator<TownPopulation> iterator = this.populations.values().iterator();
            while (iterator.hasNext()) {
                TownPopulation population = iterator.next();
                if (gameTime - population.lastInterestedTick > INACTIVE_RETENTION_TICKS) {
                    iterator.remove();
                }
            }
            if (gameTime % INTEREST_REFRESH_TICKS == 0L) {
                syncPopulationDirectives(this.level.players());
            }
        }

        private void refreshInterest(long gameTime) {
            long currentRevision = SnowtownResidentNavigationSource.currentRevision();
            if (this.navigationRevision != currentRevision) {
                resetNavigation();
                this.navigationRevision = currentRevision;
            }

            List<ServerPlayer> players = this.level.players();
            Set<AreaKey> interestedAreas = collectInterestedAreas(players);
            trimAreaCache(interestedAreas);
            for (AreaKey key : interestedAreas) {
                AreaSnapshot area = this.areas.get(key);
                if (area == null) {
                    area = SnowtownResidentNavigationSource.load(
                            this.level,
                            key.areaX(),
                            key.areaZ()
                    ).orElse(null);
                    if (area == null) {
                        continue;
                    }
                    this.areas.put(key, area);
                }
                activateNearbyTowns(area, players, gameTime);
            }
        }

        private Set<AreaKey> collectInterestedAreas(List<ServerPlayer> players) {
            Set<AreaKey> result = new HashSet<>();
            double discoveryRadius = visualInterestRange();
            for (ServerPlayer player : players) {
                int minAreaX = SnowtownResidentNavigationSource.areaCoordinate(
                        (int)Math.floor(player.getX() - discoveryRadius)
                );
                int maxAreaX = SnowtownResidentNavigationSource.areaCoordinate(
                        (int)Math.floor(player.getX() + discoveryRadius)
                );
                int minAreaZ = SnowtownResidentNavigationSource.areaCoordinate(
                        (int)Math.floor(player.getZ() - discoveryRadius)
                );
                int maxAreaZ = SnowtownResidentNavigationSource.areaCoordinate(
                        (int)Math.floor(player.getZ() + discoveryRadius)
                );
                for (int areaZ = minAreaZ; areaZ <= maxAreaZ; areaZ++) {
                    if (!SnowtownResidentNavigationSource.isSnowdinArea(areaZ)) {
                        continue;
                    }
                    for (int areaX = minAreaX; areaX <= maxAreaX; areaX++) {
                        result.add(new AreaKey(areaX, areaZ));
                    }
                }
            }
            return result;
        }

        private void activateNearbyTowns(
                AreaSnapshot area,
                List<ServerPlayer> players,
                long gameTime
        ) {
            for (TownSnapshot snapshot : area.towns()) {
                SnowtownPedestrianMap map = this.maps.computeIfAbsent(
                        snapshot.key(),
                        ignored -> new SnowtownPedestrianMap(snapshot)
                );
                if (map.anchorCount() < 2 || !isTownInterested(map, players)) {
                    continue;
                }
                TownPopulation population = this.populations.computeIfAbsent(
                        map.key(),
                        ignored -> new TownPopulation(this.level, map, snapshot, gameTime)
                );
                population.lastInterestedTick = gameTime;
            }
        }

        private boolean isTownInterested(
                SnowtownPedestrianMap map,
                List<ServerPlayer> players
        ) {
            double range = visualInterestRange();
            double rangeSquared = range * range;
            for (ServerPlayer player : players) {
                if (map.distanceToBoundsSquared(player.getX(), player.getZ()) <= rangeSquared) {
                    return true;
                }
            }
            return false;
        }

        private double visualInterestRange() {
            double configured = this.level.getServer().getPlayerList().getViewDistance() * 16.0D;
            return Math.clamp(configured, MIN_VISUAL_INTEREST_RANGE, MAX_VISUAL_INTEREST_RANGE);
        }

        private void syncPopulationDirectives(List<ServerPlayer> players) {
            Set<UUID> online = new HashSet<>();
            double rangeSquared = visualInterestRange() * visualInterestRange();
            for (ServerPlayer player : players) {
                online.add(player.getUUID());
                List<SnowtownCrowdPopulationPayload.TownPopulation> visibleTowns =
                        this.populations.values().stream()
                                .filter(candidate -> candidate.map.distanceToBoundsSquared(
                                        player.getX(),
                                        player.getZ()) <= rangeSquared)
                                .sorted(Comparator.comparing(candidate -> candidate.map.key(),
                                        SnowtownCrowdCoordinator::compareTownKeys))
                                .map(TownPopulation::directive)
                                .toList();
                CrowdDirective directive = new CrowdDirective(visibleTowns);
                CrowdDirective previous = this.directivesByPlayer.put(player.getUUID(), directive);
                if (!directive.equals(previous)) {
                    PacketDistributor.sendToPlayer(player, directive.payload(this.navigationRevision));
                }
            }
            this.directivesByPlayer.keySet().removeIf(playerId -> !online.contains(playerId));
        }

        private void interact(
                ServerPlayer player,
                SnowtownCrowdInteractPayload payload
        ) {
            TownPopulation town = this.populations.get(new TownKey(
                    payload.areaX(),
                    payload.areaZ(),
                    payload.componentIndex()
            ));
            if (town != null) {
                town.interact(player, payload);
            }
        }

        private void trimAreaCache(Set<AreaKey> interestedAreas) {
            if (this.areas.size() < MAX_CACHED_AREAS) {
                return;
            }
            this.areas.keySet().removeIf(key -> !interestedAreas.contains(key));
            this.maps.keySet().removeIf(key -> !this.populations.containsKey(key)
                    && !interestedAreas.contains(new AreaKey(key.areaX(), key.areaZ())));
        }

        private void resetNavigation() {
            this.populations.clear();
            this.maps.clear();
            this.areas.clear();
            this.directivesByPlayer.clear();
        }

        private void recordTiming(long elapsedNanos, long gameTime) {
            this.timingNanos += elapsedNanos;
            this.timingSamples++;
            this.maxTimingNanos = Math.max(this.maxTimingNanos, elapsedNanos);
            if (elapsedNanos >= SLOW_UPDATE_NANOS
                    && (this.lastSlowWarningTick == Long.MIN_VALUE
                    || gameTime - this.lastSlowWarningTick >= INACTIVE_RETENTION_TICKS)) {
                MineTale.LOGGER.warn(
                        "Snowtown crowd update was slow: level={}, elapsedMs={}, towns={}, surfaces={}",
                        this.level.dimension().location(),
                        elapsedNanos / 1_000_000.0D,
                        this.populations.size(),
                        surfaceCount()
                );
                this.lastSlowWarningTick = gameTime;
            }
            if (gameTime % 1200L == 0L && this.timingSamples > 0L) {
                MineTale.LOGGER.debug(
                        "Snowtown crowd timing: level={}, averageMs={}, maxMs={}, samples={}, towns={}, surfaces={}",
                        this.level.dimension().location(),
                        this.timingNanos / (double)this.timingSamples / 1_000_000.0D,
                        this.maxTimingNanos / 1_000_000.0D,
                        this.timingSamples,
                        this.populations.size(),
                        surfaceCount()
                );
                this.timingNanos = 0L;
                this.timingSamples = 0L;
                this.maxTimingNanos = 0L;
            }
        }

        private int surfaceCount() {
            int count = 0;
            for (TownPopulation population : this.populations.values()) {
                count += population.visualSurfaces.size();
            }
            return count;
        }

        private void shutdown() {
            resetNavigation();
        }
    }

    private static final class TownPopulation {
        private final ServerLevel level;
        private final SnowtownPedestrianMap map;
        private final SnowtownPopulationModel population;
        private final int walkableAreaBlocks;
        private final List<SurfacePopulation> visualSurfaces;
        private long lastInterestedTick;

        private TownPopulation(
                ServerLevel level,
                SnowtownPedestrianMap map,
                TownSnapshot snapshot,
                long gameTime
        ) {
            this.level = level;
            this.map = map;
            this.population = SnowtownPopulationModel.from(snapshot);
            this.walkableAreaBlocks = Math.multiplyExact(
                    snapshot.cells().size(),
                    16
            );
            this.visualSurfaces = buildSurfacePopulations(
                    map,
                    this.population.outdoorPopulation()
            );
            this.lastInterestedTick = gameTime;
            MineTale.LOGGER.debug(
                    "Snowtown population ready: town={}, buildings={}, capacity={}, outdoor={}, surfaces={}, walkableArea={}",
                    map.key(),
                    this.population.buildingCount(),
                    this.population.residentCapacity(),
                    this.population.outdoorPopulation(),
                    this.visualSurfaces.size(),
                    this.walkableAreaBlocks
            );
        }

        private SnowtownCrowdPopulationPayload.TownPopulation directive() {
            TownKey key = this.map.key();
            return new SnowtownCrowdPopulationPayload.TownPopulation(
                    key.areaX(),
                    key.areaZ(),
                    key.componentIndex(),
                    this.population.buildingCount(),
                    this.population.residentCapacity(),
                    this.population.outdoorPopulation(),
                    this.walkableAreaBlocks,
                    this.visualSurfaces
            );
        }

        private void interact(
                ServerPlayer player,
                SnowtownCrowdInteractPayload payload
        ) {
            MineTale.LOGGER.debug(
                    "Snowtown GPU resident interaction request: player={}, town={}, sector=({}, {}), surface={}, agent={}",
                    player.getGameProfile().name(),
                    this.map.key(),
                    payload.sectorX(),
                    payload.sectorZ(),
                    payload.surfaceId(),
                    payload.agentId()
            );
            if (player.isSpectator() || !player.isAlive()) {
                MineTale.LOGGER.debug("Snowtown GPU resident interaction rejected: player unavailable");
                return;
            }
            SurfacePopulation surface = this.visualSurfaces.stream()
                    .filter(candidate -> candidate.sectorX() == payload.sectorX()
                            && candidate.sectorZ() == payload.sectorZ()
                            && candidate.surfaceId() == payload.surfaceId())
                    .findFirst()
                    .orElse(null);
            if (surface == null) {
                MineTale.LOGGER.debug("Snowtown GPU resident interaction rejected: unknown surface");
                return;
            }
            if (payload.agentId() >= surface.outdoorPopulation()) {
                MineTale.LOGGER.debug(
                        "Snowtown GPU resident interaction rejected: agent quota, agent={}, quota={}",
                        payload.agentId(),
                        surface.outdoorPopulation()
                );
                return;
            }
            // 服务端没有同帧 GPU 坐标，只验证连续位置仍属于声明扇区
            if (Math.floorDiv((int)Math.floor(payload.agentX()),
                            SnowtownCrowdPopulationPayload.SECTOR_SIZE)
                            != surface.sectorX()
                    || Math.floorDiv((int)Math.floor(payload.agentZ()),
                            SnowtownCrowdPopulationPayload.SECTOR_SIZE)
                            != surface.sectorZ()) {
                MineTale.LOGGER.debug(
                        "Snowtown GPU resident interaction rejected: sector bounds, position=({}, {})",
                        payload.agentX(),
                        payload.agentZ()
                );
                return;
            }
            if (Math.abs(payload.agentY() - surface.seedY()) > MAX_SURFACE_HEIGHT_DELTA) {
                MineTale.LOGGER.debug(
                        "Snowtown GPU resident interaction rejected: height, agentY={}, seedY={}",
                        payload.agentY(),
                        surface.seedY()
                );
                return;
            }

            BlockPos feet = BlockPos.containing(
                    payload.agentX(),
                    payload.agentY(),
                    payload.agentZ()
            );
            if (!this.level.isLoaded(feet)) {
                MineTale.LOGGER.debug("Snowtown GPU resident interaction rejected: chunk unavailable");
                return;
            }
            MonsterNpcAppearance appearance = MonsterNpcAppearance.byId(
                    Math.floorMod(payload.agentId(), MonsterNpcAppearance.count())
            );
            // 服务端需容纳动画肢体、pivot 与网络延迟误差。
            double halfWidth = appearance.dimensions().width() * 0.5D
                    + INTERACTION_VISUAL_BOUNDS_MARGIN;
            AABB targetBounds = new AABB(
                    payload.agentX() - halfWidth,
                    payload.agentY() - INTERACTION_VISUAL_BOUNDS_MARGIN,
                    payload.agentZ() - halfWidth,
                    payload.agentX() + halfWidth,
                    payload.agentY() + appearance.dimensions().height()
                            + INTERACTION_VISUAL_BOUNDS_MARGIN,
                    payload.agentZ() + halfWidth
            );
            Vec3 eye = player.getEyePosition();
            double reach = player.entityInteractionRange() + INTERACTION_VALIDATION_MARGIN;
            if (targetBounds.distanceToSqr(eye) > reach * reach) {
                MineTale.LOGGER.debug(
                        "Snowtown GPU resident interaction rejected: distance, distance={}, reach={}",
                        Math.sqrt(targetBounds.distanceToSqr(eye)),
                        reach
                );
                return;
            }
            // 精确像素命中无法在服务端重放；这里只验证到包围体最近点的方块视线及其事实。
            Vec3 targetHit = new Vec3(
                    Math.clamp(eye.x, targetBounds.minX, targetBounds.maxX),
                    Math.clamp(eye.y, targetBounds.minY, targetBounds.maxY),
                    Math.clamp(eye.z, targetBounds.minZ, targetBounds.maxZ)
            );
            HitResult obstruction = this.level.clip(new ClipContext(
                    eye,
                    targetHit,
                    ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE,
                    player
            ));
            if (obstruction.getType() != HitResult.Type.MISS
                    && obstruction.getLocation().distanceToSqr(eye) + 0.01D
                            < targetHit.distanceToSqr(eye)) {
                MineTale.LOGGER.debug(
                        "Snowtown GPU resident interaction rejected: block obstruction at {}",
                        obstruction.getLocation()
                );
                return;
            }

            DialogueSessionManager.StartResult result = DialogueSessionManager.tryStartVirtual(
                    player,
                    virtualResidentId(player, payload),
                    feet,
                    VIRTUAL_RESIDENT_TYPE,
                    VIRTUAL_RESIDENT_DIALOGUE
            );
            MineTale.LOGGER.debug(
                    "Snowtown GPU resident dialogue result: player={}, surface={}, agent={}, result={}",
                    player.getGameProfile().name(),
                    payload.surfaceId(),
                    payload.agentId(),
                    result
            );
        }

    }

    // 户外人口按静态规划连通域一次性分配
    static List<SurfacePopulation> buildSurfacePopulations(
            SnowtownPedestrianMap map,
            int outdoorPopulation
    ) {
        Map<SurfaceGroupKey, List<Integer>> cellsBySurface = new HashMap<>();
        for (int cell = 0; cell < map.cellCount(); cell++) {
            int region = map.region(cell);
            if (!map.isResidentRegion(region)) {
                continue;
            }
            int worldX = (int)Math.floor(map.x(cell));
            int worldZ = (int)Math.floor(map.z(cell));
            SurfaceGroupKey key = new SurfaceGroupKey(
                    Math.floorDiv(worldX, SnowtownCrowdPopulationPayload.SECTOR_SIZE),
                    Math.floorDiv(worldZ, SnowtownCrowdPopulationPayload.SECTOR_SIZE),
                    region
            );
            cellsBySurface.computeIfAbsent(key, ignored -> new ArrayList<>()).add(cell);
        }
        if (cellsBySurface.isEmpty()) {
            if (outdoorPopulation == 0) {
                return List.of();
            }
            throw new IllegalStateException("有户外人口的 Snowtown 没有居民可达连通面");
        }

        int cellSize = SnowtownSettings.PLANNING_AREA_CELL_SIZE;
        int cellArea = Math.multiplyExact(cellSize, cellSize);
        List<SurfaceDraft> drafts = cellsBySurface.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> createSurfaceDraft(map, entry.getKey(), entry.getValue(), cellArea))
                .toList();
        long totalWeight = drafts.stream().mapToLong(SurfaceDraft::walkableAreaBlocks).sum();
        int[] populationBySurface = new int[drafts.size()];
        long[] remainderBySurface = new long[drafts.size()];
        int assigned = 0;
        for (int surface = 0; surface < drafts.size(); surface++) {
            long numerator = (long)outdoorPopulation * drafts.get(surface).walkableAreaBlocks();
            populationBySurface[surface] = (int)(numerator / totalWeight);
            remainderBySurface[surface] = numerator % totalWeight;
            assigned += populationBySurface[surface];
        }
        List<Integer> remainderOrder = new ArrayList<>(drafts.size());
        for (int surface = 0; surface < drafts.size(); surface++) {
            remainderOrder.add(surface);
        }
        remainderOrder.sort(Comparator
                .comparingLong((Integer surface) -> remainderBySurface[surface])
                .reversed()
                .thenComparing(surface -> drafts.get(surface).key()));
        for (int remaining = outdoorPopulation - assigned; remaining > 0; remaining--) {
            int surface = remainderOrder.get((outdoorPopulation - assigned - remaining)
                    % remainderOrder.size());
            populationBySurface[surface]++;
        }

        List<SurfacePopulation> result = new ArrayList<>(drafts.size());
        for (int surface = 0; surface < drafts.size(); surface++) {
            SurfaceDraft draft = drafts.get(surface);
            result.add(new SurfacePopulation(
                    draft.key().sectorX(),
                    draft.key().sectorZ(),
                    draft.key().region(),
                    draft.seedX(),
                    draft.seedY(),
                    draft.seedZ(),
                    draft.walkableAreaBlocks(),
                    populationBySurface[surface],
                    draft.planningMaskWords()
            ));
        }
        return List.copyOf(result);
    }

    private static SurfaceDraft createSurfaceDraft(
            SnowtownPedestrianMap map,
            SurfaceGroupKey key,
            List<Integer> cells,
            int cellArea
    ) {
        double meanX = cells.stream().mapToDouble(map::x).average().orElseThrow();
        double meanY = cells.stream().mapToDouble(map::y).average().orElseThrow();
        double meanZ = cells.stream().mapToDouble(map::z).average().orElseThrow();
        int seed = cells.stream()
                .min(Comparator
                        .comparingDouble((Integer cell) -> {
                            double deltaX = map.x(cell) - meanX;
                            double deltaY = map.y(cell) - meanY;
                            double deltaZ = map.z(cell) - meanZ;
                            return deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ;
                        })
                .thenComparingInt(Integer::intValue))
                .orElseThrow();
        long[] planningMask = new long[SnowtownCrowdPopulationPayload.PLANNING_MASK_WORDS];
        for (int cell : cells) {
            int localBlockX = Math.floorMod(
                    (int)Math.floor(map.x(cell)),
                    SnowtownCrowdPopulationPayload.SECTOR_SIZE
            );
            int localBlockZ = Math.floorMod(
                    (int)Math.floor(map.z(cell)),
                    SnowtownCrowdPopulationPayload.SECTOR_SIZE
            );
            int planningX = localBlockX / SnowtownSettings.PLANNING_AREA_CELL_SIZE;
            int planningZ = localBlockZ / SnowtownSettings.PLANNING_AREA_CELL_SIZE;
            int maskBit = planningZ
                    * SnowtownCrowdPopulationPayload.PLANNING_MASK_EDGE
                    + planningX;
            planningMask[maskBit / Long.SIZE] |= 1L << (maskBit % Long.SIZE);
        }
        List<Long> planningMaskWords = new ArrayList<>(planningMask.length);
        for (long word : planningMask) {
            planningMaskWords.add(word);
        }
        return new SurfaceDraft(
                key,
                (int)Math.floor(map.x(seed)),
                (int)Math.floor(map.y(seed)),
                (int)Math.floor(map.z(seed)),
                Math.multiplyExact(cells.size(), cellArea),
                List.copyOf(planningMaskWords)
        );
    }

    private static int compareTownKeys(TownKey left, TownKey right) {
        int comparison = Integer.compare(left.areaX(), right.areaX());
        if (comparison != 0) {
            return comparison;
        }
        comparison = Integer.compare(left.areaZ(), right.areaZ());
        return comparison != 0
                ? comparison
                : Integer.compare(left.componentIndex(), right.componentIndex());
    }

    private record CrowdDirective(
            List<SnowtownCrowdPopulationPayload.TownPopulation> towns
    ) {
        private CrowdDirective {
            towns = List.copyOf(towns);
        }

        SnowtownCrowdPopulationPayload payload(long layoutRevision) {
            return new SnowtownCrowdPopulationPayload(layoutRevision, this.towns);
        }
    }

    static boolean planningMaskContains(
            SurfacePopulation surface,
            double worldX,
            double worldZ
    ) {
        int blockX = (int)Math.floor(worldX);
        int blockZ = (int)Math.floor(worldZ);
        if (Math.floorDiv(blockX, SnowtownCrowdPopulationPayload.SECTOR_SIZE)
                        != surface.sectorX()
                || Math.floorDiv(blockZ, SnowtownCrowdPopulationPayload.SECTOR_SIZE)
                        != surface.sectorZ()) {
            return false;
        }
        int localX = Math.floorMod(blockX, SnowtownCrowdPopulationPayload.SECTOR_SIZE);
        int localZ = Math.floorMod(blockZ, SnowtownCrowdPopulationPayload.SECTOR_SIZE);
        int planningX = localX / SnowtownSettings.PLANNING_AREA_CELL_SIZE;
        int planningZ = localZ / SnowtownSettings.PLANNING_AREA_CELL_SIZE;
        int bit = planningZ * SnowtownCrowdPopulationPayload.PLANNING_MASK_EDGE + planningX;
        long word = surface.planningMaskWords().get(bit / Long.SIZE);
        return (word & 1L << bit % Long.SIZE) != 0L;
    }

    private static UUID virtualResidentId(
            ServerPlayer player,
            SnowtownCrowdInteractPayload payload
    ) {
        String key = player.getUUID()
                + ":" + payload.areaX()
                + ":" + payload.areaZ()
                + ":" + payload.componentIndex()
                + ":" + payload.sectorX()
                + ":" + payload.sectorZ()
                + ":" + payload.surfaceId()
                + ":" + payload.agentId();
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }


    private record SurfaceGroupKey(
            int sectorX,
            int sectorZ,
            int region
    ) implements Comparable<SurfaceGroupKey> {
        @Override
        public int compareTo(SurfaceGroupKey other) {
            int comparison = Integer.compare(this.sectorX, other.sectorX);
            if (comparison != 0) {
                return comparison;
            }
            comparison = Integer.compare(this.sectorZ, other.sectorZ);
            return comparison != 0
                    ? comparison
                    : Integer.compare(this.region, other.region);
        }
    }

    private record SurfaceDraft(
            SurfaceGroupKey key,
            int seedX,
            int seedY,
            int seedZ,
            int walkableAreaBlocks,
            List<Long> planningMaskWords
    ) {
    }
}
