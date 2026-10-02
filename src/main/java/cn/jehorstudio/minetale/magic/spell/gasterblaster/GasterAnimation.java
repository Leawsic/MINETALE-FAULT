package cn.jehorstudio.minetale.magic.spell.gasterblaster;

import com.google.gson.JsonParser;
import software.bernie.geckolib.animation.Animation;
import software.bernie.geckolib.animation.keyframe.AnimationPoint;
import software.bernie.geckolib.animation.keyframe.Keyframe;
import software.bernie.geckolib.loading.json.typeadapter.KeyFramesAdapter;
import software.bernie.geckolib.loading.math.MathValue;
import software.bernie.geckolib.loading.object.BakedAnimations;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

// 直接读取炮的动画资源，并复用 GeckoLib 的烘焙器和 EasingType
// 这里只采样固定资源中的常量下颌旋转；服务器不接受客户端资源包改变命中时间轴。
final class GasterAnimation {
    private static final BakedAnimations ANIMATIONS = load();
    private static final Animation SHOOT = ANIMATIONS.getAnimation("shoot");
    private static final Animation CLOSE = ANIMATIONS.getAnimation("close");
    private static final List<Keyframe<MathValue>> OPEN_JAW = jaw(SHOOT);
    private static final List<Keyframe<MathValue>> CLOSE_JAW = jaw(CLOSE);
    private static final double CLOSED_ANGLE = OPEN_JAW.getFirst().startValue().get(null);
    private static final double OPEN_ANGLE = OPEN_JAW.getLast().endValue().get(null);

    static double length(boolean closing) { return (closing ? CLOSE : SHOOT).length(); }

    static double openingEnd() {
        return OPEN_JAW.stream().mapToDouble(Keyframe::length).sum();
    }

    static double openingStart() {
        double time = 0;
        for (var key : OPEN_JAW) {
            if (key.startValue().get(null) != key.endValue().get(null)) return time;
            time += key.length();
        }
        throw new IllegalStateException("shoot 缺少张嘴动作");
    }

    static float aperture(boolean closing, double tick) {
        List<Keyframe<MathValue>> frames = closing ? CLOSE_JAW : OPEN_JAW;
        double elapsed = 0;
        for (var key : frames) {
            elapsed += key.length();
            if (elapsed > tick) {
                var point = new AnimationPoint(key, tick - elapsed + key.length(), key.length(),
                        key.startValue().get(null), key.endValue().get(null));
                return normalize(key.easingType().apply(point, null));
            }
        }
        return normalize(frames.getLast().endValue().get(null));
    }

    private static float normalize(double angle) {
        return (float) Math.clamp((angle - CLOSED_ANGLE) / (OPEN_ANGLE - CLOSED_ANGLE), 0, 1);
    }

    private static List<Keyframe<MathValue>> jaw(Animation animation) {
        if (animation == null || !animation.usedVariables().isEmpty()) {
            throw new IllegalStateException("炮动画必须存在，且仅使用常量关键帧");
        }
        return List.copyOf(Arrays.stream(animation.boneAnimations()).filter(b -> b.boneName().equals("jaw"))
                .findFirst().orElseThrow().rotationKeyFrames().xKeyframes());
    }

    private static BakedAnimations load() {
        String path = "/assets/minetale/geckolib/animations/magic/gaster_blaster.animation.json";
        try (var stream = GasterAnimation.class.getResourceAsStream(path)) {
            if (stream == null) throw new IllegalStateException("缺少炮动画资源: " + path);
            var json = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            return KeyFramesAdapter.GEO_GSON.fromJson(json.get("animations"), BakedAnimations.class);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("无法读取炮动画", exception);
        }
    }
}
