package dev.betavk.hooks;

import net.minecraft.src.GameSettings;
import net.minecraft.src.GuiButton;
import net.minecraft.src.GuiScreen;

/** World settings: how often the world is saved in full (on top of the game's own continuous saving of changed chunks) and the backup made when a world is opened. */
public final class WorldOptionsScreen extends GuiScreen {
    private final GuiScreen parent;
    private final GameSettings options;
    static final int[] AUTOSAVE_MINUTES = {0, 1, 2, 5, 10, 30};   // 0 = the game's own behaviour only
    static final int[] BACKUPS = {0, 3, 5, 10};                     // 0 = no backups

    public WorldOptionsScreen(GuiScreen parent, GameSettings options) {
        this.parent = parent;
        this.options = options;
    }

    static String autosaveLabel() {
        return "Autosave: " + (Opts.autosaveMinutes <= 0 ? "Vanilla" : "every " + Opts.autosaveMinutes + " min");
    }

    static String backupLabel() {
        return "World backup: " + (Opts.backups <= 0 ? "Off" : "keep " + Opts.backups);
    }

    private static int next(int[] values, int cur) {
        for (int i = 0; i < values.length; i++) if (values[i] == cur) return values[(i + 1) % values.length];
        return values[0];
    }

    @SuppressWarnings("unchecked")
    @Override public void initGui() {
        controlList.clear();
        controlList.add(new GuiButton(330, width / 2 - 100, height / 6 + 24, 200, 20, autosaveLabel()));
        controlList.add(new GuiButton(331, width / 2 - 100, height / 6 + 48, 200, 20, backupLabel()));
        if (Commands.isLocalWorld(mc)) {
            controlList.add(new GuiButton(332, width / 2 - 100, height / 6 + 72, 200, 20, cheatsLabel()));
        }
        controlList.add(new GuiButton(200, width / 2 - 100, height / 6 + 144, "Done"));
    }

    private String cheatsLabel() {
        return "Cheats: " + (Commands.enabled(mc) ? "On" : "Off");
    }

    @Override public void actionPerformed(GuiButton b) {
        if (!b.enabled) return;
        switch (b.id) {
            case 330 -> {
                Opts.autosaveMinutes = next(AUTOSAVE_MINUTES, Opts.autosaveMinutes);
                b.displayString = autosaveLabel();
            }
            case 331 -> {
                Opts.backups = next(BACKUPS, Opts.backups);
                b.displayString = backupLabel();
            }
            case 332 -> {
                Commands.toggle(mc);
                b.displayString = cheatsLabel();
            }
            case 200 -> mc.displayGuiScreen(parent);
            default -> {
            }
        }
        Settings.save(options);
    }

    @Override public void drawScreen(int mx, int my, float partial) {
        drawDefaultBackground();
        drawCenteredString(fontRenderer, "World", width / 2, 20, 0xFFFFFF);
        drawCenteredString(fontRenderer, "Autosave saves your progress.", width / 2, height / 6 + 104, 0xA0A0A0);
        drawCenteredString(fontRenderer, "Backups run when worlds open.", width / 2, height / 6 + 116, 0xA0A0A0);
        super.drawScreen(mx, my, partial);
    }
}
