package dev.betavk.net;

/**
 * Network endpoints b1.7.3 hard-coded (s3.amazonaws.com skins/cloaks, minecraft.net session server) are all dead.
 * They are now opt-in: set -Dbetavk.skinUrl=https://host/skins/%s.png (and .cloakUrl, .joinUrl with %s for the user,
 * session id and server id in that order) to point at a replacement. Unset means "feature unavailable", not "try a dead host".
 */
public final class Endpoints {
    private Endpoints() {
    }

    public static String skin(String user) {
        String t = System.getProperty("betavk.skinUrl");
        return t == null ? null : String.format(t, user);
    }

    public static String cloak(String user) {
        String t = System.getProperty("betavk.cloakUrl");
        return t == null ? null : String.format(t, user);
    }

    /** @return the session-server join URL, or null when online-mode authentication is not configured. */
    public static String join(String user, String sessionId, String serverId) {
        String t = System.getProperty("betavk.joinUrl");
        return t == null ? null : String.format(t, user, sessionId, serverId);
    }
}
