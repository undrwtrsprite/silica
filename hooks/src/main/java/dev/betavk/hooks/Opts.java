package dev.betavk.hooks;

import net.minecraft.src.GameSettings;

/** Our settings, kept in static fields (one game, one settings object). Loaded from and saved to the game's options.txt next to the vanilla keys. */
public final class Opts {
    private Opts() {
    }

    public static float fov = 70.0F;
    public static int renderChunks;      // 0 = follow vanilla gs.renderDistance (0..3); 2..32 = explicit chunk radius
    public static boolean lodEnabled = false;
    public static int lodChunks = 64;    // 8..128 = distant real-block LOD chunks past your selected Render Distance
    public static int lodQuality = 1;    // 0 = Fast (2x1x2), 1 = Balanced (1x1x1 +5c), 2 = High (1x1x1 +8c)
    public static boolean showFps;
    public static int maxFps;          // 0 = unlimited
    public static float master = 1.0F, ambient = 1.0F;   // volume multipliers; music and sound effects are the game's own musicVolume and soundVolume
    public static boolean continuousMusic;
    public static boolean toggleSneak, soundCaptions;
    public static int betterGrass = Math.max(0, Math.min(2, Integer.getInteger("betavk.bettergrass", 0)));       // 0 = Off, 1 = Fast, 2 = Fancy
    public static int autosaveMinutes;  // 0 = only the game's own saving
    public static int backups;          // 0 = no backup when a world is opened, else how many to keep
    public static String lastWorld = "";
    public static int worldSort;        // 0 = last played, 1 = name

    public static int effectiveChunks(GameSettings gs) {
        if (renderChunks >= 2) return Math.min(32, renderChunks);
        return switch (gs.renderDistance) {
            case 0 -> 12;
            case 1 -> 8;
            case 2 -> 4;
            default -> 2;
        };
    }

    public static float effectiveFarPlane(GameSettings gs) {
        if (renderChunks >= 2) return Math.min(32, renderChunks) * 16.0F;
        return (float) (256 >> gs.renderDistance);
    }

    public static int effectiveLodChunks(GameSettings gs) {
        if (!lodEnabled || lodChunks <= 0) return 0;
        return effectiveChunks(gs) + Math.max(8, Math.min(128, lodChunks));
    }

    public static void syncLegacyRenderDistance(GameSettings gs, int chunks) {
        gs.renderDistance = chunks >= 10 ? 0 : chunks >= 6 ? 1 : chunks >= 4 ? 2 : 3;
    }
}
