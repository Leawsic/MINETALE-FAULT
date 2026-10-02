package cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.structure;

import cn.jehorstudio.minetale.dimension.worldgen.RainbowCakeModel;
import cn.jehorstudio.minetale.dimension.worldgen.WorldgenSamplingContext;
import cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.settings.SnowtownSettings;
import cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.terrain.SnowdinGroundProfile;
import cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.terrain.SnowdinSurfaceResolver;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

// 规划阶段共享的稀疏台地网格，统一缓存采样并判定 footprint 地形一致性。
final class SnowtownTerrainGrid {
    private static final int PAGE_SHIFT = 4;
    private static final int PAGE_SIZE = 1 << PAGE_SHIFT;
    private static final int PAGE_MASK = PAGE_SIZE - 1;
    private static final Object EMPTY = new Object();

    private final WorldgenSamplingContext context;
    private final RainbowCakeModel model;
    private final int minX;
    private final int minZ;
    private final int width;
    private final int depth;
    private final int pageWidth;
    private final Object[][] pages;
    private int requests;
    private int samples;
    private int allocatedPages;

    SnowtownTerrainGrid(WorldgenSamplingContext context, SnowtownPlanningArea.Bounds bounds) {
        this.context = context;
        this.model = RainbowCakeModel.create(context.worldgenSeed());
        this.minX = bounds.analysisMinX();
        this.minZ = bounds.analysisMinZ();
        this.width = bounds.analysisMaxXExclusive() - minX;
        this.depth = bounds.analysisMaxZExclusive() - minZ;
        this.pageWidth = (width + PAGE_MASK) >>> PAGE_SHIFT;
        int pageDepth = (depth + PAGE_MASK) >>> PAGE_SHIFT;
        this.pages = new Object[pageWidth * pageDepth][];
    }

    boolean footprintFits(BoundingBox bounds, int anchorX, int anchorZ) {
        SnowdinGroundProfile.Terrace anchor = sample(anchorX, anchorZ);
        if (!isUsable(anchor)) {
            return false;
        }

        int step = SnowtownSettings.LOT_TERRACE_VALIDATION_SAMPLE_STEP;
        int xSamples = axisSampleCount(bounds.minX(), bounds.maxX(), step);
        int zSamples = axisSampleCount(bounds.minZ(), bounds.maxZ(), step);
        for (int xIndex = 0; xIndex < xSamples; xIndex++) {
            int x = axisSampleAt(bounds.minX(), bounds.maxX(), step, xIndex, xSamples);
            for (int zIndex = 0; zIndex < zSamples; zIndex++) {
                int z = axisSampleAt(bounds.minZ(), bounds.maxZ(), step, zIndex, zSamples);
                if (!matches(anchor, sample(x, z))) {
                    return false;
                }
            }
        }
        return true;
    }

    boolean pointFits(int anchorX, int anchorZ, int x, int z) {
        return matches(sample(anchorX, anchorZ), sample(x, z));
    }

    String summary() {
        return "requests=" + requests
                + ", samples=" + samples
                + ", hits=" + (requests - samples)
                + ", pages=" + allocatedPages;
    }

    int samples() {
        return samples;
    }

    private SnowdinGroundProfile.Terrace sample(int x, int z) {
        requests++;
        int localX = x - minX;
        int localZ = z - minZ;
        if (localX < 0 || localX >= width || localZ < 0 || localZ >= depth) {
            return sampleKernel(x, z);
        }

        int pageIndex = (localZ >>> PAGE_SHIFT) * pageWidth + (localX >>> PAGE_SHIFT);
        Object[] page = pages[pageIndex];
        if (page == null) {
            page = new Object[PAGE_SIZE * PAGE_SIZE];
            pages[pageIndex] = page;
            allocatedPages++;
        }
        int index = ((localZ & PAGE_MASK) << PAGE_SHIFT) | (localX & PAGE_MASK);
        Object cached = page[index];
        if (cached == EMPTY) {
            return null;
        }
        if (cached != null) {
            return (SnowdinGroundProfile.Terrace) cached;
        }
        SnowdinGroundProfile.Terrace result = sampleKernel(x, z);
        page[index] = result == null ? EMPTY : result;
        return result;
    }

    private SnowdinGroundProfile.Terrace sampleKernel(int x, int z) {
        samples++;
        return SnowdinSurfaceResolver.computeUncappedTerrace(
                context,
                x,
                z,
                model.sample(x, z)
        );
    }

    private static boolean isUsable(SnowdinGroundProfile.Terrace terrace) {
        return terrace != null && terrace.mask() >= SnowtownSettings.CELL_TERRACE_MASK_MIN;
    }

    private static boolean matches(
            SnowdinGroundProfile.Terrace anchor,
            SnowdinGroundProfile.Terrace sample
    ) {
        return isUsable(anchor)
                && isUsable(sample)
                && Math.abs(sample.topY() - anchor.topY())
                <= SnowtownSettings.LOT_TERRACE_MAX_TOP_DELTA;
    }

    private static int axisSampleCount(int min, int max, int step) {
        int span = Math.max(0, max - min);
        int count = span / step + 1;
        return min + (count - 1) * step == max ? count : count + 1;
    }

    private static int axisSampleAt(int min, int max, int step, int index, int count) {
        return index == count - 1 ? max : min + index * step;
    }
}
