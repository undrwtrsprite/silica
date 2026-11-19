package dev.betavk.hooks;

import net.minecraft.src.World;

import java.lang.reflect.Field;

/**
 * Runtime capability detection and cross-version compatibility helpers across Alpha and Beta jars.
 * Never directly references optional Minecraft classes from unguarded static initializers so older
 * versions (without WorldInfo, EnumOptions, WorldChunkManager, etc.) link cleanly.
 */
public final class McCompat {
    private static final String SRC = "net/minecraft/src/";

    public static final boolean HAS_WORLD_INFO = hasClass(SRC + "WorldInfo");
    public static final boolean HAS_WORLD_SETTINGS = hasClass(SRC + "WorldSettings");
    public static final boolean HAS_HANDLE_KEY_PRESS = hasMethod(net.minecraft.src.EntityPlayerSP.class, "handleKeyPress", int.class, boolean.class);
    public static final boolean HAS_WORLD_PROVIDER = hasClass(SRC + "WorldProvider");
    public static final boolean HAS_WORLD_CHUNK_MANAGER = hasClass(SRC + "WorldChunkManager");
    public static final boolean HAS_COLORIZER_GRASS = hasClass(SRC + "ColorizerGrass");
    public static final boolean HAS_FOLIAGE_PINE_BIRCH = HAS_COLORIZER_GRASS
            && hasMethod(net.minecraft.src.ColorizerFoliage.class, "getFoliageColorPine");
    public static final boolean HAS_ENUM_OPTIONS = hasClass(SRC + "EnumOptions");
    public static final boolean HAS_GUI_VIDEO_SETTINGS = hasClass(SRC + "GuiVideoSettings");
    public static final boolean HAS_GUI_WORLD_SLOT = hasClass(SRC + "GuiWorldSlot");
    public static final boolean HAS_BLOCK_TALL_GRASS = hasClass(SRC + "BlockTallGrass");
    public static final boolean HAS_GUI_CHAT = hasClass(SRC + "GuiChat");
    public static final boolean HAS_STRING_TRANSLATE = hasClass(SRC + "StringTranslate");
    public static final boolean HAS_GUI_SCALE = hasField(net.minecraft.src.GameSettings.class, "guiScale");
    public static final boolean HAS_DO_CHUNKS_NEAR = hasMethod(World.class, "doChunksNearChunkExist", int.class, int.class, int.class, int.class);
    public static final boolean HAS_CHUNK_EMPTY = hasMethod(net.minecraft.src.Chunk.class, "func_21167_h");
    public static final boolean HAS_RENDER_VIEW_ENTITY = hasField(net.minecraft.client.Minecraft.class, "renderViewEntity");
    public static final boolean HAS_ADVANCED_OPENGL = hasField(net.minecraft.src.GameSettings.class, "advancedOpengl");
    public static final boolean HAS_SKIP_ALL_RENDER_PASSES = hasMethod(net.minecraft.src.WorldRenderer.class, "skipAllRenderPasses");
    public static final boolean HAS_HIDE_GUI = hasField(net.minecraft.src.GameSettings.class, "hideGUI");
    public static final boolean HAS_SHOW_DEBUG = hasField(net.minecraft.src.GameSettings.class, "showDebugInfo");
    public static final boolean HAS_SHOULD_DRAW_HUD = hasMethod(net.minecraft.src.PlayerController.class, "shouldDrawHUD");
    public static final boolean HAS_CONTAINER = hasClass(SRC + "Container");
    public static final boolean HAS_WINDOW_CLICK = hasMethod(net.minecraft.src.PlayerController.class, "func_27174_a",
            int.class, int.class, int.class, boolean.class, net.minecraft.src.EntityPlayer.class);
    public static final boolean HAS_ITEM_NAME = hasMethod(net.minecraft.src.ItemStack.class, "getItemName");
    public static final boolean HAS_ITEM_SUBTYPES = hasMethod(net.minecraft.src.ItemStack.class, "getHasSubtypes");
    public static final boolean HAS_ITEM_DAMAGE_METHOD = hasMethod(net.minecraft.src.ItemStack.class, "getItemDamage");
    public static final boolean HAS_GET_WORLD_TIME = hasMethod(World.class, "getWorldTime");
    public static final boolean HAS_ENUM_MOVING_OBJECT_TYPE = hasClass(SRC + "EnumMovingObjectType");
    public static final boolean HAS_FRUSTRUM_CHECK_OFFSET = hasField(net.minecraft.src.RenderGlobal.class, "frustrumCheckOffset");

