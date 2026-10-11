package id.eblo.chat;

import java.util.List;

/** One line in a channel: a chat message or a system line. */
public final class ChatMessage {
    public final String channel;
    public final String id;
    public final String login;
    public final String display;
    public final String text;
    public final int color;            // RGB or -1
    public final List<String> badges;   // badge set ids
    public final long time;             // epoch millis
    public final boolean action;        // /me
    public final boolean system;
    public final boolean event;         // sub / raid etc.
    public final boolean history;
    public final boolean own;

    public volatile boolean deleted;
    public volatile boolean mention;
    public volatile boolean highlight;
    public volatile String suffix = "";
    public volatile int rev;

    private ChatMessage(String channel, String id, String login, String display, String text, int color,
                        List<String> badges, long time, boolean action, boolean system, boolean event,
                        boolean history, boolean own) {
        this.channel = channel;
        this.id = id == null ? "" : id;
        this.login = login == null ? "" : login;
        this.display = display == null || display.isEmpty() ? this.login : display;
        this.text = text;
        this.color = color;
        this.badges = badges == null ? List.of() : badges;
        this.time = time;
        this.action = action;
        this.system = system;
        this.event = event;
        this.history = history;
        this.own = own;
    }

    public static ChatMessage chat(String channel, String id, String login, String display, String text, int color,
                                   List<String> badges, long time, boolean action, boolean history, boolean own) {
        return new ChatMessage(channel, id, login, display, text, color, badges, time, action, false, false, history, own);
    }

    public static ChatMessage system(String channel, String text, boolean event, boolean history, long time) {
        return new ChatMessage(channel, "", "", "", text, -1, null, time, false, true, event, history, false);
    }

    public static ChatMessage system(String channel, String text) {
        return system(channel, text, false, false, System.currentTimeMillis());
    }

    public void touch() {
        rev++;
    }
}
