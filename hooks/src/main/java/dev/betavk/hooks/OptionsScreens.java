package dev.betavk.hooks;

import net.minecraft.src.EnumOptions;
import net.minecraft.src.GameSettings;
import net.minecraft.src.GuiButton;
import net.minecraft.src.GuiOptions;
import net.minecraft.src.GuiScreen;
import net.minecraft.src.GuiSmallButton;
import net.minecraft.src.StringTranslate;

/**
 * The main Options screen groups FOV and difficulty above the Video, Music &amp; Sounds, Controls and World menus. Also the labels of the GUI scale
 * (Auto, 1x to 4x) and the sensitivity (a percentage on a wide exponential scale; the default setting is still 100%).
 */
public final class OptionsScreens {
    private OptionsScreens() {
    }

    private static final int FOV = 301, AUDIO = 310, WORLD = 311, LEGACY_DIFFICULTY = 312;
    private static final int[] RENDER_STEPS = {2, 4, 8, 12, 16, 20, 24, 32};
    private static final String[] DIFFICULTIES = {"Peaceful", "Easy", "Normal", "Hard"};

    private static String legacyDifficultyLabel(GameSettings o) {
        return "Difficulty: " + DIFFICULTIES[Math.floorMod(o.difficulty, DIFFICULTIES.length)];
    }

    private static String fovLabel() {
        return "FOV: " + (int) Opts.fov + (Opts.fov == 70.0F ? " (default)" : "");
    }

    static int x(GuiScreen s, int col) {
        return s.width / 2 - 155 + col * 160;
    }

    static int y(GuiScreen s, int row) {
        return s.height / 6 + 24 * row;
    }

    /** replaceBody of GuiOptions.initGui. */
    @SuppressWarnings("unchecked")
    public static void init(GuiOptions go) {
        go.controlList.clear();
        GameSettings o = go.options;
        go.screenTitle = McCompat.tr("options.title", "Options");
        go.controlList.add(new SliderButton(FOV, x(go, 0), y(go, 0), (Opts.fov - 30.0F) / 80.0F, v -> Opts.fov = 30.0F + Math.round(v * 80.0F), OptionsScreens::fovLabel));
        if (McCompat.HAS_ENUM_OPTIONS) {
            BetaOptions.addDifficulty(go, o);
        } else {
            go.controlList.add(new GuiButton(LEGACY_DIFFICULTY, x(go, 1), y(go, 0), 150, 20, legacyDifficultyLabel(o)));
        }
        go.controlList.add(new GuiButton(101, x(go, 0), y(go, 1), 150, 20, McCompat.tr("options.video", "Video Settings...")));
        go.controlList.add(new GuiButton(AUDIO, x(go, 1), y(go, 1), 150, 20, "Music & Sounds..."));
        go.controlList.add(new GuiButton(100, x(go, 0), y(go, 2), 150, 20, McCompat.tr("options.controls", "Controls...")));
        go.controlList.add(new GuiButton(WORLD, x(go, 1), y(go, 2), 150, 20, "World..."));
        go.controlList.add(new GuiButton(200, go.width / 2 - 100, y(go, 4), McCompat.tr("gui.done", "Done")));
    }

    /** replaceBody of GuiOptions.actionPerformed. */
    public static void action(GuiOptions go, GuiButton b) {
        if (!b.enabled) return;
        GameSettings o = go.options;
        if (McCompat.HAS_ENUM_OPTIONS && b.id < 100) {
            BetaOptions.handleButton(o, b);
        }
        switch (b.id) {
            case LEGACY_DIFFICULTY -> {
                o.difficulty = (o.difficulty + 1) & 3;
                b.displayString = legacyDifficultyLabel(o);
                o.saveOptions();
            }
            case 101 -> {
                go.mc.gameSettings.saveOptions();
                go.mc.displayGuiScreen(McCompat.HAS_GUI_VIDEO_SETTINGS ? BetaVideoOptions.open(go, o) : new LegacyVideoScreen(go, o));
            }
            case 100 -> {
                go.mc.gameSettings.saveOptions();
                go.mc.displayGuiScreen(new net.minecraft.src.GuiControls(go, o));
            }
            case AUDIO -> {
                go.mc.gameSettings.saveOptions();
                go.mc.displayGuiScreen(new AudioScreen(go, o));
            }
            case WORLD -> {
                go.mc.gameSettings.saveOptions();
                go.mc.displayGuiScreen(new WorldOptionsScreen(go, o));
            }
            case 200 -> {
                go.mc.gameSettings.saveOptions();
                go.mc.displayGuiScreen(go.parentScreen);
            }
            default -> {
            }
        }
    }

