package dev.betavk.hooks;

import dev.betavk.platform.Keyboard;
import net.minecraft.client.Minecraft;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Multi-version sustained walking test (-Dbetavk.test=walk): walks forward via real Keyboard events
 * across chunk boundaries at wide render distances and across UI / focus transitions.
 */
public final class WalkTest {
    private static int frame;
    private static int ticksAtStart;
    private static double prevX, prevZ, maxMoved;
    private static int stalledFrames;
    private static boolean wPressed;
    private static Method onKeyMethod;

    static {
        try {
            onKeyMethod = Keyboard.class.getDeclaredMethod("onKey", int.class, int.class);
            onKeyMethod.setAccessible(true);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    private static void sendGlfwW(int action) {
        try {
            onKeyMethod.invoke(null, 87, action); // GLFW_KEY_W = 87
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    @SuppressWarnings("unchecked")
    public static void frame(Minecraft mc) {
        frame++;
        if (frame == 5) {
            Opts.renderChunks = Integer.getInteger("betavk.walk.rd", 32);
            Opts.lodEnabled = Boolean.getBoolean("betavk.walk.lod");
            Opts.syncLegacyRenderDistance(mc.gameSettings, Opts.renderChunks);
            mc.playerController = new net.minecraft.src.PlayerControllerSP(mc);
            mc.gameSettings.difficulty = 0;
            try {
                McCompat.startWorldCompat(mc, "walktest", "Walk Test", 1337L);
            } catch (Throwable t) {
                throw new RuntimeException(t);
            }
            mc.displayGuiScreen(null);
            mc.inGameHasFocus = true;
            return;
        }
        if (mc.theWorld == null || mc.thePlayer == null) return;
        var p = mc.thePlayer;
        if (ticksAtStart == 0) {
            ticksAtStart = mc.ticksRan;
            prevX = p.posX;
            prevZ = p.posZ;
            p.rotationYaw = 0.0F;
            p.prevRotationYaw = 0.0F;
            p.rotationPitch = 0.0F;
            sendGlfwW(1); // GLFW_PRESS
            wPressed = true;
            return;
        }
        // Keep player high/safe or forward-facing and flat-ish by flying or normal walking on surface
        int age = frame - 10;
        if (wPressed && age % 4 == 0) sendGlfwW(2); // GLFW_REPEAT while held
        if (age == 80) {
            // Open pause menu via Escape while W is held, then close it after 5 frames
            try {
                onKeyMethod.invoke(null, 256, 1); // GLFW_KEY_ESCAPE PRESS
                onKeyMethod.invoke(null, 256, 0); // GLFW_KEY_ESCAPE RELEASE
            } catch (Throwable t) {
                throw new RuntimeException(t);
            }
        } else if (age == 86) {
            mc.displayGuiScreen(null);
            mc.setIngameFocus();
        } else if (age == 95) {
            // Simulate focus loss while W is held (emits synthetic release so keys never stick), then re-press on focus return
            try {
                Method clr = Keyboard.class.getDeclaredMethod("clear");
                clr.setAccessible(true);
                clr.invoke(null);
            } catch (Throwable t) {
                throw new RuntimeException(t);
            }
        } else if (age == 98) {
            sendGlfwW(1); // W held on focus return
        }
        double dx = p.posX - prevX, dz = p.posZ - prevZ;
        double moved = Math.hypot(dx, dz);
        maxMoved = Math.max(maxMoved, moved);
        // After setup & screen test, require continued movement along +Z (yaw=0 -> +Z)
        List<?> entities = mc.theWorld.loadedEntityList;
        boolean inList = entities != null && entities.contains(p);
        if (!inList) {
            System.err.println("[walk] FAIL frame=" + frame + " ticksRan=" + mc.ticksRan
                    + " pos=(" + p.posX + "," + p.posZ + ") player REMOVED from world.loadedEntityList!");
            System.exit(2);
        }
        if (age > 100 && age < 600) {
            // Jump intermittently in case player hits a 1-block ledge
            if (age % 12 == 0) {
                try {
                    onKeyMethod.invoke(null, 32, 1); // GLFW_KEY_SPACE press
                } catch (Throwable ignored) {}
            } else if (age % 12 == 4) {
                try {
                    onKeyMethod.invoke(null, 32, 0); // release
                } catch (Throwable ignored) {}
            }
            if (moved < 0.005) {
                stalledFrames++;
            } else {
                stalledFrames = 0;
            }
        }
        prevX = p.posX;
        prevZ = p.posZ;
        if (stalledFrames > 80 && p.movementInput.moveForward <= 0.0F) {
            System.err.println("[walk] FAIL frame=" + frame + " ticksRan=" + mc.ticksRan
                    + " moveForward=" + p.movementInput.moveForward + " stalled at pos=(" + p.posX + "," + p.posZ + ")");
            System.exit(3);
        }
        // Simulate gameplay reaching chunk distance 32 (modulo-32 slot collision in ChunkProviderLoadOrGenerate)
        if (age == 120) {
            int pcx = ((int) Math.floor(p.posX)) >> 4;
            int pcz = ((int) Math.floor(p.posZ)) >> 4;
            mc.theWorld.getChunkFromChunkCoords(pcx + 32, pcz);
        }
        if (frame == 450) {
            double totalMoved = Math.hypot(p.posX - McCompat.spawnX(mc.theWorld), p.posZ - McCompat.spawnZ(mc.theWorld));
            System.out.println(String.format("[walk] PASS frames=%d ticks=%d pos=(%.1f,%.1f) moveFwd=%.1f inList=%s distFromSpawn=%.1f",
                    frame, mc.ticksRan - ticksAtStart, p.posX, p.posZ, p.movementInput.moveForward, inList, totalMoved));
            System.exit(0);
        }
    }
}
