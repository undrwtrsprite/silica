package dev.betavk.hooks;

import net.minecraft.src.EnumOptions;
import net.minecraft.src.GameSettings;
import net.minecraft.src.GuiButton;
import net.minecraft.src.GuiScreen;
import net.minecraft.src.GuiSlider;

/** Music &amp; Sounds: master volume, music, sound effects (the game's own two sliders) and ambient sounds (weather and caves). */
public final class AudioScreen extends GuiScreen {
    static final int CONTINUOUS_MUSIC = 322;
    static final int SOUND_CAPTIONS = 323;
    private final GuiScreen parent;
    private final GameSettings options;

    public AudioScreen(GuiScreen parent, GameSettings options) {
        this.parent = parent;
        this.options = options;
    }

    private static String pct(String name, float v) {
        return name + ": " + Math.round(v * 100.0F) + "%";
    }

    private static String continuousMusicLabel() {
        return "Continuous Music: " + (Opts.continuousMusic ? "On" : "Off");
    }

    private static void refresh() {
        var ss = dev.betavk.audio.AlSoundSystem.active;
        if (ss != null) ss.refreshVolumes();
    }

    @SuppressWarnings("unchecked")
    @Override public void initGui() {
        controlList.clear();
        controlList.add(new SliderButton(320, OptionsScreens.x(this, 0), OptionsScreens.y(this, 0), Opts.master, v -> {
            Opts.master = v;
            refresh();
        }, () -> pct("Master Volume", Opts.master)));
        if (McCompat.HAS_ENUM_OPTIONS) {
            BetaAudio.addSliders(this, options);
        } else if (McCompat.hasField(GameSettings.class, "musicVolume")) {
            LegacyVolumeSliders.addSliders(this, options);
        } else {
            controlList.add(new GuiButton(324, OptionsScreens.x(this, 1), OptionsScreens.y(this, 0), 150, 20, McCompat.legacyOptionString(options, 0)));
            controlList.add(new GuiButton(325, OptionsScreens.x(this, 0), OptionsScreens.y(this, 1), 150, 20, McCompat.legacyOptionString(options, 1)));
        }
        controlList.add(new SliderButton(321, OptionsScreens.x(this, 1), OptionsScreens.y(this, 1), Opts.ambient, v -> {
            Opts.ambient = v;
            refresh();
        }, () -> pct("Ambient", Opts.ambient)));
        controlList.add(new GuiButton(CONTINUOUS_MUSIC, width / 2 - 100, OptionsScreens.y(this, 2), 200, 20, continuousMusicLabel()));
        controlList.add(new GuiButton(SOUND_CAPTIONS, width / 2 - 100, OptionsScreens.y(this, 3), 200, 20, captionsLabel()));
        controlList.add(new GuiButton(200, width / 2 - 100, height / 6 + 120, "Done"));
    }

    private static String captionsLabel() {
        return "Sound Captions: " + (Opts.soundCaptions ? "On" : "Off");
    }

    @Override public void actionPerformed(GuiButton b) {
        if (!b.enabled) return;
        if (b.id == 324 || b.id == 325) {
            int opt = b.id - 324;
            McCompat.legacySetOptionValue(options, opt, 1);
            b.displayString = McCompat.legacyOptionString(options, opt);
            options.saveOptions();
            return;
        }
        if (b.id == CONTINUOUS_MUSIC) {
            Opts.continuousMusic = !Opts.continuousMusic;
            b.displayString = continuousMusicLabel();
            Settings.save(options);
        } else if (b.id == SOUND_CAPTIONS) {
            Opts.soundCaptions = !Opts.soundCaptions;
            SoundCaptions.clear();
            b.displayString = captionsLabel();
            Settings.save(options);
        } else if (b.id == 200) {
            Settings.save(options);
            options.saveOptions();
            mc.displayGuiScreen(parent);
        }
    }

    @Override public void onGuiClosed() {
        Settings.save(options);
        options.saveOptions();
    }

    @Override public void drawScreen(int mx, int my, float partial) {
        drawDefaultBackground();
        drawCenteredString(fontRenderer, "Music & Sounds", width / 2, 20, 0xFFFFFF);
        super.drawScreen(mx, my, partial);
    }

    private static final class BetaAudio {
        @SuppressWarnings("unchecked")
        static void addSliders(AudioScreen s, GameSettings o) {
            s.controlList.add(new GuiSlider(EnumOptions.MUSIC.returnEnumOrdinal(), OptionsScreens.x(s, 1), OptionsScreens.y(s, 0), EnumOptions.MUSIC, o.getKeyBinding(EnumOptions.MUSIC), o.getOptionFloatValue(EnumOptions.MUSIC)));
            s.controlList.add(new GuiSlider(EnumOptions.SOUND.returnEnumOrdinal(), OptionsScreens.x(s, 0), OptionsScreens.y(s, 1), EnumOptions.SOUND, o.getKeyBinding(EnumOptions.SOUND), o.getOptionFloatValue(EnumOptions.SOUND)));
        }
    }

    private static final class LegacyVolumeSliders {
        @SuppressWarnings("unchecked")
        static void addSliders(AudioScreen s, GameSettings o) {
            s.controlList.add(new SliderButton(326, OptionsScreens.x(s, 1), OptionsScreens.y(s, 0), o.musicVolume, v -> {
                o.musicVolume = v;
                refresh();
            }, () -> pct("Music", o.musicVolume)));
            s.controlList.add(new SliderButton(327, OptionsScreens.x(s, 0), OptionsScreens.y(s, 1), o.soundVolume, v -> {
                o.soundVolume = v;
                refresh();
            }, () -> pct("Sound", o.soundVolume)));
        }
    }
}
