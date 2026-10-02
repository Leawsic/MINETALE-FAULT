package cn.jehorstudio.minetale.battle.presentation.screen.render;

import cn.jehorstudio.minetale.MineTale;
import cn.jehorstudio.minetale.battle.logic.actor.ActorAppearance;
import cn.jehorstudio.minetale.battle.logic.actor.VisualRef;
import cn.jehorstudio.minetale.battle.script.ActorPrefabDefinition;
import cn.jehorstudio.minetale.battle.script.BattleDefinition;
import cn.jehorstudio.minetale.lib.ObjModels;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

// 在准备阶段静态收集 BattleDefinition 可达的声明式渲染资源。
public record BattleResourceSet(
        Set<ResourceLocation> textures,
        Set<ObjModels> models,
        Set<ExtrudedImage> extrudedImages,
        Set<BattleScene.FrameMesh> frameMeshes,
        Set<String> texts
) {
    public static final BattleResourceSet EMPTY = new BattleResourceSet(
            Set.of(), Set.of(), Set.of(), Set.of(), Set.of()
    );

    public BattleResourceSet {
        textures = Set.copyOf(Objects.requireNonNull(textures, "textures"));
        models = Set.copyOf(Objects.requireNonNull(models, "models"));
        extrudedImages = Set.copyOf(Objects.requireNonNull(extrudedImages, "extrudedImages"));
        frameMeshes = Set.copyOf(Objects.requireNonNull(frameMeshes, "frameMeshes"));
        texts = Set.copyOf(Objects.requireNonNull(texts, "texts"));
    }

    public static BattleResourceSet collect(BattleDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        Collector collector = new Collector();
        collector.scan(definition.root(), null);
        for (ActorPrefabDefinition prefab : definition.compiled().actorPrefabs().values()) {
            collector.scan(prefab.root(), null);
        }
        for (ActorPrefabDefinition prefab : definition.compiled().globalActorPrefabs().values()) {
            collector.scan(prefab.root(), null);
        }
        for (var pattern : definition.compiled().patterns().values()) {
            pattern.actions().forEach(action -> collector.scan(action, null));
        }
        for (var pattern : definition.compiled().globalPatterns().values()) {
            pattern.actions().forEach(action -> collector.scan(action, null));
        }
        for (var outcome : definition.compiled().outcomes().values()) {
            collector.scan(outcome.root(), null);
        }
        for (var outcome : definition.compiled().globalOutcomes().values()) {
            collector.scan(outcome.root(), null);
        }
        return collector.result();
    }

    public record ExtrudedImage(
            ResourceLocation texture,
            ActorAppearance.SourceRect source,
            ActorAppearance.TextureSize textureSize
    ) {
        public ExtrudedImage {
            Objects.requireNonNull(texture, "texture");
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(textureSize, "textureSize");
        }
    }

    private static final class Collector {
        private final Set<ResourceLocation> textures = new LinkedHashSet<>();
        private final Set<ObjModels> models = new LinkedHashSet<>();
        private final Set<ExtrudedImage> extrudedImages = new LinkedHashSet<>();
        private final Set<BattleScene.FrameMesh> frameMeshes = new LinkedHashSet<>();
        private final Set<String> texts = new LinkedHashSet<>();

        private void scan(JsonElement element, String memberName) {
            if (element == null || element.isJsonNull()) {
                return;
            }
            if (element.isJsonArray()) {
                for (JsonElement child : element.getAsJsonArray()) {
                    scan(child, memberName);
                }
                return;
            }
            if (element.isJsonPrimitive()) {
                scanPrimitive(element, memberName);
                return;
            }

            JsonObject object = element.getAsJsonObject();
            if ("visual".equals(memberName)) {
                try {
                    collectVisual(VisualRef.fromJson(object));
                } catch (RuntimeException exception) {
                    MineTale.LOGGER.warn("无法静态解析 Battle visual；继续按字段收集资源", exception);
                }
            }
            if ("model".equals(memberName) && object.has("id") && object.get("id").isJsonPrimitive()) {
                collectModel(object.get("id").getAsString());
            }
            for (var entry : object.entrySet()) {
                scan(entry.getValue(), entry.getKey());
            }
        }

        private void scanPrimitive(JsonElement element, String memberName) {
            if (!element.getAsJsonPrimitive().isString()) {
                return;
            }
            String value = element.getAsString();
            if ("texture".equals(memberName)) {
                try {
                    this.textures.add(ResourceLocation.parse(value));
                } catch (RuntimeException exception) {
                    MineTale.LOGGER.warn("忽略无法解析的 Battle texture id: {}", value);
                }
            } else if ("visual".equals(memberName)) {
                collectModel(value);
            } else if ("model".equals(memberName)) {
                collectModel(value);
            } else if ("text".equals(memberName)) {
                this.texts.add(value);
            }
        }

        private void collectVisual(VisualRef visual) {
            if (visual.objModel() != null) {
                this.models.add(visual.objModel());
            }
            if (visual.appearance() == null) {
                return;
            }
            switch (visual.appearance().content()) {
                case ActorAppearance.ImageContent image -> collectImage(image);
                case ActorAppearance.ModelContent model -> this.models.add(model.model());
                case ActorAppearance.ImageModelContent hybrid -> {
                    collectImage(hybrid.image());
                    this.models.add(hybrid.model().model());
                }
                case ActorAppearance.TextContent text -> this.texts.add(text.text());
                case ActorAppearance.FrameContent frame -> {
                    if (frame.worldSize() != null) {
                        this.frameMeshes.add(new BattleScene.FrameMesh(
                                (float) frame.worldSize().x(),
                                (float) frame.worldSize().y(),
                                (float) frame.worldSize().z(),
                                (float) frame.thickness()
                        ));
                    }
                }
                case ActorAppearance.ProgressContent ignored -> {
                }
            }
        }

        private void collectImage(ActorAppearance.ImageContent image) {
            if (image.texture() != null) {
                this.textures.add(image.texture());
                if (image.thickness() > 0.0D) {
                    this.extrudedImages.add(new ExtrudedImage(
                            image.texture(), image.source(), image.textureSize()
                    ));
                }
            }
            if (image.model() != null) {
                this.models.add(image.model());
            }
        }

        private void collectModel(String value) {
            try {
                this.models.add(ObjModels.valueOf(value.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                // 动态或扩展 visual ID 无法映射为 ObjModels，不进入本地预加载集合。
            }
        }

        private BattleResourceSet result() {
            return new BattleResourceSet(
                    this.textures,
                    this.models,
                    this.extrudedImages,
                    this.frameMeshes,
                    this.texts
            );
        }
    }
}
