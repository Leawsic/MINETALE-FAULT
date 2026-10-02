package cn.jehorstudio.minetale.dimension.ebott.mountain;

import cn.jehorstudio.minetale.MineTale;
import cn.jehorstudio.minetale.dimension.ebott.*;
import cn.jehorstudio.minetale.dimension.ebott.entrance.CaveEntranceGenerator;
import cn.jehorstudio.minetale.dimension.ebott.shaft.ShaftData;
import cn.jehorstudio.minetale.dimension.ebott.shaft.ShaftGenerator;
import cn.jehorstudio.minetale.dimension.worldgen.registry.ModWorldgenKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

// 只协调山体细化、原版装饰与竖井写入；具体几何由对应模块持有。
public final class MountainGenerator {
    private MountainGenerator() {
    }

    public static void onServerStarted(ServerStartedEvent event) {
        ServerLevel overworld = event.getServer().overworld();
        EbottData data = EbottData.get(overworld);
        boolean newlyResolved = data.resolve(overworld);
        EbottData.Snapshot snapshot = data.snapshot().orElseThrow(
                () -> new IllegalStateException("Ebott data was not resolved")
        );
        EbottData.publish(event.getServer(), snapshot);
        migrateShaftIfNeeded(event.getServer(), data, snapshot, newlyResolved);

        PlaceManager.Place place = snapshot.place();
        CaveEntranceGenerator.Placement cave = snapshot.caveEntrance();
        MineTale.LOGGER.info(
                "{} Ebott at ({}, {}, {}), summit={}, caveOrigin={}, caveMouth={}, "
                        + "shaftOpeningY={}, target={}({}, {}), profile={}, generation={}",
                newlyResolved ? "Resolved" : "Restored",
                place.centerX(),
                place.baseY(),
                place.centerZ(),
                place.summitY(),
                cave.origin(),
                cave.mouth(),
                cave.shaftOpeningY(),
                EbottDestination.ENABLE_SNOWDIN_TARGET ? "snowdin" : "origin",
                EbottDestination.centerX(),
                EbottDestination.centerZ(),
                place.profileVersion(),
                place.generationVersion()
        );
    }

    public static void onServerStopping(ServerStoppingEvent event) {
        EbottData.clear(event.getServer());
    }

    // CARVERS 后立即冻结原版地形，隔离邻区块 Feature 写入顺序。
    public static void captureOriginalSurface(ServerLevel level, ChunkAccess chunk) {
        EbottData.Snapshot snapshot = EbottData.snapshotFor(level.getServer());
        if (snapshot != null
                && level.dimension() == Level.OVERWORLD
                && snapshot.place().intersectsChunk(chunk.getPos())
                && !chunk.hasData(EbottMountainAttachments.SURFACE_SNAPSHOT)) {
            chunk.setData(EbottMountainAttachments.SURFACE_SNAPSHOT, SurfaceSnapshot.capture(chunk));
        }
    }

    // 这是 Mixin 包装原版 Feature 阶段的唯一入口。
    public static void applyBiomeDecoration(
            ChunkGenerator generator,
            WorldGenLevel level,
            ChunkAccess chunk,
            StructureManager structureManager
    ) {
        ServerLevel serverLevel = level.getLevel();
        EbottData.Snapshot snapshot = EbottData.snapshotFor(serverLevel.getServer());
        boolean isOverworld = serverLevel.dimension() == Level.OVERWORLD;
        if (snapshot != null
                && isOverworld
                && snapshot.place().intersectsChunk(chunk.getPos())) {
            MountainDetailing.applyBiomeDecoration(generator, level, chunk, structureManager, snapshot);
        } else {
            generator.applyBiomeDecoration(level, chunk, structureManager);
        }

        if (snapshot == null) {
            return;
        }
        if (isOverworld) {
            CaveEntranceGenerator.writeChunk(generator, level, chunk, snapshot);
            ShaftGenerator.writeSourceChunk(chunk, snapshot, level.getMinY(), level.getMaxY());
        } else if (serverLevel.dimension().equals(ModWorldgenKeys.UNDERGROUND_LEVEL)
                && ShaftGenerator.intersectsTargetChunk(
                        chunk,
                        snapshot.shaft(),
                        EbottDestination.centerX(),
                        EbottDestination.centerZ()
                )) {
            ShaftGenerator.writeTargetChunk(
                    chunk,
                    snapshot.shaft(),
                    EbottDestination.centerX(),
                    EbottDestination.centerZ(),
                    level.getMinY(),
                    level.getMaxY(),
                    EbottDestination.targetMinYResolver(serverLevel, generator),
                    EbottDestination.targetSeamY(
                            serverLevel,
                            generator,
                            snapshot.shaft().profile()
                    )
            );
        }
    }

    public static RepairResult repairNow(
            MinecraftServer server,
            EbottData data,
            EbottData.Snapshot snapshot
    ) {
        ServerLevel target = server.getLevel(ModWorldgenKeys.UNDERGROUND_LEVEL);
        if (target == null) {
            throw new IllegalStateException("missing underground dimension for Ebott shaft repair");
        }

        int caveChunks = CaveEntranceGenerator.repairGenerated(server.overworld(), snapshot);
        int sourceChunks = ShaftGenerator.repairGeneratedSource(server.overworld(), snapshot);
        ChunkGenerator targetGenerator = target.getChunkSource().getGenerator();
        int targetChunks = ShaftGenerator.repairGeneratedTarget(
                target,
                snapshot,
                EbottDestination.centerX(),
                EbottDestination.centerZ(),
                EbottDestination.targetMinYResolver(target, targetGenerator),
                EbottDestination.targetSeamY(
                        target,
                        targetGenerator,
                        snapshot.shaft().profile()
                )
        );
        data.markShaftApplied(
                ShaftData.CURRENT_APPLIED_VERSION,
                EbottDestination.ENABLE_SNOWDIN_TARGET
        );
        return new RepairResult(caveChunks, sourceChunks, targetChunks);
    }

    private static void migrateShaftIfNeeded(
            MinecraftServer server,
            EbottData data,
            EbottData.Snapshot snapshot,
            boolean newlyResolved
    ) {
        if (data.shaftData().appliedVersion() >= ShaftData.CURRENT_APPLIED_VERSION
                && data.snowdinTargetApplied() == EbottDestination.ENABLE_SNOWDIN_TARGET) {
            return;
        }
        if (newlyResolved) {
            data.markShaftApplied(
                    ShaftData.CURRENT_APPLIED_VERSION,
                    EbottDestination.ENABLE_SNOWDIN_TARGET
            );
            return;
        }
        RepairResult result = repairNow(server, data, snapshot);
        MineTale.LOGGER.info(
                "Migrated persisted Ebott entrance: caveChunks={}, sourceChunks={}, "
                        + "targetChunks={}, version={}",
                result.caveChunks(),
                result.sourceChunks(),
                result.targetChunks(),
                ShaftData.CURRENT_APPLIED_VERSION
        );
    }

    public record RepairResult(int caveChunks, int sourceChunks, int targetChunks) {
    }
}
