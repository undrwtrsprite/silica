package dev.betavk.hooks;

import dev.betavk.platform.Mouse;
import dev.betavk.render.Gfx;
import net.minecraft.client.Minecraft;
import net.minecraft.src.GuiButton;

import java.util.function.Consumer;
import java.util.function.Supplier;

/** A slider for our own options (value 0..1), drawn like the game's own sliders. */
public final class SliderButton extends GuiButton {
    public float value;
    private boolean dragging;
    private final Consumer<Float> apply;
    private final Supplier<String> label;
    private final Runnable onRelease;

    public SliderButton(int id, int x, int y, float value, Consumer<Float> apply, Supplier<String> label) {
        this(id, x, y, value, apply, label, null);
    }

    public SliderButton(int id, int x, int y, float value, Consumer<Float> apply, Supplier<String> label, Runnable onRelease) {
        this(id, x, y, 150, 20, value, apply, label, onRelease);
    }

    public SliderButton(int id, int x, int y, int w, int h, float value, Consumer<Float> apply, Supplier<String> label, Runnable onRelease) {
        super(id, x, y, w, h, label.get());
        this.value = Math.max(0.0F, Math.min(1.0F, value));
        this.apply = apply;
        this.label = label;
        this.onRelease = onRelease;
    }

    private static final boolean HAS_MOUSE_DRAGGED = McCompat.hasMethod(GuiButton.class, "mouseDragged", Minecraft.class, int.class, int.class);

    public int getHoverState(boolean over) {
        return 0;
    }

    private void set(int mouseX) {
        value = Math.max(0.0F, Math.min(1.0F, (float) (mouseX - (xPosition + 4)) / (float) (width - 8)));
        apply.accept(value);
        displayString = label.get();
    }

    @Override public void drawButton(Minecraft mc, int mx, int my) {
        if (HAS_MOUSE_DRAGGED) {
            super.drawButton(mc, mx, my);
            return;
        }
        if (!enabled2) return;
        if (dragging && !Mouse.isButtonDown(0)) mouseReleased(mx, my);
        Gfx.glBindTexture(Gfx.GL_TEXTURE_2D, mc.renderEngine.getTexture("/gui/gui.png"));
        Gfx.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        drawTexturedModalRect(xPosition, yPosition, 0, 46, width / 2, height);
        drawTexturedModalRect(xPosition + width / 2, yPosition, 200 - width / 2, 46, width / 2, height);
        mouseDragged(mc, mx, my);
        int textColor = !enabled ? -6250336 : (mx >= xPosition && my >= yPosition && mx < xPosition + width && my < yPosition + height ? 0xFFFFA0 : 0xE0E0E0);
        drawCenteredString(mc.fontRenderer, displayString, xPosition + width / 2, yPosition + (height - 8) / 2, textColor);
    }

    public void mouseDragged(Minecraft mc, int mx, int my) {
        if (!enabled || !enabled2) return;
        if (dragging) set(mx);
        Gfx.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        drawTexturedModalRect(xPosition + (int) (value * (float) (width - 8)), yPosition, 0, 66, 4, 20);
        drawTexturedModalRect(xPosition + (int) (value * (float) (width - 8)) + 4, yPosition, 196, 66, 4, 20);
    }

    public boolean mousePressed(int mx, int my) {
        return mousePressed(null, mx, my);
    }

    public boolean mousePressed(Minecraft mc, int mx, int my) {
        if (!enabled || !enabled2 || mx < xPosition || my < yPosition || mx >= xPosition + width || my >= yPosition + height) return false;
        set(mx);
        dragging = true;
        return true;
    }

    public void mouseReleased(int mx, int my) {
        if (dragging) {
            dragging = false;
            if (onRelease != null) onRelease.run();
        }
    }
}
