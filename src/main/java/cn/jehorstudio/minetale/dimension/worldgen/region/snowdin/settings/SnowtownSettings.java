package cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.settings;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

// Snowtown 规划、装箱、道路与装饰共用的顶层常量
public final class SnowtownSettings {
    private SnowtownSettings() {}

    // 会改变确定性 plan 的参数或算法必须同步提升缓存版本。
    public static final int PLANNING_SETTINGS_VERSION = 16;

    // core 决定归属与写入边界，halo 只用于分析；二者都须与 Chunk/cell 对齐。
    public static final int PLANNING_AREA_CORE_SIZE = 512;

    public static final int PLANNING_AREA_HALO_SIZE = 64;

    public static final int PLANNING_AREA_ANALYSIS_SIZE =
            PLANNING_AREA_CORE_SIZE + PLANNING_AREA_HALO_SIZE * 2;

    // reach 限制当前 Chunk 需要预热的相邻 PlanningArea。
    public static final int MAX_LOT_REACH_BLOCKS = 32;

    // 两级缓存均为可确定性重算的性能层，必须保持有界。
    public static final int MAX_CACHED_PLANNING_AREAS = 512;

    public static final int MAX_CACHED_LOT_PLANS = 512;

    // 粗 cell 同时承载地形快筛、连通域与道路规划。
    public static final int PLANNING_AREA_CELL_SIZE = 4;

    public static final int PLANNING_AREA_CELL_CENTER_OFFSET = PLANNING_AREA_CELL_SIZE / 2;

    public static final double CELL_TERRACE_MASK_MIN = 0.65;

    public static final double CELL_PILLAR_MASK_MAX = 0.20;

    public static final int FLOOD_FILL_NEIGHBOR_COUNT = 4;

    public static final int MIN_COMPONENT_CORE_CELLS = 64;

    // 装箱按 landmark、regular、infill 三阶段推进，共用候选预算与 accepted lot 上限。
    public static final int LOT_PACKING_LANDMARK_MIN_AREA = 900;

    public static final int LOT_PACKING_INFILL_MAX_AREA = 240;

    public static final int LOT_PACKING_MAX_LANDMARKS_PER_COMPONENT = 2;

    public static final int LOT_PACKING_LANDMARK_CANDIDATE_LIMIT = 512;
    public static final int LOT_PACKING_REGULAR_CANDIDATE_LIMIT = 8192;
    public static final int LOT_PACKING_INFILL_CANDIDATE_LIMIT = 16384;

    public static final int LOT_PACKING_LANDMARK_ASSET_ATTEMPTS = 3;
    public static final int LOT_PACKING_REGULAR_ASSET_ATTEMPTS = 4;
    public static final int LOT_PACKING_INFILL_ASSET_ATTEMPTS = 3;

    public static final int LOT_PACKING_MAX_ACCEPTED_PER_COMPONENT = 512;
    public static final int LOT_PACKING_CORE_CELLS_PER_ACCEPTED_LIMIT = 12;

    public static final double LOT_PACKING_REGULAR_TARGET_COVERAGE = 0.48;
    public static final double LOT_PACKING_FINAL_TARGET_COVERAGE = 0.62;

    public static final int LOT_PACKING_LANDMARK_MARGIN = 1;
    public static final int LOT_PACKING_REGULAR_MARGIN = 1;
    public static final int LOT_PACKING_INFILL_MARGIN = 1;

    public static final int LOT_PACKING_LANDMARK_FRONTAGE = 3;
    public static final int LOT_PACKING_REGULAR_FRONTAGE = 2;
    public static final int LOT_PACKING_INFILL_FRONTAGE = 1;

    // 朝向评分以门前可达性为主，局部净空和连通域中心只提供次级偏置。
    public static final int LOT_FACING_SAMPLE_DISTANCE = 10;

    public static final int LOT_FRONTAGE_NEAR_SAMPLE_DISTANCE = 3;

    public static final int LOT_FRONTAGE_NEAR_HALF_WIDTH = 1;

