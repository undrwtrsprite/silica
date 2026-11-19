package dev.betavk.hooks;

import net.minecraft.client.Minecraft;
import net.minecraft.src.EntityLiving;
import net.minecraft.src.EntityPlayer;
import net.minecraft.src.EntityPlayerSP;
import net.minecraft.src.Item;
import net.minecraft.src.ItemStack;
import net.minecraft.src.NBTTagCompound;
import net.minecraft.src.World;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.WeakHashMap;

/** Local commands and the per-save permission stored in level.dat. */
public final class Commands {
    static final List<String> WEATHER_TYPES = List.of("clear", "rain", "thunder");
    private static final String FLY_USAGE = "/fly [on|off|speed <0.1-20>|<0.1-20>]";
    private static final String TIME_USAGE = "/time set <day|night|0-23999>";
    private static final String GIVE_USAGE = "/give <item ID> [count]";
    private static final String WEATHER_USAGE = "/weather <clear|rain|thunder> [seconds]";
    private static final String CHEATS_TAG = "SilicaCheats";
    private static final Set<World> legacyCheats = Collections.newSetFromMap(new WeakHashMap<>());
    private static World flyWorld;
    private static boolean flying;
    private static float flySpeed = 1.0F;

    private Commands() {
    }

    public static void load(net.minecraft.src.WorldInfo info, NBTTagCompound tag) {
        BetaCheats.load(info, tag);
    }

    public static void copy(net.minecraft.src.WorldInfo info, net.minecraft.src.WorldInfo previous) {
        BetaCheats.copy(info, previous);
    }

    public static void save(net.minecraft.src.WorldInfo info, NBTTagCompound tag, NBTTagCompound player) {
        BetaCheats.save(info, tag);
    }

    private static final boolean HAS_MULTIPLAYER_WORLD = McCompat.hasField(net.minecraft.src.World.class, "multiplayerWorld");

    static boolean isLocalWorld(Minecraft mc) {
        return mc.theWorld != null && !mc.isMultiplayerWorld() && (!HAS_MULTIPLAYER_WORLD || !MultiplayerWorldFlag.isRemote(mc.theWorld));
    }

    private static final class MultiplayerWorldFlag {
        static boolean isRemote(net.minecraft.src.World w) { return w.multiplayerWorld; }
    }

    public static boolean canOpenChat(Minecraft mc) {
        return mc.isMultiplayerWorld() || isLocalWorld(mc);
    }

    static boolean enabled(Minecraft mc) {
        if (!isLocalWorld(mc)) return false;
        return McCompat.HAS_WORLD_INFO ? BetaCheats.enabled(mc.theWorld) : legacyCheats.contains(mc.theWorld);
    }

