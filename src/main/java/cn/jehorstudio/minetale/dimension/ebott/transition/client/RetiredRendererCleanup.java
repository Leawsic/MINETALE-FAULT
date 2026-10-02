package cn.jehorstudio.minetale.dimension.ebott.transition.client;

import cn.jehorstudio.minetale.dimension.ebott.transition.mixin.client.staging.PreparedLevelRendererAccessor;
import cn.jehorstudio.minetale.dimension.ebott.transition.mixin.client.staging.PreparedSectionDispatcherAccessor;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;

import java.util.Queue;

// 将旧 renderer 的 Section GPU buffer 分帧释放，以防阻塞换维调用栈。
final class RetiredRendererCleanup {
    private static final int DEFERRED_OPERATIONS_DELAY_TICKS = 20;
    private static final int QUIET_TICKS_BEFORE_RELEASE = 20;
    private static final int SECTIONS_PER_TICK = 2;

    private final LevelRenderer renderer;
    private final SectionRenderDispatcher dispatcher;
    private final ViewArea viewArea;
    private int quietTicks;
    private int deferredOperationDelayTicks;
    private int nextSection;
    private boolean compileQueueCleared;

    private RetiredRendererCleanup(
            LevelRenderer renderer,
            SectionRenderDispatcher dispatcher,
            ViewArea viewArea
    ) {
        this.renderer = renderer;
        this.dispatcher = dispatcher;
        this.viewArea = viewArea;
    }

    static RetiredRendererCleanup create(LevelRenderer renderer) {
        SectionRenderDispatcher dispatcher = renderer.getSectionRenderDispatcher();
        ViewArea viewArea = ((PreparedLevelRendererAccessor) renderer).minetale$getViewArea();
        return dispatcher == null || viewArea == null
                ? null
                : new RetiredRendererCleanup(renderer, dispatcher, viewArea);
    }

    boolean tick() {
        if (!this.compileQueueCleared) {
            this.dispatcher.clearCompileQueue();
            this.compileQueueCleared = true;
        }
        if (this.deferredOperationDelayTicks++ < DEFERRED_OPERATIONS_DELAY_TICKS) {
            return false;
        }
        drainOneDeferredOperation();
        if (!this.dispatcher.isQueueEmpty()) {
            this.quietTicks = 0;
            return false;
        }
        if (this.quietTicks++ < QUIET_TICKS_BEFORE_RELEASE) {
            return false;
        }

        int released = 0;
        while (released < SECTIONS_PER_TICK && this.nextSection < this.viewArea.sections.length) {
            this.viewArea.sections[this.nextSection++].reset();
            released++;
        }
        if (this.nextSection < this.viewArea.sections.length) {
            return false;
        }
        this.dispatcher.dispose();
        this.renderer.close();
        return true;
    }

    void closeNow() {
        this.renderer.setLevel(null);
        this.renderer.close();
    }

    private void drainOneDeferredOperation() {
        PreparedSectionDispatcherAccessor accessor = (PreparedSectionDispatcherAccessor) this.dispatcher;
        Runnable upload = accessor.minetale$getPendingUploads().poll();
        if (upload != null) {
            upload.run();
        }
        Queue<SectionMesh> closes = accessor.minetale$getPendingCloses();
        SectionMesh stale = closes.poll();
        if (stale != null) {
            stale.close();
        }
    }
}
