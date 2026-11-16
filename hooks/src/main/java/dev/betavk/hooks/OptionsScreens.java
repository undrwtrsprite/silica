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

    private static final int FOV = 301, AUDIO = 310, WORLD = 311;
    private static final int[] RENDER_STEPS = {2, 4, 8, 12, 16, 20, 24, 32};

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
        StringTranslate tr = StringTranslate.getInstance();
        GameSettings o = go.options;
        go.screenTitle = tr.translateKey("options.title");
        go.controlList.add(new SliderButton(FOV, x(go, 0), y(go, 0), (Opts.fov - 30.0F) / 80.0F, v -> Opts.fov = 30.0F + Math.round(v * 80.0F), OptionsScreens::fovLabel));
        go.controlList.add(new GuiSmallButton(EnumOptions.DIFFICULTY.returnEnumOrdinal(), x(go, 1), y(go, 0), EnumOptions.DIFFICULTY, o.getKeyBinding(EnumOptions.DIFFICULTY)));
        go.controlList.add(new GuiButton(101, x(go, 0), y(go, 1), 150, 20, tr.translateKey("options.video")));
        go.controlList.add(new GuiButton(AUDIO, x(go, 1), y(go, 1), 150, 20, "Music & Sounds..."));
        go.controlList.add(new GuiButton(100, x(go, 0), y(go, 2), 150, 20, tr.translateKey("options.controls")));
        go.controlList.add(new GuiButton(WORLD, x(go, 1), y(go, 2), 150, 20, "World..."));
        go.controlList.add(new GuiButton(200, go.width / 2 - 100, y(go, 4), tr.translateKey("gui.done")));
    }

    /** replaceBody of GuiOptions.actionPerformed. */
    public static void action(GuiOptions go, GuiButton b) {
        if (!b.enabled) return;
        GameSettings o = go.options;
        if (b.id < 100 && b instanceof GuiSmallButton sb) {
            o.setOptionValue(sb.returnEnumOptions(), 1);
            b.displayString = o.getKeyBinding(EnumOptions.getEnumOptions(b.id));
        }
        switch (b.id) {
            case 101 -> {
                go.mc.gameSettings.saveOptions();
                go.mc.displayGuiScreen(new net.minecraft.src.GuiVideoSettings(go, o));
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
    public static String label(GameSettings gs, EnumOptions o) {
        if (o == EnumOptions.RENDER_DISTANCE) {
            return "Render Distance: " + Opts.effectiveChunks(gs) + " chunks";
        }
        if (o == EnumOptions.GUI_SCALE) {
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
        if (o != EnumOptions.GUI_SCALE) return false;
        gs.guiScale = Math.floorMod(gs.guiScale + dir, 5);
        gs.saveOptions();
        return true;
    }
}
