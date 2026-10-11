package id.eblo.chat.net;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import id.eblo.chat.ChatMessage;
import id.eblo.chat.ChatStore;
import id.eblo.chat.Config;
import id.eblo.chat.EbloChatClient;

import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Twitch IRC client (TLS, tags + commands), anonymous or authenticated with an OAuth token. */
public final class TwitchChat {
    public enum State { OFFLINE, CONNECTING, ONLINE }

    private static final AtomicInteger GENERATION = new AtomicInteger();
    private static final Object WRITE_LOCK = new Object();
    private static final ExecutorService HTTP_POOL = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "EbloChat-HTTP");
        t.setDaemon(true);
        return t;
    });
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private static final Map<String, Map<String, String>> ROOM = new ConcurrentHashMap<>();
    private static final Map<String, String[]> USERSTATE = new ConcurrentHashMap<>(); // channel -> {color, badges, display}
    private static final Set<String> HISTORY_LOADED = ConcurrentHashMap.newKeySet();
    private static final Set<String> JOINED = ConcurrentHashMap.newKeySet();

    private static volatile State state = State.OFFLINE;
    private static volatile Socket socket;
    private static volatile BufferedWriter out;
    private static volatile String ownLogin = "";

    private TwitchChat() {}

    public static State state() {
        return state;
    }

    public static boolean canSend() {
        return !Config.d.oauth.isBlank() && !Config.d.nick.isBlank();
    }

    // ---- lifecycle -------------------------------------------------------------------------------------------

    public static void start() {
        stop();
        int gen = GENERATION.incrementAndGet();
        Thread t = new Thread(() -> run(gen), "EbloChat-IRC");
        t.setDaemon(true);
        t.start();
    }

    public static void stop() {
        GENERATION.incrementAndGet();
        closeQuietly();
        state = State.OFFLINE;
    }

    private static void closeQuietly() {
        Socket s = socket;
        socket = null;
        out = null;
        JOINED.clear();
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static void run(int gen) {
        long delay = 2000;
        while (GENERATION.get() == gen) {
            if (Config.d.channels.isEmpty() && !canSend()) {
                state = State.OFFLINE;
                sleep(1000);
                continue;
            }
            try {
                connectOnce(gen);
                delay = 2000;
            } catch (Exception e) {
                if (GENERATION.get() == gen) EbloChatClient.LOGGER.warn("IRC connection lost: {}", e.toString());
            }
            if (GENERATION.get() != gen) break;
            state = State.OFFLINE;
            closeQuietly();
            sleep(delay);
            delay = Math.min(delay * 2, 30000);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    private static void connectOnce(int gen) throws IOException {
        state = State.CONNECTING;
        Socket s = SSLSocketFactory.getDefault().createSocket("irc.chat.twitch.tv", 6697);
        s.setSoTimeout(6 * 60 * 1000);
        socket = s;
        out = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));
        BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));

        String token = Config.d.oauth.trim();
        if (!token.isEmpty() && !token.startsWith("oauth:")) token = "oauth:" + token;
        boolean authed = !token.isEmpty() && !Config.d.nick.isBlank();
        ownLogin = authed ? Config.d.nick.trim().toLowerCase(Locale.ROOT) : "";

        raw("CAP REQ :twitch.tv/tags twitch.tv/commands");
        raw("PASS " + (authed ? token : "SCHMOOPIIE"));
        raw("NICK " + (authed ? ownLogin : "justinfan" + (10000 + (int) (Math.random() * 80000))));

        String line;
        while (GENERATION.get() == gen && (line = in.readLine()) != null) {
            handle(IrcLine.parse(line), line, false);
        }
    }

    // ---- sending ---------------------------------------------------------------------------------------------

    private static void raw(String line) throws IOException {
        BufferedWriter w = out;
        if (w == null) throw new IOException("not connected");
        synchronized (WRITE_LOCK) {
            w.write(line + "\r\n");
            w.flush();
        }
    }

    public static void join(String channel) {
        channel = Config.normChannel(channel);
        if (channel.isEmpty()) return;
        JOINED.add(channel);
        if (state == State.ONLINE) {
            try {
                raw("JOIN #" + channel);
            } catch (IOException e) {
                JOINED.remove(channel);
            }
        }
        loadHistory(channel);
    }

    public static void part(String channel) {
        channel = Config.normChannel(channel);
        JOINED.remove(channel);
        if (state == State.ONLINE) {
            try {
                raw("PART #" + channel);
            } catch (IOException ignored) {
            }
        }
    }

    /** Sends a chat message as the logged-in user. Returns an error key or null on success. */
    public static String send(String channel, String text) {
        if (!canSend()) return "gui.eblochat.err.no_oauth";
        if (state != State.ONLINE) return "gui.eblochat.err.offline";
        if (!JOINED.contains(channel)) return "gui.eblochat.err.not_joined";
        try {
            raw("PRIVMSG #" + channel + " :" + text);
        } catch (IOException e) {
            return "gui.eblochat.err.offline";
        }
        boolean action = text.startsWith("/me ");
        String[] us = USERSTATE.get(channel);
        int color = -1;
        List<String> badges = new ArrayList<>();
        String display = ownLogin;
        if (us != null) {
            color = parseColor(us[0]);
            badges = parseBadges(us[1]);
            if (!us[2].isEmpty()) display = us[2];
        }
        ChatStore.add(ChatMessage.chat(channel, "", ownLogin, display, action ? text.substring(4) : text, color, badges,
                System.currentTimeMillis(), action, false, true));
        return null;
    }

    // ---- room state ------------------------------------------------------------------------------------------

    /** Short human-readable room modes, e.g. "slow 30s | subs | emote-only". */
    public static String roomInfo(String channel) {
        Map<String, String> r = ROOM.get(channel);
        if (r == null) return "";
        List<String> parts = new ArrayList<>();
        String slow = r.getOrDefault("slow", "0");
        if (!slow.equals("0") && !slow.isEmpty()) parts.add("slow " + slow + "s");
        if ("1".equals(r.get("subs-only"))) parts.add("subs");
        if ("1".equals(r.get("emote-only"))) parts.add("emote-only");
        if ("1".equals(r.get("r9k"))) parts.add("unique");
        String f = r.getOrDefault("followers-only", "-1");
        if (!f.equals("-1") && !f.isEmpty()) parts.add(f.equals("0") ? "followers" : "followers " + f + "m");
        return String.join(" | ", parts);
    }

    // ---- incoming --------------------------------------------------------------------------------------------

    static void handle(IrcLine l, String rawLine, boolean history) {
        try {
            switch (l.command) {
                case "PING" -> raw("PONG :" + l.trailing());
                case "001" -> {
                    state = State.ONLINE;
                    for (String ch : new ArrayList<>(Config.d.channels)) {
                        JOINED.add(ch);
                        raw("JOIN #" + ch);
                        loadHistory(ch);
                    }
                }
                case "RECONNECT" -> closeQuietly();
                case "PRIVMSG" -> onPrivmsg(l, history);
                case "USERNOTICE" -> onUsernotice(l, history);
                case "CLEARCHAT" -> {
                    if (history) return;
                    String target = l.params.size() > 1 ? l.trailing() : "";
                    if (target.isEmpty()) ChatStore.chatCleared(l.channel());
                    else {
                        int secs = 0;
                        try {
                            secs = Integer.parseInt(l.tag("ban-duration"));
                        } catch (NumberFormatException ignored) {
                        }
                        ChatStore.punishUser(l.channel(), target, secs);
                    }
                }
                case "CLEARMSG" -> {
                    if (!history) ChatStore.deleteById(l.channel(), l.tag("target-msg-id"));
                }
                case "ROOMSTATE" -> {
                    Map<String, String> r = ROOM.computeIfAbsent(l.channel(), k -> new ConcurrentHashMap<>());
                    for (String k : new String[]{"slow", "subs-only", "emote-only", "r9k", "followers-only"}) {
                        if (l.tags.containsKey(k)) r.put(k, l.tag(k));
                    }
                }
                case "USERSTATE" -> USERSTATE.put(l.channel(), new String[]{l.tag("color"), l.tag("badges"), l.tag("display-name")});
                case "NOTICE" -> {
                    if (!history) ChatStore.system(l.channel().isEmpty() || l.channel().equals("*") ? ChatStore.selected : l.channel(), l.trailing());
                }
                default -> {
                }
            }
        } catch (IOException e) {
            closeQuietly();
        }
    }

    private static void onPrivmsg(IrcLine l, boolean history) {
        String text = l.trailing();
        boolean action = false;
        if (text.startsWith("\u0001ACTION ") && text.endsWith("\u0001")) {
            action = true;
            text = text.substring(8, text.length() - 1);
        }
        String login = l.login();
        ChatMessage m = ChatMessage.chat(l.channel(), l.tag("id"), login, l.tag("display-name"), text,
                parseColor(l.tag("color")), parseBadges(l.tag("badges")), parseTime(l), action, history,
                login.equalsIgnoreCase(ownLogin));
        ChatStore.add(m);
    }

    private static void onUsernotice(IrcLine l, boolean history) {
        String sys = l.tag("system-msg");
        String user = l.params.size() > 1 ? l.trailing() : "";
        if (sys.isEmpty() && user.isEmpty()) return;
        String text = sys.isEmpty() ? user : (user.isEmpty() ? sys : sys + " — " + l.tag("display-name") + ": " + user);
        ChatStore.add(ChatMessage.system(l.channel(), text, true, history, parseTime(l)));
    }

    private static long parseTime(IrcLine l) {
        try {
            return Long.parseLong(l.tag("tmi-sent-ts"));
        } catch (NumberFormatException e) {
            return System.currentTimeMillis();
        }
    }

    private static int parseColor(String c) {
        if (c != null && c.length() == 7 && c.charAt(0) == '#') {
            try {
                return Integer.parseInt(c.substring(1), 16);
            } catch (NumberFormatException ignored) {
            }
        }
        return -1;
    }

    private static List<String> parseBadges(String s) {
        List<String> out = new ArrayList<>();
        if (s == null || s.isEmpty()) return out;
        for (String b : s.split(",")) {
            int slash = b.indexOf('/');
            out.add(slash > 0 ? b.substring(0, slash) : b);
        }
        return out;
    }

    // ---- history (recent-messages service, as used by Chatterino) --------------------------------------------

    private static void loadHistory(String channel) {
        if (!Config.d.loadHistory || !HISTORY_LOADED.add(channel)) return;
        HTTP_POOL.execute(() -> {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(
                                "https://recent-messages.robotty.uk/api/v2/recent-messages/" + channel + "?limit=100"))
                        .timeout(Duration.ofSeconds(15)).header("Accept", "application/json").GET().build();
                HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() != 200) return;
                JsonObject obj = JsonParser.parseString(resp.body()).getAsJsonObject();
                JsonArray arr = obj.getAsJsonArray("messages");
                if (arr == null) return;
                Set<String> seen = new HashSet<>();
                for (JsonElement e : arr) {
                    String raw = e.getAsString();
                    if (!seen.add(raw)) continue;
                    IrcLine l = IrcLine.parse(raw);
                    if (l.command.equals("PRIVMSG") || l.command.equals("USERNOTICE")) handle(l, raw, true);
                }
            } catch (Exception ex) {
                EbloChatClient.LOGGER.debug("History load failed for {}: {}", channel, ex.toString());
            }
        });
    }

    static HttpClient http() {
        return HTTP;
    }

    static ExecutorService pool() {
        return HTTP_POOL;
    }
}
