package dev.betavk.hooks;

import net.minecraft.src.SoundManager;

/** Keeps vanilla's track selection, volume and jukebox checks while removing the wait between tracks. */
public final class Music {
    private static final boolean HAS_TICKS = McCompat.hasField(SoundManager.class, "ticksBeforeMusic");

    private Music() {
    }

    private static final class TicksField {
        static void reset(SoundManager s) { s.ticksBeforeMusic = 0; }
    }

    public static void skipDelay(SoundManager sounds) {
        if (Opts.continuousMusic && HAS_TICKS) TicksField.reset(sounds);
    }
}