    public static final int LOT_FRONTAGE_MAX_SURFACE_DELTA = 1;

    public static final int LOT_FRONTAGE_CLIFF_DROP_BLOCKS = 2;

    public static final int LOT_FRONTAGE_CELL_SAMPLE_DISTANCE = 12;

    public static final int LOT_FRONTAGE_CELL_HALF_WIDTH = 1;

    public static final double LOT_OPENNESS_FACING_WEIGHT = 0.25;

    public static final double LOT_COMPONENT_CENTER_FACING_WEIGHT = 18.0;

    // 粗筛通过后仍须按稀疏网格验证 footprint 的台地支撑与高差。
    public static final int LOT_TERRACE_VALIDATION_SAMPLE_STEP = 4;

    public static final double LOT_TERRACE_MAX_TOP_DELTA = 1.0;

    public static final double LOT_REQUIRED_SURFACE_COVERAGE = 0.80;

    // 道路从门口 terminal 构建主骨架，再用受限短支路连接其余 lot。
    public static final BlockState ROAD_BLOCK = Blocks.ANDESITE.defaultBlockState();

    public static final int ROAD_FRONTAGE_OFFSET_BLOCKS = 3;

    public static final int ROAD_BUCKET_SIZE_BLOCKS = 32;

    public static final int ROAD_MAX_BACKBONE_TERMINALS_PER_REGION = 8;

    public static final int ROAD_REGULAR_BACKBONE_MIN_NEW_CELLS = 12;
    public static final int ROAD_REGULAR_BACKBONE_MAX_NEW_CELLS = 24;

    public static final int ROAD_REGULAR_CONNECTOR_MIN_NEW_CELLS = 2;
    public static final int ROAD_REGULAR_CONNECTOR_MAX_NEW_CELLS = 5;

    public static final int ROAD_INFILL_CONNECTOR_MAX_NEW_CELLS = 1;

    public static final int ROAD_MAX_ROUTE_COST_PER_CELL = 30;

    public static final int ROAD_DOOR_SNAP_RADIUS_CELLS = 2;

    public static final int ROAD_MAIN_WIDTH = 3;

    public static final int ROAD_CONNECTOR_WIDTH = 3;

    // 装饰在最终道路与建筑计划上运行，并共用全局间距预算。
    public static final int DECORATION_JUNCTION_PROBE_DISTANCE = 4;

    public static final int DECORATION_JUNCTION_MIN_ROAD_BLOCKS = 17;

    public static final int DECORATION_JUNCTION_MIN_SPACING = 48;

    public static final int DECORATION_POST_MIN_SPACING = 9;

    public static final int DECORATION_SMALL_LIGHT_FOUR_WAY_CLEARANCE = 9;

    public static final int DECORATION_POST_BUILDING_CLEARANCE = 2;

    public static final int DECORATION_POST_PALETTE_BLOCK_SIZE = 48;

    public static final double DECORATION_DIRECTIONAL_BASE_CHANCE = 0.22;

