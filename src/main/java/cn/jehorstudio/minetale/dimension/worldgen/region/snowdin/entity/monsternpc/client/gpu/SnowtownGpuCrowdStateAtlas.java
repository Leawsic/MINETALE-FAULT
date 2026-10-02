package cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.entity.monsternpc.client.gpu;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.lwjgl.system.MemoryUtil;

import java.util.BitSet;

// 固定容量的 GPU 扇区页
// 工作集变化只重新绑定槽位
final class SnowtownGpuCrowdStateAtlas implements AutoCloseable {
    static final int MAX_PAGES = 64;
    static final int PAGE_COLUMNS = 8;
    static final int STATE_PAGE_SIZE = 32;
    static final int FIELD_PAGE_SIZE = SnowtownGpuCrowdStaticField.SIZE;

    private static final int PAGE_ROWS = Math.ceilDiv(MAX_PAGES, PAGE_COLUMNS);
    private static final int STATE_ATLAS_WIDTH = PAGE_COLUMNS * STATE_PAGE_SIZE;
    private static final int STATE_ATLAS_HEIGHT = PAGE_ROWS * STATE_PAGE_SIZE;
    private static final int FIELD_ATLAS_WIDTH = PAGE_COLUMNS * FIELD_PAGE_SIZE;
    private static final int FIELD_ATLAS_HEIGHT = PAGE_ROWS * FIELD_PAGE_SIZE;

    private final BitSet occupiedPages = new BitSet(MAX_PAGES);
    private final TextureTarget positionA = stateTarget("position A");
    private final TextureTarget positionB = stateTarget("position B");
    private final TextureTarget velocityA = stateTarget("velocity A");
    private final TextureTarget velocityB = stateTarget("velocity B");
    private final TextureTarget behaviorA = stateTarget("behavior A");
    private final TextureTarget behaviorB = stateTarget("behavior B");
    private final DynamicTexture staticFields = fieldTexture("static fields");
    private final DynamicTexture lightFields = fieldTexture("light fields");

    int acquirePage() {
        int page = this.occupiedPages.nextClearBit(0);
        if (page >= MAX_PAGES) {
            throw new IllegalStateException("Snowtown GPU宏扇区页容量已满");
        }
        this.occupiedPages.set(page);
        return page;
    }

    void releasePage(int page) {
        requirePage(page);
        if (!this.occupiedPages.get(page)) {
            throw new IllegalStateException("Snowtown GPU宏扇区页被重复释放：" + page);
        }
        this.occupiedPages.clear(page);
    }

    int stateOriginX(int page) {
        requireOccupiedPage(page);
        return page % PAGE_COLUMNS * STATE_PAGE_SIZE;
    }

    int stateOriginY(int page) {
        requireOccupiedPage(page);
        return page / PAGE_COLUMNS * STATE_PAGE_SIZE;
    }

    int fieldOriginX(int page) {
        requireOccupiedPage(page);
        return page % PAGE_COLUMNS * FIELD_PAGE_SIZE;
    }

    int fieldOriginY(int page) {
        requireOccupiedPage(page);
        return page / PAGE_COLUMNS * FIELD_PAGE_SIZE;
    }

    TextureTarget positionA() {
        return this.positionA;
    }

    TextureTarget positionB() {
        return this.positionB;
    }

    TextureTarget velocityA() {
        return this.velocityA;
    }

    TextureTarget velocityB() {
        return this.velocityB;
    }

    TextureTarget behaviorA() {
        return this.behaviorA;
    }

    TextureTarget behaviorB() {
        return this.behaviorB;
    }

    GpuTextureView staticFieldView() {
        return this.staticFields.getTextureView();
    }

    GpuTextureView lightFieldView() {
        return this.lightFields.getTextureView();
    }

    void uploadStaticField(int page, int[] pixels) {
        uploadFieldPage(this.staticFields, page, pixels);
    }

    void uploadLightField(int page, int[] pixels) {
        uploadFieldPage(this.lightFields, page, pixels);
    }

    int occupiedPageCount() {
        return this.occupiedPages.cardinality();
    }

    String dimensions() {
        return "state=" + STATE_ATLAS_WIDTH + "x" + STATE_ATLAS_HEIGHT
                + ",field=" + FIELD_ATLAS_WIDTH + "x" + FIELD_ATLAS_HEIGHT;
    }

    @Override
    public void close() {
        this.positionA.destroyBuffers();
        this.positionB.destroyBuffers();
        this.velocityA.destroyBuffers();
        this.velocityB.destroyBuffers();
        this.behaviorA.destroyBuffers();
        this.behaviorB.destroyBuffers();
        this.staticFields.close();
        this.lightFields.close();
        this.occupiedPages.clear();
    }

    private void uploadFieldPage(DynamicTexture destination, int page, int[] pixels) {
        requireOccupiedPage(page);
        if (pixels.length != FIELD_PAGE_SIZE * FIELD_PAGE_SIZE) {
            throw new IllegalArgumentException("Snowtown GPU场页像素数量不匹配");
        }
        try (NativeImage patch = new NativeImage(FIELD_PAGE_SIZE, FIELD_PAGE_SIZE, false)) {
            MemoryUtil.memIntBuffer(patch.getPointer(), pixels.length).put(pixels);
            RenderSystem.getDevice().createCommandEncoder().writeToTexture(
                    destination.getTexture(),
                    patch,
                    0,
                    0,
                    fieldOriginX(page),
                    fieldOriginY(page),
                    FIELD_PAGE_SIZE,
                    FIELD_PAGE_SIZE,
                    0,
                    0);
        }
    }

    private static TextureTarget stateTarget(String suffix) {
        return new TextureTarget(
                "Snowtown GPU crowd macro " + suffix,
                STATE_ATLAS_WIDTH,
                STATE_ATLAS_HEIGHT,
                false);
    }

    private static DynamicTexture fieldTexture(String suffix) {
        DynamicTexture texture = new DynamicTexture(
                "Snowtown GPU crowd macro " + suffix,
                FIELD_ATLAS_WIDTH,
                FIELD_ATLAS_HEIGHT,
                true);
        texture.setClamp(true);
        texture.setFilter(false, false);
        return texture;
    }

    private void requireOccupiedPage(int page) {
        requirePage(page);
        if (!this.occupiedPages.get(page)) {
            throw new IllegalStateException("Snowtown GPU宏扇区页尚未分配：" + page);
        }
    }

    private static void requirePage(int page) {
        if (page < 0 || page >= MAX_PAGES) {
            throw new IllegalArgumentException("Snowtown GPU宏扇区页越界：" + page);
        }
    }
}
