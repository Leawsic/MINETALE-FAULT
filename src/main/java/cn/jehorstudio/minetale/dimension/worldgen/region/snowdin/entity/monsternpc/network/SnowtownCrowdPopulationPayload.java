package cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.entity.monsternpc.network;

import cn.jehorstudio.minetale.MineTale;
import cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.settings.SnowtownSettings;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

// 低频同步视距内的权威宏观人口
public record SnowtownCrowdPopulationPayload(
        long layoutRevision,
        List<TownPopulation> towns
) implements CustomPacketPayload {
    public static final int SECTOR_SIZE = 128;
    public static final int PLANNING_MASK_EDGE =
            SECTOR_SIZE / SnowtownSettings.PLANNING_AREA_CELL_SIZE;
    public static final int PLANNING_MASK_WORDS =
            PLANNING_MASK_EDGE * PLANNING_MASK_EDGE / Long.SIZE;
    private static final int MAX_TOWNS_PER_PAYLOAD = 256;
    private static final int MAX_SURFACES_PER_TOWN = 256;

    public static final Type<SnowtownCrowdPopulationPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(MineTale.MODID, "snowtown_crowd_population")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, SnowtownCrowdPopulationPayload> STREAM_CODEC =
            StreamCodec.ofMember(SnowtownCrowdPopulationPayload::write, SnowtownCrowdPopulationPayload::read);

    public SnowtownCrowdPopulationPayload {
        towns = List.copyOf(towns);
        if (towns.size() > MAX_TOWNS_PER_PAYLOAD) {
            throw new IllegalArgumentException("Snowtown 人口快照包含过多镇区");
        }
    }

    public boolean active() {
        return !this.towns.isEmpty();
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeLong(this.layoutRevision);
        buffer.writeVarInt(this.towns.size());
        for (TownPopulation town : this.towns) {
            town.write(buffer);
        }
    }

    private static SnowtownCrowdPopulationPayload read(RegistryFriendlyByteBuf buffer) {
        long layoutRevision = buffer.readLong();
        int townCount = readBoundedCount(buffer, MAX_TOWNS_PER_PAYLOAD, "镇区");
        List<TownPopulation> towns = new ArrayList<>(townCount);
        for (int town = 0; town < townCount; town++) {
            towns.add(TownPopulation.read(buffer));
        }
        return new SnowtownCrowdPopulationPayload(layoutRevision, towns);
    }

    private static int readBoundedCount(
            RegistryFriendlyByteBuf buffer,
            int maximum,
            String subject
    ) {
        int count = buffer.readVarInt();
        if (count < 0 || count > maximum) {
            throw new IllegalArgumentException("Snowtown 人口快照的" + subject + "数量越界：" + count);
        }
        return count;
    }

    // 单个规划连通镇区的建筑容量与固定空间切片。
    public record TownPopulation(
            int areaX,
            int areaZ,
            int componentIndex,
            int buildingCount,
            int residentCapacity,
            int outdoorPopulation,
            int walkableAreaBlocks,
            List<SurfacePopulation> surfaces
    ) {
        public TownPopulation {
            surfaces = List.copyOf(surfaces);
            if (buildingCount < 0
                    || residentCapacity < 0
                    || outdoorPopulation < 0
                    || outdoorPopulation > residentCapacity
                    || walkableAreaBlocks < 0) {
                throw new IllegalArgumentException("Snowtown 镇区人口包含非法计数");
            }
            if (surfaces.size() > MAX_SURFACES_PER_TOWN) {
                throw new IllegalArgumentException("Snowtown 镇区包含过多可走面");
            }
            int assignedPopulation = surfaces.stream()
                    .mapToInt(SurfacePopulation::outdoorPopulation)
                    .sum();
            if (assignedPopulation != outdoorPopulation) {
                throw new IllegalArgumentException(
                        "Snowtown 空间人口配额与镇区户外人口不一致："
                                + assignedPopulation + " != " + outdoorPopulation);
            }
        }

        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeInt(this.areaX);
            buffer.writeInt(this.areaZ);
            buffer.writeVarInt(this.componentIndex);
            buffer.writeVarInt(this.buildingCount);
            buffer.writeVarInt(this.residentCapacity);
            buffer.writeVarInt(this.outdoorPopulation);
            buffer.writeVarInt(this.walkableAreaBlocks);
            buffer.writeVarInt(this.surfaces.size());
            for (SurfacePopulation surface : this.surfaces) {
                surface.write(buffer);
            }
        }

        private static TownPopulation read(RegistryFriendlyByteBuf buffer) {
            int areaX = buffer.readInt();
            int areaZ = buffer.readInt();
            int componentIndex = buffer.readVarInt();
            int buildingCount = buffer.readVarInt();
            int residentCapacity = buffer.readVarInt();
            int outdoorPopulation = buffer.readVarInt();
            int walkableAreaBlocks = buffer.readVarInt();
            int surfaceCount = readBoundedCount(buffer, MAX_SURFACES_PER_TOWN, "可走面");
            List<SurfacePopulation> surfaces = new ArrayList<>(surfaceCount);
            for (int surface = 0; surface < surfaceCount; surface++) {
                surfaces.add(SurfacePopulation.read(buffer));
            }
            return new TownPopulation(
                    areaX,
                    areaZ,
                    componentIndex,
                    buildingCount,
                    residentCapacity,
                    outdoorPopulation,
                    walkableAreaBlocks,
                    surfaces
            );
        }
    }

    // 由规划连通域识别的固定 XZ 扇区人口配额。
    public record SurfacePopulation(
            int sectorX,
            int sectorZ,
            int surfaceId,
            int seedX,
            int seedY,
            int seedZ,
            int walkableAreaBlocks,
            int outdoorPopulation,
            List<Long> planningMaskWords
    ) {
        public SurfacePopulation {
            planningMaskWords = List.copyOf(planningMaskWords);
            if (walkableAreaBlocks <= 0 || outdoorPopulation < 0) {
                throw new IllegalArgumentException("Snowtown 可走面人口包含非法计数");
            }
            if (Math.floorDiv(seedX, SECTOR_SIZE) != sectorX
                    || Math.floorDiv(seedZ, SECTOR_SIZE) != sectorZ) {
                throw new IllegalArgumentException("Snowtown 可走面种子不在声明扇区内");
            }
            if (planningMaskWords.size() != PLANNING_MASK_WORDS
                    || planningMaskWords.stream().allMatch(word -> word == 0L)) {
                throw new IllegalArgumentException("Snowtown 可走面规划掩码无效");
            }
        }

        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeInt(this.sectorX);
            buffer.writeInt(this.sectorZ);
            buffer.writeVarInt(this.surfaceId);
            buffer.writeInt(this.seedX);
            buffer.writeInt(this.seedY);
            buffer.writeInt(this.seedZ);
            buffer.writeVarInt(this.walkableAreaBlocks);
            buffer.writeVarInt(this.outdoorPopulation);
            for (long word : this.planningMaskWords) {
                buffer.writeLong(word);
            }
        }

        private static SurfacePopulation read(RegistryFriendlyByteBuf buffer) {
            int sectorX = buffer.readInt();
            int sectorZ = buffer.readInt();
            int surfaceId = buffer.readVarInt();
            int seedX = buffer.readInt();
            int seedY = buffer.readInt();
            int seedZ = buffer.readInt();
            int walkableAreaBlocks = buffer.readVarInt();
            int outdoorPopulation = buffer.readVarInt();
            List<Long> planningMaskWords = new ArrayList<>(PLANNING_MASK_WORDS);
            for (int word = 0; word < PLANNING_MASK_WORDS; word++) {
                planningMaskWords.add(buffer.readLong());
            }
            return new SurfacePopulation(
                    sectorX,
                    sectorZ,
                    surfaceId,
                    seedX,
                    seedY,
                    seedZ,
                    walkableAreaBlocks,
                    outdoorPopulation,
                    planningMaskWords
            );
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
