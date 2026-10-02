package cn.jehorstudio.minetale.battle.presentation.screen.render;

import cn.jehorstudio.minetale.battle.logic.BattleLogicStateSnapshot;
import cn.jehorstudio.minetale.battle.logic.actor.ActorAppearance;
import cn.jehorstudio.minetale.battle.logic.actor.ActorRef;
import cn.jehorstudio.minetale.battle.logic.actor.ActorType;
import cn.jehorstudio.minetale.battle.logic.actor.VisualRef;
import cn.jehorstudio.minetale.battle.logic.actor.states.ActorSingleSnapshot;
import cn.jehorstudio.minetale.battle.logic.actor.states.types.ActorTypeSnapshot;
import cn.jehorstudio.minetale.battle.logic.actor.states.types.NetworkProxyStateSnapshot;
import cn.jehorstudio.minetale.battle.logic.actor.states.types.PlayerSoulMode;
import cn.jehorstudio.minetale.battle.logic.actor.states.types.PlayerSoulStateSnapshot;
import cn.jehorstudio.minetale.battle.logic.actor.states.types.ScriptedActorStateSnapshot;
import cn.jehorstudio.minetale.battle.logic.coordinate.BattleViewMode;
import cn.jehorstudio.minetale.battle.logic.coordinate.BattleCoordinateStateCache;
import cn.jehorstudio.minetale.battle.logic.coordinate.CanonicalTransform;
import cn.jehorstudio.minetale.battle.logic.coordinate.CanonicalVec3;
import cn.jehorstudio.minetale.battle.logic.rules.RuleResolvedView;
import cn.jehorstudio.minetale.battle.presentation.VisualConfig;
import cn.jehorstudio.minetale.lib.ObjLoader;
import cn.jehorstudio.minetale.lib.ObjModels;
import cn.jehorstudio.minetale.lib.Sprites;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class BattleScene {
    private BattleScene() {
    }

    // 该入口仅供确定性静态编译；实时画面应提交已采样的 Snapshot。
    public static Frame compile(BattleLogicStateSnapshot logic, int width, int height) {
        Objects.requireNonNull(logic, "logic");
        Map<ActorRef, CanonicalTransform> transforms = logic.actors().activeRenderableActors().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(ActorSingleSnapshot::ref, ActorSingleSnapshot::transform));
        BattleViewMode viewMode = RuleResolvedView.resolve(logic.rules(), logic.coordinates()).viewMode();
        return compile(new Snapshot(
                logic,
                transforms,
                viewMode,
                logic.coordinates().sceneMode(),
                logic.coordinates().viewScale(),
                logic.coordinates().soulModelRotationBlend()
        ), width, height);
    }

    public static Frame compile(Snapshot snapshot, int width, int height) {
        Objects.requireNonNull(snapshot, "snapshot");
        BattleViewport viewport = new BattleViewport(width, height);
        BattleCamera camera = BattleCamera.from(snapshot.viewMode(), viewport, snapshot.viewScale());
        List<RenderCommand> commands = new ArrayList<>();

        List<ActorSingleSnapshot> actors = snapshot.logic().actors().activeRenderableActors();
        boolean hasPrimaryFrame = actors.stream().anyMatch(BattleScene::isPrimarySpatialFrame);
        for (ActorSingleSnapshot actor : actors) {
            appendActor(commands, snapshot, actor, camera, viewport, hasPrimaryFrame);
        }
        appendSoulGuideGrid(commands, snapshot, actors);
        commands.sort(renderOrder(camera));
        Optional<VolumeRegion> volumeRegion = snapshot.sceneMode() == BattleCoordinateStateCache.SceneMode.THREE_D
                ? primaryVolumeRegion(snapshot, actors)
                : Optional.empty();
        return new Frame(
                viewport,
                camera,
                 commands,
                 localSoulTintLight(snapshot, actors, camera),
                 volumeRegion,
                 freezeShadowCasters(commands),
                 snapshot.sceneMode()
         );
    }

    // 体积区域沿用逻辑层 arena provider 顺序，但只冻结显示变换与逻辑内腔尺寸。
    private static Optional<VolumeRegion> primaryVolumeRegion(Snapshot snapshot, List<ActorSingleSnapshot> actors) {
        ActorSingleSnapshot selected = actors.stream()
                .filter(BattleScene::isPrimarySpatialFrame)
                .min(Comparator.comparingInt(BattleScene::primaryFramePriority)
                        .thenComparingInt(actor -> actor.ref().index()))
                .orElseGet(() -> actors.stream()
                        .filter(BattleScene::hasVolumeFrame)
                        .min(Comparator.comparingInt((ActorSingleSnapshot actor) ->
                                        actor.type() == ActorType.BATTLE_BOX ? 0 : 1)
                                .thenComparingInt(actor -> actor.ref().index()))
                        .orElse(null));
        if (selected == null) return Optional.empty();

        ActorAppearance.FrameContent frame =
                (ActorAppearance.FrameContent) selected.visual().appearance().content();
        CanonicalTransform transform = snapshot.displayTransforms()
                .getOrDefault(selected.ref(), selected.transform());
        CanonicalVec3 scale = transform.scale();
        Vector3f halfSize = new Vector3f(
                (float) (frame.worldSize().x() * Math.abs(scale.x()) * 0.5D),
                (float) (frame.worldSize().y() * Math.abs(scale.y()) * 0.5D),
                (float) (frame.worldSize().z() * Math.abs(scale.z()) * 0.5D)
        );
        if (!(halfSize.x() > 0.0F && halfSize.y() > 0.0F && halfSize.z() > 0.0F)) {
            return Optional.empty();
        }
        return Optional.of(new VolumeRegion(rigidActorModel(transform), halfSize));
    }

    private static boolean hasVolumeFrame(ActorSingleSnapshot actor) {
        ActorAppearance appearance = actor.visual().appearance();
        return appearance != null
                && appearance.content() instanceof ActorAppearance.FrameContent frame
                && frame.worldSize() != null;
    }

    private static int primaryFramePriority(ActorSingleSnapshot actor) {
        ScriptedActorStateSnapshot scripted = (ScriptedActorStateSnapshot) actor.typeSnapshot();
        if (scripted.tags().contains("active_arena")) return 0;
        if (scripted.tags().contains("primary_frame")) return 1;
        return 2;
    }

    private static void appendSoulGuideGrid(
            List<RenderCommand> commands,
            Snapshot snapshot,
            List<ActorSingleSnapshot> actors
    ) {
        if (!VisualConfig.SOUL_GUIDE_GRID_ENABLED()
                || snapshot.sceneMode() != BattleCoordinateStateCache.SceneMode.THREE_D
                || !Float.isFinite(VisualConfig.SOUL_GUIDE_GRID_DENSITY())
                || VisualConfig.SOUL_GUIDE_GRID_DENSITY() <= 0.0F) {
            return;
        }
        ActorSingleSnapshot frameActor = actors.stream()
                .filter(BattleScene::isPrimarySpatialFrame)
                .findFirst()
                .orElseGet(() -> actors.stream().filter(BattleScene::hasSpatialFrame).findFirst().orElse(null));
        ActorSingleSnapshot soulActor = snapshot.logic().localPlayer()
                .flatMap(player -> actors.stream()
                        .filter(actor -> actor.ref().equals(player.soulRef()))
                        .filter(actor -> actor.type() == ActorType.PLAYER_SOUL)
                        .findFirst())
                .orElseGet(() -> actors.stream()
                        .filter(actor -> actor.type() == ActorType.PLAYER_SOUL)
                        .findFirst()
                        .orElse(null));
        if (frameActor == null || soulActor == null) return;

        ActorAppearance.FrameContent frame = (ActorAppearance.FrameContent) frameActor.visual().appearance().content();
        CanonicalTransform frameTransform = snapshot.displayTransforms()
                .getOrDefault(frameActor.ref(), frameActor.transform());
        CanonicalTransform soulTransform = snapshot.displayTransforms()
                .getOrDefault(soulActor.ref(), soulActor.transform());
        CanonicalVec3 scale = frameTransform.scale();
        float sizeX = (float) (frame.worldSize().x() * Math.abs(scale.x()));
        float sizeY = (float) (frame.worldSize().y() * Math.abs(scale.y()));
        float cellSize = 1.0F / VisualConfig.SOUL_GUIDE_GRID_DENSITY();
        int cellsX = (int) Math.floor(sizeX / cellSize + 0.000001F);
        int cellsY = (int) Math.floor(sizeY / cellSize + 0.000001F);
        if (cellsX < 1 || cellsY < 1) return;

        Matrix4f frameModel = rigidActorModel(frameTransform);
        Vector3f localSoul = frameModel.invert(new Matrix4f())
                .transformPosition(new Vector3f(
                        (float) soulTransform.position().x(),
                        (float) soulTransform.position().y(),
                        (float) soulTransform.position().z()));
        float z = localSoul.z + VisualConfig.SOUL_GUIDE_GRID_HEIGHT_OFFSET();
        float halfX = cellsX * cellSize * 0.5F;
        float halfY = cellsY * cellSize * 0.5F;
        long sortKey = worldSort(((long) frameActor.ref().type().ordinal() << 32)
                | Integer.toUnsignedLong(frameActor.ref().index()));
        commands.add(RenderCommand.auxiliaryGrid(
                new Matrix4f(frameModel).translate(0.0F, 0.0F, z)
                        .scale(halfX * 2.0F, halfY * 2.0F, 1.0F),
                VisualConfig.SOUL_GUIDE_GRID_DENSITY(),
                VisualConfig.SOUL_GUIDE_GRID_LINE_WIDTH(),
                guideGridColor(),
                guideFillColor(),
                sortKey
        ));
    }

    private static boolean hasSpatialFrame(ActorSingleSnapshot actor) {
        ActorAppearance appearance = actor.visual().appearance();
        return appearance != null
                && appearance.visible()
                && appearance.content() instanceof ActorAppearance.FrameContent frame
                && frame.worldSize() != null;
    }

    private static int guideGridColor() {
        int alpha = Math.round(Math.clamp(VisualConfig.SOUL_GUIDE_GRID_ALPHA(), 0.0F, 1.0F) * 255.0F);
        return (alpha << 24) | (VisualConfig.SOUL_GUIDE_GRID_COLOR() & 0x00FFFFFF);
    }

    private static int guideFillColor() {
        int alpha = Math.round(Math.clamp(VisualConfig.SOUL_GUIDE_FILL_ALPHA(), 0.0F, 1.0F) * 255.0F);
        return (alpha << 24) | (VisualConfig.SOUL_GUIDE_FILL_COLOR() & 0x00FFFFFF);
    }

    private static Optional<SoulTintLight> localSoulTintLight(
            Snapshot snapshot,
            List<ActorSingleSnapshot> activeRenderableActors,
            BattleCamera camera
    ) {
        return snapshot.logic().localPlayer()
                .flatMap(player -> activeRenderableActors.stream()
                        .filter(actor -> actor.ref().equals(player.soulRef()))
                        .filter(actor -> actor.type() == ActorType.PLAYER_SOUL)
                        .findFirst())
                .map(actor -> {
                    CanonicalTransform transform = snapshot.displayTransforms()
                            .getOrDefault(actor.ref(), actor.transform());
                    ObjLoader.LocalBounds bounds = ObjLoader.getOrLoad(ObjModels.SOUL).getLocalBounds();
                    Vector3f worldCenter = playerSoulModel(
                            transform,
                            camera,
                            snapshot.soulModelRotationBlend()
                    ).transformPosition(new Vector3f(
                            bounds.centerX(),
                            bounds.centerY(),
                            bounds.centerZ()
                    ));
                    return new SoulTintLight(
                            transform.position(),
                            new CanonicalVec3(worldCenter.x(), worldCenter.y(), worldCenter.z()),
                            soulColor(actor.typeSnapshot()),
                            actor.ref());
                });
    }

    private static void appendActor(
            List<RenderCommand> commands,
            Snapshot snapshot,
            ActorSingleSnapshot actor,
            BattleCamera camera,
            BattleViewport viewport,
            boolean hasPrimaryFrame
    ) {
        VisualRef visual = actor.visual();
        CanonicalTransform transform = snapshot.displayTransforms().getOrDefault(actor.ref(), actor.transform());
        long actorKey = ((long) actor.ref().type().ordinal() << 32) | Integer.toUnsignedLong(actor.ref().index());
        int firstCommand = commands.size();
        switch (visual.type()) {
            case SPRITE -> appendSprite(commands, visual.sprite(), transform, camera, actorKey);
            case OBJ_MODEL -> commands.add(RenderCommand.model(actorModel(transform), visual.objModel(), 0xFFFFFFFF, worldSort(actorKey)));
            case APPEARANCE -> appendAppearance(
                    commands, actor, visual.appearance(), transform, camera, viewport,
                    actorKey, hasPrimaryFrame, snapshot.sceneMode());
            case SPECIAL -> {
                if ("player_soul".equals(visual.specialKey())) {
                    int soulColor = soulColor(actor.typeSnapshot());
                    commands.add(RenderCommand.model(
                            playerSoulModel(transform, camera, snapshot.soulModelRotationBlend()),
                            ObjModels.SOUL,
                            soulColor,
                            worldSort(actorKey)
                    ));
                }
            }
            case GECKO_MODEL, VOID -> {
                // TODO: 尚未实现
            }
        }
        for (int commandIndex = firstCommand; commandIndex < commands.size(); commandIndex++) {
            commands.set(commandIndex, commands.get(commandIndex).withSourceActor(actor.ref()));
        }
    }

    private static void appendAppearance(
            List<RenderCommand> commands,
            ActorSingleSnapshot actor,
            ActorAppearance appearance,
            CanonicalTransform transform,
            BattleCamera camera,
            BattleViewport viewport,
            long actorKey,
            boolean hasPrimaryFrame,
            BattleCoordinateStateCache.SceneMode sceneMode
    ) {
        if (!appearance.visible()) {
            return;
        }
        if (appearance.mode() == ActorAppearance.Mode.TWO_D) {
            appendScreenAppearance(commands, appearance, viewport, actorKey);
            return;
        }
        switch (appearance.content()) {
            case ActorAppearance.ImageContent image -> appendExtrudedImage(
                    commands, image, appearance.rendered(), transform, actorKey);
            case ActorAppearance.ModelContent model -> commands.add(RenderCommand.model(
                    modelTransform(transform, model), model.model(), model.tint(),
                    appearance.rendered(), worldSort(actorKey)));
            case ActorAppearance.ImageModelContent hybrid -> {
                if (sceneMode == BattleCoordinateStateCache.SceneMode.TWO_D) {
                    appendImageBillboard(commands, hybrid.image(), transform, camera, actorKey);
                } else {
                    commands.add(RenderCommand.model(
                            modelTransform(transform, hybrid.model()), hybrid.model().model(), hybrid.model().tint(),
                            appearance.rendered(), worldSort(actorKey)));
                }
            }
            case ActorAppearance.TextContent text -> appendWorldText(commands, text, transform, actorKey);
            case ActorAppearance.ProgressContent progress -> appendWorldProgress(commands, progress, transform, actorKey);
            case ActorAppearance.FrameContent frame when frame.worldSize() != null -> {
                if (!hasPrimaryFrame || isPrimarySpatialFrame(actor)) {
                    appendSpatialFrame(commands, frame, appearance.rendered(), transform, actorKey);
                }
            }
            default -> {
                // ActorAppearance 构造器已拒绝其他 3D 内容组合。
            }
        }
    }

    private static void appendScreenAppearance(
            List<RenderCommand> commands,
            ActorAppearance appearance,
            BattleViewport viewport,
            long actorKey
    ) {
        ScreenRect rect = ScreenRect.from(appearance.layout(), viewport);
        long base = screenSort(appearance.layout().zIndex(), actorKey);
        switch (appearance.content()) {
            case ActorAppearance.ImageContent image -> {
                ActorAppearance.ImagePlacement placement = image.resolvePlacement(rect.width(), rect.height());
                commands.add(RenderCommand.texturedQuad(
                        rect.regionModel(
                                (float) placement.x(),
                                (float) placement.y(),
                                (float) placement.width(),
                                (float) placement.height()),
                        image.texture(),
                        image.tint(),
                        new Vector4f((float) placement.u0(), (float) placement.v0(), (float) placement.u1(), (float) placement.v1()),
                        ActorSpaceBinding.SCREEN_PLANE,
                        AlphaMode.TRANSLUCENT,
                        base
                ));
            }
            case ActorAppearance.FrameContent frame -> appendScreenFrame(commands, rect, frame, base);
            case ActorAppearance.ProgressContent progress -> appendProgress(commands, rect, progress, base);
            case ActorAppearance.TextContent text -> commands.add(RenderCommand.text(rect, text, base));
            case ActorAppearance.ModelContent ignored -> {
                // ActorAppearance 构造器已禁止 2D 模型。
            }
            case ActorAppearance.ImageModelContent ignored -> {
                // ActorAppearance 构造器已禁止 2D image_model。
            }
        }
    }

    private static void appendSprite(
            List<RenderCommand> commands,
            Sprites sprite,
            CanonicalTransform transform,
            BattleCamera camera,
            long actorKey
    ) {
        appendSprite(commands, sprite, transform, camera, 0xFFFFFFFF, actorKey);
    }

    private static int soulColor(ActorTypeSnapshot snapshot) {
        if (snapshot instanceof PlayerSoulStateSnapshot soul) {
            return soulColor(soul.mode());
        }
        if (snapshot instanceof NetworkProxyStateSnapshot proxy
                && proxy.proxySnapshot() instanceof PlayerSoulStateSnapshot soul) {
            return soulColor(soul.mode());
        }
        return soulColor(PlayerSoulMode.NORMAL);
    }

    private static int soulColor(PlayerSoulMode mode) {
        return switch (mode) {
            case NORMAL -> 0xFFFF0000;
        };
    }

    private static void appendSprite(
            List<RenderCommand> commands,
            Sprites sprite,
            CanonicalTransform transform,
            BattleCamera camera,
            int color,
            long actorKey
    ) {
        float width = sprite.getWidth() / VisualConfig.SPRITE_PIXELS_PER_CANONICAL_UNIT();
        float height = sprite.getHeight() / VisualConfig.SPRITE_PIXELS_PER_CANONICAL_UNIT();
        commands.add(RenderCommand.texturedQuad(
                billboardModel(transform, camera, width, height),
                sprite.getPath(),
                color,
                new Vector4f(0.0F, 0.0F, 1.0F, 1.0F),
                ActorSpaceBinding.WORLD_BILLBOARD,
                AlphaMode.CUTOUT,
                worldSort(actorKey)
        ));
    }

    private static void appendImageBillboard(
            List<RenderCommand> commands,
            ActorAppearance.ImageContent image,
            CanonicalTransform transform,
            BattleCamera camera,
            long actorKey
    ) {
        float width = image.source().width() / VisualConfig.SPRITE_PIXELS_PER_CANONICAL_UNIT();
        float height = image.source().height() / VisualConfig.SPRITE_PIXELS_PER_CANONICAL_UNIT();
        commands.add(RenderCommand.texturedQuad(
                billboardModel(transform, camera, width, height),
                image.texture(),
                image.tint(),
                new Vector4f(
                        (float) image.source().x() / image.textureSize().width(),
                        (float) image.source().y() / image.textureSize().height(),
                        (float) (image.source().x() + image.source().width()) / image.textureSize().width(),
                        (float) (image.source().y() + image.source().height()) / image.textureSize().height()
                ),
                ActorSpaceBinding.WORLD_BILLBOARD,
                AlphaMode.CUTOUT,
                worldSort(actorKey)
        ));
    }

    private static void appendExtrudedImage(
            List<RenderCommand> commands,
            ActorAppearance.ImageContent image,
            ActorAppearance.RenderedBehavior rendered,
            CanonicalTransform transform,
            long actorKey
    ) {
        commands.add(RenderCommand.extrudedImage(actorModel(transform), image, rendered, worldSort(actorKey)));
    }

    private static void appendWorldText(List<RenderCommand> commands, ActorAppearance.TextContent text,
                                        CanonicalTransform transform, long actorKey) {
        ActorAppearance.PlaneSize size = Objects.requireNonNull(text.worldSize(), "world text size");
        float pixelsPerL = VisualConfig.SPRITE_PIXELS_PER_CANONICAL_UNIT();
        ScreenRect localBounds = new ScreenRect(0.0F, 0.0F,
                (float) size.width() * pixelsPerL, (float) size.height() * pixelsPerL, 0.0F);
        Matrix4f model = actorModel(transform)
                .rotateX((float) (Math.PI * 0.5D))
                .translate((float) -size.width() * 0.5F, (float) -size.height() * 0.5F, 0.0F)
                .scale(1.0F / pixelsPerL);
        commands.add(RenderCommand.text(model, localBounds, text, ActorSpaceBinding.WORLD, worldSort(actorKey)));
    }

    private static void appendWorldProgress(List<RenderCommand> commands, ActorAppearance.ProgressContent progress,
                                            CanonicalTransform transform, long actorKey) {
        ActorAppearance.PlaneSize size = Objects.requireNonNull(progress.worldSize(), "world progress size");
        Matrix4f base = actorModel(transform);
        float width = (float) size.width();
        float height = (float) size.height();
        float depth = (float) progress.thickness();
        float border = Math.min(
                progress.borderWidth() / VisualConfig.SPRITE_PIXELS_PER_CANONICAL_UNIT(),
                Math.min(width, height) * 0.5F
        );
        float innerWidth = Math.max(0.0F, width - border * 2.0F);
        float innerHeight = Math.max(0.0F, height - border * 2.0F);
        float fillWidth = innerWidth * (float) progress.fraction();
        long sort = worldSort(actorKey);
        if (fillWidth > 0.0F && innerHeight > 0.0F) {
            appendSolidBox(commands, base, -width * 0.5F + border + fillWidth * 0.5F, 0.0F,
                    fillWidth, depth, innerHeight, progress.fillColor(), sort);
            sort += 6L;
        }
        float remaining = innerWidth - fillWidth;
        if (remaining > 0.0F && innerHeight > 0.0F) {
            appendSolidBox(commands, base, width * 0.5F - border - remaining * 0.5F, 0.0F,
                    remaining, depth, innerHeight, progress.backgroundColor(), sort);
            sort += 6L;
        }
        if (border > 0.0F) {
            appendSolidBox(commands, base, 0.0F, -height * 0.5F + border * 0.5F,
                    width, depth, border, progress.borderColor(), sort);
            appendSolidBox(commands, base, 0.0F, height * 0.5F - border * 0.5F,
                    width, depth, border, progress.borderColor(), sort + 6L);
            if (innerHeight > 0.0F) {
                appendSolidBox(commands, base, -width * 0.5F + border * 0.5F, 0.0F,
                        border, depth, innerHeight, progress.borderColor(), sort + 12L);
                appendSolidBox(commands, base, width * 0.5F - border * 0.5F, 0.0F,
                        border, depth, innerHeight, progress.borderColor(), sort + 18L);
            }
        }
    }

    // 六个局部 quad 共用同一 base，Actor transform 只应用一次。
    private static void appendSolidBox(List<RenderCommand> commands, Matrix4f base, float x, float z,
                                       float width, float depth, float height, int color, long sort) {
        if (width <= 0.0F || depth <= 0.0F || height <= 0.0F) return;
        commands.add(RenderCommand.solidQuad(new Matrix4f(base).translate(x, -depth * 0.5F, z)
                .rotateX((float) (Math.PI * 0.5D)).scale(width, height, 1.0F), color, ActorSpaceBinding.WORLD, sort));
        commands.add(RenderCommand.solidQuad(new Matrix4f(base).translate(x, depth * 0.5F, z)
                .rotateX((float) (Math.PI * 0.5D)).scale(width, height, 1.0F), color, ActorSpaceBinding.WORLD, sort + 1L));
        commands.add(RenderCommand.solidQuad(new Matrix4f(base).translate(x, 0.0F, z - height * 0.5F)
                .scale(width, depth, 1.0F), color, ActorSpaceBinding.WORLD, sort + 2L));
        commands.add(RenderCommand.solidQuad(new Matrix4f(base).translate(x, 0.0F, z + height * 0.5F)
                .scale(width, depth, 1.0F), color, ActorSpaceBinding.WORLD, sort + 3L));
        commands.add(RenderCommand.solidQuad(new Matrix4f(base).translate(x - width * 0.5F, 0.0F, z)
                .rotateY((float) (Math.PI * 0.5D)).scale(height, depth, 1.0F), color, ActorSpaceBinding.WORLD, sort + 4L));
        commands.add(RenderCommand.solidQuad(new Matrix4f(base).translate(x + width * 0.5F, 0.0F, z)
                .rotateY((float) (Math.PI * 0.5D)).scale(height, depth, 1.0F), color, ActorSpaceBinding.WORLD, sort + 5L));
    }

    private static void appendScreenFrame(List<RenderCommand> commands, ScreenRect rect, ActorAppearance.FrameContent frame, long base) {
        commands.add(RenderCommand.solidQuad(rect.model(), frame.backgroundColor(), ActorSpaceBinding.SCREEN_PLANE, base));
        appendBorder(commands, rect, frame.borderWidth(), frame.borderColor(), base + 1L);
    }

    private static void appendProgress(List<RenderCommand> commands, ScreenRect rect, ActorAppearance.ProgressContent progress, long base) {
        commands.add(RenderCommand.solidQuad(rect.model(), progress.backgroundColor(), ActorSpaceBinding.SCREEN_PLANE, base));
        float fillWidth = rect.width() * (float) progress.fraction();
        if (fillWidth > 0.0F) {
            commands.add(RenderCommand.solidQuad(
                    rect.regionModel(0.0F, 0.0F, fillWidth, rect.height()),
                    progress.fillColor(), ActorSpaceBinding.SCREEN_PLANE, base + 1L));
        }
        appendBorder(commands, rect, progress.borderWidth(), progress.borderColor(), base + 2L);
    }

    private static void appendBorder(List<RenderCommand> commands, ScreenRect rect, int width, int color, long base) {
        if (width <= 0 || (color >>> 24) == 0) {
            return;
        }
        float border = Math.min(width, Math.min(rect.width(), rect.height()) * 0.5F);
        commands.add(RenderCommand.solidQuad(rect.regionModel(0.0F, 0.0F, rect.width(), border), color, ActorSpaceBinding.SCREEN_PLANE, base));
        commands.add(RenderCommand.solidQuad(rect.regionModel(0.0F, rect.height() - border, rect.width(), border), color, ActorSpaceBinding.SCREEN_PLANE, base + 1L));
        commands.add(RenderCommand.solidQuad(rect.regionModel(0.0F, border, border, rect.height() - border * 2.0F), color, ActorSpaceBinding.SCREEN_PLANE, base + 2L));
        commands.add(RenderCommand.solidQuad(rect.regionModel(rect.width() - border, border, border, rect.height() - border * 2.0F), color, ActorSpaceBinding.SCREEN_PLANE, base + 3L));
    }

    private static void appendSpatialFrame(
            List<RenderCommand> commands,
            ActorAppearance.FrameContent frame,
            ActorAppearance.RenderedBehavior rendered,
            CanonicalTransform transform,
            long actorKey
    ) {
        ActorAppearance.WorldSize size = frame.worldSize();
        if (frame.thickness() <= 0.0D || frame.borderColor() >>> 24 == 0) return;
        CanonicalVec3 scale = transform.scale();
        float sizeX = (float) (size.x() * Math.abs(scale.x()));
        float sizeY = (float) (size.y() * Math.abs(scale.y()));
        float sizeZ = (float) (size.z() * Math.abs(scale.z()));
        if (sizeX <= 0.0F || sizeY <= 0.0F || sizeZ <= 0.0F) return;
        commands.add(RenderCommand.battleFrame(
                rigidActorModel(transform),
                new FrameMesh(sizeX, sizeY, sizeZ, (float) frame.thickness()),
                frame.borderColor(),
                rendered,
                worldSort(actorKey)
        ));
    }

    private static void appendLine(
            List<RenderCommand> commands,
            Matrix4f actorModel,
            float x0, float y0, float z0,
            float x1, float y1, float z1,
            float lineWidth,
            int color,
            long sortKey
    ) {
        Vector3f start = actorModel.transformPosition(new Vector3f(x0, y0, z0));
        Vector3f end = actorModel.transformPosition(new Vector3f(x1, y1, z1));
        commands.add(RenderCommand.line(start, end, lineWidth, color, sortKey));
    }

    private static boolean isPrimarySpatialFrame(ActorSingleSnapshot actor) {
        ActorAppearance appearance = actor.visual().appearance();
        if (appearance == null || !(appearance.content() instanceof ActorAppearance.FrameContent frame) || frame.worldSize() == null) {
            return false;
        }
        return actor.typeSnapshot() instanceof ScriptedActorStateSnapshot scripted
                && scripted.tags().stream().anyMatch(tag -> "active_arena".equals(tag)
                || "primary_frame".equals(tag)
                || "battle_frame".equals(tag));
    }

    private static Matrix4f actorModel(CanonicalTransform transform) {
        CanonicalVec3 scale = transform.scale();
        return rigidActorModel(transform)
                .scale((float) scale.x(), (float) scale.y(), (float) scale.z());
    }

    // FrameMesh 已烘焙缩放；此处只保留刚体变换。
    private static Matrix4f rigidActorModel(CanonicalTransform transform) {
        CanonicalVec3 position = transform.position();
        CanonicalVec3 rotation = transform.rotationDeg();
        return new Matrix4f()
                .translate((float) position.x(), (float) position.y(), (float) position.z())
                .rotateXYZ((float) Math.toRadians(rotation.x()), (float) Math.toRadians(rotation.y()), (float) Math.toRadians(rotation.z()));
    }

    private static Matrix4f modelTransform(CanonicalTransform transform, ActorAppearance.ModelContent model) {
        ActorAppearance.ModelScale scale = model.scale();
        return actorModel(transform).scale((float) scale.x(), (float) scale.y(), (float) scale.z());
    }

    private static Matrix4f billboardModel(CanonicalTransform transform, BattleCamera camera, float width, float height) {
        CanonicalVec3 position = transform.position();
        CanonicalVec3 scale = transform.scale();
        Vector3f right = camera.right();
        // unit quad 的 V=0 位于局部 -Y，因此须取 -cameraUp 保持纹理顶部朝上。
        Vector3f up = camera.up().negate(new Vector3f());
        Vector3f normal = camera.viewDirection().negate(new Vector3f());
        return new Matrix4f(
                right.x(), right.y(), right.z(), 0.0F,
                up.x(), up.y(), up.z(), 0.0F,
                normal.x(), normal.y(), normal.z(), 0.0F,
                (float) position.x(), (float) position.y(), (float) position.z(), 1.0F
        ).rotateZ((float) Math.toRadians(transform.rollDeg()))
                .scale(width * (float) scale.x(), height * (float) scale.z(), 1.0F);
    }

    private static Matrix4f playerSoulModel(
            CanonicalTransform transform,
            BattleCamera camera,
            double rotationBlend
    ) {
        CanonicalVec3 position = transform.position();
        CanonicalVec3 scale = transform.scale();
        float dx = camera.position().x() - (float) position.x();
        float dy = camera.position().y() - (float) position.y();
        float cameraYaw = (float) Math.atan2(dx, dy);
        float frontYaw = (float) Math.toRadians(VisualConfig.SOUL_MODEL_FRONT_YAW_DEGREES());
        float threeDimensionalYaw = cameraYaw + (float) Math.toRadians(
                VisualConfig.SOUL_MODEL_CAMERA_YAW_OFFSET_DEGREES());
        return new Matrix4f()
                .translate((float) position.x(), (float) position.y(), (float) position.z())
                // OBJ 为 Y-up，canonical 全局上轴为 -Z。
                .rotateX((float) (-Math.PI * 0.5D))
                .rotateY(lerpAngle(frontYaw, threeDimensionalYaw, (float) rotationBlend))
                .scale(
                        VisualConfig.SOUL_MODEL_SCALE() * (float) scale.x(),
                        VisualConfig.SOUL_MODEL_SCALE() * (float) scale.z(),
                        VisualConfig.SOUL_MODEL_SCALE() * (float) scale.y()
                );
    }

    private static float lerpAngle(float start, float end, float progress) {
        float fullTurn = (float) (Math.PI * 2.0D);
        float delta = (end - start + (float) (Math.PI * 3.0D)) % fullTurn - (float) Math.PI;
        return start + delta * progress;
    }

    private static long worldSort(long actorKey) {
        return actorKey << 4;
    }

    private static long screenSort(int zIndex, long actorKey) {
        return (1L << 62) | (((long) zIndex - Integer.MIN_VALUE) << 30) | (actorKey & 0x3FFFFFFFL);
    }

    // 排序契约：不透明/裁剪写深度，世界半透明由远到近，屏幕层最后按 zIndex。
    private static Comparator<RenderCommand> renderOrder(BattleCamera camera) {
        return (left, right) -> {
            int layerComparison = Integer.compare(renderLayer(left), renderLayer(right));
            if (layerComparison != 0) {
                return layerComparison;
            }
            if (renderLayer(left) == 1) {
                int depthComparison = Double.compare(cameraDepth(right, camera), cameraDepth(left, camera));
                if (depthComparison != 0) {
                    return depthComparison;
                }
            }
            return Long.compare(left.sortKey(), right.sortKey());
        };
    }

    private static int renderLayer(RenderCommand command) {
        if (command.binding() == ActorSpaceBinding.SCREEN_PLANE) {
            return 2;
        }
        return command.alphaMode() == AlphaMode.TRANSLUCENT ? 1 : 0;
    }

    private static double cameraDepth(RenderCommand command, BattleCamera camera) {
        Vector3f center;
        if (command.geometry() == Geometry.LINE_SEGMENT) {
            center = new Vector3f(command.lineStart()).add(command.lineEnd()).mul(0.5F);
        } else {
            center = command.model().transformPosition(new Vector3f());
        }
        return center.sub(camera.position()).dot(camera.viewDirection());
    }

    // 为 Soul 点阴影 CPU 剔除冻结保守世界 AABB。
    private static List<ShadowCaster> freezeShadowCasters(List<RenderCommand> commands) {
        List<ShadowCaster> result = new ArrayList<>();
        for (int commandIndex = 0; commandIndex < commands.size(); commandIndex++) {
            RenderCommand command = commands.get(commandIndex);
            if (!command.rendered().castsShadow()) continue;
            WorldBounds bounds = switch (command.geometry()) {
                case OBJ_MODEL -> {
                    ObjLoader.LocalBounds local = ObjLoader.getOrLoad(command.objModel()).getLocalBounds();
                    yield WorldBounds.transformedBox(
                            command.model(),
                            local.minimumX(), local.minimumY(), local.minimumZ(),
                            local.maximumX(), local.maximumY(), local.maximumZ()
                    );
                }
                case EXTRUDED_IMAGE -> {
                    ActorAppearance.ImageContent image = command.extrudedImage();
                    yield WorldBounds.transformedBox(
                            command.model(),
                            image.source().width() / VisualConfig.SPRITE_PIXELS_PER_CANONICAL_UNIT() * 0.5F,
                            0.5F,
                            image.source().height() / VisualConfig.SPRITE_PIXELS_PER_CANONICAL_UNIT() * 0.5F
                    );
                }
                case BATTLE_FRAME -> {
                    FrameMesh mesh = command.frameMesh();
                    yield WorldBounds.transformedBox(
                            command.model(),
                            mesh.sizeX() * 0.5F,
                            mesh.sizeY() * 0.5F,
                            mesh.sizeZ() * 0.5F
                    );
                }
                default -> null;
            };
            if (bounds != null) {
                result.add(new ShadowCaster(commandIndex, bounds, command.sourceActor()));
            }
        }
        return List.copyOf(result);
    }

    public record Snapshot(
            BattleLogicStateSnapshot logic,
            Map<ActorRef, CanonicalTransform> displayTransforms,
            BattleViewMode viewMode,
            BattleCoordinateStateCache.SceneMode sceneMode,
            double viewScale,
            double soulModelRotationBlend,
            double renderedBlend
    ) {
        public Snapshot(
                BattleLogicStateSnapshot logic,
                Map<ActorRef, CanonicalTransform> displayTransforms,
                BattleViewMode viewMode,
                BattleCoordinateStateCache.SceneMode sceneMode,
                double viewScale
        ) {
            this(logic, displayTransforms, viewMode, sceneMode, viewScale, 0.0D, 0.0D);
        }

        public Snapshot(
                BattleLogicStateSnapshot logic,
                Map<ActorRef, CanonicalTransform> displayTransforms,
                BattleViewMode viewMode,
                BattleCoordinateStateCache.SceneMode sceneMode,
                double viewScale,
                double soulModelRotationBlend
        ) {
            this(logic, displayTransforms, viewMode, sceneMode, viewScale, soulModelRotationBlend, 0.0D);
        }

        public Snapshot {
            Objects.requireNonNull(logic, "logic");
            displayTransforms = Map.copyOf(Objects.requireNonNull(displayTransforms, "displayTransforms"));
            Objects.requireNonNull(viewMode, "viewMode");
            Objects.requireNonNull(sceneMode, "sceneMode");
            if (!Double.isFinite(viewScale) || viewScale <= 0.0D) {
                throw new IllegalArgumentException("viewScale must be finite and > 0.");
            }
            if (!Double.isFinite(soulModelRotationBlend)
                    || soulModelRotationBlend < 0.0D
                    || soulModelRotationBlend > 1.0D) {
                throw new IllegalArgumentException("soulModelRotationBlend must be finite and within [0, 1].");
            }
            if (!Double.isFinite(renderedBlend) || renderedBlend < 0.0D || renderedBlend > 1.0D) {
                throw new IllegalArgumentException("renderedBlend must be finite and within [0, 1].");
            }
            if (sceneMode == BattleCoordinateStateCache.SceneMode.TWO_D && renderedBlend != 0.0D) {
                throw new IllegalArgumentException("TWO_D scene requires renderedBlend=0.");
            }
        }
    }

    public record Frame(
            BattleViewport viewport,
            BattleCamera camera,
             List<RenderCommand> commands,
             Optional<SoulTintLight> soulTintLight,
             Optional<VolumeRegion> volumeRegion,
             List<ShadowCaster> shadowCasters,
             BattleCoordinateStateCache.SceneMode sceneMode
     ) {
         public Frame(BattleViewport viewport, BattleCamera camera, List<RenderCommand> commands) {
             this(
                     viewport, camera, commands, Optional.empty(), Optional.empty(),
                     freezeShadowCasters(commands), BattleCoordinateStateCache.SceneMode.TWO_D);
         }

        public Frame(BattleViewport viewport, BattleCamera camera, List<RenderCommand> commands,
                     Optional<SoulTintLight> soulTintLight) {
             this(
                     viewport, camera, commands, soulTintLight, Optional.empty(),
                     freezeShadowCasters(commands), BattleCoordinateStateCache.SceneMode.TWO_D);
         }

        public Frame(BattleViewport viewport, BattleCamera camera, List<RenderCommand> commands,
                     Optional<SoulTintLight> soulTintLight, Optional<VolumeRegion> volumeRegion) {
             this(
                     viewport, camera, commands, soulTintLight, volumeRegion,
                     freezeShadowCasters(commands), BattleCoordinateStateCache.SceneMode.TWO_D);
         }

        public Frame {
            Objects.requireNonNull(viewport, "viewport");
            Objects.requireNonNull(camera, "camera");
            commands = List.copyOf(Objects.requireNonNull(commands, "commands"));
            soulTintLight = Objects.requireNonNull(soulTintLight, "soulTintLight");
             volumeRegion = Objects.requireNonNull(volumeRegion, "volumeRegion");
             shadowCasters = List.copyOf(Objects.requireNonNull(shadowCasters, "shadowCasters"));
             Objects.requireNonNull(sceneMode, "sceneMode");
         }

        public Matrix4f screenProjection() {
            return new Matrix4f().ortho(0.0F, this.viewport.width(), this.viewport.height(), 0.0F, -1.0F, 1.0F);
        }
    }

    public record BattleViewport(int width, int height) {
        public BattleViewport {
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("Battle viewport must be positive, got " + width + "x" + height);
            }
        }

        public float aspectRatio() {
            return this.width / (float) this.height;
        }

        // fontScale 以 1080p 为基准，运行时按目标高度等比缩放。
        public float relativeUiScale() {
            return this.height / 1080.0F;
        }
    }

    public record BattleCamera(
            Matrix4f view,
            Matrix4f projection,
            Vector3f position,
            Vector3f viewDirection,
            Vector3f right,
            Vector3f up
    ) {
        public BattleCamera {
            view = new Matrix4f(Objects.requireNonNull(view, "view"));
            projection = new Matrix4f(Objects.requireNonNull(projection, "projection"));
            position = new Vector3f(Objects.requireNonNull(position, "position"));
            viewDirection = new Vector3f(Objects.requireNonNull(viewDirection, "viewDirection"));
            right = new Vector3f(Objects.requireNonNull(right, "right"));
            up = new Vector3f(Objects.requireNonNull(up, "up"));
        }

        public static BattleCamera from(BattleViewMode mode, BattleViewport viewport, double viewScale) {
            if (!Double.isFinite(viewScale) || viewScale <= 0.0D) {
                throw new IllegalArgumentException("viewScale must be finite and > 0.");
            }
            CanonicalVec3 direction = mode.viewDirection();
            CanonicalVec3 cameraUp = mode.cameraUp();
            CanonicalVec3 target = mode.orthographic() ? CanonicalVec3.ZERO : mode.camera().target();
            CanonicalVec3 baseEye = mode.orthographic()
                    ? target.subtract(direction.scale(8.0D))
                    : mode.camera().position();
            CanonicalVec3 eye = mode.orthographic()
                    ? baseEye
                    : target.add(baseEye.subtract(target).scale(1.0D / viewScale));
            Vector3f viewDirection = vector(target.subtract(eye).normalize());
            Vector3f up = vector(cameraUp).normalize();
            Vector3f right = viewDirection.cross(up, new Vector3f()).normalize();
            up = right.cross(viewDirection, new Vector3f()).normalize();
            Matrix4f view = new Matrix4f().lookAt(vector(eye), vector(target), up);
            Matrix4f projection;
            if (mode.orthographic()) {
                float orthoHeight = (float) (mode.effectiveOrthoHeight(viewport.width(), viewport.height()) / viewScale);
                float orthoWidth = orthoHeight * viewport.aspectRatio();
                float centerY = (float) mode.verticalCenterOffset();
                projection = new Matrix4f().ortho(
                        -orthoWidth * 0.5F, orthoWidth * 0.5F,
                        centerY - orthoHeight * 0.5F,
                        centerY + orthoHeight * 0.5F,
                        VisualConfig.SCENE_NEAR_PLANE(), VisualConfig.SCENE_FAR_PLANE());
            } else {
                float distance = (float) eye.subtract(target).length();
                float near = Math.max(VisualConfig.SCENE_NEAR_PLANE(),
                        distance - VisualConfig.SCENE_CLIP_DISTANCE_PADDING());
                float far = Math.max(VisualConfig.SCENE_FAR_PLANE(),
                        distance + VisualConfig.SCENE_CLIP_DISTANCE_PADDING());
                projection = new Matrix4f().perspective(
                        (float) Math.toRadians(mode.camera().fovDegrees()),
                        viewport.aspectRatio(), near, far);
                double visibleHeight = 2.0D * distance
                        * Math.tan(Math.toRadians(mode.camera().fovDegrees() * 0.5D));
                projection.m21((float) (2.0D * mode.verticalCenterOffset() / visibleHeight));
            }
            return new BattleCamera(view, projection, vector(eye), viewDirection, right, up);
        }

        private static Vector3f vector(CanonicalVec3 value) {
            return new Vector3f((float) value.x(), (float) value.y(), (float) value.z());
        }
    }

    public record ScreenRect(float x, float y, float width, float height, float rotationDeg) {
        public ScreenRect {
            if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(width) || !Float.isFinite(height)
                    || !Float.isFinite(rotationDeg) || width < 0.0F || height < 0.0F) {
                throw new IllegalArgumentException("ScreenRect values must be finite and size non-negative.");
            }
        }

        public static ScreenRect from(ActorAppearance.Layout2d layout, BattleViewport viewport) {
            return new ScreenRect(
                    (float) (layout.xPercent() * viewport.width() / 100.0D),
                    (float) (layout.yPercent() * viewport.height() / 100.0D),
                    (float) (layout.widthPercent() * viewport.width() / 100.0D),
                    (float) (layout.heightPercent() * viewport.height() / 100.0D),
                    (float) layout.rotationDeg()
            );
        }

        public Matrix4f model() {
            return regionModel(0.0F, 0.0F, this.width, this.height);
        }

        // 子区域共享父布局旋转中心。
        public Matrix4f regionModel(float offsetX, float offsetY, float regionWidth, float regionHeight) {
            return new Matrix4f()
                    .translate(this.x + this.width * 0.5F, this.y + this.height * 0.5F, 0.0F)
                    .rotateZ((float) Math.toRadians(this.rotationDeg))
                    .translate(
                            offsetX + regionWidth * 0.5F - this.width * 0.5F,
                            offsetY + regionHeight * 0.5F - this.height * 0.5F,
                            0.0F)
                    .scale(regionWidth, regionHeight, 1.0F);
        }
    }

    public record RenderCommand(
            Geometry geometry,
            Material material,
            AlphaMode alphaMode,
            ActorSpaceBinding binding,
            Matrix4f model,
            ResourceLocation texture,
            ObjModels objModel,
            int color,
            Vector4f uvRect,
            Vector3f lineStart,
            Vector3f lineEnd,
            float lineWidth,
            ScreenRect textBounds,
            ActorAppearance.TextContent text,
            ActorAppearance.ImageContent extrudedImage,
            FrameMesh frameMesh,
            AuxiliaryGrid auxiliaryGrid,
            ActorAppearance.RenderedBehavior rendered,
            long sortKey,
            ActorRef sourceActor
    ) {
        public RenderCommand {
            Objects.requireNonNull(geometry, "geometry");
            Objects.requireNonNull(material, "material");
            Objects.requireNonNull(alphaMode, "alphaMode");
            Objects.requireNonNull(binding, "binding");
            Objects.requireNonNull(rendered, "rendered");
            model = model == null ? new Matrix4f() : new Matrix4f(model);
            uvRect = uvRect == null ? new Vector4f(0.0F, 0.0F, 1.0F, 1.0F) : new Vector4f(uvRect);
        }

        public static RenderCommand solidQuad(Matrix4f model, int color, ActorSpaceBinding binding, long sortKey) {
            AlphaMode alphaMode = binding == ActorSpaceBinding.SCREEN_PLANE || alpha(color) < 255
                    ? AlphaMode.TRANSLUCENT
                    : AlphaMode.OPAQUE;
            return new RenderCommand(Geometry.UNIT_QUAD, Material.SOLID, alphaMode, binding, model, null, null, color, null, null, null, 0.0F, null, null, null, null, null, ActorAppearance.RenderedBehavior.DISABLED, sortKey, null);
        }

        public static RenderCommand texturedQuad(
                Matrix4f model,
                ResourceLocation texture,
                int color,
                Vector4f uv,
                ActorSpaceBinding binding,
                AlphaMode alphaMode,
                long sortKey
        ) {
            return new RenderCommand(Geometry.UNIT_QUAD, Material.TEXTURE, alphaMode, binding, model, Objects.requireNonNull(texture, "texture"), null, color, uv, null, null, 0.0F, null, null, null, null, null, ActorAppearance.RenderedBehavior.DISABLED, sortKey, null);
        }

        public static RenderCommand model(Matrix4f model, ObjModels objModel, int color, long sortKey) {
            return model(model, objModel, color, ActorAppearance.RenderedBehavior.FULL, sortKey);
        }

        public static RenderCommand model(
                Matrix4f model,
                ObjModels objModel,
                int color,
                ActorAppearance.RenderedBehavior rendered,
                long sortKey
        ) {
            AlphaMode alphaMode = alpha(color) < 255 ? AlphaMode.TRANSLUCENT : AlphaMode.CUTOUT;
            return new RenderCommand(Geometry.OBJ_MODEL, Material.BATTLE_ENTITY, alphaMode, ActorSpaceBinding.WORLD, model, objModel.getTexturePath(), objModel, color, null, null, null, 0.0F, null, null, null, null, null, rendered, sortKey, null);
        }

        public static RenderCommand extrudedImage(Matrix4f model, ActorAppearance.ImageContent image, long sortKey) {
            return extrudedImage(model, image, ActorAppearance.RenderedBehavior.FULL, sortKey);
        }

        public static RenderCommand extrudedImage(
                Matrix4f model,
                ActorAppearance.ImageContent image,
                ActorAppearance.RenderedBehavior rendered,
                long sortKey
        ) {
            Matrix4f thicknessModel = new Matrix4f(model).scale(1.0F, (float) image.thickness(), 1.0F);
            return new RenderCommand(Geometry.EXTRUDED_IMAGE, Material.TEXTURE, AlphaMode.CUTOUT,
                    ActorSpaceBinding.WORLD, thicknessModel, image.texture(), null, image.tint(), null,
                    null, null, 0.0F, null, null, image, null, null, rendered, sortKey, null);
        }

        public static RenderCommand line(Vector3f start, Vector3f end, float lineWidth, int color, long sortKey) {
            if (!Float.isFinite(lineWidth) || lineWidth <= 0.0F) {
                throw new IllegalArgumentException("lineWidth must be finite and > 0.");
            }
            AlphaMode alphaMode = alpha(color) < 255 ? AlphaMode.TRANSLUCENT : AlphaMode.OPAQUE;
            return new RenderCommand(Geometry.LINE_SEGMENT, Material.LINE, alphaMode, ActorSpaceBinding.WORLD, null, null, null, color, null, new Vector3f(start), new Vector3f(end), lineWidth, null, null, null, null, null, ActorAppearance.RenderedBehavior.DISABLED, sortKey, null);
        }

        public static RenderCommand auxiliaryGrid(
                Matrix4f model,
                float density,
                float lineWidth,
                int lineColor,
                int fillColor,
                long sortKey
        ) {
            return new RenderCommand(Geometry.UNIT_QUAD, Material.AUXILIARY_GRID,
                    AlphaMode.TRANSLUCENT, ActorSpaceBinding.WORLD, model, null, null, lineColor, null,
                    null, null, 0.0F, null, null, null, null,
                    new AuxiliaryGrid(density, lineWidth, fillColor), ActorAppearance.RenderedBehavior.RECEIVER_ONLY, sortKey, null);
        }

        public static RenderCommand battleFrame(Matrix4f model, FrameMesh mesh, int color, long sortKey) {
            return battleFrame(model, mesh, color, ActorAppearance.RenderedBehavior.RECEIVER_ONLY, sortKey);
        }

        public static RenderCommand battleFrame(
                Matrix4f model,
                FrameMesh mesh,
                int color,
                ActorAppearance.RenderedBehavior rendered,
                long sortKey
        ) {
            return new RenderCommand(Geometry.BATTLE_FRAME, Material.BATTLE_FRAME, AlphaMode.OPAQUE,
                    ActorSpaceBinding.WORLD, model, null, null, color, null, null, null, 0.0F,
                    null, null, null, Objects.requireNonNull(mesh, "mesh"), null, rendered, sortKey, null);
        }

        public static RenderCommand text(ScreenRect bounds, ActorAppearance.TextContent text, long sortKey) {
            return text(null, bounds, text, ActorSpaceBinding.SCREEN_PLANE, sortKey);
        }

        public static RenderCommand text(Matrix4f model, ScreenRect bounds, ActorAppearance.TextContent text,
                                         ActorSpaceBinding binding, long sortKey) {
            return new RenderCommand(Geometry.GLYPH_RUN, Material.GLYPH, AlphaMode.TRANSLUCENT, binding,
                    model, null, null, text.color(), null, null, null, 0.0F, bounds, text, null, null, null, ActorAppearance.RenderedBehavior.DISABLED, sortKey, null);
        }

        RenderCommand withSourceActor(ActorRef sourceActor) {
            return new RenderCommand(
                    this.geometry,
                    this.material,
                    this.alphaMode,
                    this.binding,
                    this.model,
                    this.texture,
                    this.objModel,
                    this.color,
                    this.uvRect,
                    this.lineStart,
                    this.lineEnd,
                    this.lineWidth,
                    this.textBounds,
                    this.text,
                    this.extrudedImage,
                    this.frameMesh,
                    this.auxiliaryGrid,
                    this.rendered,
                    this.sortKey,
                    Objects.requireNonNull(sourceActor, "sourceActor"));
        }

        private static int alpha(int color) {
            return color >>> 24;
        }
    }

    public enum Geometry {
        UNIT_QUAD,
        EXTRUDED_IMAGE,
        LINE_SEGMENT,
        BATTLE_FRAME,
        OBJ_MODEL,
        GLYPH_RUN
    }

    public enum Material {
        SOLID,
        TEXTURE,
        LINE,
        AUXILIARY_GRID,
        BATTLE_FRAME,
        BATTLE_ENTITY,
        GLYPH
    }

    public enum AlphaMode {
        OPAQUE,
        CUTOUT,
        TRANSLUCENT
    }

    // thickness 在建模前受最短边限制，以防角块与棱柱发生体积重叠。
    public record FrameMesh(float sizeX, float sizeY, float sizeZ, float thickness) {
        public FrameMesh {
            if (!Float.isFinite(sizeX) || !Float.isFinite(sizeY) || !Float.isFinite(sizeZ)
                    || sizeX <= 0.0F || sizeY <= 0.0F || sizeZ <= 0.0F) {
                throw new IllegalArgumentException("Battle frame dimensions must be finite and > 0.");
            }
            if (!Float.isFinite(thickness) || thickness <= 0.0F) {
                throw new IllegalArgumentException("Battle frame thickness must be finite and > 0.");
            }
        }

        public float effectiveThickness() {
            return Math.min(thickness, Math.min(sizeX, Math.min(sizeY, sizeZ)));
        }
    }

    public record AuxiliaryGrid(float density, float lineWidth, int fillColor) {
        public AuxiliaryGrid {
            if (!Float.isFinite(density) || density <= 0.0F) {
                throw new IllegalArgumentException("Grid density must be finite and > 0.");
            }
            if (!Float.isFinite(lineWidth) || lineWidth <= 0.0F) {
                throw new IllegalArgumentException("Grid line width must be finite and > 0.");
            }
        }
    }

    // modelPivot 保留移动枢轴语义；所有光照与阴影计算必须使用 modelBoundsCenter。
    public record SoulTintLight(
            CanonicalVec3 modelPivot,
            CanonicalVec3 modelBoundsCenter,
            int color,
            ActorRef sourceActor
    ) {
        public SoulTintLight {
            Objects.requireNonNull(modelPivot, "modelPivot");
            Objects.requireNonNull(modelBoundsCenter, "modelBoundsCenter");
            Objects.requireNonNull(sourceActor, "sourceActor");
        }
    }

    public record ShadowCaster(int commandIndex, WorldBounds bounds, ActorRef sourceActor) {
        public ShadowCaster {
            if (commandIndex < 0) throw new IllegalArgumentException("commandIndex must be non-negative.");
            Objects.requireNonNull(bounds, "bounds");
        }
    }

    public record WorldBounds(Vector3f minimum, Vector3f maximum) {
        public WorldBounds {
            minimum = new Vector3f(Objects.requireNonNull(minimum, "minimum"));
            maximum = new Vector3f(Objects.requireNonNull(maximum, "maximum"));
            if (minimum.x() > maximum.x() || minimum.y() > maximum.y() || minimum.z() > maximum.z()) {
                throw new IllegalArgumentException("World bounds minimum must not exceed maximum.");
            }
        }

        static WorldBounds transformedBox(Matrix4f model, float halfX, float halfY, float halfZ) {
            return transformedBox(model, -halfX, -halfY, -halfZ, halfX, halfY, halfZ);
        }

        static WorldBounds transformedBox(
                Matrix4f model,
                float minimumX,
                float minimumY,
                float minimumZ,
                float maximumX,
                float maximumY,
                float maximumZ
        ) {
            Vector3f minimum = new Vector3f(Float.POSITIVE_INFINITY);
            Vector3f maximum = new Vector3f(Float.NEGATIVE_INFINITY);
            Vector3f point = new Vector3f();
            for (int x = 0; x <= 1; x++) {
                for (int y = 0; y <= 1; y++) {
                    for (int z = 0; z <= 1; z++) {
                        model.transformPosition(
                                x == 0 ? minimumX : maximumX,
                                y == 0 ? minimumY : maximumY,
                                z == 0 ? minimumZ : maximumZ,
                                point
                        );
                        minimum.min(point);
                        maximum.max(point);
                    }
                }
            }
            return new WorldBounds(minimum, maximum);
        }

        public boolean contains(Vector3f point) {
            return point.x() >= minimum.x() && point.x() <= maximum.x()
                    && point.y() >= minimum.y() && point.y() <= maximum.y()
                    && point.z() >= minimum.z() && point.z() <= maximum.z();
        }
    }

    // 主 arena 的逻辑内腔。
    public record VolumeRegion(Matrix4f localToWorld, Vector3f halfSize) {
        public VolumeRegion {
            localToWorld = new Matrix4f(Objects.requireNonNull(localToWorld, "localToWorld"));
            halfSize = new Vector3f(Objects.requireNonNull(halfSize, "halfSize"));
            if (!(halfSize.x() > 0.0F && halfSize.y() > 0.0F && halfSize.z() > 0.0F)) {
                throw new IllegalArgumentException("Volume halfSize must be positive.");
            }
        }
    }

    public enum ActorSpaceBinding {
        WORLD,
        WORLD_BILLBOARD,
        SCREEN_PLANE
    }

}
