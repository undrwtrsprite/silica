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
    private static MovementInputFromOptions toggledInput;
    private Keys() {
    }

    /** atStartCancelable of EntityPlayerSP.handleKeyPress(key, down): presses do not count while a screen is open (releases always do). */
    public static boolean press(EntityPlayerSP p, int key, boolean down) {
        if (down && p.mc != null && p.mc.currentScreen != null) return true;
        if (Opts.toggleSneak && p.movementInput instanceof MovementInputFromOptions input
                && key == input.gameSettings.keyBindSneak.keyCode) {
            if (down) toggledInput = toggledInput == input ? null : input;
            return true;
        }
        return false;
    }

    public static void applySneak(MovementInputFromOptions input, EntityPlayer player) {
        if (Opts.toggleSneak) input.movementKeyStates[5] = toggledInput == input;
    }

    static void clearSneak() {
        toggledInput = null;
    }

    /** atEnd of Minecraft.setIngameFocus: the game has the mouse again, so the movement keys take the state the keyboard is really in. */
    public static void focus(Minecraft mc) {
        if (!mc.inGameHasFocus || mc.thePlayer == null || mc.currentScreen != null) return;
        GameSettings gs = mc.gameSettings;
        for (var b : new net.minecraft.src.KeyBinding[]{gs.keyBindForward, gs.keyBindBack, gs.keyBindLeft, gs.keyBindRight, gs.keyBindJump, gs.keyBindSneak}) {
            if (Opts.toggleSneak && b.keyCode == gs.keyBindSneak.keyCode) continue;
            mc.thePlayer.handleKeyPress(b.keyCode, Keyboard.isKeyDown(b.keyCode));
        }
    }
}
