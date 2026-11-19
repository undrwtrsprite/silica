package dev.betavk.boot;

import net.minecraft.client.Minecraft;
import net.minecraft.src.Session;
import net.minecraft.src.UnexpectedThrowable;

/**
 * The game's entry point (the launcher starts this, with the patch agent attached). Runs the client on the calling thread, which on macOS must be the
 * first thread (-XstartOnFirstThread).
 *
 * <pre>
 * --username NAME   --session ID        offline identity (default Player + random digits, "-")
 * --server HOST:PORT                    connect on start
 * --width N --height N --fullscreen     initial window
 * --gamedir PATH                        data directory (default: ~/Library/Application Support/minecraft)
 * --assets PATH                         Mojang asset store (indexes/ + objects/) for sounds
 * --vsync                               enable vsync (default off, like the original)
 * </pre>
 */
public final class Boot extends Minecraft {
    private Boot(int w, int h, boolean fullscreen) {
        super(null, null, null, w, h, fullscreen);
    }

    @Override public void displayUnexpectedThrowable(UnexpectedThrowable t) {
        try {
            // a1.0.1_01..a1.0.3 type this field as Exception instead of Throwable
            if (UnexpectedThrowable.class.getField("exception").get(t) instanceof Throwable th) {
                th.printStackTrace();
            }
        } catch (Throwable ignored) {}
    }

    public static void main(String[] args) {
        String user = null, session = "-", server = null;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--username" -> user = args[++i];
                case "--session" -> session = args[++i];
                case "--server" -> server = args[++i];
                case "--width" -> System.setProperty("betavk.width", args[++i]);
                case "--height" -> System.setProperty("betavk.height", args[++i]);
                case "--fullscreen" -> System.setProperty("betavk.fullscreen", "true");
                case "--gamedir" -> System.setProperty("betavk.gamedir", args[++i]);
                case "--assets" -> System.setProperty("betavk.assets", args[++i]);
                case "--vsync" -> System.setProperty("betavk.vsync", "true");
                default -> {
                    System.err.println("unknown argument: " + a);
                    System.exit(2);
                }
            }
        }
        if (user == null) user = "Player" + System.currentTimeMillis() % 1000L;
        Boot mc = new Boot(Integer.getInteger("betavk.width", 854), Integer.getInteger("betavk.height", 480), Boolean.getBoolean("betavk.fullscreen"));
        mc.session = new Session(user, session);
        try {
            Minecraft.class.getField("minecraftUri").set(mc, "www.minecraft.net");
        } catch (Throwable ignored) {}
        if (server != null) {
            String[] hp = server.split(":");
            mc.setServer(hp[0], Integer.parseInt(hp[1]));
        }
        mc.run();
    }
}
