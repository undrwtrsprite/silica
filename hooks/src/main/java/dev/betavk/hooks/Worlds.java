package dev.betavk.hooks;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import net.minecraft.client.Minecraft;
import net.minecraft.src.CompressedStreamTools;
import net.minecraft.src.GuiButton;
import net.minecraft.src.GuiMainMenu;
import net.minecraft.src.GuiSelectWorld;
import net.minecraft.src.GuiWorldSlot;
import net.minecraft.src.NBTTagCompound;
import net.minecraft.src.PlayerControllerSP;
import net.minecraft.src.SaveFormatComparator;
import net.minecraft.src.Tessellator;

/**
 * World conveniences: a Resume button on the title screen for the world played last, the world list sortable by last played or name with each world's seed
 * shown, a periodic autosave (level data, player and changed chunks) and a backup of a world when it is opened.
 */
public final class Worlds {
    private Worlds() {
    }

    private static final int RESUME = 20, SORT = 40;

    static File savesDir() {
        return new File(Minecraft.getMinecraftDir(), "saves");
    }

    record Level(String name, long seed) { }

    private static final Map<String, Level> cache = new HashMap<>();

    /** The display name and seed in a world's level.dat, or null when there is none. */
    static Level level(String folder) {
        if (cache.containsKey(folder)) return cache.get(folder);
        Level l = null;
        File f = new File(savesDir(), folder + "/level.dat");
        if (f.isFile()) {
            try (FileInputStream in = new FileInputStream(f)) {
                NBTTagCompound data = CompressedStreamTools.func_1138_a(in).getCompoundTag("Data");
                l = new Level(data.getString("LevelName"), data.getLong("RandomSeed"));
            } catch (Exception e) {
                l = null;
            }
        }
        cache.put(folder, l);
        return l;
    }

    // ---- opening a world: remember it, back it up -----------------------------------------------------

    private static long lastAutosave = System.nanoTime();

    /** atStart of Minecraft.startWorld(folder) on Alpha and early Beta (< b1.3). */
    public static void startWorld1(Minecraft mc, String folder) {
        startWorld(mc, folder, folder, 0L);
    }

    /** atStart of Minecraft.startWorld(folder, name, seed). */
    public static void startWorld(Minecraft mc, String folder, String name, long seed) {
        CommandSuggestions.clearHistory();
        lastAutosave = System.nanoTime();
        if (folder != null && !folder.equals(Opts.lastWorld)) {
            Opts.lastWorld = folder;
            Settings.save();
        }
        if (Opts.backups > 0 && folder != null) {
            try {
                backup(folder, Opts.backups);
            } catch (Exception e) {
                System.err.println("[betavk] world backup failed: " + e);
            }
        }
    }

