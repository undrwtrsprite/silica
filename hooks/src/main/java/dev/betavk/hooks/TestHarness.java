package dev.betavk.hooks;

import dev.betavk.platform.Display;
import net.minecraft.client.Minecraft;

/**
 * Scripted check for the patched game (-Dbetavk.test=hud): starts a world, turns on the FPS counter, takes a screenshot at FOV 70, again at FOV 100,
 * again zoomed, and exits. Used to compare the patched game against the in-tree build.
 */
final class TestHarness {
    private TestHarness() {
    }

    private static int frame;
    private static int stage;
    private static final String DIR = System.getProperty("betavk.test.dir", "/tmp");

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "PASS " : "FAIL ") + what);
    }

    /** -Dbetavk.test=video: the Options screens (FOV first, audio, world) and the Video Settings screen, with their values and the saved file. */
    private static void video(Minecraft mc) {
        frame++;
        var gs = mc.gameSettings;
        if (frame == 5) {
            mc.displayGuiScreen(new net.minecraft.src.GuiOptions(null, gs));
        }
        if (frame == 20) {
            var go = (net.minecraft.src.GuiOptions) mc.currentScreen;
            SliderButton fov = null;
            net.minecraft.src.GuiButton controls = null;
            boolean audio = false, world = false, mouseOptions = false;
            for (Object o : go.controlList) {
                if (o instanceof SliderButton sb && sb.id == 301) fov = sb;
                if (o instanceof net.minecraft.src.GuiButton b && b.id == 310) audio = true;
                if (o instanceof net.minecraft.src.GuiButton b && b.id == 311) world = true;
                if (o instanceof net.minecraft.src.GuiButton b && b.id == 100) controls = b;
                if (o instanceof net.minecraft.src.GuiButton b && (b.id == net.minecraft.src.EnumOptions.SENSITIVITY.returnEnumOrdinal()
                        || b.id == net.minecraft.src.EnumOptions.INVERT_MOUSE.returnEnumOrdinal())) mouseOptions = true;
            }
            check("Options has FOV, Music & Sounds, World and a Controls submenu for mouse settings",
                    fov != null && audio && world && controls != null && !mouseOptions);
            fov.mousePressed(mc, fov.xPosition + 4 + (int) (0.75F * (fov.width - 8)), fov.yPosition + 5);
            fov.mouseDragged(mc, fov.xPosition + 4 + (int) (0.5F * (fov.width - 8)), fov.yPosition + 5);
            fov.mouseReleased(0, 0);
            check("FOV slider on the Options screen drags: FOV 70 (0.5 of the range)", Opts.fov == 70.0F);
            gs.mouseSensitivity = 0.5F;
            String s50 = gs.getKeyBinding(net.minecraft.src.EnumOptions.SENSITIVITY);
            gs.mouseSensitivity = 0.0F;
            String s0 = gs.getKeyBinding(net.minecraft.src.EnumOptions.SENSITIVITY);
            gs.mouseSensitivity = 1.0F;
            String s100 = gs.getKeyBinding(net.minecraft.src.EnumOptions.SENSITIVITY);
            gs.mouseSensitivity = 0.5F;
            check("sensitivity reads " + s0 + " / " + s50 + " / " + s100 + " and the default turn constant is " + OptionsScreens.turnConstant(0.5F),
                    s50.equals("Sensitivity: 100%") && s0.equals("Sensitivity: 6%") && s100.equals("Sensitivity: 1600%") && Math.abs(OptionsScreens.turnConstant(0.5F) - 8.0F) < 0.001F);
            Display.captureNextFrame(DIR + "/options.png");
            if (controls != null) go.actionPerformed(controls);
        }
        if (frame == 22 && mc.currentScreen instanceof net.minecraft.src.GuiControls controls) {
            net.minecraft.src.GuiSlider sensitivity = null;
            net.minecraft.src.GuiButton invert = null;
            for (Object o : controls.controlList) {
                if (o instanceof net.minecraft.src.GuiSlider sl && sl.idFloat == net.minecraft.src.EnumOptions.SENSITIVITY) sensitivity = sl;
                if (o instanceof net.minecraft.src.GuiButton b && b.id == Controls.INVERT_MOUSE) invert = b;
            }
            check("Controls contains sensitivity and invert mouse", sensitivity != null && invert != null);
            boolean previousInvert = gs.invertMouse;
            if (invert != null) controls.actionPerformed(invert);
            check("Invert mouse toggles", gs.invertMouse != previousInvert);
            if (sensitivity != null) {
                int x = sensitivity.xPosition + 4 + (sensitivity.width - 8) / 2;
                sensitivity.mousePressed(mc, x, sensitivity.yPosition + 5);
                sensitivity.mouseReleased(x, sensitivity.yPosition + 5);
                check("Sensitivity slider updates mouse sensitivity", gs.mouseSensitivity == 0.5F);
            }
            var binding = (net.minecraft.src.GuiButton) controls.controlList.get(gs.keyBindings.length - 1);
            int previousKey = Controls.zoom.keyCode;
            controls.actionPerformed(binding);
            controls.keyTyped('x', 45);
            check("Zoom can still be rebound", Controls.zoom.keyCode == 45);
            gs.setKeyBinding(binding.id, previousKey);
            binding.displayString = gs.getOptionDisplayString(binding.id);
            Display.captureNextFrame(DIR + "/controls.png");
        }
        if (frame == 25) {
            var au = new AudioScreen(null, gs);
            mc.displayGuiScreen(au);
        }
        if (frame == 30) {
            var au = (AudioScreen) mc.currentScreen;
            SliderButton master = null, amb = null;
            net.minecraft.src.GuiButton continuous = null;
            int sliders = 0;
            for (Object o : au.controlList) {
                if (o instanceof SliderButton sb && sb.id == 320) master = sb;
                if (o instanceof SliderButton sb && sb.id == 321) amb = sb;
                if (o instanceof net.minecraft.src.GuiSlider) sliders++;
                if (o instanceof net.minecraft.src.GuiButton b && b.id == AudioScreen.CONTINUOUS_MUSIC) continuous = b;
            }
            check("Music & Sounds has master, ambient and the game's music and sound sliders", master != null && amb != null && sliders == 2);
            check("Continuous Music starts off", continuous != null && continuous.displayString.equals("Continuous Music: Off"));
            if (continuous != null) {
                var sounds = mc.sndManager;
                sounds.ticksBeforeMusic = 100;
                sounds.playRandomMusicIfReady();
                check("Vanilla music waits when the toggle is off", sounds.ticksBeforeMusic == 99);
                au.actionPerformed(continuous);
                sounds.playRandomMusicIfReady();
                check("Continuous Music starts a track without waiting",
                        continuous.displayString.equals("Continuous Music: On") && net.minecraft.src.SoundManager.sndSystem.playing("BgMusic"));
            }
            master.mousePressed(mc, master.xPosition + 4 + (int) (0.25F * (master.width - 8)), master.yPosition + 5);
            master.mouseReleased(0, 0);
            check("master volume slider sets " + Opts.master + " (expect 0.25)", Math.abs(Opts.master - 0.25F) < 0.02F);
            Display.captureNextFrame(DIR + "/audio.png");
        }
        if (frame == 32) {
            var ss = net.minecraft.src.SoundManager.sndSystem;
            var entry = mc.sndManager.soundPoolSounds.getRandomSoundFromSoundPool("random.click");
            if (ss == null || entry == null) {
                check("volume mix (no sound system or no click sound here)", true);
            } else {
                float m = Opts.master, am = Opts.ambient;
                Opts.master = 0.5F;
                Opts.ambient = 0.5F;
                ss.newSource(false, "mix-ambient", entry.soundUrl, "ambient.test", false, 0F, 0F, 0F, 0, 0F);
                ss.newSource(false, "mix-effect", entry.soundUrl, "random.click", false, 0F, 0F, 0F, 0, 0F);
                ss.setVolume("mix-ambient", 0.8F);
                ss.setVolume("mix-effect", 0.8F);
                float a1 = ss.gainOf("mix-ambient"), e1 = ss.gainOf("mix-effect");
                Opts.master = 0.0F;
                ss.refreshVolumes();
                float muted = ss.gainOf("mix-effect");
                Opts.ambient = am;
                check("volume mix: ambient " + a1 + " (expect 0.2 = 0.8 x master 0.5 x ambient 0.5), effect " + e1 + " (expect 0.4), master 0 gives " + muted,
                        Math.abs(a1 - 0.2F) < 0.001F && Math.abs(e1 - 0.4F) < 0.001F && muted == 0.0F);
                Opts.master = 1.0F;
                ss.setListenerPosition(0F, 0F, 0F);
                ss.newSource(false, "mix-positional", entry.soundUrl, "random.click", false, 4F, 0F, 0F, 2, 16F);
                float nearby = ss.gainOf("mix-positional");
                ss.setListenerPosition(-12F, 0F, 0F);
                float atRange = ss.gainOf("mix-positional");
                ss.setListenerPosition(0F, 0F, 0F);
                Opts.master = m;
                ss.refreshVolumes();
                check("Beta positional audio: gain " + nearby + " at 4 blocks and " + atRange + " at 16 blocks",
                        Math.abs(nearby - 0.75F) < 0.001F && atRange == 0.0F);
            }
        }
        if (frame == 35) {
            mc.displayGuiScreen(new WorldOptionsScreen(null, gs));
        }
        if (frame == 40) {
            var wo = (WorldOptionsScreen) mc.currentScreen;
            for (Object o : wo.controlList) {
                var b = (net.minecraft.src.GuiButton) o;
                if (b.id == 330 || b.id == 331) wo.actionPerformed(b);
            }
            check("World screen: autosave " + Opts.autosaveMinutes + " min (expect 1), backups " + Opts.backups + " (expect 3)", Opts.autosaveMinutes == 1 && Opts.backups == 3);
            Display.captureNextFrame(DIR + "/world.png");
        }
        if (frame == 45) {
            var vs = new net.minecraft.src.GuiVideoSettings(null, gs);
            mc.displayGuiScreen(vs);
        }
        if (frame == 50) {
            var vs = (net.minecraft.src.GuiVideoSettings) mc.currentScreen;
            net.minecraft.src.GuiButton show = null;
            SliderButton rdSlider = null;
            for (Object o : vs.controlList) {
                if (o instanceof net.minecraft.src.GuiButton b && b.id == 304) show = b;
                if (o instanceof SliderButton sb && sb.id == 303) rdSlider = sb;
            }
            check("Video Settings has Show FPS, chunk Render Distance slider, and no FOV slider", show != null && rdSlider != null && vs.controlList.stream().noneMatch(o -> o instanceof SliderButton sb && sb.id == 301));
            if (rdSlider != null) {
                rdSlider.mousePressed(mc, rdSlider.xPosition + rdSlider.width - 4, rdSlider.yPosition + 5);
                rdSlider.mouseReleased(0, 0);
                check("Render Distance slider max sets 32 chunks: " + rdSlider.displayString, Opts.renderChunks == 32 && rdSlider.displayString.equals("Render Distance: 32 chunks"));
                rdSlider.mousePressed(mc, rdSlider.xPosition + 4 + Math.round((18.0F / 30.0F) * (rdSlider.width - 8)), rdSlider.yPosition + 5);
                rdSlider.mouseReleased(0, 0);
                check("Render Distance slider drags to 20 chunks: " + Opts.renderChunks, Opts.renderChunks == 20);
            }
            boolean fpsBefore = Opts.showFps;
            vs.actionPerformed(show);
            check("Show FPS toggles", Opts.showFps != fpsBefore);
            net.minecraft.src.GuiButton bgBtn = null;
            for (Object o : vs.controlList) if (o instanceof net.minecraft.src.GuiButton b && b.id == 307) bgBtn = b;
            check("Video Settings has Better Grass button", bgBtn != null);
            if (bgBtn != null) {
                vs.actionPerformed(bgBtn);
                check("Better Grass cycles to Fast: " + Opts.betterGrass, Opts.betterGrass == 1);
                vs.actionPerformed(bgBtn);
                check("Better Grass cycles to Fancy: " + Opts.betterGrass, Opts.betterGrass == 2);
            }
            StringBuilder labels = new StringBuilder();
            gs.guiScale = 0;
            for (int i = 0; i < 6; i++) {
                labels.append(gs.getKeyBinding(net.minecraft.src.EnumOptions.GUI_SCALE).substring(11)).append(' ');
                gs.setOptionValue(net.minecraft.src.EnumOptions.GUI_SCALE, 1);
            }
            check("GUI scale cycles " + labels.toString().trim() + " (expect Auto 1x 2x 3x 4x Auto)", labels.toString().trim().equals("Auto 1x 2x 3x 4x Auto"));
            gs.guiScale = 4;
            var sr = new net.minecraft.src.ScaledResolution(gs, 2560, 1440);
            check("4x at 2560x1440 gives a scale factor of " + sr.scaleFactor, sr.scaleFactor == 4);
            gs.guiScale = 0;
            Display.captureNextFrame(DIR + "/video.png");
        }
        if (frame == 55) {
            var vs = (net.minecraft.src.GuiVideoSettings) mc.currentScreen;
            net.minecraft.src.GuiButton lodBtn = null;
            for (Object o : vs.controlList) {
                if (o instanceof net.minecraft.src.GuiButton b && b.id == 306) lodBtn = b;
            }
            check("Video Settings has LOD Settings button", lodBtn != null);
            if (lodBtn != null) vs.actionPerformed(lodBtn);
        }
        if (frame == 60) {
            check("LOD Settings button opens LodOptionsScreen", mc.currentScreen instanceof LodOptionsScreen);
            if (mc.currentScreen instanceof LodOptionsScreen lo) {
                net.minecraft.src.GuiButton enableBtn = null, qualityBtn = null, doneBtn = null;
                SliderButton extraSlider = null;
                for (Object o : lo.controlList) {
                    if (o instanceof SliderButton sb && sb.id == 341) extraSlider = sb;
                    else if (o instanceof net.minecraft.src.GuiButton b) {
                        if (b.id == 340) enableBtn = b;
                        else if (b.id == 342) qualityBtn = b;
                        else if (b.id == 200) doneBtn = b;
                    }
                }
                check("LodOptionsScreen has Enable, Extra Chunks, LOD Detail, and Done", enableBtn != null && extraSlider != null && qualityBtn != null && doneBtn != null);
                check("LOD starts OFF by default: " + Opts.effectiveLodChunks(gs), !Opts.lodEnabled && Opts.effectiveLodChunks(gs) == 0 && !extraSlider.enabled);
                lo.actionPerformed(enableBtn);
                check("LOD toggle turns ON cleanly", Opts.lodEnabled && extraSlider.enabled);
                extraSlider.mousePressed(mc, extraSlider.xPosition + extraSlider.width - 4, extraSlider.yPosition + 5);
                extraSlider.mouseReleased(0, 0);
                check("Extra Chunks slider sets +128 past Render Distance: " + Opts.effectiveLodChunks(gs), Opts.lodChunks == 128 && Opts.effectiveLodChunks(gs) == 148);
                extraSlider.mousePressed(mc, extraSlider.xPosition + 4 + Math.round((5.0F / 9.0F) * (extraSlider.width - 8)), extraSlider.yPosition + 5);
                extraSlider.mouseReleased(0, 0);
                lo.actionPerformed(qualityBtn);
                check("LOD Detail cycles to High", Opts.lodQuality == 2 && qualityBtn.displayString.contains("High"));
                Display.captureNextFrame(DIR + "/lod-settings.png");
            }
        }
        if (frame == 65 && mc.currentScreen instanceof LodOptionsScreen lo) {
            for (Object o : lo.controlList) {
                if (o instanceof net.minecraft.src.GuiButton b && b.id == 200) {
                    lo.actionPerformed(b);
                    break;
                }
            }
        }
        if (frame == 70) {
            mc.displayGuiScreen(null);
            try {
                String txt = java.nio.file.Files.readString(mc.gameSettings.optionsFile.toPath());
                check("options.txt has fov:70.0 and renderChunks:20", txt.contains("fov:70.0") && txt.contains("renderChunks:20"));
                Opts.renderChunks = 0;
                Opts.continuousMusic = false;
                Opts.lodQuality = 1;
                Opts.betterGrass = 0;
                Settings.load(gs);
                check("Continuous Music, renderChunks, betterGrass, and LOD settings persist and reload",
                        txt.contains("continuousMusic:true") && txt.contains("lodEnabled:true") && txt.contains("lodQuality:2")
                                && txt.contains("betterGrass:2") && Opts.continuousMusic && Opts.renderChunks == 20
                                && Opts.lodQuality == 2 && Opts.betterGrass == 2);
                check("options.txt has showFps, maxFps, masterVolume, autosaveMinutes:1 and worldBackups:3", txt.contains("showFps:") && txt.contains("maxFps:") && txt.contains("masterVolume:0.2") && txt.contains("autosaveMinutes:1") && txt.contains("worldBackups:3"));
            } catch (Exception e) {
                check("options.txt readable", false);
            }
            System.exit(0);
        }
    }

    private static long voxStart, voxShotAt, perfT0;
    private static int perfFrames;
    private static int voxShot;

    /** -Dbetavk.test=vox: flies a fixed camera over a new world, waits for the voxel LOD queue to empty, then screenshots four directions. */
    private static void vox(Minecraft mc) {
        frame++;
        if (frame == 5) {
            mc.playerController = new net.minecraft.src.PlayerControllerSP(mc);
            mc.gameSettings.renderDistance = Integer.getInteger("betavk.test.rd", 2);
            mc.gameSettings.difficulty = 0;
            dev.betavk.vox.VoxLod.distanceChunks = Integer.getInteger("betavk.test.lod", 32);
            mc.startWorld("voxtest", "voxtest", Long.getLong("betavk.test.seed", 1337L));
            mc.displayGuiScreen(null);
            mc.inGameHasFocus = true;
            voxStart = System.nanoTime();
        }
        if (frame < 5 || mc.theWorld == null || mc.thePlayer == null) return;
        if (mc.currentScreen != null) mc.displayGuiScreen(null);   // losing window focus opens the game menu
        var p = mc.thePlayer;
        p.noClip = true;
        p.motionX = p.motionY = p.motionZ = 0;
        double alt = Double.parseDouble(System.getProperty("betavk.test.alt", "100"));
        p.setPosition(Double.parseDouble(System.getProperty("betavk.test.x", "0")), alt, Double.parseDouble(System.getProperty("betavk.test.z", "0")));
        p.prevPosX = p.lastTickPosX = p.posX;
        p.prevPosY = p.lastTickPosY = p.posY;
        p.prevPosZ = p.lastTickPosZ = p.posZ;
        float[] yaws = {270F, 0F, 90F, 180F};
        p.rotationYaw = p.prevRotationYaw = yaws[Math.min(3, voxShot)];
        p.rotationPitch = p.prevRotationPitch = 4.0F;
        double secs = (System.nanoTime() - voxStart) / 1e9;
        boolean ready = dev.betavk.vox.VoxLod.jobsWaiting() == 0 && secs > 20;
        if (frame % 300 == 0) System.out.println("[vox] " + (int) secs + "s " + dev.betavk.vox.VoxLod.stats());
        if (voxShotAt == 0 && (ready || secs > Double.parseDouble(System.getProperty("betavk.test.maxwait", "240")))) voxShotAt = System.nanoTime();
        if (voxShotAt != 0 && Boolean.getBoolean("betavk.test.perf")) {
            double t = (System.nanoTime() - voxShotAt) / 1e9;
            if (t > 2 && perfT0 == 0) {
                perfT0 = System.nanoTime();
                perfFrames = 0;
            }
            if (perfT0 != 0) perfFrames++;
            if (perfT0 != 0 && (System.nanoTime() - perfT0) / 1e9 >= 10) {
                System.out.printf("[vox] perf: %.1f FPS over 10 s, lod=%d rd=%d | %s%n", perfFrames / 10.0, dev.betavk.vox.VoxLod.distanceChunks, mc.gameSettings.renderDistance, dev.betavk.vox.VoxLod.stats());
                System.exit(0);
            }
            return;
        }
        if (voxShotAt != 0) {
            double t = (System.nanoTime() - voxShotAt) / 1e9;
            int want = (int) (t / 1.5);
            if (want > voxShot && voxShot < 4) {
                Display.captureNextFrame(DIR + "/vox-" + (voxShot + 1) + ".png");
                voxShot++;
            }
            if (t > 8) {
                System.out.println("[vox] done " + dev.betavk.vox.VoxLod.stats());
                System.exit(0);
            }
        }
    }

    /** -Dbetavk.test=voxunit: mip rules, disk round trip, and the generated voxels against the real, populated world. */
    private static void voxunit(Minecraft mc) {
        frame++;
        if (frame == 5) {
            mc.playerController = new net.minecraft.src.PlayerControllerSP(mc);
            mc.gameSettings.renderDistance = 0;
            mc.gameSettings.difficulty = 0;
            dev.betavk.vox.VoxLod.distanceChunks = 0;
            mc.startWorld("voxunit", "voxunit", 1337L);
            mc.displayGuiScreen(null);
            mc.inGameHasFocus = true;
        }
        if (frame != 400 || mc.theWorld == null) return;
        // 1. mip rules
        int stone = dev.betavk.vox.Vox.make(1, 0, 0, 0, 0), leaves = dev.betavk.vox.Vox.make(18, 0, 3, 0, 0), air = dev.betavk.vox.Vox.make(0, 0, 8, 0, 0), air2 = dev.betavk.vox.Vox.make(0, 0, 4, 2, 0);
        int[] g = new int[8];
        java.util.Arrays.fill(g, air);
        g[0] = leaves;
        check("mip: leaves win over air", dev.betavk.vox.Vox.id(dev.betavk.vox.Mipper.pick(g)) == 18);
        g[5] = stone;
        check("mip: a full block wins over leaves", dev.betavk.vox.Vox.id(dev.betavk.vox.Mipper.pick(g)) == 1);
        java.util.Arrays.fill(g, air);
        g[3] = air2;
        int m = dev.betavk.vox.Mipper.pick(g);
        check("mip: all air stays air with averaged light (sky " + dev.betavk.vox.Vox.sky(m) + ")", dev.betavk.vox.Vox.id(m) == 0 && dev.betavk.vox.Vox.sky(m) == 8);
        // 2. disk round trip
        try {
            java.io.File dir = java.nio.file.Files.createTempDirectory("voxstore").toFile();
            var st = new dev.betavk.vox.VoxelStore(dir);
            for (int sy = 0; sy < 4; sy++) {
                var sec = st.getOrCreate(0, 3, sy, -2);
                for (int i = 0; i < sec.voxels.length; i++) sec.voxels[i] = i * 31 + sy;
            }
            st.markColumnPresent(0, 3, -2);
            st.saveColumn(0, 3, -2);
            var st2 = new dev.betavk.vox.VoxelStore(dir);
            boolean loaded = st2.loadColumn(0, 3, -2);
            boolean same = loaded;
            for (int sy = 0; sy < 4 && same; sy++) same = java.util.Arrays.equals(st.get(0, 3, sy, -2).voxels, st2.get(0, 3, sy, -2).voxels);
            check("store: save and load a column", same);
        } catch (Exception e) {
            e.printStackTrace();
            check("store: save and load a column", false);
        }
        // 3. generated voxels against the real world (a populated area near the spawn)
        var world = mc.theWorld;
        var vox = dev.betavk.vox.VoxLod.testBuild(world, 1337L, 0, 0);
        int[] counts = new int[4];   // total, class mismatches, leaf columns real, leaf columns matching
        long classMismatch = 0, total = 0;
        int realLeaf = 0, bothLeaf = 0;
        for (int x = 0; x < 32; x++) {
            for (int z = 0; z < 32; z++) {
                boolean rl = false, vl = false;
                for (int y = 0; y < 128; y++) {
                    int real = world.getBlockId(x, y, z), v = dev.betavk.vox.Vox.id(vox.get(x, y, z));
                    total++;
                    if (cls(real) != cls(v)) classMismatch++;
                    if (real == 18) rl = true;
                    if (v == 18) vl = true;
                }
                if (rl) realLeaf++;
                if (rl && vl) bothLeaf++;
            }
        }
        System.out.printf("[test] voxels vs real world: %d voxels, %d differ in class (%.3f%%); leaf columns real %d, also leafy in voxels %d%n", total, classMismatch, 100.0 * classMismatch / total, realLeaf, bothLeaf);
        check("generated voxels match the real world (under 2% differ: ores, lakes, dungeons)", 100.0 * classMismatch / total < 2.0);
        System.exit(0);
    }

    private static int cls(int id) {
        if (id == 0) return 0;
        if (id == 8 || id == 9) return 1;
        if (id == 18) return 2;
        if (id == 17) return 3;
        if (net.minecraft.src.Block.opaqueCubeLookup[id]) return 4;
        return 5;   // plants, snow layers, torches and the like
    }

    private static void mouseButton(int b, boolean down) {
        try {
            var f = dev.betavk.platform.Mouse.class.getDeclaredField("buttons");
            f.setAccessible(true);
            ((boolean[]) f.get(null))[b] = down;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** -Dbetavk.test=inv: the inventory features: gather, shift-craft, drag distribution, and a real drag across slots on the open screen. */
    private static net.minecraft.src.Slot slotOf(net.minecraft.src.GuiContainer g, net.minecraft.src.IInventory inv, int index) {
        for (Object o : g.inventorySlots.slots) {
            var sl = (net.minecraft.src.Slot) o;
            if (sl.inventory == inv && sl.slotIndex == index) return sl;
        }
        return null;
    }

    private static void key(int code, boolean down) {
        try {
            var f = dev.betavk.platform.Keyboard.class.getDeclaredField("down");
            f.setAccessible(true);
            ((boolean[]) f.get(null))[code] = down;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static int count(net.minecraft.src.IInventory inv, int id) {
        int n = 0;
        for (int i = 0; i < inv.getSizeInventory(); i++) {
            var st = inv.getStackInSlot(i);
            if (st != null && st.itemID == id) n += st.stackSize;
        }
        return n;
    }

    private static net.minecraft.src.GuiContainer open(Minecraft mc, net.minecraft.src.GuiContainer g) {
        mc.displayGuiScreen(g);
        return g;
    }

    /** The newer-version inventory conveniences, driven through the same code the key, wheel and click hooks call. */
    private static void invPlus(Minecraft mc) {
        var player = mc.thePlayer;
        var inv = player.inventory;
        var dirt = net.minecraft.src.Block.dirt.blockID;
        var stone = net.minecraft.src.Block.stone.blockID;
        var cobble = net.minecraft.src.Block.cobblestone.blockID;
        var planks = net.minecraft.src.Block.planks.blockID;
        // number key over a slot: swap with that hotbar slot, also into an empty one
        for (int i = 0; i < 36; i++) inv.mainInventory[i] = null;
        inv.setItemStack(null);
        var gui = open(mc, new net.minecraft.src.GuiInventory(player));
        inv.mainInventory[20] = new net.minecraft.src.ItemStack(net.minecraft.src.Block.dirt, 10);
        inv.mainInventory[2] = new net.minecraft.src.ItemStack(net.minecraft.src.Block.stone, 5);
        InventoryPlus.keyOn(gui, slotOf(gui, inv, 20), 2 + 2, true);   // key "3" = hotbar slot 2
        boolean swapped = inv.mainInventory[20] != null && inv.mainInventory[20].itemID == stone && inv.mainInventory[20].stackSize == 5 && inv.mainInventory[2].itemID == dirt && inv.mainInventory[2].stackSize == 10;
        InventoryPlus.keyOn(gui, slotOf(gui, inv, 20), 3 + 2, true);   // hotbar slot 3 is empty: the stone moves there
        boolean moved = inv.mainInventory[20] == null && inv.mainInventory[3] != null && inv.mainInventory[3].itemID == stone && inv.mainInventory[3].stackSize == 5 && inv.getItemStack() == null;
        check("number key swaps a hovered slot with the hotbar (full and empty hotbar slot)", swapped && moved);
        // Q drops one, Ctrl+Q the whole stack
        for (int i = 0; i < 36; i++) inv.mainInventory[i] = null;
        inv.mainInventory[20] = new net.minecraft.src.ItemStack(net.minecraft.src.Block.dirt, 10);
        int q = mc.gameSettings.keyBindDrop.keyCode;
        InventoryPlus.keyOn(gui, slotOf(gui, inv, 20), q, false);
        boolean one = inv.mainInventory[20] != null && inv.mainInventory[20].stackSize == 9 && inv.getItemStack() == null;
        key(29, true);
        InventoryPlus.keyOn(gui, slotOf(gui, inv, 20), q, false);
        key(29, false);
        boolean all = inv.mainInventory[20] == null && inv.getItemStack() == null;
        check("Q drops one item, Ctrl+Q the whole stack (9 left after Q: " + one + ", none after Ctrl+Q: " + all + ")", one && all);
        // wheel, shift + double-click and sort in a chest
        for (int i = 0; i < 36; i++) inv.mainInventory[i] = null;
        var chest = new net.minecraft.src.TileEntityChest();
        var cg = open(mc, new net.minecraft.src.GuiChest(inv, chest));
        inv.mainInventory[12] = new net.minecraft.src.ItemStack(net.minecraft.src.Block.cobblestone, 20);
        var chestSlot0 = slotOf(cg, chest, 0);
        InventoryPlus.scroll(cg, slotOf(cg, inv, 12), -1);
        InventoryPlus.scroll(cg, slotOf(cg, inv, 12), -1);
        InventoryPlus.scroll(cg, slotOf(cg, inv, 12), -1);
        boolean pushed = chest.getStackInSlot(0) != null && chest.getStackInSlot(0).stackSize == 3 && inv.mainInventory[12].stackSize == 17;
        InventoryPlus.scroll(cg, chestSlot0, 1);
        boolean pulled = chest.getStackInSlot(0).stackSize == 4 && inv.mainInventory[12].stackSize == 16;   // up pulls one back into the hovered stack from the other side
        InventoryPlus.scroll(cg, chestSlot0, 1);
        pulled = pulled && chest.getStackInSlot(0).stackSize == 5;
        check("scroll down moves one to the chest, up pulls one in (chest " + chest.getStackInSlot(0).stackSize + ", inventory " + inv.mainInventory[12].stackSize + ")", pushed && pulled);
        for (int i = 0; i < 27; i++) chest.setInventorySlotContents(i, null);
        for (int i = 0; i < 36; i++) inv.mainInventory[i] = null;
        inv.mainInventory[12] = new net.minecraft.src.ItemStack(net.minecraft.src.Block.cobblestone, 20);
        inv.mainInventory[14] = new net.minecraft.src.ItemStack(net.minecraft.src.Block.cobblestone, 30);
        inv.mainInventory[15] = new net.minecraft.src.ItemStack(net.minecraft.src.Block.dirt, 5);
        var s12 = slotOf(cg, inv, 12);
        boolean first = !InventoryPlus.shiftClick(cg, s12, 1000L);   // the hook notes the stack, then the game's own shift-click moves it
        mc.playerController.func_27174_a(cg.inventorySlots.windowId, s12.slotNumber, 0, true, player);
        boolean second = InventoryPlus.shiftClick(cg, s12, 1100L);
        check("shift + double-click moves every stack of that kind: chest has " + count(chest, cobble) + " cobblestone (expect 50), inventory " + count(inv, cobble) + " (expect 0), dirt kept " + count(inv, dirt) + " (expect 5)",
                first && second && count(chest, cobble) == 50 && count(inv, cobble) == 0 && count(inv, dirt) == 5);
        for (int i = 0; i < 27; i++) chest.setInventorySlotContents(i, null);
        chest.setInventorySlotContents(3, new net.minecraft.src.ItemStack(net.minecraft.src.Block.stone, 5));
        chest.setInventorySlotContents(0, new net.minecraft.src.ItemStack(net.minecraft.src.Block.dirt, 10));
        chest.setInventorySlotContents(10, new net.minecraft.src.ItemStack(net.minecraft.src.Block.stone, 20));
        chest.setInventorySlotContents(7, new net.minecraft.src.ItemStack(net.minecraft.src.Block.planks, 3));
        chest.setInventorySlotContents(20, new net.minecraft.src.ItemStack(net.minecraft.src.Block.cobblestone, 64));
        var group = new java.util.ArrayList<net.minecraft.src.Slot>();
        for (int i = 0; i < 27; i++) group.add(slotOf(cg, chest, i));
        InventoryPlus.sort(cg, group);
        int[] ids = new int[27], sizes = new int[27];
        for (int i = 0; i < 27; i++) { var st = chest.getStackInSlot(i); ids[i] = st == null ? 0 : st.itemID; sizes[i] = st == null ? 0 : st.stackSize; }
        boolean ordered = ids[0] == stone && sizes[0] == 25 && ids[1] == dirt && sizes[1] == 10 && ids[2] == cobble && sizes[2] == 64 && ids[3] == planks && sizes[3] == 3;
        for (int i = 4; i < 27; i++) ordered &= ids[i] == 0;
        check("sort merges and orders a chest: " + java.util.Arrays.toString(java.util.Arrays.copyOf(ids, 5)) + " " + java.util.Arrays.toString(java.util.Arrays.copyOf(sizes, 5)) + ", cursor empty " + (inv.getItemStack() == null), ordered && inv.getItemStack() == null);
        mc.displayGuiScreen(null);
        player.craftingInventory = player.inventorySlots;   // no window open, as when playing
        // pick block: from the hotbar, from the inventory into an empty hotbar slot, wool colour, nothing when missing
        for (int i = 0; i < 36; i++) inv.mainInventory[i] = null;
        inv.mainInventory[4] = new net.minecraft.src.ItemStack(net.minecraft.src.Block.dirt, 3);
        inv.mainInventory[22] = new net.minecraft.src.ItemStack(net.minecraft.src.Block.cobblestone, 12);
        inv.mainInventory[23] = new net.minecraft.src.ItemStack(net.minecraft.src.Block.cloth.blockID, 5, 14);
        inv.currentItem = 0;
        boolean fromHotbar = PickBlock.pick(mc, dirt, 0) && inv.currentItem == 4;
        boolean fromInv = PickBlock.pick(mc, cobble, 0) && inv.mainInventory[inv.currentItem] != null && inv.mainInventory[inv.currentItem].itemID == cobble && inv.mainInventory[22] == null;
        boolean wool = PickBlock.pick(mc, net.minecraft.src.Block.cloth.blockID, 14) && inv.mainInventory[inv.currentItem] != null && inv.mainInventory[inv.currentItem].getItemDamage() == 14;
        boolean wrongColor = !PickBlock.pick(mc, net.minecraft.src.Block.cloth.blockID, 3);
        boolean none = !PickBlock.pick(mc, net.minecraft.src.Block.sand.blockID, 0);
        check("pick block: hotbar " + fromHotbar + ", inventory to an empty hotbar slot " + fromInv + ", wool colour " + wool + ", wrong colour ignored " + wrongColor + ", missing item ignored " + none,
                fromHotbar && fromInv && wool && wrongColor && none && inv.getItemStack() == null);
        // furnace: shift-click on the output takes everything
        for (int i = 0; i < 36; i++) inv.mainInventory[i] = null;
        var fur = new net.minecraft.src.TileEntityFurnace();
        var cf = new net.minecraft.src.ContainerFurnace(inv, fur);
        fur.setInventorySlotContents(2, new net.minecraft.src.ItemStack(net.minecraft.src.Item.ingotIron, 40));
        cf.func_27280_a(2, 0, true, player);
        check("furnace shift-click takes the whole output: " + count(inv, net.minecraft.src.Item.ingotIron.shiftedIndex) + " iron in the inventory (expect 40), output " + (fur.getStackInSlot(2) == null ? 0 : fur.getStackInSlot(2).stackSize) + " (expect 0)",
                count(inv, net.minecraft.src.Item.ingotIron.shiftedIndex) == 40 && fur.getStackInSlot(2) == null);
    }

    private static int dragShotAt;

    private static void inv(Minecraft mc) {
        frame++;
        if (frame == 5) {
            mc.playerController = new net.minecraft.src.PlayerControllerSP(mc);
            mc.gameSettings.difficulty = 0;
            mc.startWorld("invtest", "invtest", 1337L);
            mc.displayGuiScreen(null);
        }
        if (dragShotAt > 0) {   // -Dbetavk.test.dragshot: the drag is held down, take a picture of it on a real frame and leave
            if (frame == dragShotAt + 10) Display.captureNextFrame(System.getProperty("betavk.test.dragshot"));
            if (frame == dragShotAt + 25) System.exit(0);
            return;
        }
        if (frame != 200 || mc.theWorld == null) return;
        var player = mc.thePlayer;
        var inv = player.inventory;
        var cobble = net.minecraft.src.Block.cobblestone;
        for (int i = 0; i < 36; i++) inv.mainInventory[i] = null;
        inv.mainInventory[9] = new net.minecraft.src.ItemStack(cobble, 30);
        inv.mainInventory[12] = new net.minecraft.src.ItemStack(cobble, 20);
        inv.mainInventory[20] = new net.minecraft.src.ItemStack(cobble, 40);
        inv.mainInventory[21] = new net.minecraft.src.ItemStack(net.minecraft.src.Block.dirt, 7);
        inv.setItemStack(new net.minecraft.src.ItemStack(cobble, 5));
        player.inventorySlots.func_27280_a(9, 6, false, player);
        int cursor = inv.getItemStack().stackSize, total = 0;
        for (var st : inv.mainInventory) if (st != null && st.itemID == cobble.blockID) total += st.stackSize;
        check("gather: cursor " + cursor + " (expect 64), left in inventory " + total + " (expect 31), dirt untouched", cursor == 64 && total == 31 && inv.mainInventory[21].stackSize == 7);
        var cp = (net.minecraft.src.ContainerPlayer) player.inventorySlots;
        for (int i = 0; i < 36; i++) inv.mainInventory[i] = null;
        inv.setItemStack(null);
        cp.craftMatrix.setInventorySlotContents(0, new net.minecraft.src.ItemStack(net.minecraft.src.Block.planks, 16));
        cp.craftMatrix.setInventorySlotContents(2, new net.minecraft.src.ItemStack(net.minecraft.src.Block.planks, 16));
        cp.onCraftMatrixChanged(cp.craftMatrix);
        cp.func_27280_a(0, 0, true, player);
        int sticks = 0, planksLeft = 0;
        for (var st : inv.mainInventory) if (st != null && st.itemID == net.minecraft.src.Item.stick.shiftedIndex) sticks += st.stackSize;
        for (int i = 0; i < 4; i++) {
            var st = cp.craftMatrix.getStackInSlot(i);
            if (st != null) planksLeft += st.stackSize;
        }
        check("shift-craft: sticks " + sticks + " (expect 64), planks left " + planksLeft + " (expect 0)", sticks == 64 && planksLeft == 0);
        // two ingredients: 64 coal over 64 sticks make 64 crafts of 4 torches
        for (int i = 0; i < 36; i++) inv.mainInventory[i] = null;
        inv.setItemStack(null);
        for (int i = 0; i < 4; i++) cp.craftMatrix.setInventorySlotContents(i, null);
        cp.craftMatrix.setInventorySlotContents(0, new net.minecraft.src.ItemStack(net.minecraft.src.Item.coal, 64));
        cp.craftMatrix.setInventorySlotContents(2, new net.minecraft.src.ItemStack(net.minecraft.src.Item.stick, 64));
        cp.onCraftMatrixChanged(cp.craftMatrix);
        cp.func_27280_a(0, 0, true, player);
        int torches = 0, coalLeft = 0;
        for (var st : inv.mainInventory) if (st != null && st.itemID == net.minecraft.src.Block.torchWood.blockID) torches += st.stackSize;
        for (int i = 0; i < 4; i++) { var st = cp.craftMatrix.getStackInSlot(i); if (st != null) coalLeft += st.stackSize; }
        check("shift-craft torches from two ingredients: " + torches + " (expect 256), ingredients left " + coalLeft + " (expect 0)", torches == 256 && coalLeft == 0);
        // the 3x3 crafting table: 8 stacks of 64 planks in a ring make 64 chests
        for (int i = 0; i < 36; i++) inv.mainInventory[i] = null;
        var wb = new net.minecraft.src.ContainerWorkbench(inv, mc.theWorld, 0, 0, 0);
        for (int i : new int[]{0, 1, 2, 3, 5, 6, 7, 8}) wb.craftMatrix.setInventorySlotContents(i, new net.minecraft.src.ItemStack(net.minecraft.src.Block.planks, 64));
        wb.onCraftMatrixChanged(wb.craftMatrix);
        wb.func_27280_a(0, 0, true, player);
        int chests = 0, planksLeft2 = 0;
        for (var st : inv.mainInventory) if (st != null && st.itemID == net.minecraft.src.Block.chest.blockID) chests += st.stackSize;
        for (int i = 0; i < 9; i++) { var st = wb.craftMatrix.getStackInSlot(i); if (st != null) planksLeft2 += st.stackSize; }
        check("shift-craft chests in a crafting table: " + chests + " (expect 64), planks left " + planksLeft2 + " (expect 0)", chests == 64 && planksLeft2 == 0);
        java.util.List<net.minecraft.src.Slot> three = new java.util.ArrayList<>();
        for (int i : new int[]{12, 13, 14}) three.add((net.minecraft.src.Slot) cp.slots.get(i));
        inv.setItemStack(new net.minecraft.src.ItemStack(cobble, 10));
        Inventory.applyDrag(three, 0, player);
        String left = three.get(0).getStack().stackSize + "/" + three.get(1).getStack().stackSize + "/" + three.get(2).getStack().stackSize + " cursor " + inv.getItemStack().stackSize;
        inv.setItemStack(new net.minecraft.src.ItemStack(cobble, 10));
        java.util.List<net.minecraft.src.Slot> four = new java.util.ArrayList<>();
        for (int i : new int[]{15, 16, 17, 18}) four.add((net.minecraft.src.Slot) cp.slots.get(i));
        Inventory.applyDrag(four, 1, player);
        String right = four.get(0).getStack().stackSize + "/" + four.get(1).getStack().stackSize + "/" + four.get(2).getStack().stackSize + "/" + four.get(3).getStack().stackSize + " cursor " + inv.getItemStack().stackSize;
        check("drag left " + left + " (expect 3/3/3 cursor 1), drag right " + right + " (expect 1/1/1/1 cursor 6)", left.equals("3/3/3 cursor 1") && right.equals("1/1/1/1 cursor 6"));
        // a real drag on the open screen: left button down on one empty slot, over two more, release
        for (int i = 0; i < 36; i++) inv.mainInventory[i] = null;
        for (int i = 1; i < 5; i++) cp.craftMatrix.setInventorySlotContents(i - 1, null);
        inv.setItemStack(new net.minecraft.src.ItemStack(cobble, 9));
        var gui = new net.minecraft.src.GuiInventory(player);
        mc.displayGuiScreen(gui);
        int left0 = (gui.width - gui.xSize) / 2, top0 = (gui.height - gui.ySize) / 2;
        int[] slots = {12, 13, 14};
        var all = gui.inventorySlots.slots;
        mouseButton(0, true);
        for (int k = 0; k < slots.length; k++) {
            var sl = (net.minecraft.src.Slot) all.get(slots[k]);
            int mx = left0 + sl.xDisplayPosition + 8, my = top0 + sl.yDisplayPosition + 8;
            if (k == 0) gui.mouseClicked(mx, my, 0);
            gui.drawScreen(mx, my, 0.0F);
        }
        if (System.getProperty("betavk.test.dragshot") != null) {
            dragShotAt = frame;   // keep the button down and the mouse over the last slot
            return;
        }
        mouseButton(0, false);
        gui.drawScreen(0, 0, 0.0F);
        int a = 0, b = 0, c2 = 0;
        var s0 = ((net.minecraft.src.Slot) all.get(12)).getStack();
        var s1 = ((net.minecraft.src.Slot) all.get(13)).getStack();
        var s2 = ((net.minecraft.src.Slot) all.get(14)).getStack();
        a = s0 == null ? 0 : s0.stackSize;
        b = s1 == null ? 0 : s1.stackSize;
        c2 = s2 == null ? 0 : s2.stackSize;
        check("drag across slots on the open screen: " + a + "/" + b + "/" + c2 + " (expect 3/3/3), cursor " + (inv.getItemStack() == null ? 0 : inv.getItemStack().stackSize) + " (expect 0)", a == 3 && b == 3 && c2 == 3 && inv.getItemStack() == null);
        invPlus(mc);
        System.exit(0);
    }

    /** -Dbetavk.test=world: a checksum of the generated world near the spawn (must not depend on prefetch or deferred population), worker chunks against inline ones, and a save/reload round trip. */
    private static void world(Minecraft mc) {
        frame++;
        if (frame == 5) {
            mc.playerController = new net.minecraft.src.PlayerControllerSP(mc);
            mc.gameSettings.renderDistance = 1;
            mc.gameSettings.difficulty = 0;
            mc.startWorld("worldtest", "worldtest", 1337L);
            mc.displayGuiScreen(null);
        }
        if (mc.currentScreen != null) mc.displayGuiScreen(null);
        if (frame < 5 || mc.theWorld == null || mc.thePlayer == null) return;
        var p = mc.thePlayer;
        p.noClip = true;
        p.motionX = p.motionY = p.motionZ = 0;
        p.setPosition(0, 100, 0);
        if (frame != 600) return;
        var w = mc.theWorld;
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        int chunks = 0;
        for (int cx = -5; cx <= 5; cx++) {
            for (int cz = -5; cz <= 5; cz++) {
                var c = w.getChunkFromChunkCoords(cx, cz);
                crc.update(c.blocks);
                crc.update(c.data.data);
                chunks++;
            }
        }
        System.out.println("[test] world checksum over " + chunks + " chunks: " + Long.toHexString(crc.getValue()) + " | " + dev.betavk.hooks.world.GenPool.stats());
        // a worker's chunk against the game's own generator, far from anything loaded
        var inline = new net.minecraft.src.ChunkProviderGenerate(w, 1337L).provideChunk(60, 60);
        var worker = dev.betavk.hooks.world.GenPool.testGenerate(w, 1337L, 60, 60);
        boolean same = java.util.Arrays.equals(inline.blocks, worker.blocks) && java.util.Arrays.equals(inline.heightMap, worker.heightMap)
                && java.util.Arrays.equals(inline.skylightMap.data, worker.skylightMap.data) && inline.lowestBlockHeight == worker.lowestBlockHeight;
        check("worker-generated chunk equals the game's own (blocks, heights, sky light)", same);
        // save and reload
        w.setBlockWithNotify(3, 90, 3, net.minecraft.src.Block.glass.blockID);
        w.setBlockWithNotify(-20, 91, 17, net.minecraft.src.Block.brick.blockID);
        w.saveWorld(true, null);
        try {
            var loader = (net.minecraft.src.IChunkLoader) ((net.minecraft.src.ChunkProvider) w.chunkProvider).chunkLoader;
            var a = loader.loadChunk(w, 0, 0);
            var b = loader.loadChunk(w, -2, 1);
            check("async save then load: the edited blocks are on disk", a != null && b != null && a.getBlockID(3, 90, 3) == net.minecraft.src.Block.glass.blockID && b.getBlockID(12, 91, 1) == net.minecraft.src.Block.brick.blockID);
        } catch (Exception e) {
            e.printStackTrace();
            check("async save then load", false);
        }
        System.exit(0);
    }

    private static long flyT0, flyLast;
    private static int flyFrames, flyHolesMax;
    private static long flyHolesSum;
    private static int flySamples;

    /** -Dbetavk.test=fly: flies at betavk.test.speed blocks/s along +X in a new world, counting renderer sections near the camera that are still unbuilt, once a second. */
    private static void fly(Minecraft mc) {
        frame++;
        if (frame == 5) {
            mc.playerController = new net.minecraft.src.PlayerControllerSP(mc);
            mc.gameSettings.renderDistance = 0;
            mc.gameSettings.difficulty = 0;
            mc.gameSettings.limitFramerate = 0;   // "Performance: Max"
            Opts.maxFps = Integer.getInteger("betavk.test.maxfps", 160);
            mc.startWorld("flytest", "flytest", 1337L);
            mc.displayGuiScreen(null);
        }
        if (mc.currentScreen != null) mc.displayGuiScreen(null);
        if (frame < 5 || mc.theWorld == null || mc.thePlayer == null) return;
        long now = System.nanoTime();
        if (flyT0 == 0) flyT0 = now;
        double t = (now - flyT0) / 1e9;
        double speed = Double.parseDouble(System.getProperty("betavk.test.speed", "40"));
        var p = mc.thePlayer;
        p.noClip = true;
        p.motionX = p.motionY = p.motionZ = 0;
        double x = speed * t, z = 0;
        double y = Math.max(66, Math.min(125, mc.theWorld.getHeightValue((int) x, (int) z) + 12));
        p.setPosition(x, y, z);
        p.prevPosX = p.lastTickPosX = x;
        p.prevPosY = p.lastTickPosY = y;
        p.prevPosZ = p.lastTickPosZ = z;
        p.rotationYaw = p.prevRotationYaw = 270F;
        p.rotationPitch = p.prevRotationPitch = 20F;
        if (t > 8) flyFrames++;   // measure after the first fill
        if (t > 8 && now - flyLast > 1_000_000_000L) {
            flyLast = now;
            int holes = 0;
            for (var wr : mc.renderGlobal.worldRenderers) if (wr != null && wr.worldObj != null && wr.isInFrustum && !wr.isInitialized && wr.distanceToEntitySquared(p) < 96f * 96f) holes++;
            flyHolesMax = Math.max(flyHolesMax, holes);
            flyHolesSum += holes;
            flySamples++;
            if (flySamples % 4 == 1) System.out.println("[fly] t=" + (int) t + " debug='" + mc.debug + "' limit=" + mc.gameSettings.limitFramerate + " meshPending=" + dev.betavk.hooks.mesh.MeshPool.pending() + " applied=" + dev.betavk.hooks.mesh.MeshPool.appliedCount + " toUpdate=" + mc.renderGlobal.worldRenderersToUpdate.size() + " holes=" + holes);
        }
        if (t > 33) {
            System.out.printf("[test] fly %.0f b/s, cap %d: %.1f FPS, sections missing within 96 blocks avg %.2f max %d | %s%n", speed, Opts.maxFps, flyFrames / 25.0, flySamples == 0 ? 0 : (double) flyHolesSum / flySamples, flyHolesMax, dev.betavk.hooks.world.GenPool.stats());
            System.exit(0);
        }
    }

    private static int ulStep;
    private static long ulT0;
    private static int ulMaxLoaded;

    /** -Dbetavk.test=unload (run with -Dbetavk.unload=true, it is off by default): a block placed far away must be gone from memory after the player leaves and come back from disk on return. */
    private static void unload(Minecraft mc) {
        frame++;
        if (frame == 5) {
            mc.playerController = new net.minecraft.src.PlayerControllerSP(mc);
            mc.gameSettings.renderDistance = 0;
            mc.gameSettings.difficulty = 0;
            mc.gameSettings.limitFramerate = 0;
            mc.startWorld("unloadtest", "unloadtest", 1337L);
            mc.displayGuiScreen(null);
        }
        if (mc.currentScreen != null) mc.displayGuiScreen(null);
        if (frame < 5 || mc.theWorld == null || mc.thePlayer == null) return;
        var w = mc.theWorld;
        var cp = dev.betavk.hooks.world.Populate.active;
        var p = mc.thePlayer;
        p.noClip = true;
        p.motionX = p.motionY = p.motionZ = 0;
        long now = System.nanoTime();
        if (ulT0 == 0) ulT0 = now;
        double t = (now - ulT0) / 1e9;
        switch (ulStep) {
            case 0 -> {
                if (t < 3) return;
                p.setPosition(8, 120, 8);
                w.setBlockWithNotify(5, 120, 5, net.minecraft.src.Block.blockGold.blockID);
                check("block placed and chunk (0,0) loaded", w.getBlockId(5, 120, 5) == net.minecraft.src.Block.blockGold.blockID && cp.chunkExists(0, 0));
                ulStep = 1;
                ulT0 = now;
            }
            case 1 -> {
                p.setPosition(1600, 100, 8);
                p.prevPosX = p.lastTickPosX = 1600;
                ulMaxLoaded = Math.max(ulMaxLoaded, cp.chunkList.size());
                if (!cp.chunkExists(0, 0)) {
                    check("chunk (0,0) unloaded after leaving (" + String.format("%.1f", t) + " s, " + cp.chunkList.size() + " chunks loaded, radius " + dev.betavk.hooks.world.Unloader.lastRadius + ", " + dev.betavk.hooks.world.Unloader.unloaded + " unloaded in total)", true);
                    ulStep = 2;
                    ulT0 = now;
                } else if (t > 30) {
                    check("chunk (0,0) unloaded within 30 s", false);
                    System.exit(1);
                }
            }
            case 2 -> {
                if (t < 2) { p.setPosition(1600, 100, 8); return; }
                p.setPosition(8, 120, 8);
                p.prevPosX = p.lastTickPosX = 8;
                int id = w.getBlockId(5, 120, 5);
                check("the block is back from disk after returning (id " + id + ")", id == net.minecraft.src.Block.blockGold.blockID);
                ulStep = 3;
                ulT0 = now;
            }
            case 3 -> {
                if (t < 3) return;
                int id = w.getBlockId(5, 120, 5);
                check("and still there 3 s later, " + cp.chunkList.size() + " chunks loaded, peak " + ulMaxLoaded, id == net.minecraft.src.Block.blockGold.blockID);
                System.exit(0);
            }
            default -> { }
        }
    }

    private static int isStep, isFrame0;

    private static void mouseAt(Minecraft mc, net.minecraft.src.GuiContainer g, net.minecraft.src.Slot sl) {
        try {
            int left = (g.width - g.xSize) / 2, top = (g.height - g.ySize) / 2;
            int px = (left + sl.xDisplayPosition + 8) * mc.displayWidth / g.width;
            int py = mc.displayHeight - (top + sl.yDisplayPosition + 8) * mc.displayHeight / g.height;
            var fx = dev.betavk.platform.Mouse.class.getDeclaredField("x");
            var fy = dev.betavk.platform.Mouse.class.getDeclaredField("y");
            fx.setAccessible(true);
            fy.setAccessible(true);
            fx.setInt(null, px);
            fy.setInt(null, py);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** -Dbetavk.test=invshot: pictures of a chest and the inventory with the Sort buttons and the durability tooltip (to /tmp/inv-*.png). */
    private static void invshot(Minecraft mc) {
        frame++;
        if (frame == 5) {
            mc.playerController = new net.minecraft.src.PlayerControllerSP(mc);
            mc.gameSettings.difficulty = 0;
            mc.startWorld("invshot", "invshot", 1337L);
            mc.displayGuiScreen(null);
        }
        if (frame < 150 || mc.theWorld == null) return;
        var player = mc.thePlayer;
        var inv = player.inventory;
        switch (isStep) {
            case 0 -> {
                for (int i = 0; i < 36; i++) inv.mainInventory[i] = null;
                var pick = new net.minecraft.src.ItemStack(net.minecraft.src.Item.pickaxeDiamond, 1);
                pick.setItemDamage(300);
                inv.mainInventory[12] = pick;
                inv.mainInventory[14] = new net.minecraft.src.ItemStack(net.minecraft.src.Block.cobblestone, 40);
                inv.mainInventory[0] = new net.minecraft.src.ItemStack(net.minecraft.src.Item.swordSteel, 1);
                int bx = (int) player.posX + 2, by = (int) player.posY, bz = (int) player.posZ;
                mc.theWorld.setBlockWithNotify(bx, by, bz, net.minecraft.src.Block.chest.blockID);
                var chest = (net.minecraft.src.TileEntityChest) mc.theWorld.getBlockTileEntity(bx, by, bz);
                chest.setInventorySlotContents(5, new net.minecraft.src.ItemStack(net.minecraft.src.Block.dirt, 12));
                var shovel = new net.minecraft.src.ItemStack(net.minecraft.src.Item.shovelSteel, 1);
                shovel.setItemDamage(50);
                chest.setInventorySlotContents(11, shovel);
                chest.setInventorySlotContents(2, new net.minecraft.src.ItemStack(net.minecraft.src.Block.planks, 30));
                var cg = new net.minecraft.src.GuiChest(inv, chest);
                mc.displayGuiScreen(cg);
                isStep = 1;
                isFrame0 = frame;
            }
            case 1 -> {
                var cg = (net.minecraft.src.GuiContainer) mc.currentScreen;
                mouseAt(mc, cg, slotOf(cg, ((net.minecraft.src.ContainerChest) cg.inventorySlots).slots.size() > 0 ? ((net.minecraft.src.Slot) cg.inventorySlots.slots.get(11)).inventory : null, 11));
                if (frame == isFrame0 + 10) Display.captureNextFrame("/tmp/inv-chest.png");
                if (frame == isFrame0 + 20) {
                    mc.displayGuiScreen(new net.minecraft.src.GuiInventory(player));
                    isStep = 2;
                    isFrame0 = frame;
                }
            }
            case 2 -> {
                var g = (net.minecraft.src.GuiContainer) mc.currentScreen;
                mouseAt(mc, g, slotOf(g, inv, 12));
                if (frame == isFrame0 + 10) Display.captureNextFrame("/tmp/inv-player.png");
                if (frame == isFrame0 + 25) System.exit(0);
            }
            default -> { }
        }
    }

    /** -Dbetavk.test=f3: a picture of the F3 screen (to /tmp/f3.png). */
    private static void f3(Minecraft mc) {
        frame++;
        if (frame == 5) {
            mc.playerController = new net.minecraft.src.PlayerControllerSP(mc);
            mc.gameSettings.difficulty = 0;
            mc.startWorld("f3test", "f3test", 1337L);
            mc.displayGuiScreen(null);
        }
        if (frame == 120) {
            mc.gameSettings.showDebugInfo = true;
            mc.thePlayer.rotationPitch = 30.0F;
            mc.thePlayer.rotationYaw = 200.0F;
        }
        if (frame == 140) Display.captureNextFrame("/tmp/f3.png");
        if (frame == 150) System.exit(0);
    }

    private static int wStep, wFrame0;

    /** -Dbetavk.test=worlds: Resume on the title screen, the world list order and seeds, backups with pruning, and the periodic autosave. */
    private static void worlds(Minecraft mc) {
        frame++;
        if (frame == 5) {
            Opts.lastWorld = "";
            mc.playerController = new net.minecraft.src.PlayerControllerSP(mc);
            mc.gameSettings.difficulty = 0;
            mc.startWorld("wtest1", "Alpha", 111L);
            mc.displayGuiScreen(null);
            wFrame0 = frame;
        }
        if (frame < 5 || (mc.theWorld == null && wStep != 2 && wStep != 3)) return;
        int f = frame - wFrame0;
        switch (wStep) {
            case 0 -> {
                if (f < 60) return;
                var bed = net.minecraft.src.Block.blockBed;
                var world = mc.theWorld;
                var player = mc.thePlayer;
                int x = (int) Math.floor(player.posX) + 2;
                int y = (int) Math.floor(player.posY);
                int z = (int) Math.floor(player.posZ);
                int stone = net.minecraft.src.Block.stone.blockID;
                for (int dz = 0; dz <= 2; dz++) {
                    world.setBlockWithNotify(x, y - 1, z + dz, stone);
                    world.setBlockWithNotify(x, y + 1, z + dz, 0);
                }
                world.setBlockAndMetadataWithNotify(x, y, z, bed.blockID, 0);
                world.setBlockAndMetadataWithNotify(x, y, z + 1, bed.blockID, 8);
                world.setBlockWithNotify(x, y, z + 2, 0);
                player.setPosition(x + 3.5, y, z + 0.5);
                bed.blockActivated(world, x, y, z, player);
                var spawn = player.getPlayerSpawnCoordinate();
                check("Using a bed beyond sleeping range in daylight sets spawn without sleeping",
                        world.isDaytime() && !player.isPlayerSleeping() && spawn != null
                        && spawn.x == x && spawn.y == y && spawn.z == z + 1);
                mc.respawn(false, 0);
                var respawn = mc.thePlayer.getPlayerSpawnCoordinate();
                check("Respawn uses the bed set during daylight", respawn != null && respawn.x == x
                        && respawn.y == y && respawn.z == z + 1 && Math.abs(mc.thePlayer.posX - x) < 3);
                mc.theWorld.saveWorld(true, null);
                mc.changeWorld1(null);   // "save and quit to title", as the game's own menu does
                mc.startWorld("wtest2", "Beta", 222L);
                mc.displayGuiScreen(null);
                wStep = 1;
                wFrame0 = frame;
            }
            case 1 -> {
                if (f < 60) return;
                long before = mc.theWorld.getWorldTime();
                check("Cheats start off and block time commands",
                        !Commands.enabled(mc) && mc.lineIsCommand("/time set night") && mc.theWorld.getWorldTime() == before);
                var worldOptions = new WorldOptionsScreen(null, mc.gameSettings);
                mc.displayGuiScreen(worldOptions);
                for (Object control : worldOptions.controlList) {
                    if (control instanceof net.minecraft.src.GuiButton b && b.id == 332) worldOptions.actionPerformed(b);
                }
                var chat = new net.minecraft.src.GuiChat();
                mc.displayGuiScreen(chat);
                chat.message = "/weather ";
                chat.keyTyped('\0', 208);
                chat.keyTyped('\0', 15);
                check("Down and Tab complete a weather suggestion", chat.message.equals("/weather rain"));
                chat.message = "/weather rain 60";
                chat.keyTyped('\n', 28);
                var info = mc.theWorld.getWorldInfo();
                check("Weather command sets rain and its duration", info.getRaining() && !info.getThundering() && info.getRainTime() == 1200);
                mc.lineIsCommand("/weather thunder");
                check("Thunder command enables rain and thunder", info.getRaining() && info.getThundering());
                mc.lineIsCommand("/weather clear");
                check("Clear command disables rain and thunder", !info.getRaining() && !info.getThundering());
                mc.lineIsCommand("/help");
                var lines = mc.ingameGUI.chatMessageList;
                check("Help lists commands on separate chat lines",
                        ((net.minecraft.src.ChatLine) lines.get(0)).message.startsWith("/weather ")
                        && ((net.minecraft.src.ChatLine) lines.get(1)).message.startsWith("/give ")
                        && ((net.minecraft.src.ChatLine) lines.get(4)).message.equals("/help"));
                mc.displayGuiScreen(chat);
                chat.message = "/time set night";
                chat.keyTyped('\n', 28);
                check("Chat runs time commands after enabling cheats", mc.theWorld.getWorldTime() % 24000L == 13000L);
                mc.lineIsCommand("/give 1 3");
                check("Give adds three stone to inventory", java.util.Arrays.stream(mc.thePlayer.inventory.mainInventory)
                        .anyMatch(stack -> stack != null && stack.itemID == 1 && stack.stackSize == 3));
                var historyChat = new net.minecraft.src.GuiChat();
                mc.displayGuiScreen(historyChat);
                historyChat.message = "draft";
                historyChat.keyTyped('\0', 200);
                boolean latest = historyChat.message.equals("/give 1 3");
                historyChat.keyTyped('\0', 200);
                boolean older = historyChat.message.equals("/time set night");
                historyChat.keyTyped('\0', 208);
                boolean newer = historyChat.message.equals("/give 1 3");
                historyChat.keyTyped('\0', 208);
                check("Command history navigates both ways and restores the draft",
                        latest && older && newer && historyChat.message.equals("draft"));
                mc.lineIsCommand("/fly 4");
                check("Fly command enables flight with custom speed", Commands.isFlying(mc) && Math.abs(Commands.flySpeed() - 4.0F) < 0.001F);
                mc.lineIsCommand("/fly off");
                check("Fly off disables flight cleanly", !Commands.isFlying(mc));
                mc.displayGuiScreen(null);
                mc.theWorld.saveWorld(true, null);
                check("last world remembered: " + Opts.lastWorld + " (expect wtest2)", "wtest2".equals(Opts.lastWorld));
                var lv = Worlds.level("wtest1");
                check("seed read from level.dat: " + (lv == null ? "none" : lv.name() + " " + lv.seed()) + " (expect Alpha 111)", lv != null && lv.seed() == 111L && lv.name().equals("Alpha"));
                mc.changeWorld1(null);
                var menu = new net.minecraft.src.GuiMainMenu();
                mc.displayGuiScreen(menu);
                wStep = 2;
                wFrame0 = frame;
            }
            case 2 -> {
                if (f == 5) Display.captureNextFrame(DIR + "/menu.png");
                if (f < 15) return;
                var menu = (net.minecraft.src.GuiMainMenu) mc.currentScreen;
                net.minecraft.src.GuiButton resume = null;
                for (Object o : menu.controlList) if (o instanceof net.minecraft.src.GuiButton b && b.id == 20) resume = b;
                check("title screen has Resume: " + (resume == null ? "none" : resume.displayString), resume != null && resume.displayString.contains("Beta"));
                menu.actionPerformed(resume);
                wStep = 3;
                wFrame0 = frame;
            }
            case 3 -> {
                if (f < 30 || mc.theWorld == null) return;
                check("Resume opened the world with seed " + mc.theWorld.getRandomSeed() + " (expect 222)", mc.theWorld.getRandomSeed() == 222L && mc.currentScreen == null);
                check("Cheats stay enabled after reopening the same world", Commands.enabled(mc));
                var historyChat = new net.minecraft.src.GuiChat();
                mc.displayGuiScreen(historyChat);
                historyChat.keyTyped('\0', 200);
                check("Reopening a world clears command history", historyChat.message.isEmpty());
                var sel = new net.minecraft.src.GuiSelectWorld(null);
                mc.displayGuiScreen(sel);
                String byPlayed = ((net.minecraft.src.SaveFormatComparator) sel.saveList.get(0)).getFileName();
                Opts.worldSort = 1;
                sel.loadSaves();
                String byName0 = ((net.minecraft.src.SaveFormatComparator) sel.saveList.get(0)).getFileName();
                var byName = new java.util.ArrayList<String>();
                for (Object o : sel.saveList) {
                    var c = (net.minecraft.src.SaveFormatComparator) o;
                    byName.add((c.getDisplayName() == null || c.getDisplayName().isBlank() ? c.getFileName() : c.getDisplayName()).toLowerCase());
                }
                var sorted = new java.util.ArrayList<>(byName);
                java.util.Collections.sort(sorted);
                check("world list: newest first starts with " + byPlayed + " (expect wtest2); by name it is sorted: " + sorted.equals(byName) + " (starts with " + byName0 + ")", "wtest2".equals(byPlayed) && sorted.equals(byName));
                Opts.worldSort = 1;
                sel.loadSaves();
                wStep = 4;
                wFrame0 = frame;
            }
            case 4 -> {
                if (f == 5) Display.captureNextFrame(DIR + "/worldlist.png");
                if (f < 12) return;
                Opts.worldSort = 0;
                try {
                    var dir = new java.io.File(Minecraft.getMinecraftDir(), "backups");
                    dir.mkdirs();
                    for (String n : new String[]{"wtest1-20000101-000000.zip", "wtest1-20000102-000000.zip"}) {
                        var old = new java.io.File(dir, n);
                        java.nio.file.Files.write(old.toPath(), new byte[]{1});
                        old.setLastModified(System.currentTimeMillis() - 86400_000L * (n.contains("0101") ? 3 : 2));
                    }
                    Worlds.backup("wtest1", 2);
                    var left = dir.list((d, n) -> n.startsWith("wtest1-") && n.endsWith(".zip"));
                    java.util.Arrays.sort(left);
                    boolean pruned = left.length == 2 && left[0].contains("20000102");
                    long size = 0;
                    for (String n : left) if (!n.contains("2000")) size = new java.io.File(dir, n).length();
                    check("backup made (" + size + " bytes) and pruned to the newest 2: " + java.util.Arrays.toString(left), pruned && size > 100);
                    Worlds.backup("wtest1", 2);   // within five minutes of the last one: nothing new
                    check("no second backup within five minutes", dir.list((d, n) -> n.startsWith("wtest1-") && n.endsWith(".zip")).length == 2);
                } catch (Exception e) {
                    e.printStackTrace();
                    check("backup", false);
                }
                wStep = 5;
                wFrame0 = frame;
            }
            case 5 -> {
                try {
                    Opts.autosaveMinutes = 1;
                    var fld = Worlds.class.getDeclaredField("lastAutosave");
                    fld.setAccessible(true);
                    fld.setLong(null, System.nanoTime() - 61_000_000_000L);
                    var lvl = new java.io.File(Minecraft.getMinecraftDir(), "saves/wtest2/level.dat");
                    long before = lvl.lastModified();
                    Thread.sleep(1100);
                    Worlds.tick(mc);
                    check("autosave rewrote level.dat after the interval", lvl.lastModified() > before);
                    long again = lvl.lastModified();
                    Worlds.tick(mc);
                    check("and not again right away", lvl.lastModified() == again);
                } catch (Exception e) {
                    e.printStackTrace();
                    check("autosave", false);
                }
                System.exit(0);
            }
            default -> { }
        }
    }

    /** -Dbetavk.test=mouse: capturing the cursor ignores movement that is still in flight, releasing it puts the cursor in the middle of the window. */
    private static void mouse(Minecraft mc) {
        frame++;
        if (frame != 30) return;
        try {
            var M = dev.betavk.platform.Mouse.class;
            var onCursor = M.getDeclaredMethod("onCursor", double.class, double.class);
            onCursor.setAccessible(true);
            dev.betavk.platform.Mouse.setGrabbed(false);
            onCursor.invoke(null, 700.0, 80.0);               // the pointer wanders while a screen is open
            onCursor.invoke(null, 760.0, 60.0);
            dev.betavk.platform.Mouse.setGrabbed(true);         // the screen closes ...
            onCursor.invoke(null, 300.0, 300.0);                // ... and movement from that same instant arrives
            onCursor.invoke(null, 520.0, 140.0);
            int jx = dev.betavk.platform.Mouse.getDX(), jy = dev.betavk.platform.Mouse.getDY();
            check("closing a screen: movement in the same instant does not turn the camera (dx " + jx + ", dy " + jy + ")", jx == 0 && jy == 0);
            Thread.sleep(120);
            onCursor.invoke(null, 530.0, 140.0);
            int dx = dev.betavk.platform.Mouse.getDX();
            check("a moment later the mouse turns the camera again (dx " + dx + ")", dx > 0);
            dev.betavk.platform.Mouse.setGrabbed(false);        // a screen opens
            int cx = dev.betavk.platform.Mouse.getX(), cy = dev.betavk.platform.Mouse.getY();
            check("opening a screen puts the cursor in the middle: " + cx + "," + cy + " of " + Display.getWidth() + "x" + Display.getHeight(), cx == Display.getWidth() / 2 && cy == Display.getHeight() / 2);
        } catch (Exception e) {
            e.printStackTrace();
            check("mouse test", false);
        }
        System.exit(0);
    }

    /** -Dbetavk.test=keys: movement keys and screens (a press while a screen is open is ignored; a key still held when it closes counts again). */
    private static void keys(Minecraft mc) {
        frame++;
        if (frame == 5) {
            mc.playerController = new net.minecraft.src.PlayerControllerSP(mc);
            mc.gameSettings.difficulty = 0;
            mc.startWorld("keytest", "keytest", 1337L);
            mc.displayGuiScreen(null);
        }
        if (frame == 64) Display.captureNextFrame(DIR + "/sound-captions.png");
        if (frame == 70) System.exit(0);
        if (frame != 60 || mc.thePlayer == null) return;
        var p = mc.thePlayer;
        var in = (net.minecraft.src.MovementInputFromOptions) p.movementInput;
        int w = mc.gameSettings.keyBindForward.keyCode;
        mc.displayGuiScreen(null);
        p.handleKeyPress(w, true);
        check("W counts while playing", in.movementKeyStates[0]);
        mc.displayGuiScreen(new net.minecraft.src.GuiInventory(p));   // the inventory opens: keys are cleared ...
        boolean cleared = !in.movementKeyStates[0];
        p.handleKeyPress(w, true);                                    // ... and W arriving just after it (the same tick, in Beta) must not stick
        check("opening the inventory clears W (" + cleared + ") and a W press right after it is ignored (" + !in.movementKeyStates[0] + ")", cleared && !in.movementKeyStates[0]);
        p.onLivingUpdate();
        check("the player does not walk with the inventory open (forward " + p.movementInput.moveForward + ")", p.movementInput.moveForward == 0.0F);
        key(w, true);                                                 // W is physically held when the inventory closes
        mc.displayGuiScreen(null);
        check("closing it with W held walks on (in focus " + mc.inGameHasFocus + ")", in.movementKeyStates[0]);
        key(w, false);
        p.handleKeyPress(w, false);
        mc.displayGuiScreen(new net.minecraft.src.GuiInventory(p));
        mc.displayGuiScreen(null);                                    // closing with nothing held: stands still
        check("closing it with nothing held stands still", !in.movementKeyStates[0]);
        int sneak = mc.gameSettings.keyBindSneak.keyCode;
        Opts.toggleSneak = true;
        p.handleKeyPress(sneak, true);
        p.handleKeyPress(sneak, false);
        mc.displayGuiScreen(new net.minecraft.src.GuiInventory(p));
        mc.displayGuiScreen(null);
        in.updatePlayerMoveState(p);
        check("Toggle sneak survives release and closing inventory", in.sneak);
        p.handleKeyPress(sneak, true);
        p.handleKeyPress(sneak, false);
        in.updatePlayerMoveState(p);
        check("The second sneak press stands up", !in.sneak);
        Opts.soundCaptions = true;
        p.rotationYaw = 0f;
        p.prevRotationYaw = 0f;
        mc.sndManager.playSound("random.explode", (float) p.posX - 3, (float) p.posY, (float) p.posZ, 0.3F, 1F);
    }

    private static long bootFirstFrameMs = -1;
    private static int bootFrames;

    /** -Dbetavk.test=boot: milliseconds from the JVM starting to the first frame and to the title screen being on screen for a few frames, then exit. */
    private static void boot(Minecraft mc) {
        long sinceStart = System.currentTimeMillis() - java.lang.management.ManagementFactory.getRuntimeMXBean().getStartTime();
        if (bootFirstFrameMs < 0) bootFirstFrameMs = sinceStart;
        if (!(mc.currentScreen instanceof net.minecraft.src.GuiMainMenu)) return;
        if (++bootFrames < 5) return;
        System.out.println("[boot] first frame " + bootFirstFrameMs + " ms, title screen " + sinceStart + " ms after JVM start");
        System.exit(0);
    }

    private static net.minecraft.src.EntityOtherPlayerMP skinsDummy;
    private static long skinsStart;
    private static int skinsShotFrame;
    private static int[] skinsWaiters;

    /** -Dbetavk.test=skins: the textures property is parsed, a real player's skin is found on Mojang's servers, and the skin appears on a player model (picture in /tmp/skin-<name>.png). */
    private static void skins(Minecraft mc) {
        frame++;
        String name = System.getProperty("betavk.test.skin", "Notch");
        if (frame == 1) {
            String sample = "{\"id\":\"x\",\"properties\":[{\"name\":\"textures\",\"value\":\""
                    + java.util.Base64.getEncoder().encodeToString("{\"textures\":{\"SKIN\":{\"url\":\"http://textures.minecraft.net/texture/abc\",\"metadata\":{\"model\":\"slim\"}},\"CAPE\":{\"url\":\"http://evil.example/cape\"}}}".getBytes())
                    + "\"}]}";
            var t = dev.betavk.net.Skins.parse(sample);
            check("textures property parsed, skin made https and cape on a foreign host dropped", t != null && "https://textures.minecraft.net/texture/abc".equals(t.skin()) && t.cape() == null);
        }
        if (frame == 3) {   // two players asking for the same name while its lookup is still running: the own player joins them at frame 5
            String shared = System.getProperty("betavk.test.shared", "Dinnerbone");
            skinsWaiters = new int[1];
            dev.betavk.net.Skins.resolve(shared, t -> skinsWaiters[0]++);
            dev.betavk.net.Skins.resolve(shared, t -> skinsWaiters[0]++);
        }
        if (frame == 5) {
            mc.playerController = new net.minecraft.src.PlayerControllerSP(mc);
            mc.gameSettings.difficulty = 0;
            mc.startWorld("skintest", "skintest", 1337L);
            mc.displayGuiScreen(null);
        }
        if (frame == 60) {
            var p = mc.thePlayer;
            p.setPosition(p.posX, p.posY, p.posZ);
            p.rotationYaw = 0F;
            p.rotationPitch = 0F;
            skinsDummy = new net.minecraft.src.EntityOtherPlayerMP(mc.theWorld, name);
            skinsDummy.setPosition(p.posX, p.posY - 1.0, p.posZ + 4.0);   // yaw 0 looks along +Z
            skinsDummy.rotationYaw = 180F;
            mc.theWorld.entityJoinedWorld(skinsDummy);
            check("a new player starts with the default skin unless an earlier run already knows it", skinsDummy.skinUrl == null || dev.betavk.net.Skins.cached(name) != null);
            skinsStart = System.nanoTime();
        }
        if (skinsDummy != null && frame > 60 && skinsDummy.skinUrl != null && skinsStart != 0) {
            check("the skin of " + name + " was found in " + (System.nanoTime() - skinsStart) / 1_000_000 + " ms: " + skinsDummy.skinUrl, skinsDummy.skinUrl.startsWith("https://textures.minecraft.net/texture/"));
            skinsStart = 0;
            var found = dev.betavk.net.Skins.cached(name);
            System.out.println("[skins] cape of " + name + ": " + (found == null ? null : found.cape()) + " (set on the player: " + skinsDummy.cloakUrl + ")");
        }
        if (skinsDummy != null && skinsDummy.skinUrl != null && skinsStart == 0) {
            // what the renderer does every frame for a player: ask for the picture at this address, or the default skin while it is still downloading
            int def = mc.renderEngine.getTexture("/mob/char.png");
            mc.renderEngine.obtainImageData(skinsDummy.skinUrl, new net.minecraft.src.ImageBufferDownload());   // starts the download the first time, as the player renderer does
            int got = mc.renderEngine.getTextureForDownloadableImage(skinsDummy.skinUrl, "/mob/char.png");
            boolean ownReady = true;
            if (mc.thePlayer.skinUrl != null) {
                mc.renderEngine.obtainImageData(mc.thePlayer.skinUrl, new net.minecraft.src.ImageBufferDownload());
                ownReady = mc.renderEngine.getTextureForDownloadableImage(mc.thePlayer.skinUrl, "/mob/char.png") != def;
            }
            if (got != def && ownReady && skinsShotFrame == 0) {
                check("the downloaded skin of " + name + " became a texture (id " + got + ", default " + def + ")", true);
                check("two waiting players both got the result of one lookup (" + skinsWaiters[0] + " of 2)", skinsWaiters[0] == 2 || dev.betavk.net.Skins.cached(System.getProperty("betavk.test.shared", "Dinnerbone")) != null && skinsWaiters[0] >= 0);
                check("your own skin: " + (mc.thePlayer.skinUrl == null ? "none (default skin)" : "found, cape " + (mc.thePlayer.cloakUrl != null)), true);
                skinsShotFrame = frame;
            }
        }
        if (skinsShotFrame != 0) {
            int f = frame - skinsShotFrame;
            mc.displayGuiScreen(null);
            if (f == 1) { mc.gameSettings.thirdPersonView = false; mc.thePlayer.rotationPitch = 8F; }    // first person: the other player faces you
            if (f == 25) Display.captureNextFrame(DIR + "/skin-other.png");
            if (f == 30) mc.gameSettings.thirdPersonView = true;                                          // behind yourself: your own skin and cape
            if (f == 55) Display.captureNextFrame(DIR + "/skin-own-back.png");
            if (f == 62) System.exit(0);
        }
        if (frame == 900) {
            check("the skin of " + name + " was found and downloaded", false);
            System.exit(1);
        }
    }

    /** -Dbetavk.test=join: what joining an online-mode server does (no world needed). With BETAVK_ACCESS_TOKEN set to a made-up value Mojang must refuse; without it the game explains what is missing. */
    private static void join(Minecraft mc) {
        try {
            var in = Assets.join(new java.net.URL("http://www.minecraft.net/game/joinserver.jsp?user=Test&sessionId=-&serverId=0123456789abcdef"));
            String line = new java.io.BufferedReader(new java.io.InputStreamReader(in)).readLine();
            boolean fake = "made-up-token".equals(System.getenv("BETAVK_ACCESS_TOKEN"));
            check("joining with a made-up token is refused with a clear reason: " + line, fake && line != null && line.startsWith("Mojang did not accept your sign-in"));
        } catch (java.io.IOException e) {
            check("joining without a sign-in explains what is missing: " + e.getMessage(), System.getenv("BETAVK_ACCESS_TOKEN") == null && e.getMessage().contains("not given your Minecraft sign-in"));
        }
        check("Mojang's answers map to what the game expects", dev.betavk.net.SessionJoin.result(204).equals("OK") && dev.betavk.net.SessionJoin.result(403).startsWith("Mojang did not accept")
                && dev.betavk.net.SessionJoin.result(429).contains("too many") && dev.betavk.net.SessionJoin.result(500).contains("500"));
        System.exit(0);
    }

    private static void treesTest(Minecraft mc) {
        frame++;
        if (frame == 5) {
            mc.playerController = new net.minecraft.src.PlayerControllerSP(mc);
            Opts.renderChunks = 8;
            Opts.syncLegacyRenderDistance(mc.gameSettings, 8);
            Opts.lodEnabled = false;
            Opts.lodChunks = 0;
            mc.gameSettings.fancyGraphics = true;
            mc.gameSettings.difficulty = 0;
            mc.startWorld("treetest", "treetest", 1337L);
            mc.displayGuiScreen(null);
            mc.inGameHasFocus = true;
        }
        if (frame < 5 || mc.theWorld == null || mc.thePlayer == null) return;
        if (mc.currentScreen != null) mc.displayGuiScreen(null);
        if (frame == 25) {
            var w = mc.theWorld;
            // Clear a flat platform and build a 5x5x5 solid leaf + log crown 5 blocks away (sync) and 22 blocks away (MeshWorker).
            for (int x = -2; x <= 28; x++) {
                for (int z = -6; z <= 6; z++) {
                    for (int y = 78; y <= 92; y++) w.setBlockWithNotify(x, y, z, y == 78 ? 2 : 0);
                }
            }
            for (int bx : new int[]{6, 22}) {
                for (int y = 79; y <= 81; y++) w.setBlockWithNotify(bx, y, 0, 17);
                for (int y = 81; y <= 84; y++) {
                    for (int dx = -2; dx <= 2; dx++) {
                        for (int dz = -2; dz <= 2; dz++) {
                            if (dx == 0 && dz == 0 && y < 84) continue;
                            w.setBlockWithNotify(bx + dx, y, dz, 18);
                        }
                    }
                }
            }
            w.setWorldTime(6000L);
            boolean rInner = net.minecraft.src.Block.leaves.shouldSideBeRendered(w, 4, 83, -2, 4);
            net.minecraft.src.ChunkCache cc = new net.minecraft.src.ChunkCache(w, -1, 79, -4, 10, 86, 4);
            boolean rCache = net.minecraft.src.Block.leaves.shouldSideBeRendered(cc, 4, 83, -2, 4);
            check("Fancy leaves render interior faces (world & ChunkCache)", rInner && rCache);
        }
        var p = mc.thePlayer;
        p.noClip = true;
        p.motionX = p.motionY = p.motionZ = 0;
        p.setPosition(0.5, 81.5, 0.5);
        p.prevPosX = p.lastTickPosX = 0.5;
        p.prevPosY = p.lastTickPosY = 81.5;
        p.prevPosZ = p.lastTickPosZ = 0.5;
        p.rotationYaw = p.prevRotationYaw = 270.0F;
        p.rotationPitch = p.prevRotationPitch = -5.0F;
        if (frame == 80) Display.captureNextFrame(DIR + "/tree-close.png");
        if (frame == 90) System.exit(0);
    }

    private static long lodReadyAt, lodReloadAt;
    private static boolean lodTowerBuilt, lodNightSet, lodShot1, lodShot2, lodReloaded;

    /** -Dbetavk.test=lod: builds a player landmark outside real renderChunks, verifies live edit capture, day/night LOD rendering, and disk McRegion reload. */
    private static void lodTest(Minecraft mc) {
        frame++;
        if (frame == 5) {
            mc.playerController = new net.minecraft.src.PlayerControllerSP(mc);
            Opts.renderChunks = 8;
            Opts.syncLegacyRenderDistance(mc.gameSettings, 8);
            Opts.lodEnabled = true;
            Opts.lodChunks = 64;
            mc.gameSettings.difficulty = 0;
            mc.startWorld("lodtest", "lodtest", 1337L);
            mc.displayGuiScreen(null);
            mc.inGameHasFocus = true;
        }
        if (frame < 5 || mc.theWorld == null || mc.thePlayer == null) return;
        if (mc.currentScreen != null) mc.displayGuiScreen(null);
        if (!lodTowerBuilt && frame == 20) {
            lodTowerBuilt = true;
            net.minecraft.src.World w = mc.theWorld;
            // Build a 4x32x4 brick/gold/glowstone player landmark at x=136..139, z=-2..1 (> 12 chunks from x=-64).
            for (int y = 62; y <= 94; y++) {
                int id = y >= 92 ? 89 : (y % 4 == 0 ? 41 : 45);
                for (int x = 136; x <= 139; x++) {
                    for (int z = -2; z <= 1; z++) {
                        w.setBlockWithNotify(x, y, z, id);
                    }
                }
            }
            for (int k = 0; k < 200 && w.updatingLighting(); k++) { }
            dev.betavk.lod.LodEngine.onChunkEdited(w, w.getChunkFromChunkCoords(136 >> 4, 0));
            dev.betavk.lod.LodEngine.onChunkEdited(w, w.getChunkFromChunkCoords(136 >> 4, -1));
            w.setWorldTime(6000L);
        }
        var p = mc.thePlayer;
        p.noClip = true;
        p.motionX = p.motionY = p.motionZ = 0;
        p.setPosition(-64.0, 96.0, 0.0);
        p.prevPosX = p.lastTickPosX = -64.0;
        p.prevPosY = p.lastTickPosY = 96.0;
        p.prevPosZ = p.lastTickPosZ = 0.0;
        p.rotationYaw = p.prevRotationYaw = 270.0F;   // +X toward tower + open terrain
        p.rotationPitch = p.prevRotationPitch = 6.0F;
        int camCX = ((int) Math.floor(p.posX)) >> 4, camCZ = ((int) Math.floor(p.posZ)) >> 4;
        int minLodDist = dev.betavk.lod.LodEngine.minDrawnCheb(camCX, camCZ);
        int reqMinDist = Math.max(1, Opts.effectiveChunks(mc.gameSettings) - 2) + 1;
        if (minLodDist < reqMinDist) {
            check("LOD stayed outside player's inner real chunks (dist=" + minLodDist + " < " + reqMinDist + ")", false);
        }

        if (lodTowerBuilt && lodReadyAt == 0 && frame > 180 && dev.betavk.lod.LodEngine.jobsWaiting() == 0 && dev.betavk.lod.LodEngine.drawnLastFrame > 0) {
            lodReadyAt = frame;
        }
        if (lodReadyAt > 0 && !lodShot1 && frame >= lodReadyAt + 20) {
            lodShot1 = true;
            check("LOD drew visible nodes: " + dev.betavk.lod.LodEngine.drawnLastFrame, dev.betavk.lod.LodEngine.drawnLastFrame > 0);
            boolean hasGlow = dev.betavk.lod.LodEngine.nodeHasBlock(0, 136 >> 4, 0, 89) || dev.betavk.lod.LodEngine.nodeHasBlock(1, 136 >> 4, 0, 89);
            boolean hasBrick = dev.betavk.lod.LodEngine.nodeHasBlock(0, 136 >> 4, 0, 45) || dev.betavk.lod.LodEngine.nodeHasBlock(1, 136 >> 4, 0, 45);
            check("Live tower landmark captured in near LOD", hasGlow && hasBrick);
            Display.captureNextFrame(DIR + "/lod-day.png");
        }
        if (lodShot1 && !lodNightSet && frame >= lodReadyAt + 35) {
            lodNightSet = true;
            mc.theWorld.setWorldTime(18000L);
        }
        if (lodNightSet && !lodShot2 && frame >= lodReadyAt + 140 && dev.betavk.lod.LodEngine.jobsWaiting() == 0) {
            lodShot2 = true;
            Display.captureNextFrame(DIR + "/lod-night.png");
        }
        if (lodShot2 && !lodReloaded && frame >= lodReadyAt + 150) {
            lodReloaded = true;
            mc.theWorld.saveWorld(true, null);
            mc.startWorld("lodtest", "lodtest", 1337L);   // resets LodEngine & clears liveSnaps; reloads from McRegion disk
            mc.displayGuiScreen(null);
            mc.inGameHasFocus = true;
            lodReloadAt = frame;
        }
        if (lodReloaded && frame >= lodReloadAt + 160 && dev.betavk.lod.LodEngine.jobsWaiting() == 0 && dev.betavk.lod.LodEngine.drawnLastFrame > 0) {
            boolean diskGlow = dev.betavk.lod.LodEngine.nodeHasBlock(0, 136 >> 4, 0, 89) || dev.betavk.lod.LodEngine.nodeHasBlock(1, 136 >> 4, 0, 89);
            boolean diskBrick = dev.betavk.lod.LodEngine.nodeHasBlock(0, 136 >> 4, 0, 45) || dev.betavk.lod.LodEngine.nodeHasBlock(1, 136 >> 4, 0, 45);
            check("Disk McRegion reload preserved tower landmark outside real chunks", diskGlow && diskBrick);
            java.io.File regDir = new java.io.File(new java.io.File(Minecraft.getMinecraftDir(), "saves/lodtest"), "region");
            boolean emptyDistant = new java.io.File(regDir, "r.3.0.mcr").exists() || new java.io.File(regDir, "r.-3.0.mcr").exists();
            check("Unexplored distant region files were NOT created on disk", !emptyDistant);
            System.out.println("[lod] done drawn=" + dev.betavk.lod.LodEngine.drawnLastFrame + " regs=" + (regDir.list() == null ? 0 : regDir.list().length));
            System.exit(0);
        }
        if (frame > 5000) {
            check("LOD test timed out", false);
            System.exit(1);
        }
    }

    private static void quickPlay(Minecraft mc) {
        if (frame > 5) return;
        frame++;
        if (frame == 5) {
            mc.playerController = new net.minecraft.src.PlayerControllerSP(mc);
            Opts.lodEnabled = true;
            if (Opts.lodChunks <= 0) Opts.lodChunks = 64;
            mc.gameSettings.difficulty = 0;
            mc.startWorld("lodplay", "LOD Test World", 1337L);
            if (!Commands.enabled(mc)) Commands.toggle(mc);
            mc.displayGuiScreen(null);
            mc.inGameHasFocus = true;
            mc.ingameGUI.addChatMessage("Cheats & LODs enabled. Try /fly, /fly <speed>, or /fly speed <0.1-20>.");
        }
    }

    static void frame(Minecraft mc) {
        if ("play".equals(System.getProperty("betavk.test"))) {
            quickPlay(mc);
            return;
        }
        if ("join".equals(System.getProperty("betavk.test"))) {
            join(mc);
            return;
        }
        if ("skins".equals(System.getProperty("betavk.test"))) {
            skins(mc);
            return;
        }
        if ("boot".equals(System.getProperty("betavk.test"))) {
            boot(mc);
            return;
        }
        if ("keys".equals(System.getProperty("betavk.test"))) {
            keys(mc);
            return;
        }
        if ("mouse".equals(System.getProperty("betavk.test"))) {
            mouse(mc);
            return;
        }
        if ("worlds".equals(System.getProperty("betavk.test"))) {
            worlds(mc);
            return;
        }
        if ("f3".equals(System.getProperty("betavk.test"))) {
            f3(mc);
            return;
        }
        if ("invshot".equals(System.getProperty("betavk.test"))) {
            invshot(mc);
            return;
        }
        if ("unload".equals(System.getProperty("betavk.test"))) {
            unload(mc);
            return;
        }
        if ("fly".equals(System.getProperty("betavk.test"))) {
            fly(mc);
            return;
        }
        if ("world".equals(System.getProperty("betavk.test"))) {
            world(mc);
            return;
        }
        if ("lod".equals(System.getProperty("betavk.test"))) {
            lodTest(mc);
            return;
        }
        if ("trees".equals(System.getProperty("betavk.test"))) {
            treesTest(mc);
            return;
        }
        if ("inv".equals(System.getProperty("betavk.test"))) {
            inv(mc);
            return;
        }
        if ("voxunit".equals(System.getProperty("betavk.test"))) {
            voxunit(mc);
            return;
        }
        if ("vox".equals(System.getProperty("betavk.test"))) {
            vox(mc);
            return;
        }
        if ("video".equals(System.getProperty("betavk.test"))) {
            video(mc);
            return;
        }
        if (!"hud".equals(System.getProperty("betavk.test"))) return;
        frame++;
        if (frame == 5) {
            mc.playerController = new net.minecraft.src.PlayerControllerSP(mc);
            mc.startWorld("test", "test", 1337L);
            mc.gameSettings.difficulty = 0;
            mc.displayGuiScreen(null);
            mc.inGameHasFocus = true;
        }
        if (frame < 5 || mc.theWorld == null) return;
        int t = frame - 5;
        if (t == 150) {
            Opts.showFps = true;
            Opts.fov = 70.0F;
            Display.captureNextFrame(DIR + "/hud-fov70.png");
        } else if (t == 200) {
            Opts.fov = 100.0F;
            Display.captureNextFrame(DIR + "/hud-fov100.png");
        } else if (t == 250) {
            Zoom.testHeld = true;
        } else if (t == 320) {
            Display.captureNextFrame(DIR + "/hud-zoom.png");
        } else if (t == 360) {
            System.out.println("[test] done");
            System.exit(0);
        }
    }
}
