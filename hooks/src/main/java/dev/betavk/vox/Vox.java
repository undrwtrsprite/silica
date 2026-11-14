package dev.betavk.vox;

/**
 * One voxel packed into an int: block id (bits 0-7), metadata (8-11), sky light (12-15), block light (16-19), biome tint index (20-27: temperature
 * in the high nibble, humidity in the low, both quantised from 0..1). The remaining bits are reserved. An air voxel still carries its light.
 */
public final class Vox {
    private Vox() {
    }

    public static int make(int id, int meta, int sky, int blockLight, int tint) {
        return (id & 255) | (meta & 15) << 8 | (sky & 15) << 12 | (blockLight & 15) << 16 | (tint & 255) << 20;
    }

    public static int id(int v) {
        return v & 255;
    }

    public static int meta(int v) {
        return v >> 8 & 15;
    }

    public static int sky(int v) {
        return v >> 12 & 15;
    }

    public static int blockLight(int v) {
        return v >> 16 & 15;
    }

    public static int tint(int v) {
        return v >> 20 & 255;
    }

    public static int tintOf(double temperature, double humidity) {
        int t = (int) Math.round(Math.max(0, Math.min(1, temperature)) * 15), h = (int) Math.round(Math.max(0, Math.min(1, humidity)) * 15);
        return t << 4 | h;
    }

    public static double tintTemperature(int tint) {
        return (tint >> 4 & 15) / 15.0;
    }

    public static double tintHumidity(int tint) {
        return (tint & 15) / 15.0;
    }

    public static boolean isAir(int v) {
        return (v & 255) == 0;
    }
}
