package dev.betavk.hooks;

import dev.betavk.platform.Keyboard;
import dev.betavk.platform.Mouse;
import dev.betavk.render.Gfx;
import net.minecraft.src.Container;
import net.minecraft.src.EntityPlayer;
import net.minecraft.src.GuiButton;
import net.minecraft.src.GuiContainer;
import net.minecraft.src.InventoryPlayer;
import net.minecraft.src.ItemStack;
import net.minecraft.src.Slot;
import net.minecraft.src.SlotCrafting;

import java.util.ArrayList;
import java.util.List;

/**
 * Modern inventory handling on top of the game's own screens and container: drag a stack over slots to spread it (left button: evenly, right button:
 * one each), shift-drag to shift-click every slot passed, double-click to gather matching items onto the cursor, and shift-clicking a crafting result
 * crafts repeatedly until the ingredients run out.
 */
public final class Inventory {
    private Inventory() {
    }

    private static int dragButton = -1;
    private static boolean dragShift;
    private static int dragX, dragY;
    private static final List<Slot> dragSlots = new ArrayList<>();
    private static int lastClickSlot = -1;
    private static long lastClickTime;
    private static boolean bypass;   // while finishing a one-slot drag the game's own click handling must run

    private static boolean shiftDown() {
        return Keyboard.isKeyDown(Keyboard.KEY_LSHIFT) || Keyboard.isKeyDown(Keyboard.KEY_RSHIFT);
    }

    // ---- the container: gather, shift-craft, drag distribution ------------------------------------

    /** atStartReturnIfNotNull of Container.func_27280_a (a click in a window): mode 6 = double-click gather; shift on a crafting result = craft all. */
    public static ItemStack click(Container c, int slot, int mode, boolean shift, EntityPlayer player) {
        if (mode == 6) {
            gather(c, player);
            return null;   // the game does nothing for this mode
        }
        if ((mode == 0 || mode == 1) && shift && slot >= 0 && slot < c.slots.size() && c.slots.get(slot) instanceof SlotCrafting) {
            Slot out = (Slot) c.slots.get(slot);
            ItemStack first = null;
            for (int guard = 0; guard < 2304 && out.getStack() != null; guard++) {
                int before = out.getStack().stackSize;
                ItemStack moved = c.getStackInSlot(slot);
                if (moved == null) break;
                if (first == null) first = moved;
                if (out.getStack() != null && out.getStack().stackSize < before) break;   // only part of the result fitted: stop here
            }
            return first;
        }
        return null;
    }

    private static boolean sameKind(ItemStack a, ItemStack b) {
        return a.itemID == b.itemID && (!a.getHasSubtypes() || a.getItemDamage() == b.getItemDamage());
    }

    /** Gathers matching items from every other slot onto the stack held by the cursor (crafting result slots are skipped by isItemValid). */
    private static void gather(Container c, EntityPlayer player) {
        ItemStack held = player.inventory.getItemStack();
        if (held == null || held.getMaxStackSize() <= 1) return;
        for (int i = 0; i < c.slots.size() && held.stackSize < held.getMaxStackSize(); i++) {
            Slot s = (Slot) c.slots.get(i);
            ItemStack st = s.getStack();
            if (st != null && sameKind(st, held) && s.isItemValid(st)) {
                int take = Math.min(st.stackSize, held.getMaxStackSize() - held.stackSize);
                st.stackSize -= take;
                held.stackSize += take;
                if (st.stackSize <= 0) s.putStack(null);
                s.onSlotChanged();
            }
        }
    }

    /** How many items each slot would get when the cursor stack is spread over them (0 for a slot that cannot take any). */
    public static int[] planDrag(List<Slot> slots, int button, ItemStack cursor) {
        int n = slots.size();
        int[] plan = new int[n], room = new int[n];
        if (cursor == null || n == 0) return plan;
        int eligible = 0;
        for (int i = 0; i < n; i++) {
            Slot s = slots.get(i);
            ItemStack st = s.getStack();
            int limit = Math.min(s.getSlotStackLimit(), cursor.getMaxStackSize());
            if (s.isItemValid(cursor) && (st == null || sameKind(st, cursor))) {
                room[i] = limit - (st == null ? 0 : st.stackSize);
                if (room[i] > 0) eligible++;
            }
        }
        int total = cursor.stackSize;
        if (eligible == 0) return plan;
        if (button == 1) {
            for (int i = 0; i < n && total > 0; i++) {
                if (room[i] > 0) {
                    plan[i] = 1;
                    total--;
                }
            }
        } else {
            int per = Math.max(1, total / eligible);
            for (int i = 0; i < n && total > 0; i++) {
                if (room[i] > 0) {
                    int give = Math.min(Math.min(per, room[i]), total);
                    plan[i] = give;
                    total -= give;
                }
            }
        }
        return plan;
    }

