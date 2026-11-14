package dev.betavk.net;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds a player's skin and cape on Mojang's own servers, in the background, so the game thread never waits for the network.
 * Name to id: api.minecraftservices.com. Id to textures: sessionserver.mojang.com. The pictures come from textures.minecraft.net.
 * Answers are remembered for the session only, and a "too many requests" reply from Mojang is retried after a wait.
 * The player's own skin and cape are not looked up at all: Silica reads them from the signed-in profile and passes them in
 * (BETAVK_SKIN_URL, BETAVK_CAPE_URL; see {@link #own()}).
 * Off with -Dbetavk.skins=off. Only names Mojang accepts (letters, digits, underscore, up to 16) are ever sent.
 */
public final class Skins {
    private Skins() {
    }

    /** Addresses of the skin and cape pictures; either may be null (no skin set, no cape). */
    public record Textures(String skin, String cape) {
    }

    private record Entry(Textures textures, long time) {
    }

    private record Reply(int status, String body, long retryAfterMs) {
    }

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final Pattern ID = Pattern.compile("\"id\"\\s*:\\s*\"([0-9a-fA-F]{32})\"");
    private static final Pattern VALUE = Pattern.compile("\"value\"\\s*:\\s*\"([A-Za-z0-9+/=]+)\"");
    private static final Pattern SKIN = Pattern.compile("\"SKIN\"\\s*:\\s*\\{[^}]*?\"url\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern CAPE = Pattern.compile("\"CAPE\"\\s*:\\s*\\{[^}]*?\"url\"\\s*:\\s*\"([^\"]+)\"");
    private static final String TEXTURE_HOST = "https://textures.minecraft.net/texture/";
    private static final long FRESH_MS = 6 * 3_600_000L;        // an answer this young is not asked for again (within one session)
    private static final long NOT_FOUND_RETRY_MS = 10 * 60_000L;
    private static final int ATTEMPTS = 5;

    private static final ConcurrentHashMap<String, Entry> KNOWN = new ConcurrentHashMap<>();
    /** Everyone waiting for a lookup that is in flight: the game creates the player more than once while a world loads, and each one needs the result. */
    private static final ConcurrentHashMap<String, java.util.List<Consumer<Textures>>> WAITING = new ConcurrentHashMap<>();
    private static final ExecutorService POOL = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "betavk-skins");
        t.setDaemon(true);
        return t;
    });
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    /** The signed-in player's own skin and cape, as Silica read them from their Minecraft profile; null when Silica did not pass any. */
    public static Textures own() {
        String skin = safe(System.getenv("BETAVK_SKIN_URL")), cape = safe(System.getenv("BETAVK_CAPE_URL"));
        return skin == null && cape == null ? null : new Textures(skin, cape);
    }

    public static boolean enabled() {
        return !"off".equalsIgnoreCase(System.getProperty("betavk.skins"));
    }

    /** What is known right now, without any network: null when the player has not been looked up yet (or has no textures). */
    public static Textures cached(String name) {
        Entry e = KNOWN.get(key(name));
        return e == null ? null : e.textures();
    }

    /**
     * Calls {@code done} with the player's textures: straight away on this thread when they are already known (even from an older run),
     * and again later on a background thread if a fresh lookup finds something. It is not called when nothing is found.
     */
    public static void resolve(String name, Consumer<Textures> done) {
        if (!enabled() || name == null || !NAME.matcher(name).matches()) return;
        String key = key(name);
        Entry known = KNOWN.get(key);
        long now = System.currentTimeMillis();
        if (known != null && known.textures() != null) done.accept(known.textures());
        long freshFor = known != null && known.textures() != null ? FRESH_MS : NOT_FOUND_RETRY_MS;
        if (known != null && now - known.time() < freshFor) return;
        boolean[] first = {false};
        WAITING.compute(key, (k, list) -> {
            if (list == null) {
                first[0] = true;
                list = new java.util.concurrent.CopyOnWriteArrayList<>();
            }
            list.add(done);
            return list;
        });
        if (!first[0]) return;   // a lookup is already running: it will call this one too
        POOL.execute(() -> {
            Textures found = null;
            try {
                found = lookupWithRetries(name);
            } catch (Exception e) {
                log(name + ": lookup failed (" + e.getClass().getSimpleName() + ": " + e.getMessage() + ")");
            }
            if (found != null) {
                KNOWN.put(key, new Entry(found, System.currentTimeMillis()));
            } else if (known == null || known.textures() == null) {
                KNOWN.put(key, new Entry(null, System.currentTimeMillis()));
            }
            java.util.List<Consumer<Textures>> waiting = WAITING.remove(key);
            if (found != null && waiting != null) {
                for (Consumer<Textures> c : waiting) {
                    try {
                        c.accept(found);
                    } catch (RuntimeException e) {
                        log(name + ": could not apply the skin (" + e + ")");
                    }
                }
            }
        });
    }

    private static String key(String name) {
        return name.toLowerCase(java.util.Locale.ROOT);
    }

    private static void log(String message) {
        System.out.println("[betavk] skins: " + message);
    }

    // ---- the lookup --------------------------------------------------------------------------------------

    private static Textures lookupWithRetries(String name) throws Exception {
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            log(name + ": looking up skin and cape" + (attempt > 1 ? " (attempt " + attempt + ")" : ""));
            Reply idReply = get("https://api.minecraftservices.com/minecraft/profile/lookup/name/" + name);
            Reply profile = null;
            if (idReply.status() == 200) {
                Matcher id = ID.matcher(idReply.body());
                if (!id.find()) {
                    log(name + ": the name lookup had no player id");
                    return null;
                }
                profile = get("https://sessionserver.mojang.com/session/minecraft/profile/" + id.group(1));
                if (profile.status() == 200) {
                    Textures found = parse(profile.body());
                    if (found == null) log(name + ": the profile has no custom skin or cape (default skin)");
                    else log(name + ": skin " + (found.skin() != null ? "found" : "none") + ", cape " + (found.cape() != null ? "found" : "none"));
                    return found;
                }
            }
            Reply failed = profile != null ? profile : idReply;
            if (failed.status() == 429 && attempt < ATTEMPTS) {
                long wait = Math.max(failed.retryAfterMs(), 65_000L);
                log(name + ": Mojang says too many requests, trying again in " + wait / 1000 + " s");
                Thread.sleep(wait);
                continue;
            }
            log(name + ": the " + (profile != null ? "profile" : "name") + " lookup returned HTTP " + failed.status()
                    + (failed.status() == 404 ? " (no such player)" : failed.status() == 429 ? " (too many requests, giving up for now)" : ""));
            return null;
        }
        return null;
    }

    /** Reads the skin and cape addresses out of a session-server profile (the textures property is base64 JSON). Public for the game's own tests. */
    public static Textures parse(String profileJson) {
        Matcher value = VALUE.matcher(profileJson);
        if (!value.find()) return null;
        String textures = new String(Base64.getDecoder().decode(value.group(1)), StandardCharsets.UTF_8);
        String skin = safe(first(SKIN, textures));
        String cape = safe(first(CAPE, textures));
        return skin == null && cape == null ? null : new Textures(skin, cape);
    }

    private static String first(Pattern p, String text) {
        Matcher m = p.matcher(text);
        return m.find() ? m.group(1) : null;
    }

    /** Only pictures on Mojang's texture host are used, always over https. */
    private static String safe(String url) {
        if (url == null) return null;
        String https = url.startsWith("http://") ? "https://" + url.substring(7) : url;
        return https.startsWith(TEXTURE_HOST) ? https : null;
    }

    private static Reply get(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).header("Accept", "application/json").GET().build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        long retryAfter = response.headers().firstValue("Retry-After").map(v -> {
            try {
                return Long.parseLong(v.trim()) * 1000L;
            } catch (NumberFormatException e) {
                return 0L;
            }
        }).orElse(0L);
        return new Reply(response.statusCode(), response.body(), retryAfter);
    }
}
