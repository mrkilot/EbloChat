package id.eblo.chat;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** Turns a ChatMessage into a styled Component. */
public final class MessageFormatter {
    private static final DateTimeFormatter T24 = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter T12 = DateTimeFormatter.ofPattern("h:mm a").withZone(ZoneId.systemDefault());
    private static final int[] PALETTE = {
            0xFF0000, 0x0000FF, 0x00FF00, 0xB22222, 0xFF7F50, 0x9ACD32, 0xFF4500, 0x2E8B57,
            0xDAA520, 0xD2691E, 0x5F9EA0, 0x1E90FF, 0xFF69B4, 0x8A2BE2, 0x00FF7F
    };

    private MessageFormatter() {}

    public static int colorOf(ChatMessage m) {
        if (m.color >= 0) return m.color;
        int h = 0;
        for (char c : m.login.toCharArray()) h = h * 31 + c;
        return PALETTE[Math.floorMod(h, PALETTE.length)];
    }

    /** Timestamp + channel tag + badges. */
    public static MutableComponent prefix(ChatMessage m, boolean showChannel) {
        MutableComponent c = Component.empty();
        if (Config.d.showTimestamps) {
            String t = (Config.d.use24h ? T24 : T12).format(Instant.ofEpochMilli(m.time));
            c.append(Component.literal(t + " ").withStyle(ChatFormatting.GRAY));
        }
        if (showChannel) {
            c.append(Component.literal("#" + m.channel + " ").withColor(0x9146FF));
        }
        if (!m.system && Config.d.showBadges) {
            for (String b : m.badges) {
                String[] tag = badgeTag(b);
                if (tag != null) c.append(Component.literal("[" + tag[0] + "]").withColor(Integer.parseInt(tag[1], 16)));
            }
            if (!m.badges.isEmpty()) c.append(Component.literal(" "));
        }
        return c;
    }

    public static MutableComponent name(ChatMessage m) {
        return Component.literal(m.display).withColor(colorOf(m));
    }

    public static MutableComponent body(ChatMessage m) {
        MutableComponent b = Component.literal(m.text + m.suffix);
        if (m.deleted) return b.withStyle(ChatFormatting.GRAY, ChatFormatting.STRIKETHROUGH);
        if (m.action) return b.withColor(colorOf(m));
        if (m.mention) return b.withStyle(ChatFormatting.GOLD);
        if (m.highlight) return b.withStyle(ChatFormatting.AQUA);
        return b;
    }

    public static MutableComponent full(ChatMessage m, boolean showChannel) {
        MutableComponent c = prefix(m, showChannel);
        if (m.system) {
            String star = m.event ? "★ " : "";
            return c.append(Component.literal(star + m.text).withColor(m.event ? 0xC9A0FF : 0xAAAAAA));
        }
        c.append(name(m));
        c.append(Component.literal(m.action ? " " : ": "));
        c.append(body(m));
        return c;
    }

    /** {label, RRGGBB} or null for badges that aren't shown. */
    private static String[] badgeTag(String setId) {
        return switch (setId) {
            case "broadcaster" -> new String[]{"B", "E91916"};
            case "moderator", "lead_moderator" -> new String[]{"M", "00AD03"};
            case "vip" -> new String[]{"V", "E005B9"};
            case "subscriber", "founder" -> new String[]{"S", "9146FF"};
            case "staff" -> new String[]{"ST", "9146FF"};
            case "admin", "global_mod" -> new String[]{"A", "FFD700"};
            case "partner" -> new String[]{"P", "9146FF"};
            case "bits" -> new String[]{"$", "BF94FF"};
            case "sub-gifter", "sub_gifter" -> new String[]{"G", "BF94FF"};
            default -> null;
        };
    }
}
