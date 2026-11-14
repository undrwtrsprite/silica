package dev.betavk.hooks;

import dev.betavk.platform.Display;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;

/**
 * Scene captures for comparing builds (-Dbetavk.shots=DIR, used with the bench harness' world): the same seed, the same places (found by scanning biomes and
 * the sea in a fixed order), the same clock and weather, each shot taken once every section in view is built. The same file runs in the in-tree build (as
 * dev.betavk.bench.Shots), so the two sets of PNGs can be diffed with scripts/imgdiff.py.
 */
final class Shots {
    private Shots() {
    }

    static final String DIR = System.getProperty("betavk.shots");

    private record Scene(String name, double x, double z, double dy, float yaw, float pitch, long time) { }

    private static List<Scene> scenes;
    private static int idx = -1;
    private static long sceneT0, lastHoleT, shotT;
    private static boolean captured;

    private static List<Scene> resolve(Minecraft mc) {
        var w = mc.theWorld;
        var cm = w.getWorldChunkManager();
        String[] biomes = {"Plains", "Forest", "Desert", "Tundra", "Taiga", "Ice Desert", "Swampland", "Rainforest"};
        double[][] found = new double[biomes.length][];
        double[] ocean = null;
        // spiral outwards in steps of 48 blocks
        for (int r = 0; r <= 14 && (ocean == null || java.util.Arrays.asList(found).contains(null)); r++) {
            for (int gx = -r; gx <= r; gx++) {
                for (int gz = -r; gz <= r; gz++) {
                    if (Math.max(Math.abs(gx), Math.abs(gz)) != r) continue;
                    int x = gx * 48, z = gz * 48;
                    String b = cm.getBiomeGenAt(x, z).biomeName;
                    for (int i = 0; i < biomes.length; i++) if (found[i] == null && biomes[i].equals(b)) found[i] = new double[]{x, z};
                    if (ocean == null && !"Desert".equals(b)) {
                        w.getChunkFromChunkCoords(x >> 4, z >> 4);
                        int id = w.getBlockId(x, 62, z);
                        if ((id == 8 || id == 9) && w.getBlockId(x + 40, 62, z) == id && w.getBlockId(x - 40, 62, z) == id) ocean = new double[]{x, z};
                    }
                }
            }
        }
        List<Scene> list = new ArrayList<>();
        for (int i = 0; i < biomes.length; i++) if (found[i] != null) list.add(new Scene(biomes[i].replace(' ', '-') + "-day", found[i][0], found[i][1], 4, 270F, 6F, 6000L));
        if (ocean != null) {
            list.add(new Scene("ocean-day", ocean[0], ocean[1], 3, 270F, 4F, 6000L));
            list.add(new Scene("ocean-down", ocean[0], ocean[1], 6, 90F, 45F, 6000L));
        }
        if (found[1] != null) {
            list.add(new Scene("Forest-sunset", found[1][0], found[1][1], 4, 90F, 6F, 12500L));
            list.add(new Scene("Forest-night", found[1][0], found[1][1], 4, 180F, 6F, 18000L));
            list.add(new Scene("Forest-high", found[1][0], found[1][1], 60, 0F, 25F, 6000L));
        }
        if (found[3] != null) list.add(new Scene("Tundra-high", found[3][0], found[3][1], 50, 135F, 30F, 6000L));
        return list;
    }

    private static int holes(Minecraft mc) {
        int holes = 0;
        for (var w : mc.renderGlobal.worldRenderers) {
            if (w != null && w.worldObj != null && w.isInFrustum && !w.isInitialized && w.distanceToEntitySquared(mc.thePlayer) < 96f * 96f) holes++;
        }
        return holes;
    }

    /** Called every frame once the world is up; returns true when every scene has been shot. */
    static boolean frame(Minecraft mc) {
        long now = System.nanoTime();
        var p = mc.thePlayer;
        if (scenes == null) {
            scenes = resolve(mc);
            System.out.println("[shots] " + scenes.size() + " scenes: " + scenes.stream().map(s -> s.name() + "@" + (int) s.x() + "," + (int) s.z()).toList());
        }
        if (idx >= scenes.size()) return true;
        mc.theWorld.worldInfo.setRaining(false);
        mc.theWorld.worldInfo.setThundering(false);
        if (idx < 0 || (captured && now - shotT > 400_000_000L)) {
            idx++;
            if (idx >= scenes.size()) return true;
            captured = false;
            sceneT0 = lastHoleT = now;
            Scene s = scenes.get(idx);
            mc.theWorld.getChunkFromChunkCoords((int) s.x() >> 4, (int) s.z() >> 4);
            p.noClip = true;
        }
        Scene s = scenes.get(idx);
        mc.theWorld.setWorldTime(s.time());
        double ground = mc.theWorld.getHeightValue((int) Math.floor(s.x()), (int) Math.floor(s.z()));
        double y = Math.max(ground, 62) + s.dy();
        p.setPosition(s.x(), y, s.z());
        p.prevPosX = p.lastTickPosX = s.x();
        p.prevPosY = p.lastTickPosY = y;
        p.prevPosZ = p.lastTickPosZ = s.z();
        p.motionX = p.motionY = p.motionZ = 0;
        p.rotationYaw = p.prevRotationYaw = s.yaw();
        p.rotationPitch = p.prevRotationPitch = s.pitch();
        p.health = 20;
        p.distanceWalkedModified = p.prevDistanceWalkedModified = 0F;
        p.field_775_e = p.field_774_f = p.cameraPitch = p.field_705_Q = 0F;   // no view bobbing: its phase depends on how many ticks ran
        if (mc.currentScreen != null) mc.displayGuiScreen(null);
        if (!captured) {
            if (holes(mc) > 0) lastHoleT = now;
            boolean settled = now - lastHoleT > 2_500_000_000L && now - sceneT0 > 6_000_000_000L;
            if (settled || now - sceneT0 > 60_000_000_000L) {
                Display.captureNextFrame(DIR + "/" + s.name() + ".png");
                captured = true;
                shotT = now;
                System.out.println("[shots] " + s.name() + (settled ? "" : " (not fully built)"));
            }
        }
        return false;
    }
}