    public static void applyDrag(List<Slot> slots, int button, EntityPlayer player) {
        InventoryPlayer inv = player.inventory;
        ItemStack cursor = inv.getItemStack();
        int[] plan = planDrag(slots, button, cursor);
        for (int i = 0; i < plan.length; i++) {
            if (plan[i] <= 0 || cursor == null) continue;
            Slot s = slots.get(i);
            ItemStack st = s.getStack();
            if (st == null) {
                ItemStack put = cursor.copy();
                put.stackSize = plan[i];
                s.putStack(put);
            } else {
                st.stackSize += plan[i];
                s.onSlotChanged();
            }
            cursor.stackSize -= plan[i];
        }
        if (cursor != null && cursor.stackSize <= 0) inv.setItemStack(null);
    }

    // ---- the screen: drag and double-click ---------------------------------------------------------

    private static void superClick(GuiContainer g, int mx, int my, int btn) {
        if (btn == 0) {
            for (Object o : g.controlList) {
                GuiButton b = (GuiButton) o;
                if (b.mousePressed(g.mc, mx, my)) {
                    g.mc.sndManager.playSoundFX("random.click", 1.0F, 1.0F);
                    g.actionPerformed(b);
                }
            }
        }
    }

    /** atStartCancelable of GuiContainer.mouseClicked: returns true when it handled the click itself. */
    public static boolean mouseClicked(GuiContainer g, int mx, int my, int btn) {
        Slot s = g.getSlotAtPosition(mx, my);
        int slotNum = s == null ? -1 : s.slotNumber;
        long now = System.currentTimeMillis();
        if (bypass) {
            lastClickSlot = btn == 0 ? slotNum : -1;
            lastClickTime = now;
            return false;
        }
        if (btn != 0 && btn != 1) return false;
        if (btn == 0 && InventoryPlus.sortClicked(g, mx, my)) return true;
        ItemStack cur = g.mc.thePlayer.inventory.getItemStack();
        boolean shift = shiftDown();
        if (btn == 0 && shift && cur == null && InventoryPlus.shiftClick(g, s, now)) return true;   // second click of a shift + double-click: move every matching stack
        if (btn == 0 && slotNum >= 0 && slotNum == lastClickSlot && now - lastClickTime < 300L && cur != null && !Keyboard.isKeyDown(Keyboard.KEY_LSHIFT)) {
            superClick(g, mx, my, btn);
            g.mc.playerController.func_27174_a(g.inventorySlots.windowId, slotNum, 6, false, g.mc.thePlayer);   // double-click: gather
            lastClickSlot = -1;
            return true;
        }
        if (s != null && !(s instanceof SlotCrafting)) {
            if (cur != null && !shift) {
                dragButton = btn;   // the click is decided on release: one slot = normal click, several = distribute
                dragShift = false;
                dragX = mx;
                dragY = my;
                dragSlots.clear();
                if (s.isItemValid(cur)) dragSlots.add(s);
                if (!dragSlots.isEmpty()) {
                    superClick(g, mx, my, btn);
                    return true;
                }
                dragButton = -1;
            } else if (shift && btn == 0 && s.getHasStack()) {
                dragButton = 0;
                dragShift = true;
                dragSlots.clear();
                dragSlots.add(s);
            }
        }
        lastClickSlot = btn == 0 ? slotNum : -1;
        lastClickTime = now;
        return false;
    }

    private static void finishDrag(GuiContainer g) {
        int button = dragButton;
        dragButton = -1;
        if (dragShift) {
            dragSlots.clear();
            return;
        }
        if (dragSlots.size() >= 2) {
            applyDrag(dragSlots, button, g.mc.thePlayer);
        } else if (dragSlots.size() == 1) {
            bypass = true;
            try {
                g.mouseClicked(dragX, dragY, button);
            } finally {
                bypass = false;
            }
        }
        dragSlots.clear();
    }

    /** atStart of GuiContainer.drawScreen: follows an active drag. */
    public static void poll(GuiContainer g, int mx, int my, float partial) {
        if (dragButton < 0) return;
        if (!Mouse.isButtonDown(dragButton)) {
            finishDrag(g);
            return;
        }
        Slot s = g.getSlotAtPosition(mx, my);
        if (s == null || dragSlots.contains(s)) return;
        if (dragShift) {
            if (s.getHasStack() && !(s instanceof SlotCrafting)) g.mc.playerController.func_27174_a(g.inventorySlots.windowId, s.slotNumber, 0, true, g.mc.thePlayer);
            dragSlots.add(s);
        } else {
            ItemStack cur = g.mc.thePlayer.inventory.getItemStack();
            if (cur != null && !(s instanceof SlotCrafting) && s.isItemValid(cur)) dragSlots.add(s);
        }
    }

