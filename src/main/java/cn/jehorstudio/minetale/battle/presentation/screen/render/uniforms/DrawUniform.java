package cn.jehorstudio.minetale.battle.presentation.screen.render.uniforms;

import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.MappableRingBuffer;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import cn.jehorstudio.minetale.battle.presentation.screen.render.BattleScene;

import java.nio.ByteBuffer;
import java.util.List;

// 所有 Battle draw material 共享的逐命令 UBO。
public final class DrawUniform implements AutoCloseable {
    private static final int BLOCK_SIZE = new Std140SizeCalculator()
            .putMat4f().putMat4f().putMat4f()
            .putVec4().putVec4().putVec4().putVec4().putVec4()
            .get();

    private final int stride = align(BLOCK_SIZE, RenderSystem.getDevice().getUniformOffsetAlignment());
    private MappableRingBuffer buffer;
    private int capacity;

    public void upload(BattleScene.Frame frame, int viewportWidth, int viewportHeight) {
        List<BattleScene.RenderCommand> commands = frame.commands();
        ensureCapacity(commands.size());
        if (commands.isEmpty()) {
            return;
        }
        Matrix4f identity = new Matrix4f();
        Matrix4f screenProjection = frame.screenProjection();
        try (var mapped = RenderSystem.getDevice().createCommandEncoder()
                .mapBuffer(this.buffer.currentBuffer(), false, true)) {
            ByteBuffer data = mapped.data();
            for (int index = 0; index < commands.size(); index++) {
                BattleScene.RenderCommand command = commands.get(index);
                int offset = index * this.stride;
                boolean screen = command.binding() == BattleScene.ActorSpaceBinding.SCREEN_PLANE;
                Matrix4f view = screen ? identity : frame.camera().view();
                Matrix4f projection = screen ? screenProjection : frame.camera().projection();
                view.get(offset, data);
                projection.get(offset + 64, data);
                command.model().get(offset + 128, data);
                putColor(data, offset + 192, command.material() == BattleScene.Material.GLYPH ? 0xFFFFFFFF : command.color());
                BattleScene.AuxiliaryGrid grid = command.auxiliaryGrid();
                BattleScene.FrameMesh frameMesh = command.frameMesh();
                if (frameMesh != null) {
                    data.putFloat(offset + 208, frameMesh.sizeX() * 0.5F);
                    data.putFloat(offset + 212, frameMesh.sizeY() * 0.5F);
                    data.putFloat(offset + 216, frameMesh.sizeZ() * 0.5F);
                    data.putFloat(offset + 220, frameMesh.effectiveThickness());
                } else if (grid == null) {
                    putVector(data, offset + 208, command.uvRect());
                } else {
                    putColor(data, offset + 208, grid.fillColor());
                }
                data.putFloat(offset + 224, viewportWidth);
                data.putFloat(offset + 228, viewportHeight);
                data.putFloat(offset + 232, grid == null ? command.lineWidth() : grid.lineWidth());
                data.putFloat(offset + 236, grid == null ? alphaShaderMode(command) : grid.density());
                if (frameMesh != null) {
                    putFrameCamera(data, offset + 240, frame, command);
                } else {
                    putPoint(data, offset + 240, command.lineStart());
                }
                putPoint(data, offset + 256, command.lineEnd());
                data.putFloat(offset + 268, command.rendered().receivesShadow() ? 1.0F : 0.0F);
            }
        }
    }

    public void bind(RenderPass pass, int commandIndex) {
        pass.setUniform("DrawUniform", this.buffer.currentBuffer().slice(commandIndex * this.stride, BLOCK_SIZE));
    }

    public void rotate() {
        if (this.buffer != null) {
            this.buffer.rotate();
        }
    }

    @Override
    public void close() {
        if (this.buffer != null) {
            this.buffer.close();
            this.buffer = null;
            this.capacity = 0;
        }
    }

    private void ensureCapacity(int required) {
        if (required <= this.capacity) {
            return;
        }
        int newCapacity = Math.max(8, Integer.highestOneBit(required - 1) << 1);
        if (this.buffer != null) {
            this.buffer.close();
        }
        this.buffer = new MappableRingBuffer(() -> "Battle draw uniforms", 130, Math.multiplyExact(this.stride, newCapacity));
        this.capacity = newCapacity;
    }

    private static void putColor(ByteBuffer data, int offset, int color) {
        data.putFloat(offset, ((color >>> 16) & 0xFF) / 255.0F);
        data.putFloat(offset + 4, ((color >>> 8) & 0xFF) / 255.0F);
        data.putFloat(offset + 8, (color & 0xFF) / 255.0F);
        data.putFloat(offset + 12, ((color >>> 24) & 0xFF) / 255.0F);
    }

    private static void putVector(ByteBuffer data, int offset, Vector4f vector) {
        data.putFloat(offset, vector.x());
        data.putFloat(offset + 4, vector.y());
        data.putFloat(offset + 8, vector.z());
        data.putFloat(offset + 12, vector.w());
    }

    private static void putPoint(ByteBuffer data, int offset, Vector3f point) {
        Vector3f value = point == null ? new Vector3f() : point;
        data.putFloat(offset, value.x());
        data.putFloat(offset + 4, value.y());
        data.putFloat(offset + 8, value.z());
        data.putFloat(offset + 12, 1.0F);
    }

    // StartPoint.xyz 保存局部相机位置，w 保存角点淡出；15°–20° 过渡区避免阈值闪跳。
    private static void putFrameCamera(
            ByteBuffer data,
            int offset,
            BattleScene.Frame frame,
            BattleScene.RenderCommand command
    ) {
        Matrix4f worldToLocal = command.model().invert(new Matrix4f());
        Vector3f cameraLocal = worldToLocal.transformPosition(new Vector3f(frame.camera().position()));
        Vector3f viewLocal = worldToLocal.transformDirection(new Vector3f(frame.camera().viewDirection())).normalize();
        float dominantAxis = Math.max(Math.abs(viewLocal.x()), Math.max(Math.abs(viewLocal.y()), Math.abs(viewLocal.z())));
        float transition = Math.clamp((dominantAxis - 0.9396926F) / (0.9659258F - 0.9396926F), 0.0F, 1.0F);
        float fadeStrength = 1.0F - transition * transition * (3.0F - 2.0F * transition);
        data.putFloat(offset, cameraLocal.x());
        data.putFloat(offset + 4, cameraLocal.y());
        data.putFloat(offset + 8, cameraLocal.z());
        data.putFloat(offset + 12, fadeStrength);
    }

    // Shader 契约：0=translucent，1=标准 cutout，2=任意非零 alpha 的挤出表面。
    private static float alphaShaderMode(BattleScene.RenderCommand command) {
        if (command.geometry() == BattleScene.Geometry.EXTRUDED_IMAGE) {
            return 2.0F;
        }
        return command.alphaMode() == BattleScene.AlphaMode.TRANSLUCENT ? 0.0F : 1.0F;
    }

    private static int align(int value, int alignment) {
        int safeAlignment = Math.max(1, alignment);
        return Math.multiplyExact((value + safeAlignment - 1) / safeAlignment, safeAlignment);
    }
}
