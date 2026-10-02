package cn.jehorstudio.minetale.battle.presentation.screen;

import cn.jehorstudio.minetale.battle.logic.coordinate.CanonicalVec3;
import cn.jehorstudio.minetale.battle.presentation.VisualConfig;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.DoubleConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

// 只开放安全白名单：资源规模与遍历预算使用离散选项，下一帧按正常生命周期重建。
// 连续滑条只更新内存，松开鼠标或停止操作后再持久化。
final class VisualEffectsTuningPanel
        extends ContainerObjectSelectionList<VisualEffectsTuningPanel.TuningEntry> {
    static final int MAX_WIDTH = 360;
    static final int HEADER_HEIGHT = 28;
    private static final int OUTER_MARGIN = 4;
    private static final int ENTRY_HEIGHT = 26;
    private static final int CATEGORY_HEIGHT = 18;
    private static final int SAVE_DELAY_TICKS = 10;
    private static final Component RESET = Component.literal("R");

    private int saveCountdown;
    private Tab selectedTab;

    VisualEffectsTuningPanel(Minecraft minecraft, int screenWidth, int screenHeight) {
        super(
                minecraft,
                panelWidth(screenWidth),
                Math.max(ENTRY_HEIGHT, screenHeight - HEADER_HEIGHT - OUTER_MARGIN),
                HEADER_HEIGHT,
                ENTRY_HEIGHT
        );
        this.setX(OUTER_MARGIN);
        showTab(Tab.EFFECT_SWITCHES);
    }

    private void addEffectSwitches() {
        addCategory("效果开关");
        addToggle(
                VisualConfig.LIVE_SOUL_GUIDE_GRID_ENABLED,
                "启用辅助网格",
                "控制本地 Soul 所在高度的辅助网格和填充面。"
        );
        addToggle(
                VisualConfig.LIVE_VOLUMETRIC_SHADOWS_ENABLED,
                "启用盒形体积光",
                "控制战斗框内的全局雾和方向光体积散射。关闭后可快速对比无体积光的画面。"
        );
        addToggle(
                VisualConfig.LIVE_SOUL_VOLUMETRIC_LIGHT_ENABLED,
                "启用 Soul 体积光",
                "控制本地 Soul 周围的点光单次散射；不会关闭 Soul 的表面点光。"
        );
        addToggle(
                VisualConfig.LIVE_POST_BLOOM_ENABLED,
                "启用 Bloom",
                "只处理资源缓存确认 mayEmit 的 *_s.png B 通道自发光，不使用画面亮度阈值。"
        );
        addToggle(
                VisualConfig.LIVE_ENVIRONMENT_BACKGROUND_ENABLED,
                "启用环境背景",
                "环境场 READY 后在 TWO_D/THREE_D 与 Unmodified/Rendered 中统一生成背景。"
        );
        addToggle(
                VisualConfig.LIVE_ENVIRONMENT_LIGHT_ENABLED,
                "启用环境光",
                "让 THREE_D + RENDERED 的受光 OBJ 与 BattleFrame 读取冻结 Environment Field；不改变背景。"
        );
    }

    private void addVolumetricControls() {
        addCategory("核心介质");
        addFloat(
                VisualConfig.LIVE_VOLUMETRIC_BASE_DENSITY,
                "介质密度", "越大雾越厚、光越难穿透；设为 0 时 Soul 体积光也会消失。",
                0.0D, 2.0D, 3, Curve.SQUARED
        );
        addFloat(
                VisualConfig.LIVE_VOLUMETRIC_EDGE_FALLOFF,
                "边缘收缩", "越小越接近填满战斗框；越大越集中在框中心，边缘更快变淡。",
                0.1D, 4.0D, 2, Curve.LOGARITHMIC
        );
        addFloat(
                VisualConfig.LIVE_VOLUMETRIC_SCATTERING_STRENGTH,
                "太阳散射亮度", "只改变受方向光照亮的雾有多亮，不改变雾的透明度，也不影响 Soul 体积光。",
                0.0D, 2.0D, 3, Curve.SQUARED
        );
        addFloat(
                VisualConfig.LIVE_VOLUMETRIC_SHADOW_STRENGTH,
                "光柱明暗反差", "越大，被遮挡区域越暗，体积光束和暗柱的轮廓越明显。",
                0.0D, 1.0D, 3, Curve.LINEAR
        );

        addCategory("方向与阴影外观");
        addLightDirection();
        addFloat(
                VisualConfig.LIVE_VOLUMETRIC_MINIMUM_FOG_MULTIPLIER,
                "阴影最低雾色", "限制阴影区域最暗能到什么程度；低值允许接近黑色，高值保留更多基础雾色。",
                0.0D, 1.0D, 3, Curve.LINEAR
        );
        addFloat(
                VisualConfig.LIVE_VOLUMETRIC_SHADOW_OPTICAL_DEPTH_GAIN,
                "遮挡敏感度", "越大，小型或较薄的遮挡物也更容易拉出清晰暗柱。",
                1.0D, 8.0D, 2, Curve.LOGARITHMIC
        );
        addFloat(
                VisualConfig.LIVE_VOLUMETRIC_SHADOW_EXTINCTION_GAIN,
                "阴影雾厚度", "让阴影区在变暗之外额外变得不透明；越大，暗柱越厚实。",
                0.0D, 4.0D, 2, Curve.SQUARED
        );
        addFloat(
                VisualConfig.LIVE_VOLUMETRIC_BELOW_GRID_SCATTERING_MULTIPLIER,
                "网格下方受光比例", "越小，Soul 辅助网格下方的方向光散射越弱。",
                0.0D, 1.0D, 3, Curve.LINEAR
        );

        addCategory("体积颜色");
        addRgb(
                VisualConfig.LIVE_VOLUMETRIC_BASE_FOG_COLOR,
                "基础雾色", "没有直射光时，介质自身保留的颜色。"
        );
        addRgb(
                VisualConfig.LIVE_VOLUMETRIC_SUN_SCATTER_COLOR,
                "太阳散射色", "受方向光照亮部分的附加颜色。"
        );
        addFloat(
                VisualConfig.LIVE_VOLUMETRIC_DITHER_STRENGTH_LSB,
                "体积抖动强度（LSB）",
                "在最终预乘 RGB 写入 8 位场景前加入无纹理静态屏幕空间噪声；不改变体积 Alpha、阴影积分或调试视图。",
                0.0D, 3.0D, 2, Curve.LINEAR
        );

        addCategory("全局体积色调映射");
        addToggle(
                VisualConfig.LIVE_VOLUMETRIC_TONE_MAPPING_ENABLED,
                "启用色调映射",
                "整理全局体积色的亮度范围；不会处理 Soul 体积光的辐亮度。"
        );
        addFloat(
                VisualConfig.LIVE_VOLUMETRIC_TONE_MAPPING_EXPOSURE,
                "曝光", "整体提亮或压暗全局体积颜色。",
                0.25D, 2.5D, 2, Curve.LOGARITHMIC
        );
        addFloat(
                VisualConfig.LIVE_VOLUMETRIC_TONE_MAPPING_CONTRAST,
                "对比度", "围绕支点拉开或压缩全局体积色的明暗差异。",
                0.5D, 2.0D, 2, Curve.LINEAR
        );
        addFloat(
                VisualConfig.LIVE_VOLUMETRIC_TONE_MAPPING_PIVOT,
                "对比度支点", "决定哪一档亮度位于对比度调整的中心。",
                0.05D, 0.8D, 3, Curve.LINEAR
        );

        addCategory("Soul 体积光");
        addFloat(
                VisualConfig.LIVE_SOUL_VOLUMETRIC_RADIUS,
                "发光范围", "决定随当前 Soul 颜色变化的体积光能延伸多远，不改变表面点光半径。",
                0.1D, 4.0D, 2, Curve.LINEAR
        );
        addFloat(
                VisualConfig.LIVE_SOUL_VOLUMETRIC_CORE_RADIUS,
                "中心光团大小", "越大，中心附近衰减越平缓，光团看起来越饱满。",
                0.01D, 1.0D, 3, Curve.LOGARITHMIC
        );
        addFloat(
                VisualConfig.LIVE_SOUL_VOLUMETRIC_CUTOFF_FEATHER,
                "外缘柔和度", "越小边界越硬；越大则从更靠近中心的位置开始渐隐。",
                0.01D, 1.0D, 3, Curve.LINEAR
        );
        addFloat(
                VisualConfig.LIVE_SOUL_VOLUMETRIC_PHASE_G,
                "观察方向偏向", "0 最均匀；正负值会把亮度分别偏向不同的观察夹角。",
                -0.8D, 0.8D, 2, Curve.LINEAR
        );
        addFloat(
                VisualConfig.LIVE_SOUL_VOLUMETRIC_INTENSITY,
                "Soul 体积亮度", "Soul 单次散射的唯一强度；不改变全局雾透明度或表面点光。",
                0.0D, 2_000.0D, 1, Curve.SQUARED
        );
        addFloat(
                VisualConfig.LIVE_SOUL_VOLUMETRIC_RADIANCE_SOFT_CLIP,
                "高亮软压缩阈值", "越低越早压住亮核；越高允许中心更亮、更锐利。",
                0.05D, 2.0D, 3, Curve.LOGARITHMIC
        );
        addFloat(
                VisualConfig.LIVE_SOUL_VOLUMETRIC_GRID_TRANSMISSION,
                "网格透射率", "0 表示网格完全挡住下方 Soul 体积光，1 表示完全透过。",
                0.0D, 1.0D, 3, Curve.LINEAR
        );

        addCategory("Soul 阴影外观");
        addFloat(
                VisualConfig.LIVE_SOUL_VOLUMETRIC_SHADOW_STRENGTH,
                "Soul 光柱明暗反差", "越大，被点阴影遮挡的 Soul 散射越暗，随当前 Soul 颜色变化的暗柱轮廓越明显。",
                0.0D, 1.0D, 3, Curve.LINEAR
        );
        addFloat(
                VisualConfig.LIVE_SOUL_VOLUMETRIC_MINIMUM_RADIANCE_MULTIPLIER,
                "Soul 阴影最低亮度", "限制 Soul 阴影最暗能到什么程度；0 允许完全熄灭，较高值保留更多当前 Soul 颜色的体积光。",
                0.0D, 1.0D, 3, Curve.LINEAR
        );
        addFloat(
                VisualConfig.LIVE_SOUL_VOLUMETRIC_SHADOW_SOURCE_GAIN,
                "Soul 遮挡敏感度", "越大，小型或较薄的遮挡物也更容易拉出清晰的 Soul 暗柱。",
                1.0D, 8.0D, 2, Curve.LOGARITHMIC
        );
        addFloat(
                VisualConfig.LIVE_SOUL_VOLUMETRIC_SHADOW_EXTINCTION_GAIN,
                "Soul 阴影雾厚度", "按 Soul 阴影比例增加共享介质的不透明度；越大，Soul 体积光暗柱越厚实。",
                0.0D, 8.0D, 2, Curve.SQUARED
        );

        addCategory("Soul 体积色调映射");
        addToggle(
                VisualConfig.LIVE_SOUL_VOLUMETRIC_TONE_MAPPING_ENABLED,
                "启用 Soul 色调映射",
                "只整理 Soul 体积辐亮度，不改变平行光体积色或表面点光。"
        );
        addFloat(
                VisualConfig.LIVE_SOUL_VOLUMETRIC_TONE_MAPPING_EXPOSURE,
                "Soul 曝光", "整体提亮或压暗软压缩后的 Soul 体积光。",
                0.25D, 2.5D, 2, Curve.LOGARITHMIC
        );
        addFloat(
                VisualConfig.LIVE_SOUL_VOLUMETRIC_TONE_MAPPING_CONTRAST,
                "Soul 对比度", "围绕支点拉开或压缩 Soul 体积光的明暗差异。",
                0.5D, 2.0D, 2, Curve.LINEAR
        );
        addFloat(
                VisualConfig.LIVE_SOUL_VOLUMETRIC_TONE_MAPPING_PIVOT,
                "Soul 对比度支点", "决定哪一档 Soul 亮度位于对比度调整的中心。",
                0.05D, 0.8D, 3, Curve.LINEAR
        );
    }

    private void addBloomControls() {
        addCategory("Bloom 质量");
        addQuality(
                VisualConfig.LIVE_POST_BLOOM_QUALITY,
                "Bloom 质量",
                "关：不分配专用资源；低/中使用四次双线性首层过滤，高使用九点首层过滤。"
                        + " 层数和扩散范围只由半径与画面尺寸决定。"
        );
        addCategory("材质能量与合成");
        addFloat(
                VisualConfig.LIVE_POST_BLOOM_EMISSIVE_CURVE,
                "自发光曲线", "对线性 _s.b 执行指数曲线；1 保持原值，小于 1 提亮弱发光，大于 1 压低弱发光。",
                0.1D, 4.0D, 2, Curve.LOGARITHMIC
        );
        addFloat(
                VisualConfig.LIVE_POST_BLOOM_INTENSITY,
                "泛光强度",
                "只控制最终柔和合成的辉光增益；_s.b=255 是最大编码能量，不是 HDR 屏幕亮度。",
                0.0D, 16.0D, 2, Curve.SQUARED
        );
        addFloat(
                VisualConfig.LIVE_POST_BLOOM_BLUR_RADIUS_PIXELS,
                "模糊半径（像素）", "连续选择相邻金字塔尺度；低值产生紧凑辉光，高值扩展辉光范围。",
                0.0D, 32.0D, 1, Curve.SQUARED
        );
    }

    private void addEnvironmentControls() {
        addCategory("环境背景");
        addFloat(
                VisualConfig.LIVE_ENVIRONMENT_BACKGROUND_INTENSITY,
                "背景光晕强度",
                "控制稀疏环境光瓣进入柔和压缩的强度；不会改变 Environment Field 或实体光照。",
                0.0D, 4.0D, 2, Curve.SQUARED
        );
        addFloat(
                VisualConfig.LIVE_ENVIRONMENT_BACKGROUND_WIDTH_SCALE,
                "背景光晕宽度",
                "越大光瓣越宽、重叠越柔和；只改变解析求值，不执行图像模糊。",
                0.5D, 3.0D, 2, Curve.LOGARITHMIC
        );
        addFloat(
                VisualConfig.LIVE_ENVIRONMENT_BACKGROUND_BASE_LEVEL,
                "全局环境底色",
                "控制冻结环境全局均值的保留比例；较低值让黑色继续占据画面主体。",
                0.0D, 1.0D, 3, Curve.LINEAR
        );
        addFloat(
                VisualConfig.LIVE_ENVIRONMENT_BACKGROUND_COLOR_STRENGTH,
                "背景色彩保留",
                "0 输出灰度明暗，1 完整保留环境光瓣颜色，中间值连续混合。",
                0.0D, 1.0D, 3, Curve.LINEAR
        );
        addFloat(
                VisualConfig.LIVE_ENVIRONMENT_BACKGROUND_TARGET_LUMA,
                "背景目标亮度",
                "冻结环境的全局亮度等于该值时不补偿；提高后暗场获得更多曝光，仍受内部上下限约束。",
                0.01D, 1.0D, 3, Curve.SQUARED
        );
        addFloat(
                VisualConfig.LIVE_ENVIRONMENT_BACKGROUND_ADAPTATION_STRENGTH,
                "亮度适应强度",
                "0 完全保留固定背景强度，1 完整追随目标亮度；只影响 EnvironmentBackground。",
                0.0D, 1.0D, 3, Curve.LINEAR
        );
        addFloat(
                VisualConfig.LIVE_ENVIRONMENT_BACKGROUND_DITHER_STRENGTH_LSB,
                "背景抖动强度",
                "以 8 位 SDR 的 LSB 为单位加入静态单通道蓝噪声；不影响几何、HUD、Bloom 或环境光。",
                0.0D, 3.0D, 2, Curve.LINEAR
        );

        addCategory("环境光");
        addFloat(
                VisualConfig.LIVE_ENVIRONMENT_LIGHT_INTENSITY,
                "环境光强度",
                "控制 OBJ 环境染色与加色补偿，并直接控制 BattleFrame 染色；不改变方向光、阴影、Soul 或自发光。",
                0.0D, 1.0D, 3, Curve.SQUARED
        );
        addFloat(
                VisualConfig.LIVE_ENVIRONMENT_LIGHT_WIDTH_SCALE,
                "环境光光瓣宽度",
                "越大时方向环境颜色变化越宽缓；影响 OBJ 与 BattleFrame，不影响背景光晕。",
                0.5D, 3.0D, 2, Curve.LOGARITHMIC
        );
        addFloat(
                VisualConfig.LIVE_ENVIRONMENT_LIGHT_BASE_LEVEL,
                "全局环境底色",
                "控制 Environment Field 全局均值进入材质染色的比例；不改变背景底色。",
                0.0D, 1.0D, 3, Curve.LINEAR
        );
        addFloat(
                VisualConfig.LIVE_ENVIRONMENT_LIGHT_COLOR_STRENGTH,
                "环境颜色保留",
                "0 使用灰度环境明暗，1 完整保留环境颜色；不改变背景色彩保留。",
                0.0D, 1.0D, 3, Curve.LINEAR
        );
        addFloat(
                VisualConfig.LIVE_ENVIRONMENT_LIGHT_SPATIAL_VARIATION,
                "位置变化强度",
                "0 只按表面法线采样，1 完整使用冻结代理箱出口方向；不加入随机纹理噪声。",
                0.0D, 1.0D, 3, Curve.LINEAR
        );
    }

    private void showTab(Tab tab) {
        if (this.selectedTab != null) {
            flush();
        }
        this.selectedTab = tab;
        this.clearEntries();
        this.setScrollAmount(0.0D);
        this.addEntry(new TabEntry(), ENTRY_HEIGHT);
        switch (tab) {
            case EFFECT_SWITCHES -> addEffectSwitches();
            case VOLUMETRIC -> addVolumetricControls();
            case BLOOM -> addBloomControls();
            case ENVIRONMENT -> addEnvironmentControls();
        }
    }

    static int panelWidth(int screenWidth) {
        return Math.min(MAX_WIDTH, Math.max(180, screenWidth - OUTER_MARGIN * 2));
    }

    void tick() {
        if (this.saveCountdown > 0 && --this.saveCountdown == 0) {
            VisualConfig.flushLiveValues();
        }
    }

    void flush() {
        this.saveCountdown = 0;
        VisualConfig.flushLiveValues();
    }

    @Override
    public int getRowWidth() {
        return Math.max(1, this.getWidth() - 18);
    }

    @Override
    protected void renderListBackground(GuiGraphics graphics) {
        graphics.fill(this.getX(), this.getY(), this.getRight(), this.getBottom(), 0xB8101114);
    }

    @Override
    protected void renderListSeparators(GuiGraphics graphics) {
        graphics.fill(this.getX(), this.getY(), this.getRight(), this.getY() + 1, 0xFF6A6A6A);
        graphics.fill(this.getX(), this.getBottom() - 1, this.getRight(), this.getBottom(), 0xFF6A6A6A);
    }

    private void addCategory(String title) {
        this.addEntry(new CategoryEntry(title), CATEGORY_HEIGHT);
    }

    private void addFloat(
            VisualConfig.LiveOption<Float> option,
            String label,
            String help,
            double minimum,
            double maximum,
            int decimals,
            Curve curve
    ) {
        double current = VisualConfig.liveValue(option);
        double defaultValue = VisualConfig.liveDefaultValue(option);
        MappedSlider slider = new MappedSlider(
                label, help, minimum, maximum, current, decimals, curve,
                value -> {
                    VisualConfig.setLiveValue(option, (float) value);
                    markChanged();
                },
                this::flush
        );
        this.addEntry(new ControlEntry(
                slider,
                defaultValue,
                label,
                value -> slider.setActualValue(value)
        ));
    }

    private void addToggle(VisualConfig.LiveOption<Boolean> option, String label, String help) {
        ToggleControl toggle = new ToggleControl(option, label, help);
        this.addEntry(new ControlEntry(
                toggle.button,
                VisualConfig.liveDefaultValue(option) ? 1.0D : 0.0D,
                label,
                ignored -> {
                    toggle.set(VisualConfig.liveDefaultValue(option));
                    flush();
                }
        ));
    }

    private void addQuality(
            VisualConfig.LiveOption<VisualConfig.PostQuality> option,
            String label,
            String help
    ) {
        QualityControl quality = new QualityControl(option, label, help);
        VisualConfig.PostQuality defaultValue = VisualConfig.liveDefaultValue(option);
        this.addEntry(new ControlEntry(
                quality.button,
                defaultValue.ordinal(),
                label,
                ignored -> {
                    quality.set(defaultValue);
                    flush();
                }
        ));
    }

    private void addInteger(
            VisualConfig.LiveOption<Integer> option,
            String label,
            String help,
            int minimum,
            int maximum
    ) {
        int current = VisualConfig.liveValue(option);
        int defaultValue = VisualConfig.liveDefaultValue(option);
        MappedSlider slider = new MappedSlider(
                label, help, minimum, maximum, current, 0, Curve.LINEAR,
                value -> {
                    VisualConfig.setLiveValue(option, (int) Math.round(value));
                    markChanged();
                },
                this::flush
        );
        this.addEntry(new ControlEntry(
                slider,
                defaultValue,
                label,
                slider::setActualValue
        ));
    }

    private void addRgb(VisualConfig.LiveOption<Integer> option, String label, String help) {
        addRgbChannel(option, label + " R", help + " 当前滑条控制红色通道。", 16);
        addRgbChannel(option, label + " G", help + " 当前滑条控制绿色通道。", 8);
        addRgbChannel(option, label + " B", help + " 当前滑条控制蓝色通道。", 0);
    }

    private void addRgbChannel(
            VisualConfig.LiveOption<Integer> option,
            String label,
            String help,
            int shift
    ) {
        int currentColor = VisualConfig.liveValue(option);
        int defaultColor = VisualConfig.liveDefaultValue(option);
        MappedSlider slider = new MappedSlider(
                label, help, 0.0D, 255.0D, channel(currentColor, shift), 0, Curve.LINEAR,
                value -> {
                    int color = VisualConfig.liveValue(option) & 0xFFFFFF;
                    int channelMask = 0xFF << shift;
                    int updated = (color & ~channelMask) | (((int) Math.round(value) & 0xFF) << shift);
                    VisualConfig.setLiveValue(option, updated);
                    markChanged();
                },
                this::flush
        );
        this.addEntry(new ControlEntry(
                slider,
                channel(defaultColor, shift),
                label,
                slider::setActualValue
        ));
    }

    private void addLightDirection() {
        CanonicalVec3 current = VisualConfig.GLOBAL_LIGHT_DIRECTION();
        CanonicalVec3 defaults = VisualConfig.GLOBAL_LIGHT_DIRECTION;
        MappedSlider azimuth = new MappedSlider(
                "光线方位角", "水平旋转方向光；它同时影响体积暗柱、普通阴影和表面光照。",
                -180.0D, 180.0D, azimuthDegrees(current), 1, Curve.LINEAR,
                value -> {
                    CanonicalVec3 direction = VisualConfig.GLOBAL_LIGHT_DIRECTION();
                    VisualConfig.setLiveLightDirection(directionFromAngles(value, elevationDegrees(direction)));
                    markChanged();
                },
                this::flush
        );
        this.addEntry(new ControlEntry(
                azimuth,
                azimuthDegrees(defaults),
                "光线方位角",
                azimuth::setActualValue
        ));

        MappedSlider elevation = new MappedSlider(
                "光线高度角", "上下倾斜方向光；低角度通常会形成更长的体积暗柱。",
                -80.0D, 80.0D, elevationDegrees(current), 1, Curve.LINEAR,
                value -> {
                    CanonicalVec3 direction = VisualConfig.GLOBAL_LIGHT_DIRECTION();
                    VisualConfig.setLiveLightDirection(directionFromAngles(azimuthDegrees(direction), value));
                    markChanged();
                },
                this::flush
        );
        this.addEntry(new ControlEntry(
                elevation,
                elevationDegrees(defaults),
                "光线高度角",
                elevation::setActualValue
        ));
    }

    private void markChanged() {
        this.saveCountdown = SAVE_DELAY_TICKS;
    }

    private static int channel(int color, int shift) {
        return color >>> shift & 0xFF;
    }

    private static double azimuthDegrees(CanonicalVec3 direction) {
        return Math.toDegrees(Math.atan2(direction.z(), direction.x()));
    }

    private static double elevationDegrees(CanonicalVec3 direction) {
        double length = Math.sqrt(direction.lengthSquared());
        return Math.toDegrees(Math.asin(Math.clamp(direction.y() / length, -1.0D, 1.0D)));
    }

    private static CanonicalVec3 directionFromAngles(double azimuthDegrees, double elevationDegrees) {
        double azimuth = Math.toRadians(azimuthDegrees);
        double elevation = Math.toRadians(elevationDegrees);
        double horizontal = Math.cos(elevation);
        return new CanonicalVec3(
                horizontal * Math.cos(azimuth),
                Math.sin(elevation),
                horizontal * Math.sin(azimuth)
        );
    }

    private enum Curve {
        LINEAR,
        LOGARITHMIC,
        SQUARED
    }

    private enum Tab {
        EFFECT_SWITCHES("效果开关", "集中启用或关闭辅助网格及各项主要视觉效果。"),
        VOLUMETRIC("体积介质", "调整盒形介质、方向光体积散射与 Soul 体积光。"),
        BLOOM("Bloom", "调整材质自发光曲线、质量、泛光强度与扩散范围。"),
        ENVIRONMENT("环境", "调整全模式稀疏球面高斯背景、静态 SDR 抖动与 Rendered 环境光。");

        private final String label;
        private final String help;

        Tab(String label, String help) {
            this.label = label;
            this.help = help;
        }
    }

    abstract static class TuningEntry extends ContainerObjectSelectionList.Entry<TuningEntry> {
    }

    private final class TabEntry extends TuningEntry {
        private final List<Button> buttons = Arrays.stream(Tab.values())
                .map(this::tabButton)
                .toList();

        private Button tabButton(Tab tab) {
            Button button = Button.builder(Component.literal(tab.label), ignored -> showTab(tab))
                    .bounds(0, 0, 100, 20)
                    .tooltip(Tooltip.create(Component.literal(tab.help)))
                    .build();
            button.active = selectedTab != tab;
            return button;
        }

        @Override
        public void renderContent(
                GuiGraphics graphics, int mouseX, int mouseY, boolean hovering, float partialTick
        ) {
            int gap = 3;
            int width = Math.max(
                    1,
                    (this.getContentWidth() - gap * (this.buttons.size() - 1)) / this.buttons.size()
            );
            for (int index = 0; index < this.buttons.size(); index++) {
                Button button = this.buttons.get(index);
                int x = this.getContentX() + index * (width + gap);
                int actualWidth = index == this.buttons.size() - 1
                        ? this.getContentX() + this.getContentWidth() - x
                        : width;
                button.setRectangle(actualWidth, 20, x, this.getContentY());
                button.render(graphics, mouseX, mouseY, partialTick);
            }
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return this.buttons;
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return this.buttons;
        }
    }

    private final class CategoryEntry extends TuningEntry {
        private final Component title;

        private CategoryEntry(String title) {
            this.title = Component.literal(title);
        }

        @Override
        public void renderContent(
                GuiGraphics graphics, int mouseX, int mouseY, boolean hovering, float partialTick
        ) {
            graphics.drawString(
                    VisualEffectsTuningPanel.this.minecraft.font,
                    this.title,
                    this.getContentX() + 2,
                    this.getContentY() + 2,
                    0xFFFFCC55
            );
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return List.of();
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return List.of();
        }
    }

    private final class ControlEntry extends TuningEntry {
        private final AbstractWidget control;
        private final Button reset;
        private final List<AbstractWidget> widgets;

        private ControlEntry(
                AbstractWidget control,
                double defaultValue,
                String label,
                DoubleConsumer resetAction
        ) {
            this.control = control;
            this.reset = Button.builder(RESET, ignored -> {
                        resetAction.accept(defaultValue);
                        markChanged();
                        flush();
                    })
                    .bounds(0, 0, 22, 20)
                    .tooltip(Tooltip.create(Component.literal("恢复“" + label + "”的源码默认值")))
                    .build();
            this.widgets = List.of(this.control, this.reset);
        }

        @Override
        public void renderContent(
                GuiGraphics graphics, int mouseX, int mouseY, boolean hovering, float partialTick
        ) {
            int x = this.getContentX();
            int y = this.getContentY();
            int resetWidth = 22;
            int controlWidth = Math.max(8, this.getContentWidth() - resetWidth - 3);
            this.control.setRectangle(controlWidth, 20, x, y);
            this.reset.setRectangle(resetWidth, 20, x + controlWidth + 3, y);
            this.control.render(graphics, mouseX, mouseY, partialTick);
            this.reset.render(graphics, mouseX, mouseY, partialTick);
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return this.widgets;
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return this.widgets;
        }
    }

    private final class ToggleControl {
        private final VisualConfig.LiveOption<Boolean> option;
        private final String label;
        private final Button button;

        private ToggleControl(VisualConfig.LiveOption<Boolean> option, String label, String help) {
            this.option = option;
            this.label = label;
            this.button = Button.builder(Component.empty(), ignored -> {
                        set(!VisualConfig.liveValue(this.option));
                        markChanged();
                        flush();
                    })
                    .bounds(0, 0, 100, 20)
                    .tooltip(Tooltip.create(Component.literal(help + "\n点击后立即生效并保存。")))
                    .build();
            updateMessage();
        }

        private void set(boolean value) {
            VisualConfig.setLiveValue(this.option, value);
            updateMessage();
        }

        private void updateMessage() {
            this.button.setMessage(Component.literal(
                    this.label + ": " + (VisualConfig.liveValue(this.option) ? "开" : "关")
            ));
        }
    }

    private final class QualityControl {
        private final VisualConfig.LiveOption<VisualConfig.PostQuality> option;
        private final String label;
        private final Button button;

        private QualityControl(
                VisualConfig.LiveOption<VisualConfig.PostQuality> option,
                String label,
                String help
        ) {
            this.option = option;
            this.label = label;
            this.button = Button.builder(Component.empty(), ignored -> {
                        VisualConfig.PostQuality current = VisualConfig.liveValue(this.option);
                        VisualConfig.PostQuality[] values = VisualConfig.PostQuality.values();
                        set(values[(current.ordinal() + 1) % values.length]);
                        markChanged();
                        flush();
                    })
                    .bounds(0, 0, 100, 20)
                    .tooltip(Tooltip.create(Component.literal(help + "\n点击循环切换并立即保存。")))
                    .build();
            updateMessage();
        }

        private void set(VisualConfig.PostQuality value) {
            VisualConfig.setLiveValue(this.option, value);
            updateMessage();
        }

        private void updateMessage() {
            this.button.setMessage(Component.literal(
                    this.label + ": " + qualityLabel(VisualConfig.liveValue(this.option))
            ));
        }

        private static String qualityLabel(VisualConfig.PostQuality quality) {
            return switch (quality) {
                case OFF -> "关";
                case LOW -> "低";
                case MEDIUM -> "中";
                case HIGH -> "高";
            };
        }
    }

    private static final class MappedSlider extends AbstractSliderButton {
        private final String label;
        private final double minimum;
        private final double maximum;
        private final int decimals;
        private final Curve curve;
        private final DoubleConsumer changed;
        private final Runnable committed;

        private MappedSlider(
                String label,
                String help,
                double minimum,
                double maximum,
                double current,
                int decimals,
                Curve curve,
                DoubleConsumer changed,
                Runnable committed
        ) {
            super(0, 0, 100, 20, Component.empty(), normalize(current, minimum, maximum, curve));
            this.label = label;
            this.minimum = minimum;
            this.maximum = maximum;
            this.decimals = decimals;
            this.curve = curve;
            this.changed = changed;
            this.committed = committed;
            this.setTooltip(Tooltip.create(Component.literal(
                    help + "\n范围：" + format(minimum, decimals) + " ～ " + format(maximum, decimals)
                            + "；拖动后下一帧生效。"
            )));
            updateMessage();
        }

        private void setActualValue(double actualValue) {
            this.value = normalize(actualValue, this.minimum, this.maximum, this.curve);
            applyValue();
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            this.setMessage(Component.literal(
                    this.label + ": " + format(actualValue(), this.decimals)
            ));
        }

        @Override
        protected void applyValue() {
            this.changed.accept(actualValue());
        }

        @Override
        public void onRelease(MouseButtonEvent event) {
            super.onRelease(event);
            this.committed.run();
        }

        private double actualValue() {
            return denormalize(this.value, this.minimum, this.maximum, this.curve);
        }

        private static double normalize(double actual, double minimum, double maximum, Curve curve) {
            double clamped = Math.clamp(actual, minimum, maximum);
            return switch (curve) {
                case LINEAR -> (clamped - minimum) / (maximum - minimum);
                case SQUARED -> Math.sqrt((clamped - minimum) / (maximum - minimum));
                case LOGARITHMIC -> Math.log(clamped / minimum) / Math.log(maximum / minimum);
            };
        }

        private static double denormalize(double normalized, double minimum, double maximum, Curve curve) {
            return switch (curve) {
                case LINEAR -> minimum + (maximum - minimum) * normalized;
                case SQUARED -> minimum + (maximum - minimum) * normalized * normalized;
                case LOGARITHMIC -> minimum * Math.pow(maximum / minimum, normalized);
            };
        }

        private static String format(double value, int decimals) {
            return String.format(Locale.ROOT, "%." + decimals + "f", value);
        }
    }
}
