package cn.jehorstudio.minetale.dimension.ebott.transition.client;

import cn.jehorstudio.minetale.dimension.ebott.transition.mixin.client.staging.PreparedLevelRendererAccessor;
import cn.jehorstudio.minetale.dimension.ebott.transition.mixin.client.staging.PreparedSectionCompileTaskAccessor;
import cn.jehorstudio.minetale.dimension.ebott.transition.mixin.client.staging.PreparedSectionDispatcherAccessor;
import cn.jehorstudio.minetale.dimension.ebott.transition.mixin.client.staging.PreparedViewAreaAccessor;
import cn.jehorstudio.minetale.dimension.ebott.transition.TargetFootprint;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;

// 在客户端 Tick 预算内逐步编译并上传目标足迹的 Section mesh。
final class PreparedTargetSectionPrecompiler {
    private static final int MAX_IN_FLIGHT = 4;
    private static final int MAX_UPLOADS_PER_TICK = 2;
    private static final long UPLOAD_BUDGET_NANOS = 750_000L;
    private static final int VANILLA_BLOCK_VERTEX_SIZE = DefaultVertexFormat.BLOCK.getVertexSize();
    private static final int MAX_RENDER_THREAD_COMPILES_PER_FRAME = 1;

    private final SectionRenderDispatcher dispatcher;
    private final RenderRegionCache regionCache = new RenderRegionCache();
    private final List<SectionRenderDispatcher.RenderSection> sections;
    private final ArrayDeque<SectionRenderDispatcher.RenderSection> pending;
    private final boolean sectionsOwnedByViewArea;
    private final Map<SectionRenderDispatcher.RenderSection,
            SectionRenderDispatcher.RenderSection.CompileTask> inFlight = new IdentityHashMap<>();
    private int compiledVertexSize;

    private PreparedTargetSectionPrecompiler(
            SectionRenderDispatcher dispatcher,
            List<SectionRenderDispatcher.RenderSection> sections,
            boolean sectionsOwnedByViewArea
    ) {
        this.dispatcher = dispatcher;
        this.sections = List.copyOf(sections);
        this.pending = new ArrayDeque<>(sections);
        this.sectionsOwnedByViewArea = sectionsOwnedByViewArea;
    }

    static PreparedTargetSectionPrecompiler start(
            ClientLevel level,
            LevelRenderer renderer,
            int centerBlockX,
            int centerBlockZ,
            int targetPreviewRadius,
            int cameraBlockX,
            int cameraBlockY,
            int cameraBlockZ
    ) {
        SectionRenderDispatcher dispatcher = renderer.getSectionRenderDispatcher();
        if (dispatcher == null) {
            return null;
        }

        TargetFootprint footprint = TargetFootprint.create(
                centerBlockX,
                centerBlockZ,
                targetPreviewRadius,
                cameraBlockX,
                cameraBlockZ
        );
        for (long packed : footprint.dataColumns()) {
            if (level.getChunkSource().getChunk(
                    TargetFootprint.chunkX(packed),
                    TargetFootprint.chunkZ(packed),
                    false
            ) == null) {
                return null;
            }
        }

        int cameraSectionY = SectionPos.blockToSectionCoord(cameraBlockY);
        dispatcher.setCameraPosition(new Vec3(
                cameraBlockX + 0.5,
                cameraBlockY,
                cameraBlockZ + 0.5
        ));

        List<Integer> verticalOrder = new ArrayList<>(level.getSectionsCount());
        for (int sectionY = level.getMinSectionY(); sectionY <= level.getMaxSectionY(); sectionY++) {
            verticalOrder.add(sectionY);
        }
        // 先按距玩家的列距离排序，再优先准备各列到达面以下的可见区域。
        verticalOrder.sort(Comparator
                .comparingInt((Integer sectionY) -> sectionY > cameraSectionY ? 1 : 0)
                .thenComparingInt(sectionY -> Math.abs(sectionY - cameraSectionY)));

        List<Long> sectionNodes = new ArrayList<>();
        for (long packed : footprint.renderColumns()) {
            for (int sectionY : verticalOrder) {
                int sectionX = TargetFootprint.chunkX(packed);
                int sectionZ = TargetFootprint.chunkZ(packed);
                LevelChunk chunk = level.getChunkSource().getChunk(sectionX, sectionZ, false);
                int sectionIndex = chunk.getSectionIndexFromSectionY(sectionY);
                if (chunk.getSection(sectionIndex).hasOnlyAir()) {
                    continue;
                }
                sectionNodes.add(SectionPos.asLong(sectionX, sectionY, sectionZ));
            }
        }

        List<SectionRenderDispatcher.RenderSection> viewAreaSections =
                collectCompleteViewAreaSections(renderer, sectionNodes);
        if (viewAreaSections != null) {
            return new PreparedTargetSectionPrecompiler(dispatcher, viewAreaSections, true);
        }

        List<SectionRenderDispatcher.RenderSection> independentSections =
                new ArrayList<>(sectionNodes.size());
        for (long sectionNode : sectionNodes) {
            independentSections.add(dispatcher.new RenderSection(
                    independentSections.size(), sectionNode));
        }
        return new PreparedTargetSectionPrecompiler(dispatcher, independentSections, false);
    }

