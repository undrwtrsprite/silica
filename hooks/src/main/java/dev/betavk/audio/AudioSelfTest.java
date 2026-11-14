package dev.betavk.audio;

import java.io.File;

/** Decodes and plays one file through AlSoundSystem for ~2 s and reports whether OpenAL says it played. */
public final class AudioSelfTest {
    public static void main(String[] args) throws Exception {
        File f = new File(args[0]);
        AlSoundSystem s = new AlSoundSystem();
        s.setListenerPosition(0, 0, 0);
        long t = System.nanoTime();
        s.newSource(false, "t", f.toURI().toURL(), f.getName(), false, 0, 0, 0, 0, 0);
        System.out.printf("decode+upload %.1f ms%n", (System.nanoTime() - t) / 1e6);
        s.setVolume("t", 0.2f);
        s.play("t");
        boolean sawPlaying = false;
        for (int i = 0; i < 20; i++) {
            Thread.sleep(100);
            sawPlaying |= s.playing("t");
        }
        System.out.println("AL reported AL_PLAYING at some point: " + sawPlaying);
        s.cleanup();
        System.exit(sawPlaying ? 0 : 1);
    }
}
