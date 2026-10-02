package cn.jehorstudio.minetale.battle.presentation.screen.render;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Consumer;

// 全屏 Pass 的统一边界：校验采样器、读写反馈与视口，并集中管理可选 UBO 绑定。
public final class FullscreenPassRunner {
    private final Map<String, Long> submissionNanos = new LinkedHashMap<>();

    public enum ClearPolicy {
        KEEP,
        TRANSPARENT_BLACK
    }

    public record Sampler(String name, GpuTextureView view) {
        public Sampler {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("全屏 Pass 的 Sampler 名称不能为空");
            }
            Objects.requireNonNull(view, "view");
        }

        public static Sampler color(String name, TextureTarget target) {
            Objects.requireNonNull(target, "target");
            return new Sampler(name, target.getColorTextureView());
        }
    }

    public void run(
            String label,
            RenderPipeline pipeline,
            List<Sampler> samplers,
            TextureTarget output,
            ClearPolicy clearPolicy,
            Consumer<RenderPass> uniformBinder
    ) {
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(pipeline, "pipeline");
        samplers = List.copyOf(Objects.requireNonNull(samplers, "samplers"));
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(clearPolicy, "clearPolicy");

        Set<String> names = new HashSet<>();
        for (Sampler sampler : samplers) {
            if (!names.add(sampler.name())) {
                throw new IllegalArgumentException("全屏 Pass 重复绑定 Sampler: " + sampler.name());
            }
            if (sampler.view().texture() == output.getColorTextureView().texture()) {
                throw new IllegalArgumentException("全屏 Pass 禁止读取并写入同一纹理: " + label);
            }
        }
        Set<String> declared = Set.copyOf(pipeline.getSamplers());
        if (!names.equals(declared)) {
            throw new IllegalArgumentException(
                    "全屏 Pass Sampler 与 Pipeline 声明不一致: " + label
                            + ", declared=" + declared + ", bound=" + names);
        }

        OptionalInt clearColor = clearPolicy == ClearPolicy.TRANSPARENT_BLACK
                ? OptionalInt.of(0x00000000)
                : OptionalInt.empty();
        long started = System.nanoTime();
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> label,
                output.getColorTextureView(), clearColor,
                null, OptionalDouble.empty()
        )) {
            pass.setViewport(0, 0, output.width, output.height);
            pass.setPipeline(pipeline);
            for (Sampler sampler : samplers) {
                pass.bindSampler(sampler.name(), sampler.view());
            }
            if (uniformBinder != null) {
                uniformBinder.accept(pass);
            }
            pass.draw(0, 3);
        } finally {
            this.submissionNanos.put(label, System.nanoTime() - started);
        }
    }

    public void beginFrame() {
        this.submissionNanos.clear();
    }

    public Map<String, Long> submissionNanos() {
        return Map.copyOf(this.submissionNanos);
    }
}
