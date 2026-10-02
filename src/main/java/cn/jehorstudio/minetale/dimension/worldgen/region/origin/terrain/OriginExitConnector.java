package cn.jehorstudio.minetale.dimension.worldgen.region.origin.terrain;

import cn.jehorstudio.minetale.dimension.worldgen.CakeConfig;
import cn.jehorstudio.minetale.dimension.worldgen.RainbowCakeModel;
import cn.jehorstudio.minetale.dimension.worldgen.region.origin.settings.OriginSettings;

// 将 ORIGIN 唯一出口的空气体积连续延伸到首段 Transition Tunnel。
public final class OriginExitConnector {
    private OriginExitConnector() {
    }

    public static boolean isFirstTransitionTunnel(RainbowCakeModel.CakeSample sample) {
        return sample.region() == RainbowCakeModel.CakeRegion.TRANSITION_TUNNEL
                && sample.lineProgress() >= 0.0
                && sample.lineProgress() < CakeConfig.defaults().transitionTunnelLength();
    }

    public static boolean shouldCarve(RainbowCakeModel.CakeSample sample, int y) {
        return isFirstTransitionTunnel(sample)
                && Math.abs(sample.lateralDistance()) <= OriginSettings.EXIT_HALF_WIDTH
                && y > OriginSettings.FLOOR_Y
                && y <= OriginSettings.EXIT_CEILING_Y;
    }
}
