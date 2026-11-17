package dev.betavk.hooks;

import com.sun.management.GarbageCollectionNotificationInfo;
import dev.betavk.hooks.mesh.MeshPool;
import dev.betavk.render.Gfx;
import java.io.File;
import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import javax.management.NotificationEmitter;
import javax.management.openmbean.CompositeData;
import net.minecraft.client.Minecraft;
import net.minecraft.src.EntityPlayerSP;

/**
 * Benchmark harness for the patched game (-Dbetavk.bench=true), same method and result file as the in-tree one: a fixed-seed world, a scripted TIME-driven camera
 * (same positions at the same moments in every run), a warm-up that is discarded, then frame times, chunk work, GC pauses and memory as JSON.
 * <p>
 * Properties: betavk.bench.distance (0 far, 1 normal, 2 short, 3 tiny), .seed, .warmup (s), .seconds (s), .out (json path), .label, .speed (blocks/s),
 * .walk (on foot instead of flying), .alt, .x, .z, .time, .maxfps, .advanced, .world. Called once per frame from MainLoop.afterFrame.
 */
final class Bench {
    private Bench() {
    }

    static final boolean ENABLED = Boolean.getBoolean("betavk.bench");

    private enum State { INIT, WARMUP, MEASURE, DONE }

    private static State state = State.INIT;
    private static final int DISTANCE = Integer.getInteger("betavk.bench.distance", 0);
    private static final long SEED = Long.getLong("betavk.bench.seed", 1337L);
    private static final double WARMUP_S = Double.parseDouble(System.getProperty("betavk.bench.warmup", "20"));
    private static final double MEASURE_S = Double.parseDouble(System.getProperty("betavk.bench.seconds", "60"));
    private static final double SPEED = Double.parseDouble(System.getProperty("betavk.bench.speed", "6"));
    private static final boolean WALK = Boolean.getBoolean("betavk.bench.walk");
    private static final double ALT = Double.parseDouble(System.getProperty("betavk.bench.alt", "0"));

    private static long phaseStart, lastFrame, pathStart, secStart, startupSecT0, lastHeapSample;
    private static double x0, z0, lastT, curY;
    private static boolean yInit, timeSet, measuring, shotTaken;
    private static int frames, secFrames, inactiveFrames, skipFrames, holesMax, holesSamples, initFrames;
    private static long holesSum;
    private static double[] frameMs = new double[1 << 16];
    private static final List<Double> fpsPerSecond = new ArrayList<>();
    private static final List<Integer> startupHoles = new ArrayList<>();
    private static final List<Long> heapAfterGc = new ArrayList<>();
    private static final List<Double> gcPauseMs = new ArrayList<>();
    private static long gcCountStart, gcTimeStart, peakHeapUsed, startHeapUsed;
    private static long rebuildsStart, rebuildNanosStart;

    static void frame(Minecraft mc) {
        if (state == State.INIT) {
            if (++initFrames == 3) init(mc);   // the window and the menu have been drawn once
            return;
        }
        onFrame(mc);
    }