    /** Zips saves/folder into backups/folder-DATE.zip (skipped when the newest backup is under five minutes old) and keeps the newest `keep` of that world. */
    static void backup(String folder, int keep) throws IOException {
        File src = new File(savesDir(), folder);
        if (!src.isDirectory()) return;
        File dir = new File(Minecraft.getMinecraftDir(), "backups");
        dir.mkdirs();
        List<File> mine = new ArrayList<>();
        File[] all = dir.listFiles((d, n) -> n.startsWith(folder + "-") && n.endsWith(".zip"));
        if (all != null) mine.addAll(List.of(all));
        mine.sort(Comparator.comparingLong(File::lastModified).reversed());
        if (!mine.isEmpty() && System.currentTimeMillis() - mine.get(0).lastModified() < 5 * 60_000L) return;
        long t0 = System.nanoTime();
        File out = new File(dir, folder + "-" + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()) + ".zip");
        Path root = src.toPath();
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(out)); var walk = Files.walk(root)) {
            zip.setLevel(Deflater.BEST_SPEED);   // the region files are already compressed inside; speed matters more
            for (Path p : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                zip.putNextEntry(new ZipEntry(folder + "/" + root.relativize(p).toString().replace('\\', '/')));
                Files.copy(p, zip);
                zip.closeEntry();
            }
        }
        mine.add(0, out);
        for (int i = keep; i < mine.size(); i++) mine.get(i).delete();
        System.out.printf("[betavk] backed up %s (%.1f MB) in %.0f ms%n", folder, out.length() / 1048576.0, (System.nanoTime() - t0) / 1e6);
    }

    // ---- periodic autosave --------------------------------------------------------------------------------

    /** Called every game tick (WorldHooks.tick). */
    public static void tick(Minecraft mc) {
        if (Opts.autosaveMinutes <= 0 || mc.theWorld == null || mc.thePlayer == null || mc.isMultiplayerWorld()) return;
        long now = System.nanoTime();
        if (now - lastAutosave < Opts.autosaveMinutes * 60_000_000_000L) return;
        lastAutosave = now;
        mc.theWorld.saveWorld(true, null);   // level data, the player and every chunk with changes
        System.out.printf("[betavk] autosave took %.0f ms%n", (System.nanoTime() - now) / 1e6);
    }

    // ---- title screen: Resume ---------------------------------------------------------------------------------

    /** atEnd of GuiMainMenu.initGui. */
    @SuppressWarnings("unchecked")
    public static void menuInit(GuiMainMenu gm) {
        if (Opts.lastWorld.isEmpty()) return;
        Level l = level(Opts.lastWorld);
        if (l == null) return;
        String name = l.name().isBlank() ? Opts.lastWorld : l.name();
        if (name.length() > 24) name = name.substring(0, 23) + "...";
        int originalTop = gm.height / 4 + 24;
        int resumeY = Math.max(originalTop + 8, 104); // Leave room for the animated splash below the logo.
        for (Object control : gm.controlList) {
            GuiButton button = (GuiButton) control;
            button.yPosition += resumeY - originalTop;
            if (button.id == 0 || button.id == 4) {
                // Tighten the gap before the bottom row when the copyright needs the space.
                button.yPosition = Math.max(resumeY + 96, Math.min(button.yPosition, gm.height - 34));
            }
        }
        gm.controlList.add(new GuiButton(RESUME, gm.width / 2 - 100, resumeY, "Resume: " + name));
    }

    /** atStartCancelable of GuiMainMenu.actionPerformed. */
    public static boolean menuAction(GuiMainMenu gm, GuiButton b) {
        if (b.id != RESUME || !b.enabled) return false;
        Level l = level(Opts.lastWorld);
        Minecraft mc = gm.mc;
        mc.playerController = new PlayerControllerSP(mc);
        try {
            McCompat.startWorldCompat(mc, Opts.lastWorld, l == null || l.name().isBlank() ? Opts.lastWorld : l.name(), 0L);
        } catch (Throwable ignored) {}
        mc.displayGuiScreen(null);
        return true;
    }

    // ---- world list: sorting and seeds ----------------------------------------------------------------------------

    static String sortLabel() {
        return "Sort: " + (Opts.worldSort == 1 ? "Name" : "Last played");
    }

    /** atEnd of GuiSelectWorld.loadSaves: the list in the chosen order (the game's own order is last played, newest first). */
    @SuppressWarnings("unchecked")
    public static void selectSort(GuiSelectWorld gs) {
        if (Opts.worldSort != 1 || gs.saveList == null) return;
        gs.saveList.sort(Comparator.comparing((SaveFormatComparator c) -> (c.getDisplayName() == null || c.getDisplayName().isBlank() ? c.getFileName() : c.getDisplayName()).toLowerCase())
                .thenComparing(SaveFormatComparator::getFileName));
    }

    /** atEnd of GuiSelectWorld.initGui. */
    @SuppressWarnings("unchecked")
    public static void selectInit(GuiSelectWorld gs) {
        gs.controlList.add(new GuiButton(SORT, gs.width - 112, 6, 106, 20, sortLabel()));
    }

    /** atStartCancelable of GuiSelectWorld.actionPerformed. */
    public static boolean selectAction(GuiSelectWorld gs, GuiButton b) {
        if (b.id != SORT) return false;
        Opts.worldSort = 1 - Opts.worldSort;
        Settings.save();
        b.displayString = sortLabel();
        gs.loadSaves();   // sorts again and clears the selection
        gs.buttonSelect.enabled = false;
        gs.buttonRename.enabled = false;
        gs.buttonDelete.enabled = false;
        return true;
    }

    /** atEnd of GuiWorldSlot.drawSlot: the seed on the free third line. */
    public static void slotSeed(GuiWorldSlot slot, int index, int x, int y, int h, Tessellator t) {
        GuiSelectWorld gs = slot.parentWorldGui;
        List<?> list = GuiSelectWorld.getSize(gs);
        if (index < 0 || index >= list.size()) return;
        SaveFormatComparator c = (SaveFormatComparator) list.get(index);
        if (c.func_22161_d()) return;   // a world that still has to be converted shows its own line there
        Level l = level(c.getFileName());
        if (l != null) gs.drawString(gs.fontRenderer, "Seed: " + l.seed(), x + 2, y + 22, 8421504);
    }
}
