package dev.betavk.hooks;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.src.ThreadDownloadResources;

/** Resources the original fetched from hosts that no longer exist: sounds come from the asset store on this machine, the join check from an opt-in URL. */
public final class Assets {
    private Assets() {
    }

    private static final Pattern ENTRY = Pattern.compile("\"([^\"]+)\"\\s*:\\s*\\{([^}]*)\\}");
    private static final Pattern HASH = Pattern.compile("\"hash\"\\s*:\\s*\"([0-9a-f]{40})\"");
    private static final Pattern SIZE = Pattern.compile("\"size\"\\s*:\\s*(\\d+)");

    /** replaceBody of ThreadDownloadResources.run: installs sound and music from -Dbetavk.assets (indexes/b1.7.json and objects/). */
    public static void run(ThreadDownloadResources t) {
        String assets = System.getProperty("betavk.assets");
        if (assets == null) {
            System.out.println("[betavk] no -Dbetavk.assets: skipping sound resources");
            t.loadResource(t.resourcesFolder, "");
            return;
        }
        try {
            File root = new File(assets);
            String index = new String(Files.readAllBytes(new File(root, "indexes/b1.7.json").toPath()), "UTF-8");
            Matcher m = ENTRY.matcher(index);
            int installed = 0;
            while (m.find() && !t.closing) {
                String name = m.group(1);
                String prefix = name.indexOf('/') < 0 ? "" : name.substring(0, name.indexOf('/'));
                if (!(prefix.equals("sound") || prefix.equals("newsound") || prefix.equals("streaming") || prefix.equals("music") || prefix.equals("newmusic"))) {
                    continue;   // icons, pack metadata and other-era duplicates are not game resources
                }
                Matcher hm = HASH.matcher(m.group(2)), sm = SIZE.matcher(m.group(2));
                if (!hm.find() || !sm.find()) continue;
                long size = Long.parseLong(sm.group(1));
                String hash = hm.group(1);
                File object = new File(root, "objects/" + hash.substring(0, 2) + "/" + hash);
                if (!object.isFile() || object.length() != size) {
                    System.out.println("[betavk] asset missing or wrong size: " + name);
                    continue;
                }
                File target = new File(t.resourcesFolder, name);
                if (!target.exists() || target.length() != size) {
                    target.getParentFile().mkdirs();
                    Files.copy(object.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
                t.mc.installResource(name, target);
                ++installed;
            }
            System.out.println("[betavk] installed " + installed + " sound resources from " + assets);
        } catch (Exception e) {
            e.printStackTrace();
            t.loadResource(t.resourcesFolder, "");
        }
    }

    /**
     * redirectCall of URL.openStream in NetClientHandler.handleHandshake: the original session server is gone. The game reads one line
     * from the answer and treats "OK" as success; anything else is shown as the reason the login failed.
     * <ul>
     * <li>-Dbetavk.joinUrl (with %s for user, session id, server id) names a replacement session server, if you have one.</li>
     * <li>Otherwise, when Silica passed the player's Minecraft sign-in (environment BETAVK_ACCESS_TOKEN and BETAVK_PROFILE_ID),
     * the join is made on Mojang's current session server, the same way modern clients do. Servers that check with Mojang's
     * hasJoined accept it.</li>
     * </ul>
     */
    public static InputStream join(URL original) throws IOException {
        String q = original.getQuery() == null ? "" : original.getQuery();
        String user = param(q, "user"), session = param(q, "sessionId"), server = param(q, "serverId");
        String t = System.getProperty("betavk.joinUrl");
        if (t != null) return new URL(String.format(t, user, session, server)).openStream();
        String token = System.getenv("BETAVK_ACCESS_TOKEN"), profile = System.getenv("BETAVK_PROFILE_ID");
        if (token == null || token.isEmpty() || profile == null || profile.isEmpty()) {
            throw new IOException("This server needs online-mode authentication, but this game was not given your Minecraft sign-in. Sign in with Microsoft in Silica, then press Play.");
        }
        return new java.io.ByteArrayInputStream(dev.betavk.net.SessionJoin.join(token, profile, server).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String param(String q, String key) {
        for (String p : q.split("&")) if (p.startsWith(key + "=")) return p.substring(key.length() + 1);
        return "";
    }
}