    static void toggle(Minecraft mc) {
        if (!isLocalWorld(mc)) return;
        if (McCompat.HAS_WORLD_INFO) {
            BetaCheats.toggle(mc.theWorld);
        } else {
            if (!legacyCheats.remove(mc.theWorld)) legacyCheats.add(mc.theWorld);
        }
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
                for (String line : List.of(FLY_USAGE, "/help", "/seed", TIME_USAGE, GIVE_USAGE, WEATHER_USAGE)) reply(mc, line);
            }
            case "seed" -> reply(mc, "Seed: " + McCompat.worldSeed(mc.theWorld));
            case "fly" -> fly(mc, args);
            case "time" -> time(mc, args);
            case "give" -> give(mc, args);
            case "weather" -> weather(mc, args);
            default -> reply(mc, "Unknown command. Try /help.");
        }
        return true;
    }

    public static boolean isFlying(Minecraft mc) {
        return flying && mc != null && mc.theWorld != null && mc.theWorld == flyWorld;
    }

    static float flySpeed() {
        return flySpeed;
    }

    private static void setFlying(Minecraft mc, boolean on) {
        flying = on;
        flyWorld = mc.theWorld;
        EntityPlayerSP p = mc.thePlayer;
        if (p != null) {
            p.fallDistance = 0.0F;
            McCompat.entityResetPrevWalk(p);
        }
    }

    private static void fly(Minecraft mc, String[] args) {
        if (mc.theWorld != flyWorld) {
            flyWorld = mc.theWorld;
            flying = false;
        }
        if (args.length == 1) {
            setFlying(mc, !flying);
            reply(mc, flying ? String.format(Locale.ROOT, "Flight enabled (%.1fx speed).", flySpeed) : "Flight disabled.");
            return;
        }
        String a1 = args[1].toLowerCase(Locale.ROOT);
        if (args.length == 2 && (a1.equals("on") || a1.equals("off"))) {
            setFlying(mc, a1.equals("on"));
            reply(mc, flying ? String.format(Locale.ROOT, "Flight enabled (%.1fx speed).", flySpeed) : "Flight disabled.");
            return;
        }
        String speedArg = null;
        if (args.length == 2) {
            speedArg = a1;
        } else if (args.length == 3 && a1.equals("speed")) {
            speedArg = args[2];
        }
        if (speedArg == null) {
            reply(mc, "Usage: " + FLY_USAGE);
            return;
        }
        float speed = floatNumber(speedArg);
        if (Float.isNaN(speed) || speed < 0.1F || speed > 20.0F) {
            reply(mc, "Usage: " + FLY_USAGE);
            return;
        }
        flySpeed = speed;
        setFlying(mc, true);
        reply(mc, String.format(Locale.ROOT, "Flight enabled at %.1fx speed.", flySpeed));
    }

    private static final boolean HAS_SLEEPING = McCompat.hasField(EntityPlayer.class, "sleeping");
    private static final boolean HAS_SNEAK_FIELD = McCompat.hasField(net.minecraft.src.MovementInput.class, "sneak");
    private static final boolean HAS_ADD_CHAT = McCompat.hasMethod(net.minecraft.src.GuiIngame.class, "addChatMessage", String.class);

    private static final class SleepingFlag {
        static boolean is(EntityPlayerSP p) { return p.sleeping; }
    }

    private static final class SneakInputFlag {
        static boolean is(EntityPlayerSP p) { return p.movementInput.sneak; }
    }

    private static final class ChatReply {
        static void send(Minecraft mc, String msg) { mc.ingameGUI.addChatMessage(msg); }
    }

    public static boolean flyMove(EntityLiving e, float strafe, float forward) {
        if (!flying || !(e instanceof EntityPlayerSP p) || p.mc == null || p.mc.theWorld != flyWorld
                || p.ridingEntity != null || (HAS_SLEEPING && SleepingFlag.is(p))) {
            return false;
        }
        boolean jump = p.movementInput != null && p.movementInput.jump;
        boolean sneak = HAS_SNEAK_FIELD && p.movementInput != null && SneakInputFlag.is(p);
        float s = sneak ? strafe / 0.3F : strafe;
        float f = sneak ? forward / 0.3F : forward;
        McCompat.entityMoveFlying(p, s, f, 0.14F * flySpeed);
        double targetY = ((jump ? 1 : 0) - (sneak ? 1 : 0)) * (0.45D * flySpeed);
        p.motionY = p.motionY * 0.55D + targetY * 0.45D;
        float savedWalk = McCompat.entityWalkDistance(p);
        p.moveEntity(p.motionX, p.motionY, p.motionZ);
        McCompat.entitySyncStep(p, savedWalk);
        p.motionX *= 0.78D;
        p.motionZ *= 0.78D;
        if (Math.abs(p.motionX) < 0.003D) p.motionX = 0.0D;
        if (Math.abs(p.motionY) < 0.003D && targetY == 0.0D) p.motionY = 0.0D;
        if (Math.abs(p.motionZ) < 0.003D) p.motionZ = 0.0D;
        p.fallDistance = 0.0F;
        p.onGround = false;
        return true;
    }

    public static boolean ignoreFall(EntityPlayer e, float distance) {
        return flying && e instanceof EntityPlayerSP p && p.mc != null && p.mc.theWorld == flyWorld;
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
        long current = McCompat.worldTime(mc.theWorld);
        McCompat.setWorldTime(mc.theWorld, current + Math.floorMod(tick - current, 24000L));
        reply(mc, "Time set to " + tick + ".");
    }

    private static void weather(Minecraft mc, String[] args) {
        if (!McCompat.HAS_WORLD_INFO) {
            reply(mc, "Weather is not supported in this version.");
            return;
        }
        String type = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "";
        int seconds = args.length == 3 ? number(args[2]) : 300;
        if (!WEATHER_TYPES.contains(type) || args.length > 3 || seconds < 1 || seconds > 86400) {
            reply(mc, "Usage: " + WEATHER_USAGE + " (1-86400)");
            return;
        }
        BetaCheats.setWeather(mc.theWorld, type, seconds);
        reply(mc, "Weather set to " + type + " for " + seconds + " seconds.");
    }

    private static final class BetaCheats {
        private static final Set<net.minecraft.src.WorldInfo> cheats = Collections.newSetFromMap(new WeakHashMap<>());

        static void load(net.minecraft.src.WorldInfo info, NBTTagCompound tag) {
            if (tag.getBoolean(CHEATS_TAG)) cheats.add(info);
        }

        static void copy(net.minecraft.src.WorldInfo info, net.minecraft.src.WorldInfo previous) {
            if (cheats.contains(previous)) cheats.add(info);
        }

        static void save(net.minecraft.src.WorldInfo info, NBTTagCompound tag) {
            tag.setBoolean(CHEATS_TAG, cheats.contains(info));
        }

        static boolean enabled(World w) {
            return cheats.contains(w.getWorldInfo());
        }

        static void toggle(World w) {
            var info = w.getWorldInfo();
            if (!cheats.remove(info)) cheats.add(info);
            w.saveLevel();
        }

        static void setWeather(World w, String type, int seconds) {
            var info = w.getWorldInfo();
            info.setRaining(!type.equals("clear"));
            info.setThundering(type.equals("thunder"));
            info.setRainTime(seconds * 20);
            info.setThunderTime(seconds * 20);
        }
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

    private static float floatNumber(String value) {
        try {
            return Float.parseFloat(value);
        } catch (NumberFormatException ignored) {
            return Float.NaN;
        }
    }

    private static void reply(Minecraft mc, String message) {
        if (HAS_ADD_CHAT) ChatReply.send(mc, message);
        else System.out.println("[chat] " + message);
    }
}
