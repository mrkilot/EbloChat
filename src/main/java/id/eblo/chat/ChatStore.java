package id.eblo.chat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

/** In-memory message buffers per channel + filtering, highlights, mentions tab, moderation. */
public final class ChatStore {
    /** Pseudo-channel that collects every mention (Chatterino's "Mentions" tab). */
    public static final String MENTIONS = "@mentions";

    public static volatile String selected = MENTIONS;

    private static final Map<String, List<ChatMessage>> BUFFERS = new ConcurrentHashMap<>();
    private static final Map<String, Integer> UNREAD = new ConcurrentHashMap<>();
    private static final Set<String> SEEN_IDS = new LinkedHashSet<>();

    private ChatStore() {}

    public static List<ChatMessage> list(String channel) {
        return BUFFERS.computeIfAbsent(channel, k -> new CopyOnWriteArrayList<>());
    }

    public static int unread(String channel) {
        return UNREAD.getOrDefault(channel, 0);
    }

    public static void clearUnread(String channel) {
        UNREAD.remove(channel);
    }

    public static void clear(String channel) {
        list(channel).clear();
    }

    public static void system(String channel, String text) {
        add(ChatMessage.system(channel, text));
    }

    /** Adds a message after applying ignore / blocked-term filters and mention detection. */
    public static void add(ChatMessage m) {
        if (!m.system) {
            if (Config.d.ignoredUsers.contains(m.login.toLowerCase(Locale.ROOT)) && !m.own) return;
            String lower = m.text.toLowerCase(Locale.ROOT);
            for (String term : Config.d.blockedTerms) {
                if (lower.contains(term)) return;
            }
            if (!m.id.isEmpty()) {
                synchronized (SEEN_IDS) {
                    if (!SEEN_IDS.add(m.id)) return;
                    if (SEEN_IDS.size() > 5000) {
                        var it = SEEN_IDS.iterator();
                        it.next();
                        it.remove();
                    }
                }
            }
            if (!m.own) {
                m.mention = Config.d.highlightMentions && isMention(lower);
                m.highlight = !m.mention && matchesHighlight(lower);
            }
        }
        if (m.system && m.event && !Config.d.showEvents) return;

        insert(list(m.channel), m);
        if (m.mention) insert(list(MENTIONS), m);

        if (!m.history) {
            if (!m.channel.equals(selected) && !(m.mention && MENTIONS.equals(selected))) {
                UNREAD.merge(m.channel, 1, Integer::sum);
            }
            if (m.mention) UNREAD.merge(MENTIONS, 1, Integer::sum);
            echoToGame(m);
        }
    }

    private static void insert(List<ChatMessage> buf, ChatMessage m) {
        int idx = buf.size();
        while (idx > 0 && buf.get(idx - 1).time > m.time) idx--;
        if (idx == buf.size()) buf.add(m); else buf.add(idx, m);
        while (buf.size() > Config.d.maxMessages) buf.remove(0);
    }

    private static boolean isMention(String lowerText) {
        String nick = Config.d.nick.trim().toLowerCase(Locale.ROOT);
        if (nick.isEmpty()) return false;
        return Pattern.compile("(?<![a-z0-9_])@?" + Pattern.quote(nick) + "(?![a-z0-9_])").matcher(lowerText).find();
    }

    private static boolean matchesHighlight(String lowerText) {
        for (String w : Config.d.highlightWords) {
            if (lowerText.contains(w)) return true;
        }
        return false;
    }

    // ---- moderation events -------------------------------------------------------------------------------------

    public static void deleteById(String channel, String targetId) {
        for (ChatMessage m : list(channel)) {
            if (m.id.equals(targetId)) {
                m.deleted = true;
                m.touch();
            }
        }
        for (ChatMessage m : list(MENTIONS)) {
            if (m.id.equals(targetId)) {
                m.deleted = true;
                m.touch();
            }
        }
    }

    /** seconds <= 0 means permanent ban. */
    public static void punishUser(String channel, String login, int seconds) {
        ChatMessage last = null;
        for (ChatMessage m : list(channel)) {
            if (!m.system && m.login.equalsIgnoreCase(login) && !m.deleted) {
                m.deleted = true;
                m.touch();
                last = m;
            }
        }
        String what = seconds > 0 ? "timed out (" + formatDuration(seconds) + ")" : "banned";
        if (last != null) {
            last.suffix = " [" + what + "]";
            last.touch();
        }
        if (Config.d.showModActions) system(channel, login + " was " + what);
    }

    public static void chatCleared(String channel) {
        for (ChatMessage m : list(channel)) {
            if (!m.system && !m.deleted) {
                m.deleted = true;
                m.touch();
            }
        }
        if (Config.d.showModActions) system(channel, "Chat was cleared by a moderator");
    }

    public static String formatDuration(int s) {
        if (s >= 86400) return (s / 86400) + "d";
        if (s >= 3600) return (s / 3600) + "h";
        if (s >= 60) return (s / 60) + "m";
        return s + "s";
    }

    // ---- in-game echo ------------------------------------------------------------------------------------------

    private static void echoToGame(ChatMessage m) {
        if (!Config.d.showInGameChat || m.own) return;
        if (m.system && !m.event) return;
        Minecraft mc = Minecraft.getInstance();
        Component line = MessageFormatter.full(m, true);
        boolean ping = m.mention && Config.d.mentionSound;
        mc.execute(() -> {
            if (mc.player == null) return;
            mc.player.sendSystemMessage(line);
            if (ping) {
                mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 2.0F));
            }
        });
    }
}
