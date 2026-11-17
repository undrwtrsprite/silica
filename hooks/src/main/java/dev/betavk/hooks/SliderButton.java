package dev.betavk.hooks;

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
        this.value = value;
        this.apply = apply;
        this.label = label;
        this.onRelease = onRelease;
    }

    @Override public int getHoverState(boolean over) {
        return 0;
    }

    private void set(int mouseX) {
        value = Math.max(0.0F, Math.min(1.0F, (float) (mouseX - (xPosition + 4)) / (float) (width - 8)));
        apply.accept(value);
        displayString = label.get();
    }

    @Override public void mouseDragged(Minecraft mc, int mx, int my) {
        if (!enabled || !enabled2) return;
        if (dragging) set(mx);
        dev.betavk.render.Gfx.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        drawTexturedModalRect(xPosition + (int) (value * (float) (width - 8)), yPosition, 0, 66, 4, 20);
        drawTexturedModalRect(xPosition + (int) (value * (float) (width - 8)) + 4, yPosition, 196, 66, 4, 20);
    }

    @Override public boolean mousePressed(Minecraft mc, int mx, int my) {
        if (!super.mousePressed(mc, mx, my)) return false;
        set(mx);
        dragging = true;
        return true;
    }

    @Override public void mouseReleased(int mx, int my) {
        if (dragging) {
            dragging = false;
            if (onRelease != null) onRelease.run();
        }
    }
}
