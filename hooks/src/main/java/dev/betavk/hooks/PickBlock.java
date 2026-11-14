package dev.betavk.hooks;

import dev.betavk.platform.Mouse;
import net.minecraft.client.Minecraft;
import net.minecraft.src.Block;
import net.minecraft.src.EnumMovingObjectType;
import net.minecraft.src.InventoryPlayer;
import net.minecraft.src.Item;
import net.minecraft.src.ItemStack;

/**
 * Middle-click pick block, survival style: pressing the middle button on a block selects the item that places it if it is in the hotbar, or brings it from the
 * inventory into an empty hotbar slot (else the selected one). Nothing happens when you have none.
 */
public final class PickBlock {
    private PickBlock() {
    }

    private static boolean wasDown;

    /** Called once per frame (MainLoop.afterFrame). */
    public static void poll(Minecraft mc) {
        boolean down = Mouse.isButtonDown(2);
        boolean edge = down && !wasDown;
        wasDown = down;
        if (!edge || mc.currentScreen != null || !mc.inGameHasFocus || mc.thePlayer == null || mc.theWorld == null) return;
        var hit = mc.objectMouseOver;
        if (hit == null || hit.typeOfHit != EnumMovingObjectType.TILE) return;
        int id = mc.theWorld.getBlockId(hit.blockX, hit.blockY, hit.blockZ);
        int meta = mc.theWorld.getBlockMetadata(hit.blockX, hit.blockY, hit.blockZ);
        pick(mc, id, meta);
    }

    /** The item that is used to place a block (most are the block itself). */
    static int itemFor(int id) {
        if (id == Block.redstoneWire.blockID) return Item.redstone.shiftedIndex;
        if (id == Block.doorWood.blockID) return Item.doorWood.shiftedIndex;
        if (id == Block.doorSteel.blockID) return Item.doorSteel.shiftedIndex;
        if (id == Block.signPost.blockID || id == Block.signWall.blockID) return Item.sign.shiftedIndex;
        if (id == Block.blockBed.blockID) return Item.bed.shiftedIndex;
        if (id == Block.cake.blockID) return Item.cake.shiftedIndex;
        if (id == Block.reed.blockID) return Item.reed.shiftedIndex;
        if (id == Block.stoneOvenActive.blockID) return Block.stoneOvenIdle.blockID;
        if (id == Block.oreRedstoneGlowing.blockID) return Block.oreRedstone.blockID;
        if (id == Block.torchRedstoneIdle.blockID) return Block.torchRedstoneActive.blockID;
        if (id == Block.redstoneRepeaterIdle.blockID || id == Block.redstoneRepeaterActive.blockID) return Item.redstoneRepeater.shiftedIndex;
        return id;
    }

    /** Selects or fetches the item for the block (id, meta); returns true when it changed the hotbar selection. */
    static boolean pick(Minecraft mc, int blockId, int meta) {
        if (blockId <= 0 || Block.blocksList[blockId] == null) return false;
        int itemId = itemFor(blockId);
        if (itemId < 0 || itemId >= Item.itemsList.length || Item.itemsList[itemId] == null) return false;
        int damage = Item.itemsList[itemId].getHasSubtypes() ? Block.blocksList[blockId].damageDropped(meta) : 0;
        InventoryPlayer inv = mc.thePlayer.inventory;
        int found = -1;
        for (int i = 0; i < 36 && found < 0; i++) {   // the hotbar first
            ItemStack st = inv.mainInventory[i];
            if (st != null && st.itemID == itemId && (!st.getHasSubtypes() || st.getItemDamage() == damage)) found = i;
        }
        if (found < 0) return false;
        if (found < 9) {
            inv.currentItem = found;
            return true;
        }
        int target = inv.currentItem;
        for (int h = 0; h < 9; h++) if (inv.mainInventory[h] == null) { target = h; break; }
        var pc = mc.playerController;
        var player = mc.thePlayer;
        pc.func_27174_a(0, found, 0, false, player);        // the stack from the inventory ...
        pc.func_27174_a(0, 36 + target, 0, false, player);  // ... onto the hotbar slot, taking what was there
        pc.func_27174_a(0, found, 0, false, player);        // which goes where the stack came from
        inv.currentItem = target;
        return true;
    }
}