    /** atEnd of GuiContainer.drawScreen: while dragging, show the item that each slot is about to get (a ghost icon in empty slots), tint the slots and show how many. */
    public static void preview(GuiContainer g, int mx, int my, float partial) {
        durabilityTooltip(g, mx, my);
        if (dragButton < 0 || dragShift || dragSlots.size() < 2) return;
        ItemStack cursor = g.mc.thePlayer.inventory.getItemStack();
        int[] plan = planDrag(dragSlots, dragButton, cursor);
        // the item icons need the same lighting set-up the screen uses for its slots (drawScreen switched it off again before this runs)
        Gfx.glPushMatrix();
        Gfx.glRotatef(120.0F, 1.0F, 0.0F, 0.0F);
        net.minecraft.src.RenderHelper.enableStandardItemLighting();
        Gfx.glPopMatrix();
        Gfx.glPushMatrix();
        Gfx.glTranslatef((g.width - g.xSize) / 2, (g.height - g.ySize) / 2, 0.0F);
        Gfx.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        Gfx.glEnable(Gfx.GL_RESCALE_NORMAL);
        Gfx.glEnable(Gfx.GL_LIGHTING);
        Gfx.glEnable(Gfx.GL_DEPTH_TEST);
        for (int i = 0; i < plan.length; i++) {
            Slot ds = dragSlots.get(i);
            if (plan[i] > 0 && cursor != null && !ds.getHasStack()) {
                GuiContainer.itemRenderer.renderItemIntoGUI(g.fontRenderer, g.mc.renderEngine, cursor, ds.xDisplayPosition, ds.yDisplayPosition);
            }
        }
        Gfx.glDisable(Gfx.GL_RESCALE_NORMAL);
        net.minecraft.src.RenderHelper.disableStandardItemLighting();
        Gfx.glDisable(Gfx.GL_LIGHTING);
        Gfx.glDisable(Gfx.GL_DEPTH_TEST);
        for (int i = 0; i < plan.length; i++) {
            Slot ds = dragSlots.get(i);
            int tint = plan[i] > 0 ? (ds.getHasStack() ? 0x80FFFFFF : 0x40FFFFFF) : 0x80FF4040;   // lighter over a ghost icon so it stays visible
            g.drawGradientRect(ds.xDisplayPosition, ds.yDisplayPosition, ds.xDisplayPosition + 16, ds.yDisplayPosition + 16, tint, tint);
            if (plan[i] > 0) {
                String amount = "" + plan[i];
                g.fontRenderer.drawStringWithShadow(amount, ds.xDisplayPosition + 17 - g.fontRenderer.getStringWidth(amount), ds.yDisplayPosition + 9, 0xFFFFFF);
            }
        }
        Gfx.glEnable(Gfx.GL_LIGHTING);
        Gfx.glEnable(Gfx.GL_DEPTH_TEST);
        Gfx.glPopMatrix();
    }

    /** A second line under the item name for tools and armor: how many uses are left. */
    private static void durabilityTooltip(GuiContainer g, int mx, int my) {
        if (g.mc.thePlayer.inventory.getItemStack() != null) return;
        Slot s = g.getSlotAtPosition(mx, my);
        if (s == null || !s.getHasStack()) return;
        ItemStack st = s.getStack();
        int max = st.getMaxDamage();
        if (max <= 0) return;
        String name = McCompat.itemDisplayName(st);
        if (name.length() == 0 && McCompat.HAS_ITEM_NAME) return;
        String line = "Durability: " + (max - McCompat.itemDamage(st)) + " / " + max;
        int left = (g.width - g.xSize) / 2, top = (g.height - g.ySize) / 2;
        int x = mx - left + 12, y = my - top - 12;
        int w = Math.max(name.isEmpty() ? 0 : g.fontRenderer.getStringWidth(name), g.fontRenderer.getStringWidth(line));
        Gfx.glPushMatrix();
        Gfx.glTranslatef(left, top, 0.0F);
        Gfx.glDisable(Gfx.GL_LIGHTING);
        Gfx.glDisable(Gfx.GL_DEPTH_TEST);
        // the game already drew its box (x-3..x+nameWidth+3, y-3..y+11) and the name: add only what is new, so no area is covered twice and darker
        int nameW = g.fontRenderer.getStringWidth(name);
        g.drawGradientRect(x - 3, y + 11, x + w + 3, y + 8 + 3 + 10, -1073741824, -1073741824);                  // the row for the new line
        if (w > nameW) g.drawGradientRect(x + nameW + 3, y - 3, x + w + 3, y + 11, -1073741824, -1073741824);     // widening the name row when the new line is longer
        g.fontRenderer.drawStringWithShadow(line, x, y + 10, 0xAAAAAA);
        Gfx.glEnable(Gfx.GL_LIGHTING);
        Gfx.glEnable(Gfx.GL_DEPTH_TEST);
        Gfx.glPopMatrix();
    }
}
