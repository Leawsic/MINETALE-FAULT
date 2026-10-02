package cn.jehorstudio.minetale.dimension.ebott.entrance;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;

// 在连续局部坐标中定义山侧洞口，再光栅化到方块中心。
final class CaveEntranceMouth {
    private CaveEntranceMouth() {
    }

    // 投影方块中心而非整数角点
    static void open(ChunkAccess chunk, CaveEntranceGenerator.Placement placement) {
        CaveEntranceGenerator.TunnelCurve curve = CaveEntranceGenerator.tunnelCurve(placement);
        CaveEntranceGenerator.CurvePoint before = curve.sample(0.98);
        CaveEntranceGenerator.CurvePoint end = curve.sample(1.0);
        double directionX = end.x() - before.x();
        double directionZ = end.z() - before.z();
        double directionLength = StrictMath.hypot(directionX, directionZ);
        directionX /= directionLength;
        directionZ /= directionLength;
        double lateralX = -directionZ;
        double lateralZ = directionX;
        int horizontalReach = Mth.ceil(
                StrictMath.abs(directionX) * (CaveEntranceGenerator.MOUTH_OUTWARD_LENGTH + 1.0)
                        + StrictMath.abs(lateralX) * CaveEntranceGenerator.MOUTH_HORIZONTAL_RADIUS
        ) + 1;
        int depthReach = Mth.ceil(
                StrictMath.abs(directionZ) * (CaveEntranceGenerator.MOUTH_OUTWARD_LENGTH + 1.0)
                        + StrictMath.abs(lateralZ) * CaveEntranceGenerator.MOUTH_HORIZONTAL_RADIUS
        ) + 1;
        ChunkPos chunkPos = chunk.getPos();
        int minX = Math.max(chunkPos.getMinBlockX(), placement.mouthX() - horizontalReach);
        int maxX = Math.min(chunkPos.getMaxBlockX(), placement.mouthX() + horizontalReach);
        int minZ = Math.max(chunkPos.getMinBlockZ(), placement.mouthZ() - depthReach);
        int maxZ = Math.min(chunkPos.getMaxBlockZ(), placement.mouthZ() + depthReach);
        int minY = Math.max(
                chunk.getMinY(),
                placement.mouthY() - Mth.ceil(CaveEntranceGenerator.MOUTH_VERTICAL_RADIUS)
        );
        int maxY = Math.min(
                chunk.getMaxY(),
                placement.mouthY() + Mth.ceil(CaveEntranceGenerator.MOUTH_VERTICAL_RADIUS)
        );
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = minX; x <= maxX; x++) {
            double deltaX = x - placement.mouthX();
            for (int z = minZ; z <= maxZ; z++) {
                double deltaZ = z - placement.mouthZ();
                double outward = deltaX * directionX + deltaZ * directionZ;
                if (outward < -5.5
                        || outward > CaveEntranceGenerator.MOUTH_OUTWARD_LENGTH + 0.5) {
                    continue;
                }
                double lateral = deltaX * lateralX + deltaZ * lateralZ;
                for (int y = minY; y <= maxY; y++) {
                    double vertical = y - placement.mouthY();
                    double ellipse = lateral * lateral
                            / (CaveEntranceGenerator.MOUTH_HORIZONTAL_RADIUS
                            * CaveEntranceGenerator.MOUTH_HORIZONTAL_RADIUS)
                            + vertical * vertical
                            / (CaveEntranceGenerator.MOUTH_VERTICAL_RADIUS
                            * CaveEntranceGenerator.MOUTH_VERTICAL_RADIUS);
                    if (ellipse < 1.0) {
                        setCaveAir(chunk, cursor.set(x, y, z));
                    }
                }
            }
        }
    }

    private static void setCaveAir(ChunkAccess chunk, BlockPos position) {
        chunk.removeBlockEntity(position);
        chunk.setBlockState(position, Blocks.CAVE_AIR.defaultBlockState());
    }
}
