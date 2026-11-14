package dev.betavk.hooks;

import dev.betavk.platform.Keyboard;
import dev.betavk.platform.Mouse;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.src.Container;
import net.minecraft.src.ContainerChest;
import net.minecraft.src.ContainerPlayer;
import net.minecraft.src.GuiButton;
import net.minecraft.src.GuiContainer;
import net.minecraft.src.GuiScreen;
import net.minecraft.src.InventoryPlayer;
import net.minecraft.src.ItemStack;
import net.minecraft.src.Slot;
import net.minecraft.src.SlotCrafting;
import net.minecraft.src.SlotFurnace;

/**
 * Inventory conveniences from newer versions, written as sequences of the game's own window clicks (so they work in single player and on a server alike):
 * number keys over a slot swap it with that hotbar slot, Q / Ctrl+Q over a slot drops one item / the whole stack, scrolling over a slot moves one item to
 * (scroll down) or from (scroll up) the other side, shift + double-click moves every matching stack, and a Sort button above the inventory and above chests.
 */
public final class InventoryPlus {
    private InventoryPlus() {
    }

    static boolean ctrl() {
        return Keyboard.isKeyDown(29) || Keyboard.isKeyDown(157);
    }

    private static boolean sameKind(ItemStack a, ItemStack b) {
        return a != null && b != null && a.itemID == b.itemID && (!a.getHasSubtypes() || a.getItemDamage() == b.getItemDamage());
    }

    private static void click(GuiContainer g, Slot s, int button) {
        g.mc.playerController.func_27174_a(g.inventorySlots.windowId, s == null ? -999 : s.slotNumber, button, false, g.mc.thePlayer);
    }

    private static Slot hovered(GuiContainer g) {
        int mx = Mouse.getX() * g.width / g.mc.displayWidth;
        int my = g.height - Mouse.getY() * g.height / g.mc.displayHeight - 1;
        return g.getSlotAtPosition(mx, my);
    }

    private static boolean isOutput(Slot s) {
        return s instanceof SlotCrafting || s instanceof SlotFurnace;
    }

    private static boolean cursorEmpty(GuiContainer g) {
        return g.mc.thePlayer.inventory.getItemStack() == null;
    }

    // ---- keys: number swap, drop ---------------------------------------------------------------------

    /** atStartCancelable of GuiContainer.keyTyped: true when the key was used here. */
    public static boolean key(GuiContainer g, char c, int key) {
        Minecraft mc = g.mc;
        boolean number = key >= 2 && key <= 10;
        boolean drop = key == mc.gameSettings.keyBindDrop.keyCode;
        if (!number && !drop) return false;
        return keyOn(g, hovered(g), key, number);
    }

    static boolean keyOn(GuiContainer g, Slot s, int key, boolean number) {
        if (s == null || !cursorEmpty(g)) return false;
        if (number) {
            swapWithHotbar(g, s, key - 2);
            return true;
        }
        if (isOutput(s) || !s.getHasStack()) return false;
        click(g, s, 0);   // pick the stack up
        if (ctrl()) {
            click(g, null, 0);   // outside the window: the whole stack drops
        } else {
            click(g, null, 1);   // one item drops
            click(g, s, 0);      // the rest goes back
        }
        return true;
    }

    private static Slot hotbarSlot(Container c, InventoryPlayer inv, int index) {
        for (Object o : c.slots) {
            Slot s = (Slot) o;
            if (s.inventory == inv && s.slotIndex == index) return s;
        }
        return null;
    }

    private static void swapWithHotbar(GuiContainer g, Slot a, int h) {
        InventoryPlayer inv = g.mc.thePlayer.inventory;
        Slot hb = hotbarSlot(g.inventorySlots, inv, h);
        if (hb == null || hb == a || a instanceof SlotCrafting) return;
        ItemStack hs = hb.getStack(), as = a.getStack();
        if (hs == null && as == null) return;
        if (hs != null && !a.isItemValid(hs)) return;   // the other way round would leave the item on the cursor
        if (as != null && !hb.isItemValid(as)) return;
        click(g, a, 0);    // pick up what is in the hovered slot (nothing if it is empty)
        click(g, hb, 0);   // put it on the hotbar slot, taking what was there
        click(g, a, 0);    // and that goes into the hovered slot
    }

    // ---- shift + double-click --------------------------------------------------------------------------

    private static Slot lastShiftSlot;
    private static ItemStack lastShiftKind;
    private static long lastShiftTime;

