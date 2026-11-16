package dev.betavk.hooks;

import net.minecraft.src.EnumOptions;
import net.minecraft.src.GameSettings;
import net.minecraft.src.GuiButton;
import net.minecraft.src.GuiSmallButton;
import net.minecraft.src.GuiVideoSettings;
import net.minecraft.src.ScaledResolution;
import net.minecraft.src.StringTranslate;

/**
 * The Video Settings screen with our options added (FOV, Max FPS, Show FPS, Fullscreen). The game's own options are built exactly as
 * before; ours follow them. Sliders do not rebuild the screen (rebuilding recreated the slider under the cursor and dropped the drag).
 */
public final class VideoScreen {
    private VideoScreen() {
    }

    private static final EnumOptions[] VANILLA = {EnumOptions.GRAPHICS, EnumOptions.RENDER_DISTANCE, EnumOptions.AMBIENT_OCCLUSION, EnumOptions.FRAMERATE_LIMIT,
            EnumOptions.ANAGLYPH, EnumOptions.VIEW_BOBBING, EnumOptions.GUI_SCALE, EnumOptions.ADVANCED_OPENGL};
    private static final int FOV = 301, FPS_CAP = 302, RENDER_DIST = 303, SHOW_FPS = 304, FULLSCREEN = 305;
    private static final int MIN_CHUNKS = 2, MAX_CHUNKS = 32;
    private static int appliedChunks = -1;

    private static String renderLabel(GameSettings opts) {
        return "Render Distance: " + Opts.effectiveChunks(opts) + " chunks";
    }

    private static void applyRenderDistance(GuiVideoSettings gs) {
        int cur = Opts.effectiveChunks(gs.guiGameSettings);
        if (cur == appliedChunks) return;
        appliedChunks = cur;
        Settings.save(gs.guiGameSettings);
        if (gs.mc.theWorld != null && gs.mc.renderGlobal != null) gs.mc.renderGlobal.loadRenderers();
    }

    private static String fovLabel() {
        return "FOV: " + (int) Opts.fov + (Opts.fov == 70.0F ? " (default)" : "");
    }

    private static String fpsLabel() {
        return "Max FPS: " + (Opts.maxFps <= 0 ? "Unlimited" : String.valueOf(Opts.maxFps));
    }

    private static String showFpsLabel() {
        return "Show FPS: " + (Opts.showFps ? "ON" : "OFF");
    }

    private static String fullscreenLabel(GuiVideoSettings gs) {
        return "Fullscreen: " + (gs.mc.fullscreen ? "ON" : "OFF");
    }

    /** replaceBody of GuiVideoSettings.initGui. */
    @SuppressWarnings("unchecked")
    public static void init(GuiVideoSettings gs) {
        GameSettings opts = gs.guiGameSettings;
        StringTranslate tr = StringTranslate.getInstance();
        gs.field_22107_a = tr.translateKey("options.videoTitle");
        appliedChunks = Opts.effectiveChunks(opts);
        int n = 0;
        for (EnumOptions o : VANILLA) {
            if (o == EnumOptions.RENDER_DISTANCE) {
                int chunks = Opts.effectiveChunks(opts);
                float pos = (float) (chunks - MIN_CHUNKS) / (float) (MAX_CHUNKS - MIN_CHUNKS);
                gs.controlList.add(new SliderButton(RENDER_DIST, slotX(gs, n), slotY(gs, n), pos,
                        v -> {
                            int c = MIN_CHUNKS + Math.round(v * (MAX_CHUNKS - MIN_CHUNKS));
                            Opts.renderChunks = c;
                            Opts.syncLegacyRenderDistance(opts, c);
                        }, () -> renderLabel(opts), () -> applyRenderDistance(gs)));
            } else {
                gs.controlList.add(new GuiSmallButton(o.returnEnumOrdinal(), slotX(gs, n), slotY(gs, n), o, opts.getKeyBinding(o)));
            }
            n++;
        }
        int x0 = gs.width / 2 - 155;
        gs.controlList.add(new SliderButton(FPS_CAP, slotX(gs, n), slotY(gs, n), Opts.maxFps <= 0 ? 1.0F : Math.max(0, Math.min(21, (Opts.maxFps - 30) / 10)) / 22.0F,
                v -> {
                    int idx = Math.round(v * 22.0F);
                    Opts.maxFps = idx >= 22 ? 0 : 30 + 10 * idx;
                }, VideoScreen::fpsLabel));
        n++;
        gs.controlList.add(new GuiButton(SHOW_FPS, slotX(gs, n), slotY(gs, n), 150, 20, showFpsLabel()));
        n++;
        gs.controlList.add(new GuiButton(FULLSCREEN, slotX(gs, n), slotY(gs, n), 150, 20, fullscreenLabel(gs)));
        n++;
        gs.controlList.add(new GuiButton(200, gs.width / 2 - 100, gs.height / 6 + 24 * ((n + 1) >> 1) + 12, tr.translateKey("gui.done")));
    }

    private static int slotX(GuiVideoSettings gs, int n) {
        return gs.width / 2 - 155 + n % 2 * 160;
    }

    private static int slotY(GuiVideoSettings gs, int n) {
        return gs.height / 6 + 24 * (n >> 1);
    }

    /** replaceBody of GuiVideoSettings.actionPerformed. */
    public static void action(GuiVideoSettings gs, GuiButton b) {
        if (!b.enabled) return;
        if (b instanceof SliderButton) return;   // no rebuild while dragging
        GameSettings opts = gs.guiGameSettings;
        if (b.id < 100 && b instanceof GuiSmallButton sb) {
            opts.setOptionValue(sb.returnEnumOptions(), 1);
            b.displayString = opts.getKeyBinding(EnumOptions.getEnumOptions(b.id));
        }
        switch (b.id) {
            case SHOW_FPS -> Opts.showFps = !Opts.showFps;
            case FULLSCREEN -> gs.mc.toggleFullscreen();
            case 200 -> {
                opts.saveOptions();
                gs.mc.displayGuiScreen(gs.field_22110_h);
            }
            default -> {
            }
        }
        Settings.save(opts);
        ScaledResolution sr = new ScaledResolution(gs.mc.gameSettings, gs.mc.displayWidth, gs.mc.displayHeight);
        gs.setWorldAndResolution(gs.mc, sr.getScaledWidth(), sr.getScaledHeight());
    }

    /** addMethod GuiVideoSettings.onGuiClosed: slider changes are saved when the screen closes. */
    public static void closed(GuiVideoSettings gs) {
        applyRenderDistance(gs);
        gs.guiGameSettings.saveOptions();
    }
}