    public static volatile net.minecraft.client.Minecraft minecraft;
    private static volatile Field worldRandomSeedField;
    private static volatile boolean worldRandomSeedLookedUp;
    private static volatile Field worldTimeField;
    private static volatile Field itemDamageField;
    private static volatile Field hitTypeField;

    private McCompat() {}

    public static boolean hasClass(String internalName) {
        return McCompat.class.getClassLoader().getResource(internalName + ".class") != null;
    }

    public static boolean hasField(Class<?> cls, String name) {
        try {
            cls.getField(name);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean hasMethod(Class<?> cls, String name, Class<?>... params) {
        try {
            cls.getMethod(name, params);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Translates a UI key when StringTranslate exists (b1.1+), else returns the English fallback. */
    public static String tr(String key, String fallback) {
        if (!HAS_STRING_TRANSLATE) return fallback;
        String s = Translate.get(key);
        return (s == null || s.isEmpty() || s.equals(key)) ? fallback : s;
    }

    private static final class Translate {
        static String get(String key) {
            return net.minecraft.src.StringTranslate.getInstance().translateKey(key);
        }
    }

    private static final long BOOT_START_NS = System.nanoTime();
    private static long firstFrameMs = -1L;
    private static int bootFrames;

    /** Lightweight boot-test check that does not link Beta-specific UI classes (GuiVideoSettings, EnumOptions, etc.). */
    public static void bootFrame(net.minecraft.client.Minecraft mc) {
        bootFrames++;
        long elapsedMs = (System.nanoTime() - BOOT_START_NS) / 1_000_000L;
        if (firstFrameMs < 0) firstFrameMs = elapsedMs;
        if (mc.currentScreen != null && bootFrames >= 2) {
            System.out.println("[boot] first frame " + firstFrameMs + " ms, title screen " + elapsedMs + " ms after JVM start");
            mc.shutdown();
        }
    }

    private static int compatStage;
    private static int compatTicks;
    private static int compatMoveFrames;
    private static long compatMoveStartNs = -1L;
    private static boolean compatInvOpened;
    private static double compatStartX, compatStartZ, compatMoved;

    /** Starts a singleplayer world across pre-b1.3 (String), b1.3..b1.7.3 (String,String,long), and experimental b1.8+ (String,String,WorldSettings). */
    public static void startWorldCompat(net.minecraft.client.Minecraft mc, String folder, String name, long seed) throws Throwable {
        if (HAS_WORLD_SETTINGS) {
            Class<?> ws = Class.forName(SRC.replace('/', '.') + "WorldSettings");
            Object settings = ws.getConstructor(long.class, int.class, boolean.class).newInstance(seed, 0, true);
            net.minecraft.client.Minecraft.class.getMethod("startWorld", String.class, String.class, ws).invoke(mc, folder, name, settings);
        } else if (HAS_WORLD_INFO) {
            mc.startWorld(folder, name, seed);
        } else {
            net.minecraft.client.Minecraft.class.getMethod("startWorld", String.class).invoke(mc, "compat_world".equals(folder) ? "World1" : folder);
        }
    }

    public static void setKeyBindState(net.minecraft.client.Minecraft mc, int code, boolean down) {
        if (HAS_HANDLE_KEY_PRESS) {
            mc.thePlayer.handleKeyPress(code, down);
        } else {
            try {
                net.minecraft.src.KeyBinding.class.getMethod("setKeyBindState", int.class, boolean.class).invoke(null, code, down);
            } catch (Throwable ignored) {}
        }
    }

    /** Cross-version UI + in-world smoke test (Options, Video, Controls, Audio, World, LOD, movement, item use, inventory, Vulkan). */
    public static void compatFrame(net.minecraft.client.Minecraft mc) {
        if (compatStage == 0) {
            if (mc.currentScreen == null) return;
            compatStage = 1;
            var go = new net.minecraft.src.GuiOptions(mc.currentScreen, mc.gameSettings);
            mc.displayGuiScreen(go);
            int optCount = go.controlList.size();
            if (optCount < 6) throw new AssertionError("GuiOptions too few buttons: " + optCount);
            go.setWorldAndResolution(mc, go.width, go.height);
            if (go.controlList.size() != optCount) throw new AssertionError("GuiOptions duplicated buttons on resize: " + go.controlList.size());
            testSliders(mc, go);
            var gc = new net.minecraft.src.GuiControls(go, mc.gameSettings);
            mc.displayGuiScreen(gc);
            testSliders(mc, gc);
            var as = new AudioScreen(go, mc.gameSettings);
            mc.displayGuiScreen(as);
            testSliders(mc, as);
            var ws = new WorldOptionsScreen(go, mc.gameSettings);
            mc.displayGuiScreen(ws);
            var ls = new LodOptionsScreen(go, mc.gameSettings);
            mc.displayGuiScreen(ls);
            testSliders(mc, ls);
            if (!HAS_GUI_VIDEO_SETTINGS) {
                var lv = new LegacyVideoScreen(go, mc.gameSettings);
                mc.displayGuiScreen(lv);
                testSliders(mc, lv);
            } else {
                BetaVideo.open(mc, go);
                testSliders(mc, mc.currentScreen);
            }
            long fsStart = System.nanoTime();
            mc.toggleFullscreen();
            mc.toggleFullscreen();
            long fsMs = (System.nanoTime() - fsStart) / 1_000_000L;
            if (fsMs > 500) throw new AssertionError("Fullscreen toggle paused too long: " + fsMs + " ms");
            Opts.lodEnabled = true;
            System.out.println("[compat] PASS UI screens & sliders initialized (fullscreen toggle=" + fsMs + " ms)");
            // Enter a world
            try {
                try {
                    mc.playerController = new net.minecraft.src.PlayerControllerSP(mc);
                } catch (IllegalStateException demoMode) {
                    // b1.3-demo throws "Invalid game mode" from PlayerControllerSP; use PlayerControllerDemo if present
                    Class<?> demo = Class.forName(SRC.replace('/', '.') + "PlayerControllerDemo");
                    mc.playerController = (net.minecraft.src.PlayerController) demo.getConstructor(net.minecraft.client.Minecraft.class).newInstance(mc);
                }
                startWorldCompat(mc, "compat_world", "Compat", 12345L);
                mc.displayGuiScreen(null);
            } catch (Throwable t) {
                throw new RuntimeException("startWorld failed", t);
            }
            return;
        }
        if (compatStage == 1) {
            if (mc.theWorld != null && mc.thePlayer != null) {
                compatTicks++;
                long now = System.nanoTime();
                if (compatMoveStartNs < 0 && compatTicks >= 10) {
                    compatMoveStartNs = now;
                    mc.thePlayer.inventory.mainInventory[0] = new net.minecraft.src.ItemStack(net.minecraft.src.Block.dirt, 10);
                    mc.thePlayer.inventory.currentItem = 0;
                    compatStartX = mc.thePlayer.posX;
                    compatStartZ = mc.thePlayer.posZ;
                    setKeyBindState(mc, mc.gameSettings.keyBindForward.keyCode, true);
                } else if (!compatInvOpened && compatMoveStartNs > 0) {
                    compatMoveFrames++;
                    if (compatMoveFrames >= 20 && now - compatMoveStartNs >= 600_000_000L) {
                        setKeyBindState(mc, mc.gameSettings.keyBindForward.keyCode, false);
                        compatMoved = Math.hypot(mc.thePlayer.posX - compatStartX, mc.thePlayer.posZ - compatStartZ);
                        mc.clickMouse(0);
                        mc.clickMouse(1);
                        CompatGuiInventory.open(mc);
                        compatInvOpened = true;
                    }
                } else if (compatInvOpened && mc.currentScreen != null && now - compatMoveStartNs >= 750_000_000L) {
                    mc.displayGuiScreen(null);
                }
                String shot = System.getProperty("betavk.test.shot");
                if (compatTicks == 40 && shot != null) dev.betavk.platform.Display.captureNextFrame(shot);
                if (compatInvOpened && mc.currentScreen == null && compatTicks >= 45) {
                    System.out.println("[compat] PASS in-world ticks=" + compatTicks + " seed=" + worldSeed(mc.theWorld)
                            + " moved=" + String.format("%.3f", compatMoved));
                    mc.shutdown();
                    compatStage = 2;
                }
            }
        }
    }

    private static void testSliders(net.minecraft.client.Minecraft mc, net.minecraft.src.GuiScreen s) {
        for (Object o : s.controlList) {
            if (o instanceof SliderButton sb && sb.enabled) {
                float orig = sb.value;
                int yMid = sb.yPosition + sb.height / 2;
                int xRight = sb.xPosition + sb.width - 10;
                int xLeft = sb.xPosition + 10;
                s.mouseClicked(xRight, yMid, 0);
                if (Math.abs(sb.value - 1.0F) > 0.2F) throw new AssertionError("GuiScreen.mouseClicked did not move SliderButton right: " + sb.value + " on " + s.getClass().getSimpleName());
                sb.mouseDragged(mc, xLeft, yMid);
                if (sb.value > 0.25F) throw new AssertionError("SliderButton mouseDragged failed: " + sb.value);
                sb.mouseReleased(xLeft, yMid);
                sb.mousePressed(mc, sb.xPosition + 4 + Math.round(orig * (sb.width - 8)), yMid);
                sb.mouseReleased(sb.xPosition, yMid);
            } else if (o instanceof net.minecraft.src.GuiButton gb && !(o instanceof SliderButton) && !gb.getClass().getSimpleName().contains("GuiSmallButton")
                    && HAS_MOUSE_DRAGGED && o.getClass().getSimpleName().equals("GuiSlider")) {
                int yMid = gb.yPosition + gb.height / 2;
                s.mouseClicked(gb.xPosition + gb.width - 10, yMid, 0);
            }
        }
    }

    private static final boolean HAS_MOUSE_DRAGGED = hasMethod(net.minecraft.src.GuiButton.class, "mouseDragged",
            net.minecraft.client.Minecraft.class, int.class, int.class);

    private static final class CompatGuiInventory {
        static void open(net.minecraft.client.Minecraft mc) {
            try {
                for (var c : net.minecraft.src.GuiInventory.class.getConstructors()) {
                    Class<?>[] pt = c.getParameterTypes();
                    if (pt.length == 1 && pt[0].isAssignableFrom(net.minecraft.src.EntityPlayer.class)) {
                        mc.displayGuiScreen((net.minecraft.src.GuiScreen) c.newInstance(mc.thePlayer));
                        return;
                    }
                    if (pt.length == 2 && pt[0].isAssignableFrom(mc.thePlayer.inventory.getClass())) {
                        var craft = mc.thePlayer.inventory.getClass().getField("craftingInventory").get(mc.thePlayer.inventory);
                        mc.displayGuiScreen((net.minecraft.src.GuiScreen) c.newInstance(mc.thePlayer.inventory, craft));
                        return;
                    }
                    if (pt.length == 1 && pt[0].isAssignableFrom(mc.thePlayer.inventory.getClass())) {
                        mc.displayGuiScreen((net.minecraft.src.GuiScreen) c.newInstance(mc.thePlayer.inventory));
                        return;
                    }
                }
            } catch (Throwable t) {
                throw new RuntimeException("open GuiInventory failed", t);
            }
        }
    }

    private static final class BetaVideo {
        static void open(net.minecraft.client.Minecraft mc, net.minecraft.src.GuiScreen parent) {
            mc.displayGuiScreen(new net.minecraft.src.GuiVideoSettings(parent, mc.gameSettings));
        }
    }

    public static String legacyOptionString(net.minecraft.src.GameSettings gs, int opt) {
        try {
            return String.valueOf(net.minecraft.src.GameSettings.class.getMethod("getOptionDisplayString", int.class).invoke(gs, opt));
        } catch (Throwable ignored) {
            return opt == 0 ? "Music" : "Sound";
        }
    }

    public static void legacySetOptionValue(net.minecraft.src.GameSettings gs, int opt, int dir) {
        try {
            net.minecraft.src.GameSettings.class.getMethod("setOptionValue", int.class, int.class).invoke(gs, opt, dir);
        } catch (Throwable ignored) {}
    }

    /** Returns the world's seed on both Beta (WorldInfo) and Alpha/early Beta (World.randomSeed). */
    private static volatile java.lang.reflect.Constructor<net.minecraft.src.ScaledResolution> legacySrCtor;

    public static net.minecraft.src.ScaledResolution scaledResolution(net.minecraft.client.Minecraft mc) {
        if (HAS_GUI_SCALE) return new net.minecraft.src.ScaledResolution(mc.gameSettings, mc.displayWidth, mc.displayHeight);
        try {
            var c = legacySrCtor;
            if (c == null) {
                c = net.minecraft.src.ScaledResolution.class.getConstructor(int.class, int.class);
                legacySrCtor = c;
            }
            return c.newInstance(mc.displayWidth, mc.displayHeight);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public static boolean hideGui(net.minecraft.src.GameSettings gs) {
        return HAS_HIDE_GUI && gs.hideGUI;
    }

    public static boolean showDebugInfo(net.minecraft.src.GameSettings gs) {
        return HAS_SHOW_DEBUG && gs.showDebugInfo;
    }

    public static boolean shouldDrawHud(net.minecraft.src.PlayerController pc) {
        return !HAS_SHOULD_DRAW_HUD || pc.shouldDrawHUD();
    }

    public static net.minecraft.src.EntityLiving renderViewEntity(net.minecraft.client.Minecraft mc) {
        if (mc == null) return null;
        return HAS_RENDER_VIEW_ENTITY ? mc.renderViewEntity : mc.thePlayer;
    }

    public static boolean isNether(World world) {
        if (world == null || !HAS_WORLD_PROVIDER) return false;
        return Nether.check(world);
    }

    private static final class Nether {
        static boolean check(World w) {
            Object wp = w.worldProvider;
            if (wp == null) return false;
            try { return wp.getClass().getField("isNether").getBoolean(wp); } catch (Throwable ignored) {}
            try { return wp.getClass().getField("isHellWorld").getBoolean(wp); } catch (Throwable ignored) {}
            try { return wp.getClass().getField("worldType").getInt(wp) == -1; } catch (Throwable ignored) {}
            return false;
        }
    }

    public static float[] lightBrightnessTable(World world) {
        if (HAS_WORLD_PROVIDER && world != null) return ProviderLight.table(world);
        return LegacyLight.table();
    }

    private static final class ProviderLight {
        static float[] table(World w) { return w.worldProvider.lightBrightnessTable; }
    }

    private static final class LegacyLight {
        private static final float[] TABLE;
        static {
            float[] t = null;
            try {
                Field f = World.class.getField("lightBrightnessTable");
                t = (float[]) f.get(null);
            } catch (Throwable ignored) {}
            if (t == null) {
                t = new float[16];
                for (int i = 0; i <= 15; i++) {
                    float f1 = 1.0F - i / 15.0F;
                    t[i] = (1.0F - f1) / (f1 * 3.0F + 1.0F) * 0.95F + 0.05F;
                }
            }
            TABLE = t;
        }
        static float[] table() { return TABLE; }
    }

    public static long worldSeed(World world) {
        if (world == null) return 0L;
        if (HAS_WORLD_INFO) return world.getRandomSeed();
        Field f = worldRandomSeedField;
        if (!worldRandomSeedLookedUp) {
            try {
                f = World.class.getField("randomSeed");
                f.setAccessible(true);
            } catch (Throwable ignored) {
                f = null;
            }
            worldRandomSeedField = f;
            worldRandomSeedLookedUp = true;
        }
        if (f != null) {
            try {
                return f.getLong(world);
            } catch (Throwable ignored) {}
        }
        return 0L;
    }

    public static int spawnX(World world) {
        if (world == null) return 0;
        if (HAS_WORLD_INFO) return Spawn.x(world);
        try { return World.class.getField("spawnX").getInt(world); } catch (Throwable ignored) { return 0; }
    }

    public static int spawnZ(World world) {
        if (world == null) return 0;
        if (HAS_WORLD_INFO) return Spawn.z(world);
        try { return World.class.getField("spawnZ").getInt(world); } catch (Throwable ignored) { return 0; }
    }

    private static final class Spawn {
        static int x(World w) { return w.getSpawnPoint().x; }
        static int z(World w) { return w.getSpawnPoint().z; }
    }

    public static java.io.File saveDirectory(World world) {
        if (world == null) return null;
        if (HAS_WORLD_INFO) return SaveDir.get(world);
        try { return (java.io.File) World.class.getField("saveDirectory").get(world); } catch (Throwable ignored) { return null; }
    }

    private static final class SaveDir {
        static java.io.File get(World w) {
            return w.saveHandler instanceof net.minecraft.src.SaveHandler sh ? sh.getSaveDirectory() : null;
        }
    }

    public static void fixGuiScale(net.minecraft.src.GameSettings gs) {
        if (HAS_GUI_SCALE && gs != null) GuiScaleAccess.fix(gs);
    }

    private static final class GuiScaleAccess {
        static void fix(net.minecraft.src.GameSettings gs) {
            if (gs.guiScale < 0 || gs.guiScale > 4) gs.guiScale = 0;
        }
    }

    public static boolean itemHasSubtypes(net.minecraft.src.ItemStack st) {
        return st != null && HAS_ITEM_SUBTYPES && ItemSubtypesAccess.has(st);
    }

    private static final class ItemSubtypesAccess {
        static boolean has(net.minecraft.src.ItemStack st) { return st.getHasSubtypes(); }
    }

    public static int itemDamage(net.minecraft.src.ItemStack st) {
        if (st == null) return 0;
        if (HAS_ITEM_DAMAGE_METHOD) return ItemDamageAccess.get(st);
        Field f = itemDamageField;
        if (f == null) {
            try { f = net.minecraft.src.ItemStack.class.getField("itemDamage"); }
            catch (Throwable ignored) {
                try { f = net.minecraft.src.ItemStack.class.getField("itemDmg"); }
                catch (Throwable ignored2) { return 0; }
            }
            itemDamageField = f;
        }
        try { return f.getInt(st); } catch (Throwable ignored) { return 0; }
    }

    private static final class ItemDamageAccess {
        static int get(net.minecraft.src.ItemStack st) { return st.getItemDamage(); }
    }

    public static String itemDisplayName(net.minecraft.src.ItemStack st) {
        if (st == null) return "";
        if (HAS_ITEM_NAME) return ItemNameAccess.get(st);
        if (HAS_ITEM_SUBTYPES && HAS_STRING_TRANSLATE) return LegacyBetaItemName.get(st);
        return "";
    }

    private static final class ItemNameAccess {
        static String get(net.minecraft.src.ItemStack st) {
            String raw = st.getItemName();
            return raw == null ? "" : ("" + net.minecraft.src.StringTranslate.getInstance().translateNamedKey(raw)).trim();
        }
    }

    private static final class LegacyBetaItemName {
        static String get(net.minecraft.src.ItemStack st) {
            var item = st.getItem();
            if (item == null) return "";
            try {
                String raw = (String) net.minecraft.src.Item.class.getMethod("getItemNameIS", net.minecraft.src.ItemStack.class).invoke(item, st);
                if (raw == null) return "";
                return ("" + net.minecraft.src.StringTranslate.getInstance().translateNamedKey(raw)).trim();
            } catch (Throwable ignored) {
                return "";
            }
        }
    }

    public static long worldTime(World w) {
        if (w == null) return 0L;
        if (HAS_GET_WORLD_TIME) return WorldTimeAccess.get(w);
        Field f = worldTimeField;
        if (f == null) {
            try { f = World.class.getField("worldTime"); } catch (Throwable ignored) { return 0L; }
            worldTimeField = f;
        }
        try { return f.getLong(w); } catch (Throwable ignored) { return 0L; }
    }

    public static void setWorldTime(World w, long t) {
        if (w == null) return;
        if (HAS_GET_WORLD_TIME) { WorldTimeAccess.set(w, t); return; }
        Field f = worldTimeField;
        if (f == null) {
            try { f = World.class.getField("worldTime"); } catch (Throwable ignored) { return; }
            worldTimeField = f;
        }
        try { f.setLong(w, t); } catch (Throwable ignored) {}
    }

    private static final class WorldTimeAccess {
        static long get(World w) { return w.getWorldTime(); }
        static void set(World w, long t) { w.setWorldTime(t); }
    }

    public static String biomeName(World w, int bx, int bz) {
        if (w == null || !HAS_WORLD_CHUNK_MANAGER) return "Alpha";
        return BiomeAccess.get(w, bx, bz);
    }

    private static final class BiomeAccess {
        static String get(World w, int bx, int bz) {
            return w.getWorldChunkManager().getBiomeGenAt(bx, bz).biomeName;
        }
    }

    public static boolean isTileHit(net.minecraft.src.MovingObjectPosition hit) {
        if (hit == null) return false;
        if (HAS_ENUM_MOVING_OBJECT_TYPE) return EnumHitAccess.isTile(hit);
        Field f = hitTypeField;
        if (f == null) {
            try { f = net.minecraft.src.MovingObjectPosition.class.getField("typeOfHit"); } catch (Throwable ignored) { return false; }
            hitTypeField = f;
        }
        try { return f.getInt(hit) == 0; } catch (Throwable ignored) { return false; }
    }

    private static final class EnumHitAccess {
        static boolean isTile(net.minecraft.src.MovingObjectPosition hit) {
            return hit.typeOfHit == net.minecraft.src.EnumMovingObjectType.TILE;
        }
    }
}