    /** Called from the screen's click hook for a shift + left click on a slot; true when it handled the second click of a double-click. */
    static boolean shiftClick(GuiContainer g, Slot s, long now) {
        if (s == null || isOutput(s)) {
            lastShiftSlot = null;
            return false;
        }
        if (s == lastShiftSlot && lastShiftKind != null && now - lastShiftTime < 350L) {
            ItemStack kind = lastShiftKind;
            lastShiftSlot = null;
            for (int i = 0; i < g.inventorySlots.slots.size(); i++) {
                Slot o = (Slot) g.inventorySlots.slots.get(i);
                if (o.inventory == s.inventory && !isOutput(o) && sameKind(o.getStack(), kind)) {
                    g.mc.playerController.func_27174_a(g.inventorySlots.windowId, o.slotNumber, 0, true, g.mc.thePlayer);
                }
            }
            return true;
        }
        lastShiftSlot = s;
        lastShiftKind = s.getStack() == null ? null : s.getStack().copy();
        lastShiftTime = now;
        return false;
    }

    // ---- mouse wheel ------------------------------------------------------------------------------------

    /** atStart of GuiScreen.handleMouseInput: scroll over a slot. Down moves one item of the hovered stack to the other side, up pulls one of the same item from it. */
    public static void wheel(GuiScreen screen) {
        if (!(screen instanceof GuiContainer g)) return;
        int d = Mouse.getEventDWheel();
        if (d == 0 || !cursorEmpty(g)) return;
        int mx = Mouse.getEventX() * g.width / g.mc.displayWidth;
        int my = g.height - Mouse.getEventY() * g.height / g.mc.displayHeight - 1;
        scroll(g, g.getSlotAtPosition(mx, my), d);
    }

    static void scroll(GuiContainer g, Slot s, int d) {
        if (s == null || isOutput(s) || !s.getHasStack()) return;
        List<Slot> other = otherSide(g, s);
        if (other.isEmpty()) return;
        if (d < 0) {   // down: push one from here
            Slot to = target(other, s.getStack());
            if (to != null) moveOne(g, s, to);
        } else {       // up: pull one of this kind to here (into this stack, else the first place on this side)
            Slot from = null;
            for (Slot o : other) if (sameKind(o.getStack(), s.getStack())) { from = o; break; }
            if (from == null) return;
            Slot to = target(sideOf(g, s), from.getStack());
            if (to != null) moveOne(g, from, to);
        }
    }

    private static boolean playerSlot(GuiContainer g, Slot s) {
        return s.inventory == g.mc.thePlayer.inventory;
    }

    /** The slots on the same side of the window as s (the player's inventory, or the container). */
    private static List<Slot> sideOf(GuiContainer g, Slot s) {
        List<Slot> l = new ArrayList<>();
        for (Object o : g.inventorySlots.slots) {
            Slot x = (Slot) o;
            if (isOutput(x) || x.inventory != s.inventory) continue;
            if (playerSlot(g, x) && x.slotIndex >= 36) continue;   // armor
            l.add(x);
        }
        return l;
    }

    private static List<Slot> otherSide(GuiContainer g, Slot s) {
        List<Slot> l = new ArrayList<>();
        boolean mine = playerSlot(g, s);
        boolean playerScreen = g.inventorySlots instanceof ContainerPlayer;
        for (Object o : g.inventorySlots.slots) {
            Slot x = (Slot) o;
            if (isOutput(x)) continue;
            if (playerSlot(g, x) && x.slotIndex >= 36) continue;
            if (playerScreen) {   // no container: hotbar <-> main inventory
                if (!playerSlot(g, x) || (x.slotIndex < 9) == (s.slotIndex < 9)) continue;
                l.add(x);
            } else if (mine ? !playerSlot(g, x) : playerSlot(g, x)) {
                l.add(x);
            }
        }
        return l;
    }

    /** A slot that can take one more of the stack: a partial stack of the same kind first, else the first empty slot. */
    private static Slot target(List<Slot> slots, ItemStack item) {
        for (Slot x : slots) {
            ItemStack st = x.getStack();
            if (st != null && sameKind(st, item) && st.stackSize < Math.min(st.getMaxStackSize(), x.getSlotStackLimit()) && x.isItemValid(item)) return x;
        }
        for (Slot x : slots) if (x.getStack() == null && x.isItemValid(item)) return x;
        return null;
    }

    private static void moveOne(GuiContainer g, Slot from, Slot to) {
        if (from == to) return;
        click(g, from, 0);   // pick up the stack
        click(g, to, 1);     // one goes into the target
        click(g, from, 0);   // the rest goes back
    }

    // ---- sort -------------------------------------------------------------------------------------------

    /** A small button with a sort glyph that sorts the slot group it was made for. */
    static final class SortButton extends GuiButton {
        final List<Slot> group;

        SortButton(int id, int x, int y, List<Slot> group) {
            super(id, x, y, 10, 10, "");
            this.group = group;
        }

