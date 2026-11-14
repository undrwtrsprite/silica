package dev.betavk.hooks;

import net.minecraft.src.EntityPlayer;
import net.minecraft.src.GuiIngame;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Recent audible world sounds, grouped by caption and pointed to relative to the player. */
public final class SoundCaptions {
    private static final long LIFETIME_NS = 3_000_000_000L;
    private static final int MAX_CAPTIONS = 5;
    private static final Map<String, Caption> recent = new LinkedHashMap<>();

    private record Caption(float x, float z, long time) {}

    private SoundCaptions() {}

    public static void hear(String id, float x, float z) {
        String label = label(id);
        recent.remove(label);
        recent.put(label, new Caption(x, z, System.nanoTime()));
        if (recent.size() > MAX_CAPTIONS) recent.remove(recent.keySet().iterator().next());
    }

    static void clear() {
        recent.clear();
    }

    static void draw(GuiIngame gui, int width, int height) {
        if (!Opts.soundCaptions || gui.mc.thePlayer == null || gui.mc.gameSettings.hideGUI) return;
        long now = System.nanoTime();
        recent.values().removeIf(caption -> now - caption.time() >= LIFETIME_NS);
        var font = gui.mc.fontRenderer;
        int y = height - 36 - recent.size() * 11;
        for (var entry : recent.entrySet()) {
            String label = entry.getKey();
            Caption caption = entry.getValue();
            int left = width - font.getStringWidth(label) - 23;
            gui.drawRect(left, y, width - 3, y + 10, 0xB0000000);
            gui.drawString(font, label, left + 10, y + 1, 0xFFFFFF);
            int direction = direction(gui.mc.thePlayer, caption);
            if (direction < 0) gui.drawString(font, "<", left + 2, y + 1, 0xFFFFFF);
            if (direction > 0) gui.drawString(font, ">", width - 10, y + 1, 0xFFFFFF);
            y += 11;
        }
    }

    private static int direction(EntityPlayer player, Caption caption) {
        double dx = caption.x() - player.posX, dz = caption.z() - player.posZ;
        double yaw = Math.toRadians(player.rotationYaw);
        double forward = -dx * Math.sin(yaw) + dz * Math.cos(yaw);
        double right = -dx * Math.cos(yaw) - dz * Math.sin(yaw);
        if (Math.hypot(dx, dz) < 0.5 || forward > Math.abs(right) * 1.7) return 0;
        return right < 0 ? -1 : 1;
    }

    private static String label(String id) {
        String key = id.toLowerCase(Locale.ROOT).replace('/', '.').replaceFirst("\\.(ogg|wav|mus)$", "")
                .replaceFirst("\\d+$", "");
        if (key.startsWith("step.")) return "Footsteps";
        if (key.startsWith("mob.")) {
            String mob = key.substring(4).split("\\.")[0];
            return title(mob) + (key.contains("hurt") ? " hurts" : key.contains("death") ? " dies" : " sound");
        }
        return switch (key) {
            case "random.explode" -> "Explosion";
            case "ambient.weather.rain" -> "Rain";
            case "ambient.weather.thunder" -> "Thunder";
            case "ambient.cave.cave" -> "Cave ambience";
            case "liquid.water" -> "Water flows";
            case "liquid.lava" -> "Lava bubbles";
            case "fire.fire" -> "Fire crackles";
            default -> title(key.substring(key.lastIndexOf('.') + 1).replace('_', ' '));
        };
    }

    private static String title(String name) {
        return name.isEmpty() ? "Sound" : Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }
}
