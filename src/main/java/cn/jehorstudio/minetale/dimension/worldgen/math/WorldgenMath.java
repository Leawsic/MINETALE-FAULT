package cn.jehorstudio.minetale.dimension.worldgen.math;

import net.minecraft.util.Mth;

public final class WorldgenMath {
    private WorldgenMath() {}

    public static long channelSeed(long worldgenSeed, int channelSalt) {
        long h = worldgenSeed ^ 0x9E3779B97F4A7C15L;
        h ^= (long) channelSalt * 0xBF58476D1CE4E5B9L;
        h = mix64(h);
        return h;
    }

    public static int hash(int x, int y, int z) {
        int h = x * 734287 ^ y * 912931 ^ z * 1299827 ^ 0x9E3779B9;
        h ^= h >>> 16;
        h *= 0x7feb352d;
        h ^= h >>> 15;
        h *= 0x846ca68b;
        h ^= h >>> 16;
        return h;
    }

    public static int hash(int x, int y, int z, long channelSeed) {
        long h = channelSeed;
        h ^= (long) x * 0x632BE59BD9B4E019L;
        h ^= (long) y * 0x9E3779B97F4A7C15L;
        h ^= (long) z * 0x85157AF5L;
        return (int) mix64(h);
    }

    public static double unitNoise(int x, int y, int z, int salt) {
        int h = hash(x + salt * 31, y - salt * 17, z + salt * 13);
        return (h & 0xFFFF) / 65535.0;
    }

    public static double unitNoise(int x, int y, int z, long channelSeed) {
        int h = hash(x, y, z, channelSeed);
        return (h & 0xFFFF) / 65535.0;
    }

    public static double signedNoise(int x, int y, int z, int salt) {
        return unitNoise(x, y, z, salt) * 2.0 - 1.0;
    }

    public static double signedNoise(int x, int y, int z, long channelSeed) {
        return unitNoise(x, y, z, channelSeed) * 2.0 - 1.0;
    }

    public static double hashToUnit(int x, int y, int z, int salt) {
        int h = hash(x + salt * 19, y - salt * 23, z + salt * 29);
        return (h & 0xFFFF) / 65535.0;
    }

    public static double hashToUnit(int x, int y, int z, long channelSeed) {
        int h = hash(x, y, z, channelSeed);
        return (h & 0xFFFF) / 65535.0;
    }

    public static double valueNoise1d(double x, int salt) {
        int x0 = Mth.floor(x);
        int x1 = x0 + 1;

        double tx = smoothstep(x - x0);

        double a = hashToUnit(x0, 0, 0, salt);
        double b = hashToUnit(x1, 0, 0, salt);

        return Mth.lerp(tx, a, b);
    }

    public static double valueNoise1d(double x, long channelSeed) {
        int x0 = Mth.floor(x);
        int x1 = x0 + 1;

        double tx = smoothstep(x - x0);

        double a = hashToUnit(x0, 0, 0, channelSeed);
        double b = hashToUnit(x1, 0, 0, channelSeed);

        return Mth.lerp(tx, a, b);
    }

    public static double signedValueNoise1d(double x, int salt) {
        return valueNoise1d(x, salt) * 2.0 - 1.0;
    }

    public static double signedValueNoise1d(double x, long channelSeed) {
        return valueNoise1d(x, channelSeed) * 2.0 - 1.0;
    }

    public static double valueNoise2d(double x, double z, int salt) {
        int x0 = Mth.floor(x);
        int z0 = Mth.floor(z);

        int x1 = x0 + 1;
        int z1 = z0 + 1;

        double tx = smoothstep(x - x0);
        double tz = smoothstep(z - z0);

        double c00 = hashToUnit(x0, 0, z0, salt);
        double c10 = hashToUnit(x1, 0, z0, salt);
        double c01 = hashToUnit(x0, 0, z1, salt);
        double c11 = hashToUnit(x1, 0, z1, salt);

        double xA = Mth.lerp(tx, c00, c10);
        double xB = Mth.lerp(tx, c01, c11);

        return Mth.lerp(tz, xA, xB);
    }

    public static double valueNoise2d(double x, double z, long channelSeed) {
        int x0 = Mth.floor(x);
        int z0 = Mth.floor(z);

        int x1 = x0 + 1;
        int z1 = z0 + 1;

        double tx = smoothstep(x - x0);
        double tz = smoothstep(z - z0);

        double c00 = hashToUnit(x0, 0, z0, channelSeed);
        double c10 = hashToUnit(x1, 0, z0, channelSeed);
        double c01 = hashToUnit(x0, 0, z1, channelSeed);
        double c11 = hashToUnit(x1, 0, z1, channelSeed);

        double xA = Mth.lerp(tx, c00, c10);
        double xB = Mth.lerp(tx, c01, c11);

        return Mth.lerp(tz, xA, xB);
    }

    public static double signedValueNoise2d(double x, double z, int salt) {
        return valueNoise2d(x, z, salt) * 2.0 - 1.0;
    }

    public static double signedValueNoise2d(double x, double z, long channelSeed) {
        return valueNoise2d(x, z, channelSeed) * 2.0 - 1.0;
    }

