package dev.betavk.hooks;

import dev.betavk.platform.Keyboard;
import net.minecraft.client.Minecraft;
import net.minecraft.src.EntityPlayerSP;
import net.minecraft.src.GameSettings;
import net.minecraft.src.EntityPlayer;
import net.minecraft.src.MovementInputFromOptions;

/**
 * Movement keys and screens. In Beta a movement key pressed in the same tick as the inventory key, but after it, was recorded as held after the screen had
 * already cleared the keys; the screen then swallowed the release, so the player kept walking with the inventory open. Here a screen means no movement at all,
 * and closing it picks up whichever movement keys are really held at that moment (as newer versions do).
 */
public final class Keys {
    private static final boolean HAS_SNEAK = McCompat.hasField(GameSettings.class, "keyBindSneak");
    private static MovementInputFromOptions toggledInput;
    private Keys() {
    }

    private static final class SneakKey {
        static net.minecraft.src.KeyBinding get(GameSettings gs) { return gs.keyBindSneak; }
    }

    /** atStartCancelable of EntityPlayerSP.handleKeyPress(key, down): presses do not count while a screen is open (releases always do). */
    public static boolean press(EntityPlayerSP p, int key, boolean down) {
        if (down && p.mc != null && p.mc.currentScreen != null) return true;
        if (HAS_SNEAK && Opts.toggleSneak && p.movementInput instanceof MovementInputFromOptions input
                && key == SneakKey.get(input.gameSettings).keyCode) {
            if (down) toggledInput = toggledInput == input ? null : input;
            return true;
        }
        return false;
    }

    public static void applySneak(MovementInputFromOptions input, EntityPlayer player) {
        if (HAS_SNEAK && Opts.toggleSneak) input.movementKeyStates[5] = toggledInput == input;
    }

    static void clearSneak() {
        toggledInput = null;
    }

    /** atEnd of Minecraft.setIngameFocus: the game has the mouse again, so the movement keys take the state the keyboard is really in. */
    public static void focus(Minecraft mc) {
        if (!mc.inGameHasFocus || mc.thePlayer == null || mc.currentScreen != null) return;
        GameSettings gs = mc.gameSettings;
        net.minecraft.src.KeyBinding sneak = HAS_SNEAK ? SneakKey.get(gs) : null;
        for (var b : new net.minecraft.src.KeyBinding[]{gs.keyBindForward, gs.keyBindBack, gs.keyBindLeft, gs.keyBindRight, gs.keyBindJump, sneak}) {
            if (b == null) continue;
            if (sneak != null && Opts.toggleSneak && b.keyCode == sneak.keyCode) continue;
            McCompat.setKeyBindState(mc, b.keyCode, Keyboard.isKeyDown(b.keyCode));
        }
    }
}
