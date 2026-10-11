package id.eblo.chat;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Persistent user settings (config/eblochat.json). */
public final class Config {
    public static final class Data {
        public List<String> channels = new ArrayList<>();
        public String nick = "";
        public String oauth = "";
        public boolean showInGameChat = true;
        public boolean showTimestamps = true;
        public boolean use24h = true;
        public boolean showBadges = true;
        public boolean highlightMentions = true;
        public boolean mentionSound = true;
        public boolean showModActions = true;
        public boolean showEvents = true;
        public boolean loadHistory = true;
        public boolean hideDeleted = false;
        public int maxMessages = 500;
        public List<String> highlightWords = new ArrayList<>();
        public List<String> ignoredUsers = new ArrayList<>();
        public List<String> blockedTerms = new ArrayList<>();
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public static Data d = new Data();

    private Config() {}

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("eblochat.json");
    }

    public static void load() {
        Path f = file();
        if (Files.exists(f)) {
            try (Reader r = Files.newBufferedReader(f)) {
                Data loaded = GSON.fromJson(r, Data.class);
                if (loaded != null) d = loaded;
            } catch (Exception e) {
                EbloChatClient.LOGGER.error("Failed to load config", e);
            }
        }
        if (d.channels == null) d.channels = new ArrayList<>();
        if (d.highlightWords == null) d.highlightWords = new ArrayList<>();
        if (d.ignoredUsers == null) d.ignoredUsers = new ArrayList<>();
        if (d.blockedTerms == null) d.blockedTerms = new ArrayList<>();
        if (d.nick == null) d.nick = "";
        if (d.oauth == null) d.oauth = "";
        if (d.maxMessages < 50) d.maxMessages = 50;
        save();
    }

    public static void save() {
        try (Writer w = Files.newBufferedWriter(file())) {
            GSON.toJson(d, w);
        } catch (IOException e) {
            EbloChatClient.LOGGER.error("Failed to save config", e);
        }
    }

    /** "a, b;c" -> [a, b, c] (lower-case, trimmed, '#' and '@' stripped, de-duplicated). */
    public static List<String> parseList(String csv) {
        List<String> out = new ArrayList<>();
        if (csv == null) return out;
        for (String s : csv.split("[,;\\s]+")) {
            s = s.trim().toLowerCase();
            while (s.startsWith("#") || s.startsWith("@")) s = s.substring(1);
            if (!s.isEmpty() && !out.contains(s)) out.add(s);
        }
        return out;
    }

    /** Like parseList but keeps spaces inside entries (split on commas only) - for highlight words / blocked terms. */
    public static List<String> parsePhrases(String csv) {
        List<String> out = new ArrayList<>();
        if (csv == null) return out;
        for (String s : csv.split(",")) {
            s = s.trim().toLowerCase();
            if (!s.isEmpty() && !out.contains(s)) out.add(s);
        }
        return out;
    }

    public static String join(List<String> l) {
        return String.join(", ", l);
    }

    public static String normChannel(String s) {
        s = s == null ? "" : s.trim().toLowerCase();
        while (s.startsWith("#") || s.startsWith("@")) s = s.substring(1);
        return s.replaceAll("[^a-z0-9_]", "");
    }
}