        @Override public void drawButton(Minecraft mc, int mx, int my) {
            boolean over = mx >= xPosition && my >= yPosition && mx < xPosition + width && my < yPosition + height;
            drawRect(xPosition, yPosition, xPosition + width, yPosition + height, 0xFF000000);
            drawRect(xPosition + 1, yPosition + 1, xPosition + width - 1, yPosition + height - 1, over ? 0xFF8B8B8B : 0xFFC6C6C6);
            int ink = over ? 0xFFFFFFA0 : 0xFF404040;
            drawRect(xPosition + 2, yPosition + 2, xPosition + 8, yPosition + 3, ink);
            drawRect(xPosition + 2, yPosition + 4, xPosition + 6, yPosition + 5, ink);
            drawRect(xPosition + 2, yPosition + 6, xPosition + 4, yPosition + 7, ink);
        }
    }

    /** Adds the sort buttons the first time the screen is drawn. */
    static void ensureButtons(GuiContainer g) {
        for (Object o : g.controlList) if (o instanceof SortButton) return;
        InventoryPlayer inv = g.mc.thePlayer.inventory;
        int left = (g.width - g.xSize) / 2, top = (g.height - g.ySize) / 2;
        List<Slot> main = new ArrayList<>(), box = new ArrayList<>();
        for (Object o : g.inventorySlots.slots) {
            Slot s = (Slot) o;
            if (s.inventory == inv) {
                if (s.slotIndex >= 9 && s.slotIndex < 36) main.add(s);
            } else if (g.inventorySlots instanceof ContainerChest) {
                box.add(s);
            }
        }
        if (!main.isEmpty()) g.controlList.add(button(930, left, top, main));
        if (!box.isEmpty()) g.controlList.add(button(931, left, top, box));
    }

    private static SortButton button(int id, int left, int top, List<Slot> group) {
        int right = 0, minY = Integer.MAX_VALUE;
        for (Slot s : group) {
            right = Math.max(right, s.xDisplayPosition + 17);
            minY = Math.min(minY, s.yDisplayPosition - 1);
        }
        return new SortButton(id, left + right - 10, top + minY - 12, group);
    }

    /** The sort button under the mouse, if the click is on one. */
    static boolean sortClicked(GuiContainer g, int mx, int my) {
        for (Object o : g.controlList) {
            if (o instanceof SortButton b && mx >= b.xPosition && my >= b.yPosition && mx < b.xPosition + b.width && my < b.yPosition + b.height) {
                g.mc.sndManager.playSoundFX("random.click", 1.0F, 1.0F);
                if (cursorEmpty(g)) sort(g, b.group);
                return true;
            }
        }
        return false;
    }

    private static int rank(ItemStack s) {
        return (s.itemID < 256 ? 0 : 1) * 100000 + s.itemID;
    }

    /** Merges partial stacks, then orders the group (blocks before items, by id, damage, bigger stacks first) with swaps made of three clicks each. */
    static void sort(GuiContainer g, List<Slot> group) {
        // 1. merge: top up each partial stack from the later stacks of the same kind
        for (int i = 0; i < group.size(); i++) {
            Slot a = group.get(i);
            for (int j = i + 1; j < group.size(); j++) {
                ItemStack sa = a.getStack();
                if (sa == null || sa.stackSize >= sa.getMaxStackSize()) break;
                Slot b = group.get(j);
                if (!sameKind(b.getStack(), sa)) continue;
                click(g, b, 0);
                click(g, a, 0);
                if (!cursorEmpty(g)) click(g, b, 0);   // what did not fit goes back
            }
        }
        // 2. order
        List<ItemStack> want = new ArrayList<>();
        for (Slot s : group) if (s.getStack() != null) want.add(s.getStack().copy());
        want.sort(Comparator.<ItemStack>comparingInt(InventoryPlus::rank).thenComparingInt(ItemStack::getItemDamage).thenComparingInt(s -> -s.stackSize));
        for (int i = 0; i < group.size(); i++) {
            ItemStack w = i < want.size() ? want.get(i) : null;
            Slot a = group.get(i);
            ItemStack cur = a.getStack();
            if (w == null ? cur == null : cur != null && sameKind(cur, w) && cur.stackSize == w.stackSize) continue;
            if (w == null) continue;
            for (int j = i + 1; j < group.size(); j++) {
                ItemStack c = group.get(j).getStack();
                if (c != null && sameKind(c, w) && c.stackSize == w.stackSize) {
                    Slot b = group.get(j);
                    click(g, b, 0);   // pick up the wanted stack
                    click(g, a, 0);   // swap it with what is in the slot
                    click(g, b, 0);   // and put that where the wanted one was
                    break;
                }
            }
        }
    }
}
