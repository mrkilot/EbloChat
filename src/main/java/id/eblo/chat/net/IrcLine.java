package id.eblo.chat.net;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** A parsed IRCv3 line (with Twitch tags). */
public final class IrcLine {
    public final Map<String, String> tags = new HashMap<>();
    public String prefix = "";
    public String command = "";
    public final List<String> params = new ArrayList<>();

    public static IrcLine parse(String raw) {
        IrcLine l = new IrcLine();
        int i = 0;
        if (raw.startsWith("@")) {
            int sp = raw.indexOf(' ');
            if (sp < 0) return l;
            for (String kv : raw.substring(1, sp).split(";")) {
                int eq = kv.indexOf('=');
                if (eq < 0) l.tags.put(kv, "");
                else l.tags.put(kv.substring(0, eq), unescape(kv.substring(eq + 1)));
            }
            i = sp + 1;
        }
        while (i < raw.length() && raw.charAt(i) == ' ') i++;
        if (i < raw.length() && raw.charAt(i) == ':') {
            int sp = raw.indexOf(' ', i);
            if (sp < 0) return l;
            l.prefix = raw.substring(i + 1, sp);
            i = sp + 1;
        }
        String rest = raw.substring(Math.min(i, raw.length()));
        String trailing = null;
        int t = rest.indexOf(" :");
        if (t >= 0) {
            trailing = rest.substring(t + 2);
            rest = rest.substring(0, t);
        }
        String[] parts = rest.trim().split(" +");
        if (parts.length > 0) l.command = parts[0];
        for (int k = 1; k < parts.length; k++) l.params.add(parts[k]);
        if (trailing != null) l.params.add(trailing);
        return l;
    }

    private static String unescape(String s) {
        if (s.indexOf('\\') < 0) return s;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                switch (n) {
                    case 's' -> sb.append(' ');
                    case ':' -> sb.append(';');
                    case 'r' -> sb.append('\r');
                    case 'n' -> sb.append('\n');
                    default -> sb.append(n);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    public String login() {
        int b = prefix.indexOf('!');
        return b > 0 ? prefix.substring(0, b) : prefix;
    }

    public String trailing() {
        return params.isEmpty() ? "" : params.get(params.size() - 1);
    }

    /** First parameter without the leading '#'. */
    public String channel() {
        if (params.isEmpty()) return "";
        String c = params.get(0);
        return c.startsWith("#") ? c.substring(1) : c;
    }

    public String tag(String key) {
        return tags.getOrDefault(key, "");
    }
}
