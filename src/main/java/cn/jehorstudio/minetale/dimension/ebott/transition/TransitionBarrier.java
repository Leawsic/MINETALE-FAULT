package cn.jehorstudio.minetale.dimension.ebott.transition;

import cn.jehorstudio.minetale.dimension.ebott.transition.seam.DimensionSeam;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

// 纯结界碰撞几何，实体状态与通行决策由调用方提供。
public final class TransitionBarrier {
    private static final double THICKNESS = 1.0 / 16.0;

    private TransitionBarrier() {
    }

    public static VoxelShape collisionShape(DimensionSeam seam, AABB queryBox) {
        DimensionSeam.CircularSeamAperture aperture = seam.aperture();
        return collisionShape(
                seam.sourceCenterX(),
                seam.sourceCenterZ(),
                seam.sourcePlaneY(),
                aperture.centerOffsetX(),
                aperture.centerOffsetZ(),
                aperture.radius(),
                queryBox
        );
    }

    public static VoxelShape collisionShape(
            double sourceCenterX,
            double sourceCenterZ,
            double sourcePlaneY,
            double apertureCenterOffsetX,
            double apertureCenterOffsetZ,
            double apertureRadius,
            AABB queryBox
    ) {
        double centerX = sourceCenterX + apertureCenterOffsetX;
        double centerZ = sourceCenterZ + apertureCenterOffsetZ;
        double minX = centerX - apertureRadius;
        double minY = sourcePlaneY - THICKNESS;
        double minZ = centerZ - apertureRadius;
        double maxX = centerX + apertureRadius;
        double maxY = sourcePlaneY;
        double maxZ = centerZ + apertureRadius;
        if (queryBox.maxX <= minX || queryBox.minX >= maxX
                || queryBox.maxY <= minY || queryBox.minY >= maxY
                || queryBox.maxZ <= minZ || queryBox.minZ >= maxZ) {
            return Shapes.empty();
        }
        return Shapes.create(new AABB(minX, minY, minZ, maxX, maxY, maxZ));
    }
}
