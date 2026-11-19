package dev.betavk.hooks;

import dev.betavk.platform.Mouse;
import net.minecraft.client.Minecraft;
import net.minecraft.src.Block;
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
        if (!McCompat.isTileHit(hit)) return;
        int id = mc.theWorld.getBlockId(hit.blockX, hit.blockY, hit.blockZ);
        int meta = mc.theWorld.getBlockMetadata(hit.blockX, hit.blockY, hit.blockZ);
        pick(mc, id, meta);
    }

    /** The item that is used to place a block (most are the block itself); invariant Alpha/Beta IDs. */
    static int itemFor(int id) {
        return switch (id) {
            case 26 -> 355;          // bed -> Item.bed
            case 55 -> 331;          // redstoneWire -> Item.redstone
            case 62 -> 61;           // stoneOvenActive -> stoneOvenIdle
            case 63, 68 -> 323;      // signPost / signWall -> Item.sign
            case 64 -> 324;          // doorWood -> Item.doorWood
            case 71 -> 330;          // doorSteel -> Item.doorSteel
            case 74 -> 73;           // oreRedstoneGlowing -> oreRedstone
            case 75 -> 76;           // torchRedstoneIdle -> torchRedstoneActive
            case 83 -> 338;          // reed -> Item.reed
            case 92 -> 354;          // cake -> Item.cake
            case 93, 94 -> 356;      // redstoneRepeater -> Item.redstoneRepeater
            default -> id;
        };
    }

    /** Selects or fetches the item for the block (id, meta); returns true when it changed the hotbar selection. */
    static boolean pick(Minecraft mc, int blockId, int meta) {
        if (blockId <= 0 || Block.blocksList[blockId] == null) return false;
        int itemId = itemFor(blockId);
        if (itemId < 0 || itemId >= Item.itemsList.length || Item.itemsList[itemId] == null) return false;
        int damage = McCompat.HAS_ITEM_SUBTYPES ? BetaPick.damage(itemId, blockId, meta) : 0;
        InventoryPlayer inv = mc.thePlayer.inventory;
        int found = -1;
        for (int i = 0; i < 36 && found < 0; i++) {   // the hotbar first
            ItemStack st = inv.mainInventory[i];
            if (st != null && st.itemID == itemId && (!McCompat.itemHasSubtypes(st) || McCompat.itemDamage(st) == damage)) found = i;
        }
        if (found < 0) return false;
        if (found < 9) {
            inv.currentItem = found;
            return true;
        }
        int target = inv.currentItem;
        for (int h = 0; h < 9; h++) if (inv.mainInventory[h] == null) { target = h; break; }
        if (McCompat.HAS_ANY_WINDOW_CLICK) {
            BetaPick.swap(mc, found, target);
        } else {
            ItemStack tmp = inv.mainInventory[target];
            inv.mainInventory[target] = inv.mainInventory[found];
            inv.mainInventory[found] = tmp;
        }
        inv.currentItem = target;
        return true;
    }

    private static final class BetaPick {
        static int damage(int itemId, int blockId, int meta) {
            return Item.itemsList[itemId].getHasSubtypes() ? Block.blocksList[blockId].damageDropped(meta) : 0;
        }

        static void swap(Minecraft mc, int found, int target) {
            var player = mc.thePlayer;
            McCompat.windowClick(mc, 0, found, 0, false, player);
            McCompat.windowClick(mc, 0, 36 + target, 0, false, player);
            McCompat.windowClick(mc, 0, found, 0, false, player);
        }
    }
}
