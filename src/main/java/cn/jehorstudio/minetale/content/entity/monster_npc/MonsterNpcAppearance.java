package cn.jehorstudio.minetale.content.entity.monster_npc;

import cn.jehorstudio.minetale.MineTale;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityDimensions;
import software.bernie.geckolib.animation.RawAnimation;

// 匿名居民样本的模型、动画、纹理与尺寸组合。
public enum MonsterNpcAppearance {
    ADULT_CAMEL("adult", "camel", 2.05F, 4.90F, 3.91F, 21.0F),
    ADULT_CAT("adult", "cat", 1.30F, 1.70F, 1.31F, 6.0F),
    ADULT_COW("adult", "cow", 1.25F, 2.40F, 1.88F, 12.0F),
    ADULT_DONKEY("adult", "donkey", 1.75F, 3.25F, 2.63F, 11.0F),
    ADULT_FOX("adult", "fox", 1.05F, 1.50F, 1.06F, 6.0F),
    ADULT_GOAT("adult", "goat", 1.45F, 2.90F, 1.44F, 10.0F),
    ADULT_HORSE("adult", "horse", 1.75F, 3.05F, 2.60F, 11.0F),
    ADULT_LLAMA("adult", "llama", 1.40F, 3.70F, 1.97F, 14.0F),
    ADULT_MULE("adult", "mule", 1.90F, 3.25F, 2.49F, 11.0F),
    ADULT_PANDA("adult", "panda", 1.95F, 2.95F, 2.19F, 9.0F),
    ADULT_PIG("adult", "pig", 1.15F, 1.85F, 1.38F, 6.0F),
    ADULT_POLAR_BEAR("adult", "polar_bear", 1.40F, 2.70F, 2.25F, 10.0F),
    ADULT_SHEEP("adult", "sheep", 1.00F, 2.10F, 1.75F, 12.0F),
    ADULT_WOLF("adult", "wolf", 1.00F, 2.00F, 1.44F, 8.0F),
    BABY_CAMEL("baby", "camel", 1.00F, 2.65F, 1.81F, 13.0F),
    BABY_CAT("baby", "cat", 0.65F, 0.95F, 0.56F, 3.0F),
    BABY_COW("baby", "cow", 0.90F, 1.50F, 1.12F, 6.0F),
    BABY_DONKEY("baby", "donkey", 1.25F, 2.50F, 1.84F, 8.0F),
    BABY_FOX("baby", "fox", 0.85F, 0.95F, 0.56F, 3.0F),
    BABY_GOAT("baby", "goat", 0.65F, 1.20F, 0.88F, 5.0F),
    BABY_HORSE("baby", "horse", 1.25F, 2.40F, 1.44F, 9.0F),
    BABY_LLAMA("baby", "llama", 0.90F, 2.10F, 1.31F, 8.0F),
    BABY_MULE("baby", "mule", 1.35F, 2.40F, 1.38F, 8.0F),
    BABY_PANDA("baby", "panda", 0.95F, 1.25F, 0.88F, 3.0F),
    BABY_PIG("baby", "pig", 0.70F, 1.10F, 0.75F, 3.0F),
    BABY_POLAR_BEAR("baby", "polar_bear", 0.90F, 1.25F, 0.94F, 3.0F),
    BABY_SHEEP("baby", "sheep", 0.65F, 1.15F, 0.88F, 5.0F),
    BABY_WOLF("baby", "wolf", 0.70F, 1.10F, 0.69F, 3.0F);

    private static final MonsterNpcAppearance[] VALUES = values();
    private static final double MODEL_UNITS_PER_BLOCK = 16.0D;
    private static final double TICKS_PER_SECOND = 20.0D;
    private static final double WALK_CYCLE_SECONDS = 1.0D;
    private static final double WALK_SWING_DEGREES = 28.0D;
    private static final float CROWD_RADIUS_BASE = 0.18F;
    private static final float CROWD_RADIUS_DIMENSION_WEIGHT = 0.24F;

    private final ResourceLocation geckoAsset;
    private final ResourceLocation texture;
    private final EntityDimensions dimensions;
    private final String idleAnimationName;
    private final String walkAnimationName;
    private final RawAnimation idleAnimation;
    private final RawAnimation walkAnimation;
    private final double walkingSpeedBlocksPerTick;

    MonsterNpcAppearance(
            String age,
            String species,
            float width,
            float height,
            float eyeHeight,
            float legLengthModelUnits
    ) {
        this.geckoAsset = ResourceLocation.fromNamespaceAndPath(
                MineTale.MODID,
                "monster/" + age + "/" + species
        );
        this.texture = ResourceLocation.fromNamespaceAndPath(
                MineTale.MODID,
                "textures/entity/monster/" + age + "/" + species + ".png"
        );
        this.dimensions = EntityDimensions.scalable(width, height).withEyeHeight(eyeHeight);
        this.idleAnimationName = "animation." + species + "." + age + ".idle";
        this.walkAnimationName = "animation." + species + "." + age + ".walk";
        this.idleAnimation = RawAnimation.begin().thenLoop(this.idleAnimationName);
        this.walkAnimation = RawAnimation.begin().thenLoop(this.walkAnimationName);
        this.walkingSpeedBlocksPerTick = calculateWalkingSpeed(legLengthModelUnits);
    }

    public int id() {
        return ordinal();
    }

    public ResourceLocation geckoAsset() {
        return this.geckoAsset;
    }

    public ResourceLocation modelResource() {
        return this.geckoAsset.withPrefix("entity/");
    }

    public ResourceLocation animationResource() {
        return this.geckoAsset.withPrefix("entity/");
    }

    public ResourceLocation textureResource() {
        return this.texture;
    }

    public EntityDimensions dimensions() {
        return this.dimensions;
    }

    public RawAnimation animation(boolean walking) {
        return walking ? this.walkAnimation : this.idleAnimation;
    }

    public String animationName(boolean walking) {
        return walking ? this.walkAnimationName : this.idleAnimationName;
    }

    // 这里只返回实体半径；人际间距由 crowd simulation 另行叠加。
    public float collisionRadius() {
        return this.dimensions.width() * 0.5F;
    }

    // 远景 crowd 半径按统一比例压缩但保留相对体型，personal space 再补足观感间距。
    public float crowdCollisionRadius() {
        return CROWD_RADIUS_BASE + collisionRadius() * CROWD_RADIUS_DIMENSION_WEIGHT;
    }

    public double walkingSpeedBlocksPerTick() {
        return this.walkingSpeedBlocksPerTick;
    }

    public float walkingSpeedBlocksPerSecond() {
        return (float) (this.walkingSpeedBlocksPerTick * TICKS_PER_SECOND);
    }

    public static int count() {
        return VALUES.length;
    }

    public static MonsterNpcAppearance byId(int id) {
        return id >= 0 && id < VALUES.length ? VALUES[id] : VALUES[0];
    }

    public static MonsterNpcAppearance fromResidentId(long residentId) {
        long mixed = residentId ^ (residentId >>> 33);
        mixed *= 0xff51afd7ed558ccdL;
        mixed ^= mixed >>> 33;
        return VALUES[Math.floorMod(mixed, VALUES.length)];
    }

    private static double calculateWalkingSpeed(double legLengthModelUnits) {
        double legLengthBlocks = legLengthModelUnits / MODEL_UNITS_PER_BLOCK;
        double stridePerCycle = 4.0D * legLengthBlocks * Math.sin(Math.toRadians(WALK_SWING_DEGREES));
        return stridePerCycle / (WALK_CYCLE_SECONDS * TICKS_PER_SECOND);
    }
}
