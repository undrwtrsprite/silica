package dev.betavk.platform;

import java.io.IOException;

/** Stand-in for org.lwjgl.Sys. */
public final class Sys {
    private Sys() {
    }

    public static String getVersion() {
        return org.lwjgl.Version.getVersion();
    }

    public static boolean openURL(String url) {
        try {
            new ProcessBuilder("/usr/bin/open", url).start();
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }
}
