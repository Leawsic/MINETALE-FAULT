package cn.jehorstudio.minetale.battle.logic.actor;

import cn.jehorstudio.minetale.lib.ObjModels;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;

import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

// Snapshot 携带的纯外观定义：Content 描述绘制内容，Mode 只决定渲染空间，两者均不承载交互语义。
public record ActorAppearance(
        Mode mode,
        boolean visible,
        Content content,
        Layout2d layout,
        RenderedBehavior rendered
) {
    private static final Set<String> COMMON_FIELDS = Set.of("type", "mode", "visible", "layout", "rendered");

    public ActorAppearance(Mode mode, boolean visible, Content content) {
        this(mode, visible, content, mode == Mode.TWO_D ? Layout2d.FULL_VIEWPORT : null,
                legacyRenderedBehavior(mode, content));
    }

    public ActorAppearance(Mode mode, boolean visible, Content content, Layout2d layout) {
        this(mode, visible, content, layout, legacyRenderedBehavior(mode, content));
    }

    public ActorAppearance {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(rendered, "rendered");
        if (mode == Mode.TWO_D) {
            Objects.requireNonNull(layout, "2d appearance layout");
        } else if (layout != null) {
            throw new IllegalArgumentException(mode.id() + " appearance cannot contain viewport layout.");
        }
        switch (content) {
            case ImageContent image -> validateImageMode(mode, image);
            case ModelContent ignored -> requireThreeDimensional(mode, "model");
            case ImageModelContent hybrid -> validateImageModelMode(mode, hybrid);
            case TextContent text -> validateTextMode(mode, text);
            case ProgressContent progress -> validateProgressMode(mode, progress);
            case FrameContent frame -> validateFrameMode(mode, frame);
        }
        validateRenderedBehavior(mode, content, rendered);
    }

    public static ActorAppearance fromJson(JsonObject root) {
        Objects.requireNonNull(root, "root");
        Kind kind = Kind.parse(requireString(root, "type"));
        Mode mode = Mode.parse(requireString(root, "mode"));
        rejectUnknownFields(root, allowedFields(kind, mode));
        if (mode != Mode.TWO_D && root.has("layout")) {
            throw new IllegalArgumentException(mode.id() + " appearance cannot contain viewport layout.");
        }
        if (mode == Mode.TWO_D && root.has("rendered")) {
            throw new IllegalArgumentException("2d appearance cannot contain rendered behavior.");
        }
        Content content = switch (kind) {
            case IMAGE -> parseTextureImage(requireObject(root, "image"), mode == Mode.THREE_D);
            case MODEL -> parseModel(requireObject(root, "model"));
            case IMAGE_MODEL -> new ImageModelContent(
                    parseTextureImage(requireObject(root, "image"), false),
                    parseModel(requireObject(root, "model"))
            );
            case TEXT -> parseText(requireObject(root, "text"), mode == Mode.THREE_D);
            case PROGRESS_BAR -> parseProgress(requireObject(root, "progress"), mode == Mode.THREE_D);
            case FRAME -> mode == Mode.TWO_D
                    ? parseFrame2d(requireObject(root, "frame"))
                    : FrameContent.spatial(
                            worldSize(requireObject(root, "frame"), "worldSize"),
                            requirePositiveFiniteDouble(requireObject(root, "frame"), "thickness"),
                            color(requireObject(root, "frame"), "borderColor", "#FFFFFFFF")
                    );
        };
        if (mode == Mode.TWO_D && !root.has("layout")) {
            throw new IllegalArgumentException("2d appearance requires layout.");
        }
        Layout2d layout = mode == Mode.TWO_D ? parseLayout(requireObject(root, "layout")) : null;
        RenderedBehavior rendered = root.has("rendered")
                ? parseRenderedBehavior(requireObject(root, "rendered"))
                : legacyRenderedBehavior(mode, content);
        return new ActorAppearance(mode, optionalBoolean(root, "visible", true), content, layout, rendered);
    }

    public ActorAppearance withText(String text) {
        TextContent current = requireTextContent();
        return withContent(current.withText(text));
    }

    public ActorAppearance withImageThickness(double thickness) {
        if (this.mode != Mode.THREE_D || !(this.content instanceof ImageContent image)) {
            throw new IllegalStateException("Actor appearance is not a 3d image.");
        }
        return withContent(image.withThickness(thickness));
    }

    public ActorAppearance withImageTint(int tint) {
        Content next = switch (this.content) {
            case ImageContent image -> image.withTint(tint);
            case ImageModelContent hybrid -> hybrid.withImage(hybrid.image().withTint(tint));
            default -> throw new IllegalStateException("Actor appearance is not an image.");
        };
        return withContent(next);
    }

    public ActorAppearance withModel(ObjModels model, int tint) {
        Content next = switch (this.content) {
            case ModelContent current -> current.withModel(model, tint);
            case ImageModelContent hybrid -> hybrid.withModel(hybrid.model().withModel(model, tint));
            default -> throw new IllegalStateException("Actor appearance is not a model.");
        };
        return withContent(next);
    }

    public ActorAppearance withTextColor(int color) {
        TextContent current = requireTextContent();
        return withContent(current.withColor(color));
    }

    public ActorAppearance withProgressStyle(int fillColor, int backgroundColor, int borderColor, int borderWidth) {
        ProgressContent current = requireProgressContent();
        return withContent(current.withStyle(fillColor, backgroundColor, borderColor, borderWidth));
    }

    public ActorAppearance withProgressThickness(double thickness) {
        if (this.mode != Mode.THREE_D) {
            throw new IllegalStateException("Actor appearance is not a 3d progress bar.");
        }
        ProgressContent current = requireProgressContent();
        return withContent(current.withThickness(thickness));
    }

    public ActorAppearance withFrame2dStyle(int backgroundColor, int borderColor, int borderWidth) {
        if (this.mode != Mode.TWO_D || !(this.content instanceof FrameContent)) {
            throw new IllegalStateException("Actor appearance is not a 2d frame.");
        }
        return withContent(FrameContent.twoDimensional(backgroundColor, borderColor, borderWidth));
    }

    public ActorAppearance withFrameSpatialStyle(double thickness, int borderColor) {
        if (this.mode == Mode.TWO_D || !(this.content instanceof FrameContent frame) || frame.worldSize() == null) {
            throw new IllegalStateException("Actor appearance is not a spatial frame.");
        }
        return withContent(FrameContent.spatial(frame.worldSize(), thickness, borderColor));
    }

    public ActorAppearance withImageTexture(
            ResourceLocation texture,
            SourceRect source,
            TextureSize textureSize
    ) {
        ImageContent current = switch (this.content) {
            case ImageContent image -> image;
            case ImageModelContent hybrid -> hybrid.image();
            default -> throw new IllegalStateException("Actor appearance is not a texture-backed image.");
        };
        ImageContent nextImage = ImageContent.texture(
                texture, source, textureSize, current.tint(), current.thickness(),
                current.fitMode(), current.aspectMode(), current.fixedAspectRatio()
        );
        Content next = this.content instanceof ImageModelContent hybrid
                ? hybrid.withImage(nextImage)
                : nextImage;
        return withContent(next);
    }

    public ActorAppearance insertText(int codePointIndex, String inserted) {
        TextContent current = requireTextContent();
        return withContent(current.insert(codePointIndex, inserted));
    }

    public ActorAppearance deleteText(int startCodePoint, int count) {
        TextContent current = requireTextContent();
        return withContent(current.delete(startCodePoint, count));
    }

    public ActorAppearance withProgressMinimum(double minimum) {
        ProgressContent current = requireProgressContent();
        return withContent(current.withMinimum(minimum));
    }

    public ActorAppearance withProgressMaximum(double maximum) {
        ProgressContent current = requireProgressContent();
        return withContent(current.withMaximum(maximum));
    }

    public ActorAppearance withProgressValue(double value) {
        ProgressContent current = requireProgressContent();
        return withContent(current.withValue(value));
    }

    private ActorAppearance withContent(Content next) {
        return new ActorAppearance(this.mode, this.visible, next, this.layout, this.rendered);
    }

    public TextContent requireTextContent() {
        if (this.content instanceof TextContent text) {
            return text;
        }
        throw new IllegalStateException("Actor appearance is not text.");
    }

    public ProgressContent requireProgressContent() {
        if (this.content instanceof ProgressContent progress) {
            return progress;
        }
        throw new IllegalStateException("Actor appearance is not a progress bar.");
    }

    private static RenderedBehavior parseRenderedBehavior(JsonObject root) {
        rejectUnknownRenderedFields(root, Set.of("enabled", "castsShadow", "receivesShadow"));
        return new RenderedBehavior(
                requireBoolean(root, "enabled"),
                requireBoolean(root, "castsShadow"),
                requireBoolean(root, "receivesShadow")
        );
    }

    private static RenderedBehavior legacyRenderedBehavior(Mode mode, Content content) {
        if (mode == Mode.TWO_D || !supportsRenderedBehavior(content)) {
            return RenderedBehavior.DISABLED;
        }
        return content instanceof FrameContent
                ? RenderedBehavior.RECEIVER_ONLY
                : RenderedBehavior.FULL;
    }

    private static void validateRenderedBehavior(Mode mode, Content content, RenderedBehavior rendered) {
        if (mode == Mode.TWO_D && !rendered.equals(RenderedBehavior.DISABLED)) {
            throw new IllegalArgumentException("2d appearance cannot enable rendered behavior.");
        }
        if (!supportsRenderedBehavior(content)
                && (rendered.enabled() || rendered.castsShadow() || rendered.receivesShadow())) {
            throw new IllegalArgumentException(content.kind().id() + " appearance does not support rendered behavior.");
        }
    }

    private static boolean supportsRenderedBehavior(Content content) {
        return content instanceof ImageContent
                || content instanceof ModelContent
                || content instanceof ImageModelContent
                || content instanceof FrameContent;
    }

    private static Set<String> allowedFields(Kind kind, Mode mode) {
        Set<String> fields = new HashSet<>(COMMON_FIELDS);
        switch (kind) {
            case IMAGE -> fields.add("image");
            case MODEL -> fields.add("model");
            case IMAGE_MODEL -> {
                fields.add("image");
                fields.add("model");
            }
            case TEXT -> fields.add("text");
            case PROGRESS_BAR -> fields.add("progress");
            case FRAME -> fields.add("frame");
        }
        return Set.copyOf(fields);
    }

    private static void validateImageMode(Mode mode, ImageContent image) {
        if (image.texture() == null || image.model() != null) {
            throw new IllegalArgumentException(mode.id() + " image appearance requires exactly one texture.");
        }
        if (mode == Mode.THREE_D && image.thickness() <= 0.0D) {
            throw new IllegalArgumentException("3d image appearance requires positive thickness.");
        }
        if (mode != Mode.THREE_D && image.thickness() != 0.0D) {
            throw new IllegalArgumentException(mode.id() + " image appearance cannot define thickness.");
        }
    }

    private static void validateImageModelMode(Mode mode, ImageModelContent hybrid) {
        if (mode != Mode.DUAL) {
            throw new IllegalArgumentException("image_model appearance supports mode=dual only.");
        }
        validateImageMode(Mode.DUAL, hybrid.image());
    }

    private static void validateFrameMode(Mode mode, FrameContent frame) {
        if (mode == Mode.TWO_D) {
            if (frame.worldSize() != null || frame.thickness() != 0.0D) {
                throw new IllegalArgumentException("2d frame appearance cannot contain spatial frame data.");
            }
            return;
        }
        if (frame.worldSize() == null || frame.thickness() <= 0.0D) {
            throw new IllegalArgumentException(mode.id() + " frame appearance requires worldSize and thickness.");
        }
        double smallest = Math.min(frame.worldSize().x(), Math.min(frame.worldSize().y(), frame.worldSize().z()));
        if (frame.thickness() * 2.0D > smallest) {
            throw new IllegalArgumentException("Frame thickness must not exceed half of its smallest world size.");
        }
    }

    private static void validateTextMode(Mode mode, TextContent text) {
        if (mode == Mode.TWO_D) {
            if (text.worldSize() != null) {
                throw new IllegalArgumentException("2d text appearance cannot define worldSize.");
            }
            return;
        }
        requireThreeDimensional(mode, "text");
        Objects.requireNonNull(text.worldSize(), "3d text worldSize");
    }

    private static void validateProgressMode(Mode mode, ProgressContent progress) {
        if (mode == Mode.TWO_D) {
            if (progress.worldSize() != null || progress.thickness() != 0.0D) {
                throw new IllegalArgumentException("2d progress_bar appearance cannot define spatial data.");
            }
            return;
        }
        requireThreeDimensional(mode, "progress_bar");
        Objects.requireNonNull(progress.worldSize(), "3d progress worldSize");
        requirePositiveFinite(progress.thickness(), "progress thickness");
    }

    private static void requireTwoDimensional(Mode mode, String kind) {
        if (mode != Mode.TWO_D) {
            throw new IllegalArgumentException(kind + " appearance currently supports mode=2d only.");
        }
    }

    private static void requireThreeDimensional(Mode mode, String kind) {
        if (mode != Mode.THREE_D) {
            throw new IllegalArgumentException(kind + " appearance supports mode=3d only.");
        }
    }

    public enum Kind {
        IMAGE("image"),
        MODEL("model"),
        IMAGE_MODEL("image_model"),
        TEXT("text"),
        PROGRESS_BAR("progress_bar"),
        FRAME("frame");

        private final String id;

        Kind(String id) {
            this.id = id;
        }

        public String id() {
            return this.id;
        }

        static Kind parse(String id) {
            for (Kind value : values()) {
                if (value.id.equals(id)) {
                    return value;
                }
            }
            throw new IllegalArgumentException("Unsupported actor appearance type: " + id + ".");
        }
    }

    public enum Mode {
        TWO_D("2d"),
        THREE_D("3d"),
        DUAL("dual");

        private final String id;

        Mode(String id) {
            this.id = id;
        }

        public String id() {
            return this.id;
        }

        static Mode parse(String id) {
            for (Mode value : values()) {
                if (value.id.equals(id)) {
                    return value;
                }
            }
            throw new IllegalArgumentException("Unsupported actor appearance mode: " + id + ".");
        }
    }

    // 在全局 Rendered 模式下覆盖单个外观的表面与阴影参与策略。
    public record RenderedBehavior(boolean enabled, boolean castsShadow, boolean receivesShadow) {
        public static final RenderedBehavior DISABLED = new RenderedBehavior(false, false, false);
        public static final RenderedBehavior FULL = new RenderedBehavior(true, true, true);
        public static final RenderedBehavior RECEIVER_ONLY = new RenderedBehavior(true, false, true);
    }

    // 以视口左上角为原点的百分比布局，不参与战斗逻辑坐标换算。
    public record Layout2d(
            double xPercent,
            double yPercent,
            double widthPercent,
            double heightPercent,
            double rotationDeg,
            int zIndex
    ) {
        public static final Layout2d FULL_VIEWPORT = new Layout2d(0.0D, 0.0D, 100.0D, 100.0D, 0.0D, 0);

        public Layout2d {
            requireFinite(xPercent, "layout.rectPercent[0]");
            requireFinite(yPercent, "layout.rectPercent[1]");
            requirePositiveFinite(widthPercent, "layout.rectPercent[2]");
            requirePositiveFinite(heightPercent, "layout.rectPercent[3]");
            requireFinite(rotationDeg, "layout.rotationDeg");
            if (xPercent < 0.0D || yPercent < 0.0D
                    || xPercent + widthPercent > 100.0D
                    || yPercent + heightPercent > 100.0D) {
                throw new IllegalArgumentException("layout.rectPercent must remain inside the viewport.");
            }
        }
    }

    public enum TextAlign {
        LEFT,
        CENTER,
        RIGHT;

        static TextAlign parse(String value) {
            try {
                return valueOf(value.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("Unsupported text alignment: " + value + ".", exception);
            }
        }
    }

    public enum VerticalTextAlign {
        TOP,
        MIDDLE,
        BOTTOM;

        static VerticalTextAlign parse(String value) {
            try {
                return valueOf(value.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("Unsupported vertical text alignment: " + value + ".", exception);
            }
        }
    }

    public enum TextFit {
        NONE,
        SHRINK_TO_FIT,
        WRAP,
        CLIP;

        static TextFit parse(String value) {
            try {
                return valueOf(value.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("Unsupported text fit mode: " + value + ".", exception);
            }
        }
    }

    public enum FontScaleMode {
        FIXED,
        AUTO;

        static FontScaleMode parse(String value) {
            try {
                return valueOf(value.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("Unsupported font scale mode: " + value + ".", exception);
            }
        }
    }

    public record WorldSize(double x, double y, double z) {
        public WorldSize {
            requirePositiveFinite(x, "worldSize.x");
            requirePositiveFinite(y, "worldSize.y");
            requirePositiveFinite(z, "worldSize.z");
        }
    }

    // 世界平面以 X 为宽、Z 为高；厚度由具体 Content 单独定义。
    public record PlaneSize(double width, double height) {
        public PlaneSize {
            requirePositiveFinite(width, "worldSize.width");
            requirePositiveFinite(height, "worldSize.height");
        }
    }

    public sealed interface Content permits ImageContent, ModelContent, ImageModelContent, TextContent, ProgressContent, FrameContent {
        Kind kind();
    }

    // scale 只归一化 OBJ 顶点，不替代 Actor 自身的空间变换。
    public record ModelContent(ObjModels model, int tint, ModelScale scale) implements Content {
        public ModelContent(ObjModels model, int tint) {
            this(model, tint, ModelScale.ONE);
        }

        public ModelContent {
            Objects.requireNonNull(model, "model");
            Objects.requireNonNull(scale, "scale");
        }

        public ModelContent withModel(ObjModels nextModel, int nextTint) {
            return new ModelContent(nextModel, nextTint, this.scale);
        }

        @Override
        public Kind kind() {
            return Kind.MODEL;
        }
    }

    public record ModelScale(double x, double y, double z) {
        public static final ModelScale ONE = new ModelScale(1.0D, 1.0D, 1.0D);

        public ModelScale {
            requirePositiveFinite(x, "model.scale[0]");
            requirePositiveFinite(y, "model.scale[1]");
            requirePositiveFinite(z, "model.scale[2]");
        }
    }

    public record ImageModelContent(ImageContent image, ModelContent model) implements Content {
        public ImageModelContent {
            Objects.requireNonNull(image, "image");
            Objects.requireNonNull(model, "model");
        }

        public ImageModelContent withImage(ImageContent next) {
            return new ImageModelContent(next, this.model);
        }

        public ImageModelContent withModel(ModelContent next) {
            return new ImageModelContent(this.image, next);
        }

        @Override
        public Kind kind() {
            return Kind.IMAGE_MODEL;
        }
    }

    public record ImageContent(
            ResourceLocation texture,
            SourceRect source,
            TextureSize textureSize,
            int tint,
            double thickness,
            ObjModels model,
            FitMode fitMode,
            AspectMode aspectMode,
            double fixedAspectRatio
    ) implements Content {
        public ImageContent {
            Objects.requireNonNull(fitMode, "fitMode");
            Objects.requireNonNull(aspectMode, "aspectMode");
            if (!Double.isFinite(thickness) || thickness < 0.0D || thickness > 16.0D) {
                throw new IllegalArgumentException("Image thickness must be finite and in [0,16].");
            }
            if (texture != null) {
                Objects.requireNonNull(source, "source");
                Objects.requireNonNull(textureSize, "textureSize");
                if (source.x() + source.width() > textureSize.width()
                        || source.y() + source.height() > textureSize.height()) {
                    throw new IllegalArgumentException("Image source rectangle must remain inside textureSize.");
                }
            } else if (source != null || textureSize != null) {
                throw new IllegalArgumentException("Model-backed image must not define texture source data.");
            }
            if (aspectMode == AspectMode.FIXED) {
                requirePositiveFinite(fixedAspectRatio, "fixedAspectRatio");
            } else if (fixedAspectRatio != 0.0D) {
                throw new IllegalArgumentException("Only aspect=fixed accepts fixedAspectRatio.");
            }
        }

        public static ImageContent texture(
                ResourceLocation texture,
                SourceRect source,
                TextureSize textureSize,
                int tint,
                double thickness,
                FitMode fitMode,
                AspectMode aspectMode,
                double fixedAspectRatio
        ) {
            return new ImageContent(
                    Objects.requireNonNull(texture, "texture"),
                    Objects.requireNonNull(source, "source"),
                    Objects.requireNonNull(textureSize, "textureSize"),
                    tint,
                    thickness,
                    null,
                    fitMode,
                    aspectMode,
                    fixedAspectRatio
            );
        }

        public static ImageContent texture(
                ResourceLocation texture,
                SourceRect source,
                TextureSize textureSize,
                int tint,
                FitMode fitMode,
                AspectMode aspectMode,
                double fixedAspectRatio
        ) {
            return texture(texture, source, textureSize, tint, 0.0D, fitMode, aspectMode, fixedAspectRatio);
        }

        public static ImageContent model(ObjModels model) {
            return new ImageContent(
                    null,
                    null,
                    null,
                    0xFFFFFFFF,
                    0.0D,
                    Objects.requireNonNull(model, "model"),
                    FitMode.NONE,
                    AspectMode.SOURCE,
                    0.0D
            );
        }

        public ImageContent withThickness(double next) {
            return new ImageContent(this.texture, this.source, this.textureSize, this.tint, next,
                    this.model, this.fitMode, this.aspectMode, this.fixedAspectRatio);
        }

        public ImageContent withTint(int next) {
            return new ImageContent(this.texture, this.source, this.textureSize, next, this.thickness,
                    this.model, this.fitMode, this.aspectMode, this.fixedAspectRatio);
        }

        public ImagePlacement resolvePlacement(double containerWidth, double containerHeight) {
            if (texture == null) {
                throw new IllegalStateException("Model-backed image has no 2d placement.");
            }
            if (!Double.isFinite(containerWidth) || !Double.isFinite(containerHeight)
                    || containerWidth < 0.0D || containerHeight < 0.0D) {
                throw new IllegalArgumentException("Image container size must be finite and >= 0.");
            }
            double aspectRatio = aspectRatio(containerHeight == 0.0D ? 1.0D : containerWidth / containerHeight);
            double x = 0.0D;
            double y = 0.0D;
            double width = containerWidth;
            double height = containerHeight;
            double sourceX = this.source.x();
            double sourceY = this.source.y();
            double sourceWidth = this.source.width();
            double sourceHeight = this.source.height();

            if (this.fitMode == FitMode.CONTAIN && containerWidth > 0.0D && containerHeight > 0.0D) {
                if (containerWidth / containerHeight > aspectRatio) {
                    width = containerHeight * aspectRatio;
                    x = (containerWidth - width) * 0.5D;
                } else {
                    height = containerWidth / aspectRatio;
                    y = (containerHeight - height) * 0.5D;
                }
            } else if (this.fitMode == FitMode.COVER && containerWidth > 0.0D && containerHeight > 0.0D) {
                double containerAspect = containerWidth / containerHeight;
                if (containerAspect > aspectRatio) {
                    double fraction = aspectRatio / containerAspect;
                    double cropped = sourceHeight * fraction;
                    sourceY += (sourceHeight - cropped) * 0.5D;
                    sourceHeight = cropped;
                } else if (containerAspect < aspectRatio) {
                    double fraction = containerAspect / aspectRatio;
                    double cropped = sourceWidth * fraction;
                    sourceX += (sourceWidth - cropped) * 0.5D;
                    sourceWidth = cropped;
                }
            } else if (this.fitMode == FitMode.NONE) {
                height = this.source.height();
                width = height * aspectRatio;
                x = (containerWidth - width) * 0.5D;
                y = (containerHeight - height) * 0.5D;
            }

            return new ImagePlacement(
                    x,
                    y,
                    width,
                    height,
                    sourceX / this.textureSize.width(),
                    sourceY / this.textureSize.height(),
                    (sourceX + sourceWidth) / this.textureSize.width(),
                    (sourceY + sourceHeight) / this.textureSize.height()
            );
        }

        public double aspectRatio(double parentAspectRatio) {
            requirePositiveFinite(parentAspectRatio, "parentAspectRatio");
            return switch (this.aspectMode) {
                case PARENT -> parentAspectRatio;
                case SOURCE -> (double) this.source.width() / this.source.height();
                case FIXED -> this.fixedAspectRatio;
            };
        }

        @Override
        public Kind kind() {
            return Kind.IMAGE;
        }
    }

    public record SourceRect(int x, int y, int width, int height) {
        public SourceRect {
            if (x < 0 || y < 0 || width <= 0 || height <= 0 || width > 16384 || height > 16384) {
                throw new IllegalArgumentException("Image source must use non-negative origin and positive size <= 16384.");
            }
        }
    }

    public record TextureSize(int width, int height) {
        public TextureSize {
            if (width <= 0 || height <= 0 || width > 16384 || height > 16384) {
                throw new IllegalArgumentException("textureSize axes must be between 1 and 16384.");
            }
        }
    }

    public record ImagePlacement(
            double x,
            double y,
            double width,
            double height,
            double u0,
            double v0,
            double u1,
            double v1
    ) {
        public ImagePlacement {
            if (!Double.isFinite(x) || !Double.isFinite(y)
                    || !Double.isFinite(width) || !Double.isFinite(height)
                    || !Double.isFinite(u0) || !Double.isFinite(v0)
                    || !Double.isFinite(u1) || !Double.isFinite(v1)
                    || width < 0.0D || height < 0.0D) {
                throw new IllegalArgumentException("Image placement values must be finite with non-negative size.");
            }
        }
    }

    public enum FitMode {
        STRETCH,
        CONTAIN,
        COVER,
        NONE;

        static FitMode parse(String value) {
            try {
                return valueOf(value.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("Unsupported image fit mode: " + value + ".", exception);
            }
        }
    }

    public enum AspectMode {
        PARENT,
        SOURCE,
        FIXED;

        static AspectMode parse(String value) {
            try {
                return valueOf(value.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("Unsupported image aspect mode: " + value + ".", exception);
            }
        }
    }

    public record TextContent(
            String text,
            int color,
            boolean shadow,
            TextAlign align,
            VerticalTextAlign verticalAlign,
            TextFit fit,
            FontScale fontScale,
            PlaneSize worldSize
    ) implements Content {
        public TextContent(String text, int color, boolean shadow, TextAlign align,
                           VerticalTextAlign verticalAlign, TextFit fit, FontScale fontScale) {
            this(text, color, shadow, align, verticalAlign, fit, fontScale, null);
        }
        public TextContent {
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(align, "align");
            Objects.requireNonNull(verticalAlign, "verticalAlign");
            Objects.requireNonNull(fit, "fit");
            Objects.requireNonNull(fontScale, "fontScale");
            requireTextLength(text);
        }

        public TextContent withText(String next) {
            return new TextContent(next, this.color, this.shadow, this.align, this.verticalAlign, this.fit, this.fontScale, this.worldSize);
        }

        public TextContent withColor(int next) {
            return new TextContent(this.text, next, this.shadow, this.align, this.verticalAlign, this.fit, this.fontScale, this.worldSize);
        }

        public TextContent insert(int codePointIndex, String inserted) {
            Objects.requireNonNull(inserted, "inserted");
            int utf16Index = codePointOffset(this.text, codePointIndex);
            return withText(this.text.substring(0, utf16Index) + inserted + this.text.substring(utf16Index));
        }

        public TextContent delete(int startCodePoint, int count) {
            if (count < 0) {
                throw new IllegalArgumentException("Text delete count must be >= 0.");
            }
            int start = codePointOffset(this.text, startCodePoint);
            int available = this.text.codePointCount(start, this.text.length());
            int end = this.text.offsetByCodePoints(start, Math.min(count, available));
            return withText(this.text.substring(0, start) + this.text.substring(end));
        }

        public String prefix(int codePointCount) {
            int end = codePointOffset(this.text, Math.min(codePointCount, this.text.codePointCount(0, this.text.length())));
            return this.text.substring(0, end);
        }

        @Override
        public Kind kind() {
            return Kind.TEXT;
        }
    }

    public record FontScale(FontScaleMode mode, double value) {
        public FontScale {
            Objects.requireNonNull(mode, "mode");
            if (mode == FontScaleMode.FIXED) {
                requirePositiveFinite(value, "fontScale.value");
                if (value > 16.0D) {
                    throw new IllegalArgumentException("fontScale.value must be <= 16.");
                }
            } else if (value != 0.0D) {
                throw new IllegalArgumentException("fontScale auto mode must not define a fixed value.");
            }
        }

        public static FontScale fixed(double value) {
            return new FontScale(FontScaleMode.FIXED, value);
        }

        public static FontScale auto() {
            return new FontScale(FontScaleMode.AUTO, 0.0D);
        }
    }

    public record ProgressContent(
            double minimum,
            double maximum,
            double value,
            int fillColor,
            int backgroundColor,
            int borderColor,
            int borderWidth,
            PlaneSize worldSize,
            double thickness
    ) implements Content {
        public ProgressContent(double minimum, double maximum, double value, int fillColor,
                               int backgroundColor, int borderColor, int borderWidth) {
            this(minimum, maximum, value, fillColor, backgroundColor, borderColor, borderWidth, null, 0.0D);
        }
        public ProgressContent {
            requireFinite(minimum, "progress minimum");
            requireFinite(maximum, "progress maximum");
            requireFinite(value, "progress value");
            if (minimum >= maximum) {
                throw new IllegalArgumentException("Progress minimum must be less than maximum.");
            }
            if (value < minimum || value > maximum) {
                throw new IllegalArgumentException("Progress value must be within [minimum, maximum].");
            }
            requireBorderWidth(borderWidth);
            requireFinite(thickness, "progress thickness");
            if (thickness < 0.0D) throw new IllegalArgumentException("Progress thickness must be >= 0.");
        }

        public double fraction() {
            return (this.value - this.minimum) / (this.maximum - this.minimum);
        }

        public ProgressContent withMinimum(double minimum) {
            if (!Double.isFinite(minimum) || minimum >= this.maximum) {
                throw new IllegalArgumentException("Progress minimum must be finite and less than maximum.");
            }
            return new ProgressContent(minimum, this.maximum, Math.max(minimum, this.value), this.fillColor, this.backgroundColor, this.borderColor, this.borderWidth, this.worldSize, this.thickness);
        }

        public ProgressContent withMaximum(double maximum) {
            if (!Double.isFinite(maximum) || maximum <= this.minimum) {
                throw new IllegalArgumentException("Progress maximum must be finite and greater than minimum.");
            }
            return new ProgressContent(this.minimum, maximum, Math.min(maximum, this.value), this.fillColor, this.backgroundColor, this.borderColor, this.borderWidth, this.worldSize, this.thickness);
        }

        public ProgressContent withValue(double value) {
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("Progress value must be finite.");
            }
            double clamped = Math.max(this.minimum, Math.min(this.maximum, value));
            return new ProgressContent(this.minimum, this.maximum, clamped, this.fillColor, this.backgroundColor, this.borderColor, this.borderWidth, this.worldSize, this.thickness);
        }

        public ProgressContent withStyle(int fillColor, int backgroundColor, int borderColor, int borderWidth) {
            return new ProgressContent(this.minimum, this.maximum, this.value, fillColor, backgroundColor, borderColor, borderWidth, this.worldSize, this.thickness);
        }

        public ProgressContent withThickness(double thickness) {
            return new ProgressContent(this.minimum, this.maximum, this.value, this.fillColor, this.backgroundColor,
                    this.borderColor, this.borderWidth, this.worldSize, thickness);
        }

        @Override
        public Kind kind() {
            return Kind.PROGRESS_BAR;
        }
    }

    public record FrameContent(
            int backgroundColor,
            int borderColor,
            int borderWidth,
            WorldSize worldSize,
            double thickness
    ) implements Content {
        public FrameContent {
            requireBorderWidth(borderWidth);
            requireFinite(thickness, "frame thickness");
            if (thickness < 0.0D) {
                throw new IllegalArgumentException("Frame thickness must be >= 0.");
            }
        }

        public static FrameContent twoDimensional(int backgroundColor, int borderColor, int borderWidth) {
            return new FrameContent(backgroundColor, borderColor, borderWidth, null, 0.0D);
        }

        public static FrameContent spatial(WorldSize worldSize, double thickness, int borderColor) {
            return new FrameContent(0x00000000, borderColor, 0, Objects.requireNonNull(worldSize, "worldSize"), thickness);
        }

        @Override
        public Kind kind() {
            return Kind.FRAME;
        }
    }

    private static int codePointOffset(String text, int codePointIndex) {
        int count = text.codePointCount(0, text.length());
        if (codePointIndex < 0 || codePointIndex > count) {
            throw new IllegalArgumentException("Text index must be between 0 and " + count + ".");
        }
        return text.offsetByCodePoints(0, codePointIndex);
    }

    public static ObjModels parseObjModel(String value) {
        try {
            return ObjModels.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unknown 3d model: " + value + ".", exception);
        }
    }

    private static JsonObject requireObject(JsonObject root, String member) {
        JsonElement element = root.get(member);
        if (element == null || !element.isJsonObject()) {
            throw new IllegalArgumentException(member + " must be an object.");
        }
        return element.getAsJsonObject();
    }

    private static TextContent parseText(JsonObject root, boolean spatial) {
        rejectUnknownFields(root, spatial
                ? Set.of("value", "color", "shadow", "align", "verticalAlign", "fit", "fontScale", "worldSize")
                : Set.of("value", "color", "shadow", "align", "verticalAlign", "fit", "fontScale"));
        return new TextContent(
                requireString(root, "value"),
                color(root, "color", "#FFFFFFFF"),
                optionalBoolean(root, "shadow", false),
                TextAlign.parse(optionalString(root, "align", "left")),
                VerticalTextAlign.parse(optionalString(root, "verticalAlign", "top")),
                TextFit.parse(optionalString(root, "fit", "clip")),
                parseFontScale(root),
                spatial ? planeSize(root, "worldSize") : null
        );
    }

    private static Layout2d parseLayout(JsonObject root) {
        rejectUnknownLayoutFields(root, Set.of("space", "rectPercent", "rotationDeg", "zIndex"));
        if (!"viewport.percent".equals(requireString(root, "space"))) {
            throw new IllegalArgumentException("layout.space currently requires viewport.percent.");
        }
        JsonArray rect = requireArray(root, "rectPercent", 4);
        return new Layout2d(
                finiteAt(rect, 0, "layout.rectPercent"),
                finiteAt(rect, 1, "layout.rectPercent"),
                finiteAt(rect, 2, "layout.rectPercent"),
                finiteAt(rect, 3, "layout.rectPercent"),
                optionalFiniteDouble(root, "rotationDeg", 0.0D),
                optionalInt(root, "zIndex", 0)
        );
    }

    private static FontScale parseFontScale(JsonObject text) {
        JsonElement element = text.get("fontScale");
        if (element == null) {
            return FontScale.fixed(1.0D);
        }
        if (!element.isJsonObject()) {
            throw new IllegalArgumentException("fontScale must be an object.");
        }
        JsonObject scale = element.getAsJsonObject();
        rejectUnknownFields(scale, Set.of("type", "value"));
        FontScaleMode mode = FontScaleMode.parse(requireString(scale, "type"));
        return mode == FontScaleMode.AUTO
                ? FontScale.auto()
                : FontScale.fixed(requirePositiveFiniteDouble(scale, "value"));
    }

    private static ProgressContent parseProgress(JsonObject root, boolean spatial) {
        rejectUnknownFields(root, spatial
                ? Set.of("min", "max", "value", "fillColor", "backgroundColor", "borderColor", "borderWidth", "worldSize", "thickness")
                : Set.of("min", "max", "value", "fillColor", "backgroundColor", "borderColor", "borderWidth"));
        return new ProgressContent(
                requireFiniteDouble(root, "min"),
                requireFiniteDouble(root, "max"),
                requireFiniteDouble(root, "value"),
                color(root, "fillColor", "#FFFFFF00"),
                color(root, "backgroundColor", "#FF400000"),
                color(root, "borderColor", "#FFFFFFFF"),
                optionalNonNegativeInt(root, "borderWidth", 1),
                spatial ? planeSize(root, "worldSize") : null,
                spatial ? requirePositiveFiniteDouble(root, "thickness") : 0.0D
        );
    }

    private static PlaneSize planeSize(JsonObject root, String member) {
        JsonArray values = requireArray(root, member, 2);
        return new PlaneSize(finiteAt(values, 0, member), finiteAt(values, 1, member));
    }

    private static FrameContent parseFrame2d(JsonObject root) {
        rejectUnknownFields(root, Set.of("backgroundColor", "borderColor", "borderWidth"));
        return FrameContent.twoDimensional(
                color(root, "backgroundColor", "#E0000000"),
                color(root, "borderColor", "#FFFFFFFF"),
                optionalNonNegativeInt(root, "borderWidth", 2)
        );
    }

    private static ImageContent parseTextureImage(JsonObject root, boolean spatial) {
        Set<String> fields = new HashSet<>(Set.of(
                "texture", "source", "textureSize", "tint", "fit", "aspect", "aspectRatio"
        ));
        if (spatial) {
            fields.add("thickness");
        }
        rejectUnknownFields(root, Set.copyOf(fields));
        FitMode fit = FitMode.parse(optionalString(root, "fit", "contain"));
        AspectMode aspect = AspectMode.parse(optionalString(root, "aspect", "source"));
        double fixedAspectRatio = aspect == AspectMode.FIXED
                ? requirePositiveFiniteDouble(root, "aspectRatio")
                : 0.0D;
        return ImageContent.texture(
                requireResourceLocation(root, "texture"),
                source(root),
                textureSize(root),
                color(root, "tint", "#FFFFFFFF"),
                spatial ? requirePositiveFiniteDouble(root, "thickness") : 0.0D,
                fit,
                aspect,
                fixedAspectRatio
        );
    }

    private static ModelContent parseModel(JsonObject root) {
        rejectUnknownFields(root, Set.of("id", "tint", "scale"));
        ModelScale scale = ModelScale.ONE;
        if (root.has("scale")) {
            JsonArray values = requireArray(root, "scale", 3);
            scale = new ModelScale(
                    requireFiniteNumber(values.get(0), "scale[0]"),
                    requireFiniteNumber(values.get(1), "scale[1]"),
                    requireFiniteNumber(values.get(2), "scale[2]")
            );
        }
        return new ModelContent(
                parseObjModel(requireString(root, "id")),
                color(root, "tint", "#FFFFFFFF"),
                scale
        );
    }

    private static SourceRect source(JsonObject root) {
        JsonArray array = requireArray(root, "source", 4);
        return new SourceRect(
                requireNonNegativeInteger(array.get(0), "source[0]"),
                requireNonNegativeInteger(array.get(1), "source[1]"),
                requirePositiveInteger(array.get(2), "source[2]"),
                requirePositiveInteger(array.get(3), "source[3]")
        );
    }

    private static TextureSize textureSize(JsonObject root) {
        JsonArray array = requireArray(root, "textureSize", 2);
        return new TextureSize(
                requirePositiveInteger(array.get(0), "textureSize[0]"),
                requirePositiveInteger(array.get(1), "textureSize[1]")
        );
    }

    private static JsonArray requireArray(JsonObject root, String member, int size) {
        JsonElement element = root.get(member);
        if (element == null || !element.isJsonArray() || element.getAsJsonArray().size() != size) {
            throw new IllegalArgumentException(member + " must be a " + size + "-number array.");
        }
        return element.getAsJsonArray();
    }

    private static double finiteAt(JsonArray array, int index, String path) {
        double value = array.get(index).getAsDouble();
        requireFinite(value, path + "[" + index + "]");
        return value;
    }

    private static int requireNonNegativeInteger(JsonElement element, String path) {
        int value = requireInteger(element, path);
        if (value < 0) throw new IllegalArgumentException(path + " must be >= 0.");
        return value;
    }

    private static int requirePositiveInteger(JsonElement element, String path) {
        int value = requireInteger(element, path);
        if (value <= 0) throw new IllegalArgumentException(path + " must be > 0.");
        return value;
    }

    private static int requireInteger(JsonElement element, String path) {
        double value = element.getAsDouble();
        if (!Double.isFinite(value) || value != Math.rint(value) || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(path + " must be an integer.");
        }
        return (int) value;
    }

    private static String requireString(JsonObject root, String member) {
        JsonElement element = root.get(member);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(member + " must be a string.");
        }
        return element.getAsString();
    }

    private static String optionalString(JsonObject root, String member, String fallback) {
        return root.has(member) ? requireString(root, member) : fallback;
    }

    private static boolean optionalBoolean(JsonObject root, String member, boolean fallback) {
        JsonElement element = root.get(member);
        if (element == null) {
            return fallback;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException(member + " must be a boolean.");
        }
        return element.getAsBoolean();
    }

    private static boolean requireBoolean(JsonObject root, String member) {
        if (!root.has(member)) {
            throw new IllegalArgumentException(member + " is required.");
        }
        return optionalBoolean(root, member, false);
    }

    private static int optionalInt(JsonObject root, String member, int fallback) {
        JsonElement element = root.get(member);
        if (element == null) {
            return fallback;
        }
        return requireInteger(element, member);
    }

    private static int optionalNonNegativeInt(JsonObject root, String member, int fallback) {
        int value = optionalInt(root, member, fallback);
        if (value < 0) {
            throw new IllegalArgumentException(member + " must be >= 0.");
        }
        return value;
    }

    private static int requirePositiveInt(JsonObject root, String member) {
        int value = optionalInt(root, member, -1);
        if (value <= 0) {
            throw new IllegalArgumentException(member + " must be a positive integer.");
        }
        return value;
    }

    private static double requireFiniteDouble(JsonObject root, String member) {
        if (!root.has(member)) {
            throw new IllegalArgumentException(member + " is required.");
        }
        return optionalFiniteDouble(root, member, 0.0D);
    }

    private static double requirePositiveFiniteDouble(JsonObject root, String member) {
        double value = requireFiniteDouble(root, member);
        requirePositiveFinite(value, member);
        return value;
    }

    private static double optionalFiniteDouble(JsonObject root, String member, double fallback) {
        JsonElement element = root.get(member);
        if (element == null) {
            return fallback;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(member + " must be a number.");
        }
        double value = element.getAsDouble();
        requireFinite(value, member);
        return value;
    }

    private static WorldSize worldSize(JsonObject root, String member) {
        JsonElement element = root.get(member);
        if (element == null) {
            throw new IllegalArgumentException(member + " is required.");
        }
        JsonArray array = requireArraySize(element, member, 3);
        return new WorldSize(
                requireFiniteNumber(array.get(0), member + "[0]"),
                requireFiniteNumber(array.get(1), member + "[1]"),
                requireFiniteNumber(array.get(2), member + "[2]")
        );
    }

    private static JsonArray requireArraySize(JsonElement element, String member, int size) {
        if (!element.isJsonArray() || element.getAsJsonArray().size() != size) {
            throw new IllegalArgumentException(member + " must contain exactly " + size + " numbers.");
        }
        return element.getAsJsonArray();
    }

    private static double requireFiniteNumber(JsonElement element, String member) {
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(member + " must be a number.");
        }
        double value = element.getAsDouble();
        requireFinite(value, member);
        return value;
    }

    private static ResourceLocation requireResourceLocation(JsonObject root, String member) {
        String value = requireString(root, member);
        ResourceLocation location = ResourceLocation.tryParse(value);
        if (location == null) {
            throw new IllegalArgumentException(member + " must be a valid resource location.");
        }
        return location;
    }

    private static int color(JsonObject root, String member, String fallback) {
        String value = optionalString(root, member, fallback);
        return parseColor(value, member);
    }

    public static int parseColor(String value, String member) {
        if (!value.matches("#[0-9A-Fa-f]{6}([0-9A-Fa-f]{2})?")) {
            throw new IllegalArgumentException(member + " must use #RRGGBB or #AARRGGBB.");
        }
        long rgb = Long.parseLong(value.substring(1), 16);
        return value.length() == 7 ? 0xFF000000 | (int) rgb : (int) rgb;
    }

    private static void rejectUnknownFields(JsonObject root, Set<String> allowedFields) {
        for (String field : root.keySet()) {
            if (!allowedFields.contains(field)) {
                throw new IllegalArgumentException("Unknown field for " + requireString(root, "type") + ": " + field + ".");
            }
        }
    }

    private static void rejectUnknownLayoutFields(JsonObject root, Set<String> allowedFields) {
        for (String field : root.keySet()) {
            if (!allowedFields.contains(field)) {
                throw new IllegalArgumentException("Unknown 2d layout field: " + field + ".");
            }
        }
    }

    private static void rejectUnknownRenderedFields(JsonObject root, Set<String> allowedFields) {
        for (String field : root.keySet()) {
            if (!allowedFields.contains(field)) {
                throw new IllegalArgumentException("Unknown rendered behavior field: " + field + ".");
            }
        }
    }

    private static void requireTextLength(String text) {
        if (text.length() > 4096) {
            throw new IllegalArgumentException("Text must not exceed 4096 UTF-16 code units.");
        }
    }

    private static void requireBorderWidth(int borderWidth) {
        if (borderWidth < 0 || borderWidth > 1024) {
            throw new IllegalArgumentException("Border width must be between 0 and 1024.");
        }
    }

    private static void requirePositiveFinite(double value, String name) {
        requireFinite(value, name);
        if (value <= 0.0D) {
            throw new IllegalArgumentException(name + " must be > 0.");
        }
    }

    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite.");
        }
    }

    private static void requireNonNegative(int value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must be >= 0.");
        }
    }
}
