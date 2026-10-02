package cn.jehorstudio.minetale.dimension.ebott.transition.client;

import cn.jehorstudio.minetale.dimension.ebott.transition.mixin.client.staging.PreparedSectionBufferPoolAccessor;
import cn.jehorstudio.minetale.dimension.ebott.transition.mixin.client.staging.PreparedSectionDispatcherAccessor;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.SectionBufferBuilderPool;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.server.packs.resources.ResourceManager;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Queue;

// prepared renderer 独占有界编译池；退役后须等所有在途 pack 归还再释放 native buffer。
public final class PreparedSectionPoolRegistry {
    private static final int PREPARED_POOL_SIZE = 2;
    private static final Map<LevelRenderer, OwnedPool> ACTIVE = new IdentityHashMap<>();
    private static final List<OwnedPool> RETIRING = new ArrayList<>();

    private PreparedSectionPoolRegistry() {
    }

    public static void attach(LevelRenderer renderer) {
        if (ACTIVE.containsKey(renderer)) {
            throw new IllegalStateException("prepared renderer 已拥有 Section builder pool");
        }
        SectionRenderDispatcher dispatcher = renderer.getSectionRenderDispatcher();
        if (dispatcher == null) {
            throw new IllegalStateException("prepared renderer 尚未建立 Section dispatcher");
        }
        SectionBufferBuilderPool pool = SectionBufferBuilderPool.allocate(PREPARED_POOL_SIZE);
        int capacity = pool.getFreeBufferCount();
        if (capacity <= 0) {
            throw new IllegalStateException("prepared renderer 未获得 Section builder");
        }
        ((PreparedSectionDispatcherAccessor) dispatcher).minetale$setBufferPool(pool);
        ACTIVE.put(renderer, new OwnedPool(pool, capacity));
    }

    // setLevel(null) 可从多条清理路径到达，因此退役操作必须幂等。
    public static void retire(LevelRenderer renderer) {
        OwnedPool owned = ACTIVE.remove(renderer);
        if (owned != null) {
            RETIRING.add(owned);
        }
    }

    public static void reloadActive(ResourceManager resourceManager) {
        for (LevelRenderer renderer : List.copyOf(ACTIVE.keySet())) {
            renderer.onResourceManagerReload(resourceManager);
        }
    }

    // native builder pack 只能在客户端主线程确认无在途任务后回收。
    public static void tick() {
        Iterator<OwnedPool> iterator = RETIRING.iterator();
        while (iterator.hasNext()) {
            if (iterator.next().closeIfQuiescent()) {
                iterator.remove();
            }
        }
    }

    private record OwnedPool(
            SectionBufferBuilderPool pool,
            int capacity
    ) {
        private boolean closeIfQuiescent() {
            if (this.pool.getFreeBufferCount() != this.capacity) {
                return false;
            }
            Queue<SectionBufferBuilderPack> free =
                    ((PreparedSectionBufferPoolAccessor) this.pool).minetale$getFreeBuffers();
            if (free.size() != this.capacity) {
                return false;
            }
            SectionBufferBuilderPack pack;
            while ((pack = free.poll()) != null) {
                pack.close();
            }
            return true;
        }
    }
}
