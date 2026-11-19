package dev.betavk.hooks;

import dev.betavk.hooks.mesh.MeshWorker;
import net.minecraft.src.AxisAlignedBB;
import net.minecraft.src.Block;
import net.minecraft.src.EntityOtherPlayerMP;
import net.minecraft.src.EntityPlayer;
import net.minecraft.src.EntityPlayerSP;
import net.minecraft.src.NextTickListEntry;
import net.minecraft.src.Vec3D;

/** Small corrections to the game's own code. */
public final class SmallFixes {
    private SmallFixes() {
    }

    private static boolean onWorker() {
        return Thread.currentThread() instanceof MeshWorker;
    }

    /** replaceBody of NextTickListEntry.hashCode: the original packed x/z/y/id into 32 bits with overlapping fields, so many positions collided and the HashMap
     *  buckets degenerated into trees. The set only de-duplicates (ordering comes from the TreeSet), so any good mix keeps the behaviour. */
    public static int tickHash(NextTickListEntry e) {
        int h = e.xCoord * -1640531535 ^ e.zCoord * 668265263;
        h = (h ^ h >>> 15) * -2048144777 + e.yCoord * 374761393 + e.blockID * 1274126177;
        return h ^ h >>> 13;
    }

    /** atStartReturnIfNotNull of AxisAlignedBB.getBoundingBoxFromPool: the pool is game-thread state, a mesh worker gets a fresh box. */
    public static AxisAlignedBB pooledBox(double a, double b, double c, double d, double e, double f) {
        return onWorker() ? AxisAlignedBB.getBoundingBox(a, b, c, d, e, f) : null;
    }

    /** atStartReturnIfNotNull of Vec3D.createVector: likewise. */
    public static Vec3D pooledVector(double x, double y, double z) {
        return onWorker() ? Vec3D.createVectorHelper(x, y, z) : null;
    }

    /** redirectStaticRead of Block.stairSingle in BlockStep: compared with the Block it is called on, which is a worker's private copy there. */
    public static Block stairSingle() {
        return Thread.currentThread() instanceof MeshWorker w ? w.blocks[Block.stairSingle.blockID] : Block.stairSingle;
    }

    /** atStart of GuiScreen.setWorldAndResolution: early Alpha (e.g. a1.0.1_01) didn't clear controlList before initGui(), leaving duplicate buttons on resize/reopen. */
    public static void clearControls(net.minecraft.src.GuiScreen s, net.minecraft.client.Minecraft mc, int w, int h) {
        if (s != null && s.controlList != null) s.controlList.clear();
    }

    /** redirectCall of Thread.stop() in NetworkMasterThread: stop() no longer exists; interrupting ends the blocked reader and writer. */
    public static void stopThread(Thread t) {
        t.interrupt();
    }

    /** replaceBody of GuiScreen.getClipboardString: the AWT clipboard is not available in the headless window we run in. */
    public static String clipboard() {
        return org.lwjgl.glfw.GLFW.glfwGetClipboardString(dev.betavk.platform.Display.handle());
    }

    // ---- network endpoints that no longer exist ---------------------------------------------------------

    private static String endpoint(String property, String user) {
        String t = System.getProperty(property);
        return t == null ? null : String.format(t, user);
    }

    public static final class Multiplayer {
        /**
         * afterFieldSet of EntityOtherPlayerMP.skinUrl: the S3 skin bucket is gone. -Dbetavk.skinUrl=https://host/skins/%s.png points at a replacement;
         * without it the player's real skin and cape are looked up on Mojang's servers in the background (-Dbetavk.skins=off disables that).
         */
        public static void otherSkin(EntityOtherPlayerMP e) {
            applyTextures(e);
        }
    }

    public static void ownSkin(EntityPlayerSP e) {
        // Silica read the player's own skin and cape from their Minecraft profile and passed them in: nothing to look up
        var own = dev.betavk.net.Skins.own();
        if (own != null && dev.betavk.net.Skins.enabled() && endpoint("betavk.skinUrl", e.username) == null) {
            e.skinUrl = own.skin();
            if (own.cape() != null) {
                e.playerCloakUrl = own.cape();
                e.cloakUrl = own.cape();
            }
            return;
        }
        applyTextures(e);
    }

    private static final boolean HAS_CLOAK = McCompat.hasField(EntityPlayer.class, "playerCloakUrl");

    private static final class CloakFields {
        static void set(EntityPlayer e, String url) {
            e.playerCloakUrl = url;
            e.cloakUrl = url;
        }
    }

    private static void applyTextures(EntityPlayer e) {
        String template = endpoint("betavk.skinUrl", e.username);
        if (template != null) {
            e.skinUrl = template;
            return;
        }
        e.skinUrl = null;
        // the pictures arrive later on a background thread; the renderer starts downloading as soon as the field holds an address
        dev.betavk.net.Skins.resolve(e.username, t -> {
            if (t.skin() != null) e.skinUrl = t.skin();
            if (HAS_CLOAK && t.cape() != null) CloakFields.set(e, t.cape());
        });
    }

    /** afterFieldSet of EntityPlayer.playerCloakUrl (the game copies it to cloakUrl right after). */
    public static void cloak(EntityPlayer e) {
        String template = endpoint("betavk.cloakUrl", e.username);
        if (template != null) {
            e.playerCloakUrl = template;
            return;
        }
        var own = e instanceof EntityPlayerSP ? dev.betavk.net.Skins.own() : null;
        var known = own != null ? own : dev.betavk.net.Skins.cached(e.username);
        e.playerCloakUrl = known == null ? null : known.cape();
    }
}
