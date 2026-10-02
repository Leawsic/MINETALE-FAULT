package cn.jehorstudio.minetale.dimension.ebott.entrance.campfire.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.CampfireSmokeParticle;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.RandomSource;

// 原版营火烟雾速度，延长寿命以覆盖约 200 格上升距离。
public final class MysteriousCampfireSmokeParticle extends CampfireSmokeParticle {
    private static final double TARGET_RISE_DISTANCE = 200.0;
    private static final int MAX_LIFETIME = 10_000;

    private MysteriousCampfireSmokeParticle(
            ClientLevel level,
            double x,
            double y,
            double z,
            double xSpeed,
            double ySpeed,
            double zSpeed,
            boolean signalSmoke,
            float alpha,
            TextureAtlasSprite sprite
    ) {
        super(level, x, y, z, xSpeed, ySpeed, zSpeed, signalSmoke, sprite);
        setAlpha(alpha);
        setLifetime(lifetimeForRiseDistance(this.yd, this.gravity));
    }

    private static int lifetimeForRiseDistance(double initialVerticalSpeed, double gravity) {
        double height = 0.0;
        double verticalSpeed = initialVerticalSpeed;
        for (int ticks = 1; ticks <= MAX_LIFETIME; ticks++) {
            verticalSpeed -= gravity;
            height += verticalSpeed;
            if (height >= TARGET_RISE_DISTANCE) {
                return ticks;
            }
        }
        return MAX_LIFETIME;
    }

    public static final class CosyProvider implements ParticleProvider<SimpleParticleType> {
        private final SpriteSet sprites;

        public CosyProvider(SpriteSet sprites) {
            this.sprites = sprites;
        }

        @Override
        public Particle createParticle(
                SimpleParticleType particleType,
                ClientLevel level,
                double x,
                double y,
                double z,
                double xSpeed,
                double ySpeed,
                double zSpeed,
                RandomSource random
        ) {
            return new MysteriousCampfireSmokeParticle(
                    level, x, y, z, xSpeed, ySpeed, zSpeed, false, 0.9F, sprites.get(random)
            );
        }
    }

    public static final class SignalProvider implements ParticleProvider<SimpleParticleType> {
        private final SpriteSet sprites;

        public SignalProvider(SpriteSet sprites) {
            this.sprites = sprites;
        }

        @Override
        public Particle createParticle(
                SimpleParticleType particleType,
                ClientLevel level,
                double x,
                double y,
                double z,
                double xSpeed,
                double ySpeed,
                double zSpeed,
                RandomSource random
        ) {
            return new MysteriousCampfireSmokeParticle(
                    level, x, y, z, xSpeed, ySpeed, zSpeed, true, 0.95F, sprites.get(random)
            );
        }
    }
}
