package dev.betavk.hooks;

import net.minecraft.src.SoundManager;

/** Keeps vanilla's track selection, volume and jukebox checks while removing the wait between tracks. */
public final class Music {
    private Music() {
    }

    public static void skipDelay(SoundManager sounds) {
        if (Opts.continuousMusic) sounds.ticksBeforeMusic = 0;
    }
}