    public static double signedValueNoise3d(double x, double y, double z, int salt) {
        int x0 = Mth.floor(x);
        int y0 = Mth.floor(y);
        int z0 = Mth.floor(z);

        int x1 = x0 + 1;
        int y1 = y0 + 1;
        int z1 = z0 + 1;

        double tx = smoothstep(x - x0);
        double ty = smoothstep(y - y0);
        double tz = smoothstep(z - z0);

        double c000 = hashToUnit(x0, y0, z0, salt);
        double c100 = hashToUnit(x1, y0, z0, salt);
        double c010 = hashToUnit(x0, y1, z0, salt);
        double c110 = hashToUnit(x1, y1, z0, salt);
        double c001 = hashToUnit(x0, y0, z1, salt);
        double c101 = hashToUnit(x1, y0, z1, salt);
        double c011 = hashToUnit(x0, y1, z1, salt);
        double c111 = hashToUnit(x1, y1, z1, salt);

        double x00 = Mth.lerp(tx, c000, c100);
        double x10 = Mth.lerp(tx, c010, c110);
        double x01 = Mth.lerp(tx, c001, c101);
        double x11 = Mth.lerp(tx, c011, c111);

        double y0v = Mth.lerp(ty, x00, x10);
        double y1v = Mth.lerp(ty, x01, x11);

        return Mth.lerp(tz, y0v, y1v) * 2.0 - 1.0;
    }

    public static double signedValueNoise3d(double x, double y, double z, long channelSeed) {
        int x0 = Mth.floor(x);
        int y0 = Mth.floor(y);
        int z0 = Mth.floor(z);

        int x1 = x0 + 1;
        int y1 = y0 + 1;
        int z1 = z0 + 1;

        double tx = smoothstep(x - x0);
        double ty = smoothstep(y - y0);
        double tz = smoothstep(z - z0);

        double c000 = hashToUnit(x0, y0, z0, channelSeed);
        double c100 = hashToUnit(x1, y0, z0, channelSeed);
        double c010 = hashToUnit(x0, y1, z0, channelSeed);
        double c110 = hashToUnit(x1, y1, z0, channelSeed);
        double c001 = hashToUnit(x0, y0, z1, channelSeed);
        double c101 = hashToUnit(x1, y0, z1, channelSeed);
        double c011 = hashToUnit(x0, y1, z1, channelSeed);
        double c111 = hashToUnit(x1, y1, z1, channelSeed);

        double x00 = Mth.lerp(tx, c000, c100);
        double x10 = Mth.lerp(tx, c010, c110);
        double x01 = Mth.lerp(tx, c001, c101);
        double x11 = Mth.lerp(tx, c011, c111);

        double y0v = Mth.lerp(ty, x00, x10);
        double y1v = Mth.lerp(ty, x01, x11);

        return Mth.lerp(tz, y0v, y1v) * 2.0 - 1.0;
    }

    public static double cellularDistance2d(double x, double z, int salt) {
        int cellX = Mth.floor(x);
        int cellZ = Mth.floor(z);

        double best = Double.MAX_VALUE;

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int cx = cellX + dx;
                int cz = cellZ + dz;

                double featureX = cx + hashToUnit(cx, 0, cz, salt);
                double featureZ = cz + hashToUnit(cx, 0, cz, salt + 1);

                double ox = x - featureX;
                double oz = z - featureZ;
                double distance = Math.sqrt(ox * ox + oz * oz);

                best = Math.min(best, distance);
            }
        }

        return best;
    }

    private static long mix64(long value) {
        value ^= value >>> 30;
        value *= 0xBF58476D1CE4E5B9L;
        value ^= value >>> 27;
        value *= 0x94D049BB133111EBL;
        value ^= value >>> 31;
        return value;
    }

    public static double smoothstep(double t) {
        t = Mth.clamp(t, 0.0, 1.0);
        return t * t * (3.0 - 2.0 * t);
    }

    public static double smooth(double value) {
        value = Mth.clamp(value, 0.0, 1.0);
        return value * value * (3.0 - 2.0 * value);
    }

    public static double distanceToSegment3d(
            double px,
            double py,
            double pz,
            double ax,
            double ay,
            double az,
            double bx,
            double by,
            double bz
    ) {
        double vx = bx - ax;
        double vy = by - ay;
        double vz = bz - az;

        double wx = px - ax;
        double wy = py - ay;
        double wz = pz - az;

        double len2 = vx * vx + vy * vy + vz * vz;

        if (len2 <= 1.0E-6) {
            return distance3d(px, py, pz, ax, ay, az);
        }

        double t = (wx * vx + wy * vy + wz * vz) / len2;
        t = Mth.clamp(t, 0.0, 1.0);

        double cx = ax + vx * t;
        double cy = ay + vy * t;
        double cz = az + vz * t;

        return distance3d(px, py, pz, cx, cy, cz);
    }

    public static double distanceToSegment2d(
            double px,
            double pz,
            double ax,
            double az,
            double bx,
            double bz
    ) {
        double vx = bx - ax;
        double vz = bz - az;

        double wx = px - ax;
        double wz = pz - az;

        double len2 = vx * vx + vz * vz;

        if (len2 <= 1.0E-6) {
            double dx = px - ax;
            double dz = pz - az;
            return Math.sqrt(dx * dx + dz * dz);
        }

        double t = (wx * vx + wz * vz) / len2;
        t = Mth.clamp(t, 0.0, 1.0);

        double cx = ax + vx * t;
        double cz = az + vz * t;

        double dx = px - cx;
        double dz = pz - cz;

        return Math.sqrt(dx * dx + dz * dz);
    }

    public static double distance3d(
            double x1,
            double y1,
            double z1,
            double x2,
            double y2,
            double z2
    ) {
        double dx = x1 - x2;
        double dy = y1 - y2;
        double dz = z1 - z2;

        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