    /** addMethod GuiOptions.onGuiClosed: the FOV slider is saved when the screen closes. */
    public static void closed(GuiOptions go) {
        go.mc.gameSettings.saveOptions();
    }

    // ---- labels and values -------------------------------------------------------------------------

    /** The sensitivity the slider position stands for: 100% at the default (the middle), a factor of 16 either way across the slider. */
    public static float sensitivity(float slider) {
        return (float) Math.pow(2.0, (slider - 0.5F) * 8.0);
    }

    /** replaceFloat of the 8.0 mouse-turn constant: with the vanilla formula (0.6 s + 0.2)^3 * K this makes the turn speed exactly sensitivity(s). */
    public static float turnConstant(float slider) {
        float v = slider * 0.6F + 0.2F;
        return sensitivity(slider) / (v * v * v);
    }

    /** atStartReturnIfNotNull of GameSettings.getKeyBinding: the labels of the options we changed. */
    private static final EnumOptions OPT_GUI_SCALE = enumOpt("GUI_SCALE");

    private static EnumOptions enumOpt(String name) {
        try {
            return (EnumOptions) EnumOptions.class.getField(name).get(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static String label(GameSettings gs, EnumOptions o) {
        if (o == EnumOptions.RENDER_DISTANCE) {
            return "Render Distance: " + Opts.effectiveChunks(gs) + " chunks";
        }
        if (OPT_GUI_SCALE != null && o == OPT_GUI_SCALE) {
            int s = gs.guiScale;
            return "GUI Scale: " + (s <= 0 ? "Auto" : s + "x");
        }
        if (o == EnumOptions.SENSITIVITY) {
            return "Sensitivity: " + Math.round(sensitivity(gs.mouseSensitivity) * 100.0F) + "%";
        }
        return null;
    }

    /** atStartCancelable of GameSettings.setOptionValue: GUI scale and cycling chunk distance (F key). */
    public static boolean setOption(GameSettings gs, EnumOptions o, int dir) {
        if (o == EnumOptions.RENDER_DISTANCE) {
            int cur = Opts.effectiveChunks(gs);
            int idx = 0, bestDiff = Integer.MAX_VALUE;
            for (int i = 0; i < RENDER_STEPS.length; i++) {
                int diff = Math.abs(RENDER_STEPS[i] - cur);
                if (diff < bestDiff) { bestDiff = diff; idx = i; }
            }
            int next = RENDER_STEPS[Math.floorMod(idx + dir, RENDER_STEPS.length)];
            Opts.renderChunks = next;
            Opts.syncLegacyRenderDistance(gs, next);
            if (gs.mc != null && gs.mc.renderGlobal != null) gs.mc.renderGlobal.renderDistance = -1;
            gs.saveOptions();
            return true;
        }
        if (OPT_GUI_SCALE == null || o != OPT_GUI_SCALE) return false;
        gs.guiScale = Math.floorMod(gs.guiScale + dir, 5);
        gs.saveOptions();
        return true;
    }

    private static final class BetaOptions {
        @SuppressWarnings("unchecked")
        static void addDifficulty(GuiOptions go, GameSettings o) {
            go.controlList.add(new GuiSmallButton(EnumOptions.DIFFICULTY.returnEnumOrdinal(), x(go, 1), y(go, 0), EnumOptions.DIFFICULTY, o.getKeyBinding(EnumOptions.DIFFICULTY)));
        }

        static void handleButton(GameSettings o, GuiButton b) {
            if (b instanceof GuiSmallButton sb) {
                o.setOptionValue(sb.returnEnumOptions(), 1);
                b.displayString = o.getKeyBinding(EnumOptions.getEnumOptions(b.id));
            }
        }
    }

    private static final class BetaVideoOptions {
        static GuiScreen open(GuiOptions go, GameSettings o) {
            return new net.minecraft.src.GuiVideoSettings(go, o);
        }
    }
}
