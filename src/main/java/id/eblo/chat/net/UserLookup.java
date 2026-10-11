package id.eblo.chat.net;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/** User-card data from the public IVR API (account age, followers, roles, live state). */
public final class UserLookup {
    public static final class Info {
        public boolean ok;
        public String error = "";
        public String login = "", display = "", id = "", bio = "", created = "";
        public long followers = -1;
        public boolean banned, live, partner, affiliate, staff;
    }

    private UserLookup() {}

    public static CompletableFuture<Info> fetch(String login) {
        return CompletableFuture.supplyAsync(() -> {
            Info info = new Info();
            info.login = login;
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.ivr.fi/v2/twitch/user?login="
                                + URLEncoder.encode(login, StandardCharsets.UTF_8)))
                        .timeout(Duration.ofSeconds(12)).header("Accept", "application/json").GET().build();
                HttpResponse<String> resp = TwitchChat.http().send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() != 200) {
                    info.error = "HTTP " + resp.statusCode();
                    return info;
                }
                JsonElement root = JsonParser.parseString(resp.body());
                JsonObject o = null;
                if (root.isJsonArray() && !root.getAsJsonArray().isEmpty()) o = root.getAsJsonArray().get(0).getAsJsonObject();
                else if (root.isJsonObject()) o = root.getAsJsonObject();
                if (o == null) {
                    info.error = "not found";
                    return info;
                }
                info.display = str(o, "displayName");
                info.id = str(o, "id");
                info.bio = str(o, "bio");
                String created = str(o, "createdAt");
                info.created = created.length() >= 10 ? created.substring(0, 10) : created;
                if (o.has("followers") && o.get("followers").isJsonPrimitive()) info.followers = o.get("followers").getAsLong();
                info.banned = o.has("banned") && o.get("banned").isJsonPrimitive() && o.get("banned").getAsBoolean();
                info.live = o.has("stream") && o.get("stream").isJsonObject();
                if (o.has("roles") && o.get("roles").isJsonObject()) {
                    JsonObject r = o.getAsJsonObject("roles");
                    info.partner = bool(r, "isPartner");
                    info.affiliate = bool(r, "isAffiliate");
                    info.staff = bool(r, "isStaff");
                }
                info.ok = true;
            } catch (Exception e) {
                info.error = e.getClass().getSimpleName();
            }
            return info;
        }, TwitchChat.pool());
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : "";
    }

    private static boolean bool(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() && o.get(k).getAsBoolean();
    }
}
