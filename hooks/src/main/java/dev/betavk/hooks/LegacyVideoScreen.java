package dev.betavk.hooks;

import net.minecraft.src.GameSettings;
import net.minecraft.src.GuiButton;
import net.minecraft.src.GuiScreen;

/**
 * Full Video Settings screen for Alpha and early Beta (< b1.5) where vanilla GuiVideoSettings does not exist.
 * Gives older versions the same Graphics, Render Distance (2-32 chunks), Max FPS, Show FPS, Better Grass,
 * Fullscreen, View Bobbing, Anaglyph, and LOD Settings controls as Beta 1.7.3.
 */
public final class LegacyVideoScreen extends GuiScreen {
    private static final int GRAPHICS = 10, ANAGLYPH = 11, BOBBING = 12;
    private static final int FPS_CAP = 302, RENDER_DIST = 303, SHOW_FPS = 304, FULLSCREEN = 305, LOD_SETTINGS = 306, BETTER_GRASS = 307;
    private static final int MIN_CHUNKS = 2, MAX_CHUNKS = 32;

    private final GuiScreen parent;
    private final GameSettings opts;
    private int appliedChunks = -1;

    public LegacyVideoScreen(GuiScreen parent, GameSettings opts) {
        this.parent = parent;
        this.opts = opts;
    }

    private static String renderLabel(GameSettings opts) {
        return "Render Distance: " + Opts.effectiveChunks(opts) + " chunks";
    }

    private static String fpsLabel() {
        return "Max FPS: " + (Opts.maxFps <= 0 ? "Unlimited" : String.valueOf(Opts.maxFps));
    }

    private static String showFpsLabel() {
        return "Show FPS: " + (Opts.showFps ? "ON" : "OFF");
    }

    private static String betterGrassLabel() {
        return switch (Opts.betterGrass) {
            case 1 -> "Better Grass: Fast";
            case 2 -> "Better Grass: Fancy";
            default -> "Better Grass: OFF";
        };
    }

    private void applyRenderDistance() {
        int cur = Opts.effectiveChunks(opts);
        if (cur == appliedChunks) return;
        appliedChunks = cur;
        Settings.save(opts);
        if (mc.theWorld != null && mc.renderGlobal != null) mc.renderGlobal.loadRenderers();
    }

    @SuppressWarnings("unchecked")
    @Override public void initGui() {
        controlList.clear();
        appliedChunks = Opts.effectiveChunks(opts);
        controlList.add(new GuiButton(GRAPHICS, OptionsScreens.x(this, 0), OptionsScreens.y(this, 0), 150, 20, "Graphics: " + (opts.fancyGraphics ? "Fancy" : "Fast")));
        controlList.add(new SliderButton(RENDER_DIST, OptionsScreens.x(this, 1), OptionsScreens.y(this, 0),
                (float) (appliedChunks - MIN_CHUNKS) / (MAX_CHUNKS - MIN_CHUNKS),
                v -> {
                    int c = MIN_CHUNKS + Math.round(v * (MAX_CHUNKS - MIN_CHUNKS));
                    Opts.renderChunks = c;
                    Opts.syncLegacyRenderDistance(opts, c);
                },
                () -> renderLabel(opts), this::applyRenderDistance));
        controlList.add(new GuiButton(ANAGLYPH, OptionsScreens.x(this, 0), OptionsScreens.y(this, 1), 150, 20, "3D Anaglyph: " + (opts.anaglyph ? "ON" : "OFF")));
        controlList.add(new GuiButton(BOBBING, OptionsScreens.x(this, 1), OptionsScreens.y(this, 1), 150, 20, "View Bobbing: " + (opts.viewBobbing ? "ON" : "OFF")));
        controlList.add(new SliderButton(FPS_CAP, OptionsScreens.x(this, 0), OptionsScreens.y(this, 2),
                Opts.maxFps <= 0 ? 1.0F : Math.max(0.0F, Math.min(1.0F, (Opts.maxFps - 30) / 210.0F)),
                v -> Opts.maxFps = v >= 0.985F ? 0 : Math.round((30 + v * 210.0F) / 5.0F) * 5,
                LegacyVideoScreen::fpsLabel));
        controlList.add(new GuiButton(SHOW_FPS, OptionsScreens.x(this, 1), OptionsScreens.y(this, 2), 150, 20, showFpsLabel()));
        controlList.add(new GuiButton(BETTER_GRASS, OptionsScreens.x(this, 0), OptionsScreens.y(this, 3), 150, 20, betterGrassLabel()));
        controlList.add(new GuiButton(FULLSCREEN, OptionsScreens.x(this, 1), OptionsScreens.y(this, 3), 150, 20, "Fullscreen: " + (dev.betavk.platform.Display.isFullscreen() ? "ON" : "OFF")));
        controlList.add(new GuiButton(LOD_SETTINGS, width / 2 - 100, OptionsScreens.y(this, 4), Opts.lodEnabled ? "LOD Settings..." : "LOD Settings (OFF)"));
        controlList.add(new GuiButton(200, width / 2 - 100, height / 6 + 132, "Done"));
    }

    @Override public void actionPerformed(GuiButton b) {
        if (!b.enabled) return;
        switch (b.id) {
            case GRAPHICS -> {
                opts.fancyGraphics = !opts.fancyGraphics;
                b.displayString = "Graphics: " + (opts.fancyGraphics ? "Fancy" : "Fast");
                if (mc.renderGlobal != null) mc.renderGlobal.loadRenderers();
            }
            case ANAGLYPH -> {
                opts.anaglyph = !opts.anaglyph;
                b.displayString = "3D Anaglyph: " + (opts.anaglyph ? "ON" : "OFF");
                if (mc.renderEngine != null) mc.renderEngine.refreshTextures();
            }
            case BOBBING -> {
                opts.viewBobbing = !opts.viewBobbing;
                b.displayString = "View Bobbing: " + (opts.viewBobbing ? "ON" : "OFF");
            }
            case SHOW_FPS -> {
                Opts.showFps = !Opts.showFps;
                b.displayString = showFpsLabel();
            }
            case BETTER_GRASS -> {
                Opts.betterGrass = (Opts.betterGrass + 1) % 3;
                b.displayString = betterGrassLabel();
                if (mc.renderGlobal != null) mc.renderGlobal.loadRenderers();
            }
            case FULLSCREEN -> {
                mc.toggleFullscreen();
                b.displayString = "Fullscreen: " + (dev.betavk.platform.Display.isFullscreen() ? "ON" : "OFF");
            }
            case LOD_SETTINGS -> {
                Settings.save(opts);
                mc.displayGuiScreen(new LodOptionsScreen(this, opts));
                return;
            }
            case 200 -> {
                applyRenderDistance();
                Settings.save(opts);
                opts.saveOptions();
                mc.displayGuiScreen(parent);
                return;
            }
            default -> {
            }
        }
        Settings.save(opts);
        opts.saveOptions();
    }

    @Override public void onGuiClosed() {
        applyRenderDistance();
        Settings.save(opts);
        opts.saveOptions();
    }

    @Override public void drawScreen(int mx, int my, float partial) {
        drawDefaultBackground();
        drawCenteredString(fontRenderer, "Video Settings", width / 2, 20, 0xFFFFFF);
        super.drawScreen(mx, my, partial);
    }
}
