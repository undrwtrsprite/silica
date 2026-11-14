package dev.betavk.net;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Tells Mojang's session server that this player is about to join a server, the way every modern client does, so that a server can
 * confirm the player with Mojang's hasJoined. Beta 1.7.3's own call went to a session server that no longer exists.
 */
public final class SessionJoin {
    private SessionJoin() {
    }

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    /** @return "OK" when Mojang accepted the join (what the game expects), otherwise a short reason the game shows as the login failure. */
    public static String join(String accessToken, String profileId, String serverId) {
        String body = "{\"accessToken\":\"" + json(accessToken) + "\",\"selectedProfile\":\"" + json(profileId) + "\",\"serverId\":\"" + json(serverId) + "\"}";
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://sessionserver.mojang.com/session/minecraft/join"))
                    .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            return result(response.statusCode());
        } catch (Exception e) {
            return "Could not reach Mojang's session server (" + e.getClass().getSimpleName() + ")";
        }
    }

    /** Package-visible for the game's own tests. */
    public static String result(int status) {
        return switch (status) {
            case 204, 200 -> "OK";
            case 401, 403 -> "Mojang did not accept your sign-in (it may have expired). Sign out and in again in Silica, then press Play.";
            case 429 -> "Mojang says too many join requests. Wait a minute and try again.";
            default -> "Mojang's session server answered HTTP " + status;
        };
    }

    private static String json(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
