package dev.betavk.hooks;

/** Our settings, kept in static fields (one game, one settings object). Loaded from and saved to the game's options.txt next to the vanilla keys. */
public final class Opts {
    private Opts() {
    }

    public static float fov = 70.0F;
    public static boolean showFps;
    public static int maxFps;          // 0 = unlimited
    public static float master = 1.0F, ambient = 1.0F;   // volume multipliers; music and sound effects are the game's own musicVolume and soundVolume
    public static boolean continuousMusic;
    public static boolean toggleSneak, soundCaptions;
    public static int autosaveMinutes;  // 0 = only the game's own saving
    public static int backups;          // 0 = no backup when a world is opened, else how many to keep
    public static String lastWorld = "";
    public static int worldSort;        // 0 = last played, 1 = name
}