    // 渲染使用本对象持有的快照，避免 Sodium 重建可见列表时改变迭代集合。
    List<SectionRenderDispatcher.RenderSection> sections() {
        return this.sections;
    }

    int pendingCount() {
        return this.pending.size();
    }

    int inFlightCount() {
        return this.inFlight.size();
    }

    boolean hasRenderableSections() {
        return this.sections.stream()
                .anyMatch(section -> section.getSectionMesh().hasRenderableLayers());
    }

    // 正式 renderer 接管期间保留现有 mesh，直到对应的接缝裁剪版本被逐步替换。
    void prepareForAdoption() {
        if (this.sectionsOwnedByViewArea) {
            this.sections.forEach(section -> section.setDirty(false));
        }
    }

    void close(boolean preserveSectionMeshes) {
        this.pending.clear();
        if (!preserveSectionMeshes || !this.sectionsOwnedByViewArea) {
            this.sections.forEach(SectionRenderDispatcher.RenderSection::reset);
        }
        this.inFlight.clear();
    }

    void tick() {
        drainUploadsWithinBudget();
        this.inFlight.entrySet().removeIf(entry ->
                ((PreparedSectionCompileTaskAccessor) (Object) entry.getValue())
                        .minetale$getCompleted()
                        .get());
        if (this.compiledVertexSize == 0 || this.compiledVertexSize != VANILLA_BLOCK_VERTEX_SIZE) {
            return;
        }
        while (this.inFlight.size() < MAX_IN_FLIGHT && !this.pending.isEmpty()) {
            SectionRenderDispatcher.RenderSection section = this.pending.removeFirst();
            SectionRenderDispatcher.RenderSection.CompileTask task =
                    section.createCompileTask(this.regionCache);
            this.dispatcher.schedule(task);
            section.setNotDirty();
            this.inFlight.put(section, task);
        }
    }

    // Iris 的 BLOCK 顶点格式只在地形帧内生效；构建与绘制必须处于同一 stride 环境。
    void prepareForRender(int requiredVertexSize) {
        if (requiredVertexSize <= 0) {
            throw new IllegalArgumentException("requiredVertexSize 必须为正数");
        }
        if (this.compiledVertexSize != requiredVertexSize) {
            resetForVertexSize(requiredVertexSize);
        }
        if (requiredVertexSize == VANILLA_BLOCK_VERTEX_SIZE) {
            return;
        }
        for (int compiled = 0;
             compiled < MAX_RENDER_THREAD_COMPILES_PER_FRAME && !this.pending.isEmpty();
             compiled++) {
            SectionRenderDispatcher.RenderSection section = this.pending.removeFirst();
            this.dispatcher.rebuildSectionSync(section, this.regionCache);
            this.dispatcher.uploadAllPendingUploads();
            section.setNotDirty();
        }
    }

    // 仅复用完整覆盖目标足迹的正式 mesh；Sodium 的备用 ViewArea 不保证该覆盖范围。
    private static List<SectionRenderDispatcher.RenderSection> collectCompleteViewAreaSections(
            LevelRenderer renderer,
            List<Long> sectionNodes
    ) {
        ViewArea viewArea = ((PreparedLevelRendererAccessor) renderer).minetale$getViewArea();
        if (viewArea == null) {
            return null;
        }
        PreparedViewAreaAccessor viewAreaAccessor = (PreparedViewAreaAccessor) viewArea;
        List<SectionRenderDispatcher.RenderSection> sections = new ArrayList<>(sectionNodes.size());
        for (long sectionNode : sectionNodes) {
            SectionRenderDispatcher.RenderSection section =
                    viewAreaAccessor.minetale$getRenderSection(sectionNode);
            if (section == null) {
                return null;
            }
            sections.add(section);
        }
        return sections;
    }

    private void resetForVertexSize(int requiredVertexSize) {
        this.pending.clear();
        this.inFlight.clear();
        for (SectionRenderDispatcher.RenderSection section : this.sections) {
            section.reset();
            this.pending.addLast(section);
        }
        this.compiledVertexSize = requiredVertexSize;
    }

    private void drainUploadsWithinBudget() {
        PreparedSectionDispatcherAccessor accessor = (PreparedSectionDispatcherAccessor) this.dispatcher;
        Queue<Runnable> uploads = accessor.minetale$getPendingUploads();
        long deadline = System.nanoTime() + UPLOAD_BUDGET_NANOS;
        for (int uploaded = 0; uploaded < MAX_UPLOADS_PER_TICK; uploaded++) {
            Runnable upload = uploads.poll();
            if (upload == null) {
                break;
            }
            upload.run();
            if (System.nanoTime() >= deadline) {
                break;
            }
        }
        Queue<SectionMesh> closes = accessor.minetale$getPendingCloses();
        SectionMesh stale = closes.poll();
        if (stale != null) {
            stale.close();
        }
    }
}
