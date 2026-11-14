package dev.betavk.hooks;

import net.minecraft.client.Minecraft;
import net.minecraft.src.Item;
import net.minecraft.src.ItemStack;
import net.minecraft.src.NBTTagCompound;
import net.minecraft.src.WorldInfo;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.WeakHashMap;

/** Local commands and the per-save permission stored in level.dat. */
public final class Commands {
    static final List<String> WEATHER_TYPES = List.of("clear", "rain", "thunder");
    private static final String TIME_USAGE = "/time set <day|night|0-23999>";
    private static final String GIVE_USAGE = "/give <item ID> [count]";
    private static final String WEATHER_USAGE = "/weather <clear|rain|thunder> [seconds]";
    private static final String CHEATS_TAG = "SilicaCheats";
    private static final Set<WorldInfo> cheats = Collections.newSetFromMap(new WeakHashMap<>());

    private Commands() {
    }

    public static void load(WorldInfo info, NBTTagCompound tag) {
        if (tag.getBoolean(CHEATS_TAG)) cheats.add(info);
    }

    public static void copy(WorldInfo info, WorldInfo previous) {
        if (cheats.contains(previous)) cheats.add(info);
    }

    public static void save(WorldInfo info, NBTTagCompound tag, NBTTagCompound player) {
        tag.setBoolean(CHEATS_TAG, cheats.contains(info));
    }

    static boolean isLocalWorld(Minecraft mc) {
        return mc.theWorld != null && !mc.isMultiplayerWorld() && !mc.theWorld.multiplayerWorld;
    }

    public static boolean canOpenChat(Minecraft mc) {
        return mc.isMultiplayerWorld() || isLocalWorld(mc);
    }

    static boolean enabled(Minecraft mc) {
        return isLocalWorld(mc) && cheats.contains(mc.theWorld.getWorldInfo());
    }

    static void toggle(Minecraft mc) {
        if (!isLocalWorld(mc)) return;
        WorldInfo info = mc.theWorld.getWorldInfo();
        if (!cheats.remove(info)) cheats.add(info);
        mc.theWorld.saveLevel();
    }

    public static boolean run(Minecraft mc, String message) {
        if (!message.startsWith("/") || !isLocalWorld(mc) || mc.thePlayer == null) return false;
        if (!enabled(mc)) {
            reply(mc, "Enable Cheats in Options > World to use commands.");
            return true;
        }

        CommandSuggestions.remember(message);
        String[] args = message.substring(1).trim().split("\\s+");
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "help" -> {
                for (String line : List.of("/help", "/seed", TIME_USAGE, GIVE_USAGE, WEATHER_USAGE)) reply(mc, line);
            }
            case "seed" -> reply(mc, "Seed: " + mc.theWorld.getRandomSeed());
            case "time" -> time(mc, args);
            case "give" -> give(mc, args);
            case "weather" -> weather(mc, args);
            default -> reply(mc, "Unknown command. Try /help.");
        }
        return true;
    }

    private static void time(Minecraft mc, String[] args) {
        String usage = "Usage: " + TIME_USAGE;
        if (args.length != 3 || !args[1].equalsIgnoreCase("set")) {
            reply(mc, usage);
            return;
        }
        int tick = switch (args[2].toLowerCase(Locale.ROOT)) {
            case "day" -> 1000;
            case "night" -> 13000;
            default -> number(args[2]);
        };
        if (tick < 0 || tick >= 24000) {
            reply(mc, usage);
            return;
        }
        long current = mc.theWorld.getWorldTime();
        mc.theWorld.setWorldTime(current + Math.floorMod(tick - current, 24000L));
        reply(mc, "Time set to " + tick + ".");
    }

    private static void weather(Minecraft mc, String[] args) {
        String type = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "";
        int seconds = args.length == 3 ? number(args[2]) : 300;
        if (!WEATHER_TYPES.contains(type) || args.length > 3 || seconds < 1 || seconds > 86400) {
            reply(mc, "Usage: " + WEATHER_USAGE + " (1-86400)");
            return;
        }
        WorldInfo info = mc.theWorld.getWorldInfo();
        info.setRaining(!type.equals("clear"));
        info.setThundering(type.equals("thunder"));
        info.setRainTime(seconds * 20);
        info.setThunderTime(seconds * 20);
        reply(mc, "Weather set to " + type + " for " + seconds + " seconds.");
    }

    private static void give(Minecraft mc, String[] args) {
        int id = args.length >= 2 ? number(args[1]) : -1;
        int count = args.length == 3 ? number(args[2]) : 1;
        if (args.length > 3 || id <= 0 || id >= Item.itemsList.length || Item.itemsList[id] == null || count < 1 || count > 64) {
            reply(mc, "Usage: /give <item ID> [count 1-64]");
            return;
        }
        Item item = Item.itemsList[id];
        int remaining = count;
        while (remaining > 0) {
            int amount = Math.min(remaining, item.getItemStackLimit());
            ItemStack stack = new ItemStack(item, amount);
            mc.thePlayer.inventory.addItemStackToInventory(stack);
            if (stack.stackSize > 0) mc.thePlayer.dropPlayerItem(stack);
            remaining -= amount;
        }
        reply(mc, "Gave " + count + " of item " + id + ".");
    }

    private static int number(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static void reply(Minecraft mc, String message) {
        mc.ingameGUI.addChatMessage(message);
    }
}
