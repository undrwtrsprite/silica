package dev.betavk.hooks;

import net.minecraft.src.GameSettings;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * Our options live in the game's own options.txt, after the vanilla lines: fov, showFps, maxFps (the Zoom key is a normal key binding and is saved with the others). The vanilla loader ignores keys
 * it does not know, so both coexist, and files written by the earlier in-tree build load unchanged.
 */
public final class Settings {
    private Settings() {
    }

    private static GameSettings last;

    private static File file() {
        return last == null ? null : last.optionsFile;
    }

    /** atEnd of GameSettings.loadOptions. */
    public static void load(GameSettings gs) {
        last = gs;
        if (gs.guiScale < 0 || gs.guiScale > 4) gs.guiScale = 0;
        File f = file();
        if (f == null || !f.isFile()) return;
        try {
            for (String line : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
                String[] kv = line.split(":", 2);
                if (kv.length != 2) continue;
                try {
                    switch (kv[0]) {
                        case "fov" -> Opts.fov = Math.max(30.0F, Math.min(110.0F, Float.parseFloat(kv[1])));
                        case "showFps" -> Opts.showFps = Boolean.parseBoolean(kv[1]);
                        case "maxFps" -> Opts.maxFps = Math.max(0, Integer.parseInt(kv[1]));
                        case "masterVolume" -> Opts.master = Math.max(0.0F, Math.min(1.0F, Float.parseFloat(kv[1])));
                        case "ambientVolume" -> Opts.ambient = Math.max(0.0F, Math.min(1.0F, Float.parseFloat(kv[1])));
                        case "continuousMusic" -> Opts.continuousMusic = Boolean.parseBoolean(kv[1]);
                        case "toggleSneak" -> Opts.toggleSneak = Boolean.parseBoolean(kv[1]);
                        case "soundCaptions" -> Opts.soundCaptions = Boolean.parseBoolean(kv[1]);
                        case "autosaveMinutes" -> Opts.autosaveMinutes = Math.max(0, Integer.parseInt(kv[1]));
                        case "worldBackups" -> Opts.backups = Math.max(0, Integer.parseInt(kv[1]));
                        case "lastWorld" -> Opts.lastWorld = kv[1];
                        case "worldSort" -> Opts.worldSort = Math.max(0, Math.min(1, Integer.parseInt(kv[1])));
                        default -> {
                        }
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /** atEnd of GameSettings.saveOptions: the vanilla file is already written; append ours. */
    public static void save(GameSettings gs) {
        last = gs;
        save();
    }

    public static void save() {
        File f = file();
        if (f == null) return;
        try {
            List<String> lines = f.isFile() ? Files.readAllLines(f.toPath(), StandardCharsets.UTF_8) : new java.util.ArrayList<>();
            lines.removeIf(l -> l.startsWith("fov:") || l.startsWith("showFps:") || l.startsWith("maxFps:") || l.startsWith("masterVolume:") || l.startsWith("ambientVolume:")
                    || l.startsWith("continuousMusic:") || l.startsWith("toggleSneak:") || l.startsWith("soundCaptions:")
                    || l.startsWith("autosaveMinutes:") || l.startsWith("worldBackups:") || l.startsWith("lastWorld:") || l.startsWith("worldSort:"));
            lines.add("fov:" + Opts.fov);
            lines.add("showFps:" + Opts.showFps);
            lines.add("maxFps:" + Opts.maxFps);
            lines.add("masterVolume:" + Opts.master);
            lines.add("ambientVolume:" + Opts.ambient);
            lines.add("continuousMusic:" + Opts.continuousMusic);
            lines.add("toggleSneak:" + Opts.toggleSneak);
            lines.add("soundCaptions:" + Opts.soundCaptions);
            lines.add("autosaveMinutes:" + Opts.autosaveMinutes);
            lines.add("worldBackups:" + Opts.backups);
            if (!Opts.lastWorld.isEmpty()) lines.add("lastWorld:" + Opts.lastWorld);
            lines.add("worldSort:" + Opts.worldSort);
            Files.write(f.toPath(), lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