    public static void validate() {
        if (PLANNING_AREA_CORE_SIZE % 16 != 0) {
            throw new IllegalStateException("Snowtown PlanningArea core size must align to chunk size");
        }
        if (PLANNING_AREA_CORE_SIZE % PLANNING_AREA_CELL_SIZE != 0) {
            throw new IllegalStateException("Snowtown PlanningArea core size must align to cell size");
        }
        if (PLANNING_AREA_HALO_SIZE % PLANNING_AREA_CELL_SIZE != 0) {
            throw new IllegalStateException("Snowtown PlanningArea halo size must align to cell size");
        }
        if (FLOOD_FILL_NEIGHBOR_COUNT != 4 && FLOOD_FILL_NEIGHBOR_COUNT != 8) {
            throw new IllegalStateException("Snowtown flood fill neighbor count must be 4 or 8");
        }
        if (LOT_PACKING_INFILL_MAX_AREA <= 0
                || LOT_PACKING_LANDMARK_MIN_AREA <= LOT_PACKING_INFILL_MAX_AREA
                || LOT_PACKING_MAX_LANDMARKS_PER_COMPONENT < 0
                || LOT_PACKING_LANDMARK_CANDIDATE_LIMIT <= 0
                || LOT_PACKING_REGULAR_CANDIDATE_LIMIT <= 0
                || LOT_PACKING_INFILL_CANDIDATE_LIMIT <= 0
                || LOT_PACKING_LANDMARK_ASSET_ATTEMPTS <= 0
                || LOT_PACKING_REGULAR_ASSET_ATTEMPTS <= 0
                || LOT_PACKING_INFILL_ASSET_ATTEMPTS <= 0
                || LOT_PACKING_MAX_ACCEPTED_PER_COMPONENT <= 0
                || LOT_PACKING_CORE_CELLS_PER_ACCEPTED_LIMIT <= 0
                || LOT_PACKING_REGULAR_TARGET_COVERAGE < 0.0
                || LOT_PACKING_REGULAR_TARGET_COVERAGE > LOT_PACKING_FINAL_TARGET_COVERAGE
                || LOT_PACKING_FINAL_TARGET_COVERAGE > 1.0
                || LOT_PACKING_LANDMARK_MARGIN < 1
                || LOT_PACKING_REGULAR_MARGIN < 1
                || LOT_PACKING_INFILL_MARGIN < 1
                || LOT_PACKING_LANDMARK_FRONTAGE < LOT_PACKING_LANDMARK_MARGIN
                || LOT_PACKING_REGULAR_FRONTAGE < LOT_PACKING_REGULAR_MARGIN
                || LOT_PACKING_INFILL_FRONTAGE < LOT_PACKING_INFILL_MARGIN
                || LOT_TERRACE_VALIDATION_SAMPLE_STEP <= 0
                || LOT_TERRACE_MAX_TOP_DELTA < 0.0) {
            throw new IllegalStateException("Snowtown lot packing settings are invalid");
        }
        if (ROAD_BUCKET_SIZE_BLOCKS <= 0
                || ROAD_MAX_BACKBONE_TERMINALS_PER_REGION <= 0
                || ROAD_REGULAR_BACKBONE_MIN_NEW_CELLS < 0
                || ROAD_REGULAR_BACKBONE_MAX_NEW_CELLS < ROAD_REGULAR_BACKBONE_MIN_NEW_CELLS
                || ROAD_REGULAR_CONNECTOR_MIN_NEW_CELLS < 0
                || ROAD_REGULAR_CONNECTOR_MAX_NEW_CELLS < ROAD_REGULAR_CONNECTOR_MIN_NEW_CELLS
                || ROAD_INFILL_CONNECTOR_MAX_NEW_CELLS < 0
                || ROAD_MAX_ROUTE_COST_PER_CELL <= 0
                || ROAD_DOOR_SNAP_RADIUS_CELLS < 0
                || ROAD_MAIN_WIDTH <= 0
                || ROAD_CONNECTOR_WIDTH <= 0) {
            throw new IllegalStateException("Snowtown road settings must be positive");
        }
        if (DECORATION_JUNCTION_PROBE_DISTANCE <= 0
                || DECORATION_JUNCTION_MIN_ROAD_BLOCKS <= 0
                || DECORATION_JUNCTION_MIN_ROAD_BLOCKS > 25
                || DECORATION_JUNCTION_MIN_SPACING <= 0
                || DECORATION_POST_MIN_SPACING <= 0
                || DECORATION_SMALL_LIGHT_FOUR_WAY_CLEARANCE < DECORATION_POST_MIN_SPACING
                || DECORATION_POST_BUILDING_CLEARANCE < 0
                || DECORATION_POST_PALETTE_BLOCK_SIZE <= 0
                || DECORATION_DIRECTIONAL_BASE_CHANCE < 0.0
                || DECORATION_DIRECTIONAL_BASE_CHANCE > 1.0) {
            throw new IllegalStateException("Snowtown decoration settings are invalid");
        }
    }
}
