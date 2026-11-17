package dev.betavk.hooks;

import dev.betavk.lod.LodEngine;
import net.minecraft.src.GameSettings;
import net.minecraft.src.GuiButton;
import net.minecraft.src.GuiScreen;

/** LOD settings submenu: toggle LODs completely on/off, extra chunks beyond Render Distance, and progressive near detail quality. */
public final class LodOptionsScreen extends GuiScreen {
    private static final int ENABLE = 340, CHUNKS = 341, QUALITY = 342;
    private static final int[] EXTRA_STEPS = {8, 16, 24, 32, 48, 64, 80, 96, 112, 128};
    private final GuiScreen parent;
    private final GameSettings options;
    private SliderButton extraSlider;
    private GuiButton qualityBtn;

    public LodOptionsScreen(GuiScreen parent, GameSettings options) {
        this.parent = parent;
        this.options = options;
    }

    static String enabledLabel() {
        return "LODs: " + (Opts.lodEnabled ? "ON" : "OFF");
    }

    static String extraLabel() {
        return "Extra Chunks: +" + Opts.lodChunks;
    }

    static String qualityLabel() {
        return switch (Opts.lodQuality) {
            case 2 -> "LOD Detail: High";
            case 0 -> "LOD Detail: Fast";
            default -> "LOD Detail: Balanced";
        };
    }

    private static int stepIndex() {
        int cur = Opts.lodChunks;
        int best = 0;
        for (int i = 0; i < EXTRA_STEPS.length; i++) {
            if (Math.abs(EXTRA_STEPS[i] - cur) < Math.abs(EXTRA_STEPS[best] - cur)) best = i;
        }
        return best;
    }

    private void syncEnabledState() {
        if (extraSlider != null) extraSlider.enabled = Opts.lodEnabled;
        if (qualityBtn != null) qualityBtn.enabled = Opts.lodEnabled;
    }

    @SuppressWarnings("unchecked")
    @Override public void initGui() {
        controlList.clear();
        int x = width / 2 - 100;
        controlList.add(new GuiButton(ENABLE, x, height / 6 + 24, 200, 20, enabledLabel()));
        float pos = (float) stepIndex() / (float) (EXTRA_STEPS.length - 1);
        extraSlider = new SliderButton(CHUNKS, x, height / 6 + 48, 200, 20, pos,
                v -> Opts.lodChunks = EXTRA_STEPS[Math.round(v * (EXTRA_STEPS.length - 1))],
                LodOptionsScreen::extraLabel, () -> Settings.save(options));
        controlList.add(extraSlider);
        qualityBtn = new GuiButton(QUALITY, x, height / 6 + 72, 200, 20, qualityLabel());
        controlList.add(qualityBtn);
        controlList.add(new GuiButton(200, x, height / 6 + 144, "Done"));
        syncEnabledState();
    }

    @Override public void actionPerformed(GuiButton b) {
        if (!b.enabled) return;
        if (b instanceof SliderButton) return;
        switch (b.id) {
            case ENABLE -> {
                Opts.lodEnabled = !Opts.lodEnabled;
                b.displayString = enabledLabel();
                syncEnabledState();
                if (mc != null && mc.theWorld != null) {
                    LodEngine.reset(mc.theWorld);
                }
            }
            case QUALITY -> {
                Opts.lodQuality = (Opts.lodQuality + 1) % 3;
                b.displayString = qualityLabel();
            }
            case 200 -> {
                Settings.save(options);
                mc.displayGuiScreen(parent);
                return;
            }
            default -> {
            }
        }
        Settings.save(options);
    }

    @Override public void onGuiClosed() {
        Settings.save(options);
    }

    @Override public void drawScreen(int mx, int my, float partial) {
        drawDefaultBackground();
        drawCenteredString(fontRenderer, "LOD Settings", width / 2, 20, 0xFFFFFF);
        String line1 = Opts.lodEnabled
                ? "Loads " + Opts.lodChunks + " extra chunks beyond Render Distance (" + Opts.effectiveLodChunks(options) + " total)."
                : "LOD rendering is completely disabled.";
        String line2 = switch (Opts.lodQuality) {
            case 2 -> "Near: 1x1x1 blocks (+8c), stepping down to 2x1x2, 4x2x4, 8x4x8, 16x4x16.";
            case 0 -> "Fast: starts at 2x1x2 blocks and steps down to 4x2x4, 8x4x8, 16x4x16.";
            default -> "Near: 1x1x1 blocks (+5c), stepping down to 2x1x2, 4x2x4, 8x4x8, 16x4x16.";
        };
        drawCenteredString(fontRenderer, line1, width / 2, height / 6 + 104, 0xA0A0A0);
        drawCenteredString(fontRenderer, line2, width / 2, height / 6 + 116, 0x808080);
        super.drawScreen(mx, my, partial);
    }
}
