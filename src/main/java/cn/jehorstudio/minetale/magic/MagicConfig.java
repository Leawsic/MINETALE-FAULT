package cn.jehorstudio.minetale.magic;

// 施法与激光视觉的内部参数。
public final class MagicConfig {
    // 新建魔法状态的默认值
    public static final double MAX_MANA = 100.0;
    public static final double REGEN = 1.0;
    public static final double COST_MULTIPLIER = 1.0;
    public static final double ACTIVATION_COST = 5.0;
    public static final double POWER = 20.0;
    public static final int CHARGE_TICKS = 60;
    public static final int WINDUP_TICKS = 30;
    // 完全张嘴之后、开始收口之前的稳定激光时长。
    public static final int BEAM_TICKS = 30;
    public static final int CLOSE_TICKS = 10;
    public static final double LOCK_RANGE = 96.0;
    public static final double MIN_SCALE = 0.5;
    public static final double MAX_SCALE = 10.0;
    public static final double MIN_RADIUS = 0.25;
    public static final double MAX_RADIUS = 4.0;
    public static final double MIN_DAMAGE = 0.1;
    public static final double MAX_DAMAGE = 1.0;
    public static final double KARMA_CAP = 4.0;

    // 视觉参数以激光半径 1 格为基准；实际范围和位移随当前半径缩放。
    public static final float BEAM_DISTORTION_RADIUS = 2.0F;
    public static final float BEAM_DISTORTION_STRENGTH = 1.0F;
    public static final float BEAM_DISTORTION_FALLOFF = 2.0F;
    public static final float BEAM_DISTORTION_WAVE = 1.0F;
    public static final float BEAM_DISTORTION_SPEED = 1.5707F;

    private MagicConfig() {}
}
