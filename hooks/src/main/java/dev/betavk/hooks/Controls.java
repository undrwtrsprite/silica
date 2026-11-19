package dev.betavk.hooks;

import net.minecraft.src.GameSettings;
import net.minecraft.src.GuiButton;
import net.minecraft.src.GuiControls;
import net.minecraft.src.KeyBinding;

import java.util.Arrays;

/** Adds mouse settings and the Zoom binding to the game's Controls screen. */
public final class Controls {
    static final int SENSITIVITY = 340, INVERT_MOUSE = 341, SNEAK_MODE = 342;

    private Controls() {
    }

    public static KeyBinding zoom;
    private static final boolean HAS_MOUSE_SENSITIVITY = McCompat.hasField(GameSettings.class, "mouseSensitivity");

    /** beforeCall of loadOptions inside GameSettings's constructor: the binding must exist before the options file is read. */
    public static void addZoom(GameSettings gs) {
        zoom = new KeyBinding("Zoom", 46);   // C; the description is shown as is
        KeyBinding[] all = Arrays.copyOf(gs.keyBindings, gs.keyBindings.length + 1);
        all[all.length - 1] = zoom;
        gs.keyBindings = all;
    }

    /** Append mouse options so the vanilla key binding buttons retain their list indices. */
    @SuppressWarnings("unchecked")
    public static void addMouseSettings(GuiControls controls) {
        GameSettings options = controls.options;
        int bindings = options.keyBindings.length;
        int row = (bindings + 1) / 2;
        if (McCompat.HAS_ENUM_OPTIONS) {
            BetaControls.add(controls, options, row);
        } else {
            if (HAS_MOUSE_SENSITIVITY) {
                float cur = legacySensitivity(options);
                controls.controlList.add(new SliderButton(SENSITIVITY, OptionsScreens.x(controls, 0), OptionsScreens.y(controls, row), cur,
                        v -> setLegacySensitivity(options, v), () -> legacySensitivityLabel(options)));
            }
            controls.controlList.add(new GuiButton(INVERT_MOUSE, OptionsScreens.x(controls, 1), OptionsScreens.y(controls, row),
                    150, 20, "Invert Mouse: " + (options.invertMouse ? "ON" : "OFF")));
        }
        if (bindings % 2 != 0) {
            controls.controlList.add(new GuiButton(SNEAK_MODE, OptionsScreens.x(controls, 1), OptionsScreens.y(controls, row - 1),
                    150, 20, sneakLabel()));
        }
    }

    private static String sneakLabel() {
        return "Sneak: " + (Opts.toggleSneak ? "Toggle" : "Hold");
    }

    private static float legacySensitivity(GameSettings options) {
        try {
            return GameSettings.class.getField("mouseSensitivity").getFloat(options);
        } catch (Throwable ignored) {
            return 0.5F;
        }
    }

    private static String legacySensitivityLabel(GameSettings options) {
        return "Sensitivity: " + Math.round(OptionsScreens.sensitivity(legacySensitivity(options)) * 100.0F) + "%";
    }

    private static void setLegacySensitivity(GameSettings options, float v) {
        try {
            GameSettings.class.getField("mouseSensitivity").setFloat(options, v);
        } catch (Throwable ignored) {}
    }

    public static boolean mouseAction(GuiControls controls, GuiButton button) {
        if (button.id != SENSITIVITY && button.id != INVERT_MOUSE && button.id != SNEAK_MODE) return false;
        if (controls.buttonId >= 0) {
            int binding = controls.buttonId;
            ((GuiButton) controls.controlList.get(binding)).displayString = controls.options.getOptionDisplayString(binding);
            controls.buttonId = -1;
        }
        if (button.enabled && button.id == INVERT_MOUSE) {
            if (McCompat.HAS_ENUM_OPTIONS) {
                BetaControls.invert(controls, button);
            } else {
                controls.options.invertMouse = !controls.options.invertMouse;
                button.displayString = "Invert Mouse: " + (controls.options.invertMouse ? "ON" : "OFF");
                controls.options.saveOptions();
            }
        }
        if (button.enabled && button.id == SNEAK_MODE) {
            Opts.toggleSneak = !Opts.toggleSneak;
            Keys.clearSneak();
            button.displayString = sneakLabel();
            controls.options.saveOptions();
        }
        return true;
    }

    public static void closed(GuiControls controls) {
        controls.options.saveOptions();
    }

    private static final class BetaControls {
        @SuppressWarnings("unchecked")
        static void add(GuiControls controls, GameSettings options, int row) {
            var sensitivity = net.minecraft.src.EnumOptions.SENSITIVITY;
            controls.controlList.add(new net.minecraft.src.GuiSlider(SENSITIVITY, OptionsScreens.x(controls, 0), OptionsScreens.y(controls, row),
                    sensitivity, options.getKeyBinding(sensitivity), options.getOptionFloatValue(sensitivity)));
            controls.controlList.add(new GuiButton(INVERT_MOUSE, OptionsScreens.x(controls, 1), OptionsScreens.y(controls, row), 150, 20,
                    options.getKeyBinding(net.minecraft.src.EnumOptions.INVERT_MOUSE)));
        }

        static void invert(GuiControls controls, GuiButton button) {
            controls.options.setOptionValue(net.minecraft.src.EnumOptions.INVERT_MOUSE, 1);
            button.displayString = controls.options.getKeyBinding(net.minecraft.src.EnumOptions.INVERT_MOUSE);
        }
    }
}