    private static void init(Minecraft mc) {
        int chunks = Integer.getInteger("betavk.bench.chunks", 0);
        Opts.renderChunks = chunks >= 2 ? Math.min(32, chunks) : 0;
        int benchLod = Integer.getInteger("betavk.bench.lod", 0);
        Opts.lodEnabled = benchLod > 0;
        if (benchLod > 0) Opts.lodChunks = benchLod;
        mc.gameSettings.renderDistance = Opts.renderChunks >= 2 ? 0 : DISTANCE;
        if (Opts.renderChunks >= 2) Opts.syncLegacyRenderDistance(mc.gameSettings, Opts.renderChunks);
        mc.gameSettings.limitFramerate = 0;   // "Performance: Max": no sleeping
        Opts.maxFps = Integer.getInteger("betavk.bench.maxfps", 0);   // uncapped unless asked
        mc.gameSettings.difficulty = 0;       // peaceful: no mob spawns
        mc.gameSettings.showDebugInfo = false;
        mc.gameSettings.advancedOpengl = Boolean.getBoolean("betavk.bench.advanced");   // occlusion culling, off by default as in the game
        mc.gameSettings.soundVolume = 0f;
        mc.gameSettings.musicVolume = 0f;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            ((NotificationEmitter) gc).addNotificationListener((n, hb) -> {
                if (!measuring || !GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION.equals(n.getType())) return;
                GarbageCollectionNotificationInfo info = GarbageCollectionNotificationInfo.from((CompositeData) n.getUserData());
                synchronized (gcPauseMs) {
                    gcPauseMs.add((double) info.getGcInfo().getDuration());
                }
            }, null, null);
        }
        mc.playerController = new net.minecraft.src.PlayerControllerSP(mc);
        long t0 = System.nanoTime();
        String worldName = System.getProperty("betavk.bench.world", "bench");
        mc.startWorld(worldName, worldName, SEED);   // synchronous: returns once the world is created
        if (Boolean.getBoolean("betavk.worldgen")) {   // load the 25x25 spawn area in the original server's nested order, then save and exit
            net.minecraft.src.ChunkCoordinates sp = mc.theWorld.getSpawnPoint();
            for (int ox = -196; ox <= 196; ox += 16)
                for (int oz = -196; oz <= 196; oz += 16) mc.theWorld.getChunkFromChunkCoords((Integer.getInteger("betavk.worldgen.x", sp.x) + ox) >> 4, (Integer.getInteger("betavk.worldgen.z", sp.z) + oz) >> 4);
            var pop = dev.betavk.hooks.world.Populate.active;
            if (pop != null) dev.betavk.hooks.world.Populate.drain(pop, -1);
            mc.theWorld.saveWorld(true, null);   // chunks are only written by a save, and no game tick runs before we exit
            System.out.println("[worldgen] spawn " + sp.x + "," + sp.z + ": 25x25 chunks loaded; " + dev.betavk.hooks.world.GenPool.stats());
            mc.shutdown();
            state = State.DONE;
            return;
        }
        System.out.printf("[bench] startWorld (initial chunk generation) took %.0f ms; %s%n", (System.nanoTime() - t0) / 1e6, dev.betavk.hooks.world.GenPool.stats());
        mc.displayGuiScreen(null);
        // The world spawn is chosen with an unseeded Random in b1.7.3, so it differs between runs: anchor the path at a fixed coordinate instead.
        x0 = Double.parseDouble(System.getProperty("betavk.bench.x", "0"));
        z0 = Double.parseDouble(System.getProperty("betavk.bench.z", "0"));
        mc.theWorld.setWorldTime(6000L);
        pathStart = phaseStart = lastFrame = System.nanoTime();
        state = State.WARMUP;
        System.out.printf("[bench] world ready, seed=%d distance=%d path origin=(%.1f, %.1f); warm-up %.0f s then measure %.0f s%n", SEED, DISTANCE, x0, z0, WARMUP_S, MEASURE_S);
    }

    private static int holes(Minecraft mc) {
        int holes = 0;
        for (var w : mc.renderGlobal.worldRenderers) {
            if (w != null && w.worldObj != null && w.isInFrustum && !w.isInitialized && w.distanceToEntitySquared(mc.thePlayer) < 96f * 96f) holes++;
        }
        return holes;
    }

    private static void onFrame(Minecraft mc) {
        if (state == State.DONE || mc.theWorld == null || mc.thePlayer == null) return;
        long now = System.nanoTime();
        double t = (now - pathStart) / 1e9;
        if (mc.currentScreen != null) mc.displayGuiScreen(null);
        if (Shots.DIR != null) {   // scene captures instead of the flight
            if (Shots.frame(mc)) {
                state = State.DONE;
                mc.shutdown();
            }
            return;
        }
        drive(mc, t);

        if (state == State.WARMUP && (now - phaseStart) / 1e9 >= WARMUP_S) {
            state = State.MEASURE;
            measuring = true;
            phaseStart = secStart = lastFrame = now;
            long[] gc = gcTotals();
            gcCountStart = gc[0];
            gcTimeStart = gc[1];
            startHeapUsed = usedHeap();
            rebuildsStart = MeshPool.appliedCount;
            rebuildNanosStart = MeshPool.appliedNanos;
            System.out.println("[bench] measuring...");
            return;
        }
        if (state == State.WARMUP && startupHoles.size() < 20 && now - startupSecT0 >= 1_000_000_000L) {
            startupSecT0 = now;   // sections in view near the camera that are still unbuilt, once per second from world start
            startupHoles.add(holes(mc));
        }
        if (state != State.MEASURE) {
            lastFrame = now;
            return;
        }

        double ms = (now - lastFrame) / 1e6;
        lastFrame = now;
        if (skipFrames > 0) {   // frames around our own screenshot readback are not the game's
            skipFrames--;
            return;
        }
        if (frames == frameMs.length) frameMs = Arrays.copyOf(frameMs, frames * 2);
        frameMs[frames++] = ms;
        secFrames++;
        if (!dev.betavk.platform.Display.isActive()) inactiveFrames++;
        if (!shotTaken && (now - phaseStart) / 1e9 >= MEASURE_S / 2 && System.getProperty("betavk.bench.out") != null) {
            shotTaken = true;
            skipFrames = 3;
            dev.betavk.platform.Display.captureNextFrame(System.getProperty("betavk.bench.out").replaceAll("\\.json$", "") + ".png");
        }
        if (now - secStart >= 1_000_000_000L) {
            if (now - lastHeapSample >= 30_000_000_000L) {   // live heap after the last collection: a leak shows as this series climbing
                lastHeapSample = now;
                long live = 0;
                for (java.lang.management.MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
                    if (pool.getType() == java.lang.management.MemoryType.HEAP && pool.getCollectionUsage() != null) live += pool.getCollectionUsage().getUsed();
                }
                heapAfterGc.add(live >> 20);
            }
            int h = holes(mc);
            holesMax = Math.max(holesMax, h);
            holesSum += h;
            holesSamples++;
            fpsPerSecond.add(secFrames * 1e9 / (now - secStart));
            secFrames = 0;
            secStart = now;
            peakHeapUsed = Math.max(peakHeapUsed, usedHeap());
        }
        if ((now - phaseStart) / 1e9 >= MEASURE_S) {
            state = State.DONE;
            measuring = false;
            try {
                writeResult(mc);
            } catch (IOException e) {
                e.printStackTrace();
            }
            mc.shutdown();
        }
    }

    // ---- scripted camera -----------------------------------------------------------------

    private static boolean walkInit;
    private static double walkCheckT, walkCheckX, walkCheckZ;
    private static float walkYaw = 270.0F;

    /** Walk path: a survival player on foot (collision, gravity, jumping over obstacles, mob spawning, autosave), heading +X and turning when stuck. */
    private static void walk(Minecraft mc, double t) {
        EntityPlayerSP p = mc.thePlayer;
        if (!walkInit) {
            walkInit = true;
            p.noClip = false;
            int gy = mc.theWorld.getHeightValue((int) Math.floor(x0), (int) Math.floor(z0));
            p.setPosition(x0, gy + 2.0, z0);
            walkCheckT = t;
            walkCheckX = p.posX;
            walkCheckZ = p.posZ;
            p.movementInput = new net.minecraft.src.MovementInput() {
                @Override public void updatePlayerMoveState(net.minecraft.src.EntityPlayer pl) {
                    moveForward = 1.0F;
                    moveStrafe = 0.0F;
                    jump = pl.isCollidedHorizontally || pl.isInWater();
                }
            };
            mc.theWorld.setWorldTime(6000L);
        }
        if (t - walkCheckT >= 6.0) {   // not getting anywhere (water, a cliff, a wall): turn 90 degrees
            if (Math.hypot(p.posX - walkCheckX, p.posZ - walkCheckZ) < 12.0) walkYaw += 90.0F;
            walkCheckT = t;
            walkCheckX = p.posX;
            walkCheckZ = p.posZ;
        }
        p.rotationYaw = p.prevRotationYaw = walkYaw;
        p.rotationPitch = p.prevRotationPitch = 8.0F;
        p.health = 20;
    }

    private static void drive(Minecraft mc, double t) {
        if (WALK) {
            walk(mc, t);
            return;
        }
        EntityPlayerSP p = mc.thePlayer;
        double x = x0 + SPEED * t;
        double z = z0 + 12.0 * Math.sin(t / 5.0);
        float yaw = (float) (270.0 + 70.0 * Math.sin(2 * Math.PI * t / 16.0));   // 270 faces +X; sweep +-70 degrees
        float pitch = (float) (32.0 + 12.0 * Math.sin(2 * Math.PI * t / 11.0));
        p.noClip = true;
        // follow the terrain 12 blocks up, rate-limited (time based), so the view always contains geometry
        double ground = mc.theWorld.getHeightValue((int) Math.floor(x), (int) Math.floor(z));
        double target = Math.max(66.0, Math.min(125.0, ground + 12.0 + ALT));
        double dt = Math.min(0.1, Math.max(0.0, t - lastT));
        lastT = t;
        if (!yInit) {
            yInit = true;
            curY = target;
        }
        curY += Math.max(-10.0 * dt, Math.min(10.0 * dt, target - curY));
        p.setPosition(x, curY, z);
        p.prevPosX = p.lastTickPosX = x;
        p.prevPosY = p.lastTickPosY = curY;
        p.prevPosZ = p.lastTickPosZ = z;
        p.motionX = p.motionY = p.motionZ = 0;
        p.rotationYaw = p.prevRotationYaw = yaw;
        p.rotationPitch = p.prevRotationPitch = pitch;
        p.health = 20;
        if (!timeSet) {   // set the clock once: pinning it every frame would stop the 40-tick autosave from ever firing
            timeSet = true;
            mc.theWorld.setWorldTime(Long.getLong("betavk.bench.time", 6000L));
        }
    }

    // ---- result --------------------------------------------------------------------------

    private static long usedHeap() {
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    private static long[] gcTotals() {
        long c = 0, ms = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            c += Math.max(0, gc.getCollectionCount());
            ms += Math.max(0, gc.getCollectionTime());
        }
        return new long[]{c, ms};
    }

    private static double pct(double[] sorted, double p) {
        return sorted[Math.min(sorted.length - 1, (int) Math.ceil(sorted.length * p) - 1)];
    }

    private static long rssKb() {
        try {
            Process pr = new ProcessBuilder("/bin/ps", "-o", "rss=", "-p", Long.toString(ProcessHandle.current().pid())).start();
            return Long.parseLong(new String(pr.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim());
        } catch (Exception e) {
            return -1;
        }
    }

    private static void writeResult(Minecraft mc) throws IOException {
        double[] ms = Arrays.copyOf(frameMs, frames);
        double[] sorted = ms.clone();
        Arrays.sort(sorted);
        double avg = Arrays.stream(ms).average().orElse(0);
        double p50 = pct(sorted, 0.50), p99 = pct(sorted, 0.99), p999 = pct(sorted, 0.999);
        long[] gc = gcTotals();
        double gcMax = 0, gcSum = 0;
        int gcN;
        synchronized (gcPauseMs) {
            gcN = gcPauseMs.size();
            for (double d : gcPauseMs) {
                gcMax = Math.max(gcMax, d);
                gcSum += d;
            }
        }
        String gcNames = String.join("+", ManagementFactory.getGarbageCollectorMXBeans().stream().map(GarbageCollectorMXBean::getName).toList());
        long rebuilds = MeshPool.appliedCount - rebuildsStart, rebuildNanos = MeshPool.appliedNanos - rebuildNanosStart;
        int tracked = 0;
        for (var w : mc.renderGlobal.worldRenderers) if (w != null) tracked++;
        StringBuilder sb = new StringBuilder();
        Locale l = Locale.ROOT;
        sb.append("{\n");
        sb.append(String.format(l, "  \"label\": \"%s\",\n", System.getProperty("betavk.bench.label", "")));
        sb.append(String.format(l, "  \"os_arch\": \"%s\", \"java\": \"%s\", \"gc\": \"%s\",\n", System.getProperty("os.arch"), System.getProperty("java.version"), gcNames));
        sb.append(String.format(l, "  \"jvm_args\": \"%s\", \"heap_max_mb\": %d, \"cpus\": %d,\n", String.join(" ", ManagementFactory.getRuntimeMXBean().getInputArguments()).replace("\"", "'").replace("\\", "/"),
                Runtime.getRuntime().maxMemory() >> 20, Runtime.getRuntime().availableProcessors()));
        sb.append(String.format(l, "  \"gl_renderer\": \"%s\", \"gl_version\": \"%s\",\n", Gfx.glGetString(Gfx.GL_RENDERER), Gfx.glGetString(Gfx.GL_VERSION)));
        sb.append(String.format(l, "  \"window\": \"%dx%d\", \"seed\": %d, \"render_distance_setting\": %d, \"warmup_s\": %.0f, \"measure_s\": %.0f,\n", mc.displayWidth, mc.displayHeight, SEED, DISTANCE, WARMUP_S, MEASURE_S));
        sb.append(String.format(l, "  \"frames\": %d, \"avg_fps\": %.2f, \"avg_frame_ms\": %.3f, \"p50_ms\": %.3f, \"p99_ms\": %.3f, \"p99_9_ms\": %.3f, \"max_ms\": %.3f,\n", frames, 1000.0 / avg, avg, p50, p99, p999, sorted[sorted.length - 1]));
        sb.append(String.format(l, "  \"one_percent_low_fps\": %.2f, \"min_second_fps\": %.2f,\n", 1000.0 / p99, fpsPerSecond.stream().mapToDouble(Double::doubleValue).min().orElse(0)));
        // chunk work here is the sections the mesh workers delivered (the game thread no longer rebuilds them itself)
        sb.append(String.format(l, "  \"chunk_rebuilds\": %d, \"rebuild_avg_ms\": %.3f, \"rebuild_max_ms\": 0, \"rebuild_ms_per_frame_avg\": %.3f, \"rebuild_ms_per_frame_max\": 0,\n", rebuilds, rebuilds == 0 ? 0 : rebuildNanos / 1e6 / rebuilds, frames == 0 ? 0 : rebuildNanos / 1e6 / frames));
        sb.append(String.format(l, "  \"gc_collections\": %d, \"gc_total_ms\": %d, \"gc_pause_events\": %d, \"gc_pause_max_ms\": %.1f, \"gc_pause_sum_ms\": %.1f,\n", gc[0] - gcCountStart, gc[1] - gcTimeStart, gcN, gcMax, gcSum));
        sb.append(String.format(l, "  \"heap_used_start_mb\": %d, \"heap_used_peak_sampled_mb\": %d, \"heap_committed_mb\": %d, \"rss_mb\": %d,\n", startHeapUsed >> 20, peakHeapUsed >> 20, ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getCommitted() >> 20, rssKb() / 1024));
        sb.append("  \"holes_startup_per_second\": ").append(startupHoles).append(",\n");
        sb.append(String.format(l, "  \"gen_prefetch\": \"%s\",\n", dev.betavk.hooks.world.GenPool.stats()));
        sb.append(String.format(l, "  \"unloader\": \"%s\",\n", dev.betavk.hooks.world.Unloader.stats()));
        sb.append("  \"heap_after_gc_mb_every_30s\": ").append(heapAfterGc).append(",\n");
        sb.append(String.format(l, "  \"holes_max\": %d, \"holes_avg\": %.2f,\n", holesMax, holesSamples == 0 ? 0 : (double) holesSum / holesSamples));
        sb.append(String.format(l, "  \"renderers_total\": %d, \"renderers_orphaned\": -1,\n", tracked));
        sb.append(String.format(l, "  \"renderers_debug\": \"%s\",\n", String.valueOf(mc.renderGlobal.getDebugInfoRenders())));
        sb.append(String.format(l, "  \"mesh_workers\": %d, \"mesh_jobs\": %d, \"mesh_avg_ms\": %.3f, \"mesh_applied\": %d, \"mesh_apply_avg_ms\": %.3f, \"mesh_stale_dropped\": %d, \"mesh_fallbacks\": %d,\n",
                MeshPool.WORKERS, MeshPool.meshCount.get(), MeshPool.meshCount.get() == 0 ? 0 : MeshPool.meshNanos.get() / 1e6 / MeshPool.meshCount.get(),
                MeshPool.appliedCount, MeshPool.appliedCount == 0 ? 0 : MeshPool.appliedNanos / 1e6 / MeshPool.appliedCount, MeshPool.droppedStale, MeshPool.fallbacks));
        sb.append(String.format(l, "  \"window_inactive_frames\": %d,\n", inactiveFrames));
        sb.append("  \"fps_per_second\": [");
        for (int i = 0; i < fpsPerSecond.size(); i++) sb.append(i == 0 ? "" : ", ").append(String.format(l, "%.1f", fpsPerSecond.get(i)));
        sb.append("]\n}\n");

        String out = System.getProperty("betavk.bench.out");
        if (out != null) {
            File f = new File(out);
            f.getParentFile().mkdirs();
            Files.writeString(f.toPath(), sb.toString());
            System.out.println("[bench] wrote " + out);
        }
        System.out.print(sb);
    }
}
