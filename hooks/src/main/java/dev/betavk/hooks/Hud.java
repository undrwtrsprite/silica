package dev.betavk.hooks;

import dev.betavk.render.Gfx;
import net.minecraft.src.FontRenderer;
import net.minecraft.src.GuiIngame;
import net.minecraft.src.ItemStack;
import net.minecraft.src.Material;
import net.minecraft.src.ScaledResolution;
import net.minecraft.src.StringTranslate;

/** The in-game overlay additions: the FPS counter and the name of the held item that shows above the hotbar and fades out. */
public final class Hud {
    private Hud() {
    }

    private static int heldKey = -1, heldTimer;
    private static String heldName = "";

    /** atStart of GuiIngame.updateTick: notices a change of the held item and starts the name timer. */
    public static void tick(GuiIngame gi) {
        var mc = gi.mc;
        ItemStack held = mc.thePlayer == null ? null : mc.thePlayer.inventory.getCurrentItem();
        int key = held == null ? -1 : mc.thePlayer.inventory.currentItem * 100000 + held.itemID * 16 + (held.getHasSubtypes() ? held.getItemDamage() & 15 : 0);
        if (key != heldKey) {
            if (held != null) {
                String name = ("" + StringTranslate.getInstance().translateNamedKey(held.getItemName())).trim();
                if (name.length() > 0) {   // an item whose name cannot be resolved yet is retried next tick rather than marked as seen
                    heldKey = key;
                    heldName = name;
                    heldTimer = 50;
                }
            } else {
                heldKey = key;
                heldTimer = 0;
            }
        } else if (heldTimer > 0) {
            --heldTimer;
        }
    }

    /** atEnd of GuiIngame.renderGameOverlay. */
    public static void draw(GuiIngame gi, float partial, boolean screenOpen, int mx, int my) {
        var mc = gi.mc;
        FontRenderer font = mc.fontRenderer;
        ScaledResolution sr = new ScaledResolution(mc.gameSettings, mc.displayWidth, mc.displayHeight);
        int w = sr.getScaledWidth(), h = sr.getScaledHeight();
        SoundCaptions.draw(gi, w, h);
        if (heldTimer > 0 && !mc.gameSettings.hideGUI) {
            float hp = heldTimer - partial;
            int a = Math.min(255, (int) (hp * 255.0F / 20.0F));   // fades out over the last second
            if (a > 8) {
                boolean hasHud = mc.playerController.shouldDrawHUD();
                // just above the hearts, clear of the armor row and the air bubbles when they are showing
                int y = h - (hasHud ? (mc.thePlayer.getPlayerArmorValue() > 0 || mc.thePlayer.isInsideOfMaterial(Material.water) ? 52 : 41) : 34);
                Gfx.glEnable(Gfx.GL_BLEND);   // the HUD leaves blending off; without it the text alpha is ignored and the name vanishes without fading
                Gfx.glBlendFunc(Gfx.GL_SRC_ALPHA, Gfx.GL_ONE_MINUS_SRC_ALPHA);
                font.drawStringWithShadow(heldName, (w - font.getStringWidth(heldName)) / 2, y, 0xFFFFFF | a << 24);
                Gfx.glDisable(Gfx.GL_BLEND);
            }
        }
        if (mc.gameSettings.showDebugInfo && mc.thePlayer != null && mc.theWorld != null) debugLines(mc, font);
        if (Opts.showFps && !mc.gameSettings.showDebugInfo) {
            int fps = fps(mc.debug);
            String text = fps + " FPS";
            font.drawStringWithShadow(text, w - font.getStringWidth(text) - 3, 3, fps >= 60 ? 0xFFFFFF : fps >= 30 ? 0xFFFF55 : 0xFF5555);
        }
    }

    /** The game keeps "NN fps, M chunk updates" once a second. */
    private static int fps(String debug) {
        int i = 0;
        while (i < debug.length() && Character.isDigit(debug.charAt(i))) i++;
        return i == 0 ? 0 : Integer.parseInt(debug.substring(0, i));
    }

    private static final String[] FACING = {"south (+Z)", "west (-X)", "north (-Z)", "east (+X)"};

    /** More lines under the game's own x, y, z and f lines on the F3 screen: block and chunk, facing, biome, light, the block looked at, the time of day. */
    private static void debugLines(net.minecraft.client.Minecraft mc, FontRenderer font) {
        var p = mc.thePlayer;
        var w = mc.theWorld;
        int bx = (int) Math.floor(p.posX), by = (int) Math.floor(p.boundingBox.minY), bz = (int) Math.floor(p.posZ);
        float yaw = ((p.rotationYaw % 360.0F) + 540.0F) % 360.0F - 180.0F;
        int f = net.minecraft.src.MathHelper.floor_double((double) (p.rotationYaw * 4.0F / 360.0F) + 0.5D) & 3;
        long t = w.getWorldTime();
        int hour = (int) ((t / 1000L + 6L) % 24L), min = (int) (t % 1000L * 60L / 1000L);
        String[] lines = new String[6];
        lines[0] = String.format("Block: %d %d %d   Chunk: %d %d (in chunk %d %d)", bx, by, bz, bx >> 4, bz >> 4, bx & 15, bz & 15);
        lines[1] = String.format("Facing: %s   yaw %.1f   pitch %.1f", FACING[f], yaw, p.rotationPitch);
        lines[2] = "Biome: " + w.getWorldChunkManager().getBiomeGenAt(bx, bz).biomeName;
        lines[3] = "Light: sky " + w.getSavedLightValue(net.minecraft.src.EnumSkyBlock.Sky, bx, by, bz) + "   block " + w.getSavedLightValue(net.minecraft.src.EnumSkyBlock.Block, bx, by, bz)
                + "   (combined " + w.getBlockLightValue(bx, by, bz) + ")";
        var hit = mc.objectMouseOver;
        if (hit != null && hit.typeOfHit == net.minecraft.src.EnumMovingObjectType.TILE) {
            int id = w.getBlockId(hit.blockX, hit.blockY, hit.blockZ), meta = w.getBlockMetadata(hit.blockX, hit.blockY, hit.blockZ);
            String name = id > 0 && net.minecraft.src.Block.blocksList[id] != null ? net.minecraft.src.StringTranslate.getInstance().translateNamedKey(new ItemStack(id, 1, 0).getItemName()) : "";
            lines[4] = "Looking at: " + (name == null || name.isBlank() ? "block" : name.trim()) + " (id " + id + ":" + meta + ") at " + hit.blockX + " " + hit.blockY + " " + hit.blockZ;
        } else {
            lines[4] = "Looking at: nothing";
        }
        lines[5] = String.format("Time: %d (day %d, %02d:%02d)", t, t / 24000L + 1L, hour, min);
        for (int i = 0; i < lines.length; i++) font.drawStringWithShadow(lines[i], 2, 100 + 10 * i, 14737632);
    }
}
