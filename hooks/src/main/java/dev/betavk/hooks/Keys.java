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
    static final boolean HAS_SNEAK = McCompat.hasField(GameSettings.class, "keyBindSneak");
    private static final boolean HAS_KEY_STATES = McCompat.hasField(MovementInputFromOptions.class, "movementKeyStates");
    private static MovementInputFromOptions toggledInput;
    private Keys() {
    }

    private static final class SneakKey {
        static net.minecraft.src.KeyBinding get(GameSettings gs) { return gs.keyBindSneak; }
    }

    private static final class ModernSneak {
        private static final java.lang.reflect.Field PRESSED;
        static {
            java.lang.reflect.Field f = null;
            try { f = net.minecraft.src.KeyBinding.class.getField("field_35965_e"); } catch (Throwable ignored) {}
            PRESSED = f;
        }
        static void set(GameSettings gs, boolean down) {
            if (PRESSED == null) return;
            try { PRESSED.setBoolean(gs.keyBindSneak, down); } catch (Throwable ignored) {}
        }
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
        if (!HAS_SNEAK || !Opts.toggleSneak) return;
        boolean sneaked = toggledInput == input;
        if (HAS_KEY_STATES) {
            input.movementKeyStates[5] = sneaked;
        } else {
            ModernSneak.set(input.gameSettings, sneaked);
        }
    }

    private static boolean modernSneakWasDown;

    /** Handles toggle-sneak key presses on versions without EntityPlayerSP.handleKeyPress (e.g. b1.8+). */
    public static void modernToggleSneak(Minecraft mc, int key, boolean down) {
        if (McCompat.HAS_HANDLE_KEY_PRESS || !HAS_SNEAK || !Opts.toggleSneak || mc == null) {
            modernSneakWasDown = false;
            return;
        }
        if (mc.thePlayer == null || key != SneakKey.get(mc.gameSettings).keyCode) return;
        if (down && !modernSneakWasDown && mc.currentScreen == null && mc.inGameHasFocus
                && mc.thePlayer.movementInput instanceof MovementInputFromOptions input) {
            toggledInput = toggledInput == input ? null : input;
        }
        modernSneakWasDown = down;
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
