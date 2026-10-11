package id.eblo.chat.gui;

import id.eblo.chat.ChatMessage;
import id.eblo.chat.ChatStore;
import id.eblo.chat.Config;
import id.eblo.chat.MessageFormatter;
import id.eblo.chat.net.TwitchChat;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Main chat window: channel tabs, scrollable history, input line, clickable names. */
public class EbloChatScreen extends Screen {
    private static final int LH = 9;

    private record Cached(int rev, int width, boolean showChannel, List<FormattedCharSequence> lines, int prefixW, int nameW) {}

    private record Line(FormattedCharSequence text, ChatMessage msg, boolean first, Cached cached) {}

    private record Hit(int y, ChatMessage msg, int nameX, int nameW) {}

    private final Map<ChatMessage, Cached> cache = new HashMap<>();
    private final List<Hit> hits = new ArrayList<>();
    private EditBox input;
    private int scroll;
    private String pendingText = "";

    public EbloChatScreen() {
        super(Component.translatable("gui.eblochat.title"));
    }

    private static List<String> tabs() {
        List<String> t = new ArrayList<>();
        t.add(ChatStore.MENTIONS);
        t.addAll(Config.d.channels);
        return t;
    }

    @Override
    protected void init() {
        List<String> tabs = tabs();
        if (!tabs.contains(ChatStore.selected)) ChatStore.selected = tabs.size() > 1 ? tabs.get(1) : ChatStore.MENTIONS;
        ChatStore.clearUnread(ChatStore.selected);

        int x = 4;
        for (String tab : tabs) {
            String base = tab.equals(ChatStore.MENTIONS) ? Component.translatable("gui.eblochat.tab.mentions").getString() : "#" + tab;
            int unread = ChatStore.unread(tab);
            String label = unread > 0 && !tab.equals(ChatStore.selected) ? base + " •" : base;
            int w = this.font.width(label) + 12;
            if (x + w > this.width - 74) break;
            Component msg = tab.equals(ChatStore.selected)
                    ? Component.literal(label).withStyle(ChatFormatting.YELLOW)
                    : Component.literal(label);
            final String target = tab;
            addRenderableWidget(Button.builder(msg, b -> select(target)).bounds(x, 4, w, 16).build());
            x += w + 2;
        }
        addRenderableWidget(Button.builder(Component.translatable("gui.eblochat.btn.settings"),
                b -> this.minecraft.setScreen(new SettingsScreen(this))).bounds(this.width - 70, 4, 66, 16).build());

        input = new EditBox(this.font, 4, this.height - 22, this.width - 8, 18, Component.empty());
        input.setMaxLength(500);
        input.setValue(pendingText);
        addRenderableWidget(input);
        setInitialFocus(input);
        cache.clear();
    }

    private void select(String tab) {
        pendingText = input.getValue();
        ChatStore.selected = tab;
        scroll = 0;
        rebuildWidgets();
    }

    private void cycleTab() {
        List<String> tabs = tabs();
        int i = tabs.indexOf(ChatStore.selected);
        select(tabs.get((i + 1) % tabs.size()));
    }

    // ---- layout ----------------------------------------------------------------------------------------------

    private Cached layout(ChatMessage m, int wrapW, boolean showChannel) {
        Cached c = cache.get(m);
        if (c != null && c.rev == m.rev && c.width == wrapW && c.showChannel == showChannel) return c;
        Component full = MessageFormatter.full(m, showChannel);
        List<FormattedCharSequence> lines = this.font.split(full, wrapW);
        int pw = this.font.width(MessageFormatter.prefix(m, showChannel));
        int nw = m.system ? 0 : this.font.width(MessageFormatter.name(m));
        c = new Cached(m.rev, wrapW, showChannel, lines, pw, nw);
        if (cache.size() > 1500) cache.clear();
        cache.put(m, c);
        return c;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        super.extractRenderState(g, mouseX, mouseY, delta);

        int left = 6, right = this.width - 6, top = 26, bottom = this.height - 36;
        int wrapW = right - left;
        int visible = Math.max(1, (bottom - top - 4) / LH);
        boolean showChannel = ChatStore.MENTIONS.equals(ChatStore.selected);

        g.fill(left - 3, top - 1, right + 3, bottom, 0x70000000);

        // collect lines newest-first
        List<Line> all = new ArrayList<>();
        List<ChatMessage> msgs = ChatStore.list(ChatStore.selected);
        int need = scroll + visible;
        for (int i = msgs.size() - 1; i >= 0 && all.size() < need; i--) {
            ChatMessage m = msgs.get(i);
            if (m.deleted && Config.d.hideDeleted) continue;
            Cached c = layout(m, wrapW, showChannel);
            for (int j = c.lines.size() - 1; j >= 0; j--) all.add(new Line(c.lines.get(j), m, j == 0, c));
        }
        if (all.size() < need) scroll = Math.max(0, all.size() - visible);

        hits.clear();
        g.enableScissor(left - 3, top, right + 3, bottom);
        for (int k = scroll; k < all.size() && k < scroll + visible + 1; k++) {
            Line line = all.get(k);
            int y = bottom - 3 - LH * (k - scroll + 1);
            if (line.msg.mention) g.fill(left - 3, y - 1, right + 3, y + LH - 1, 0x55FF8800);
            else if (line.msg.highlight) g.fill(left - 3, y - 1, right + 3, y + LH - 1, 0x4400AAFF);
            else if (line.msg.event) g.fill(left - 3, y - 1, right + 3, y + LH - 1, 0x449146FF);
            g.text(this.font, line.text, left, y, 0xFFFFFFFF, true);
            hits.add(new Hit(y, line.msg, line.first ? left + line.cached.prefixW : -1, line.first ? line.cached.nameW : 0));
        }
        g.disableScissor();

        if (all.isEmpty()) {
            Component hint = Config.d.channels.isEmpty()
                    ? Component.translatable("gui.eblochat.no_channels")
                    : Component.translatable("gui.eblochat.empty");
            g.text(this.font, hint, left, top + 4, 0xFF888888);
        }
        if (scroll > 0) {
            Component more = Component.translatable("gui.eblochat.scrolled", scroll);
            g.text(this.font, more, right - this.font.width(more), bottom - LH - 1, 0xFFFFCC66, true);
        }

        // status line
        TwitchChat.State st = TwitchChat.state();
        int dot = switch (st) {
            case ONLINE -> 0xFF55FF55;
            case CONNECTING -> 0xFFFFFF55;
            case OFFLINE -> 0xFFFF5555;
        };
        String stKey = switch (st) {
            case ONLINE -> "gui.eblochat.status.online";
            case CONNECTING -> "gui.eblochat.status.connecting";
            case OFFLINE -> "gui.eblochat.status.offline";
        };
        String room = ChatStore.MENTIONS.equals(ChatStore.selected) ? "" : TwitchChat.roomInfo(ChatStore.selected);
        g.text(this.font, Component.literal("● "), 6, bottom + 3, dot, true);
        g.text(this.font, Component.translatable(stKey).append(room.isEmpty() ? "" : "  |  " + room), 16, bottom + 3, 0xFFAAAAAA, true);
    }

    // ---- input -----------------------------------------------------------------------------------------------

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        if (key == 257 || key == 335) { // Enter / keypad Enter
            submit();
            return true;
        }
        if (key == 258) { // Tab
            cycleTab();
            return true;
        }
        if (key == 266) { // Page Up
            scroll += 8;
            return true;
        }
        if (key == 267) { // Page Down
            scroll = Math.max(0, scroll - 8);
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        scroll = Math.max(0, scroll + (int) Math.signum(sy) * 3);
        return true;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) return true;
        double mx = event.x(), my = event.y();
        int btn = event.button();
        for (Hit h : hits) {
            if (my < h.y || my >= h.y + LH) continue;
            if (h.nameW > 0 && h.nameX >= 0 && mx >= h.nameX && mx < h.nameX + h.nameW && !h.msg.system) {
                if (btn == 0) {
                    this.minecraft.setScreen(new UserCardScreen(this, h.msg.channel, h.msg.login));
                } else if (btn == 1) {
                    input.setValue(input.getValue() + "@" + h.msg.display + " ");
                    setFocused(input);
                }
                return true;
            }
            if (btn == 1) { // right click anywhere else on a message = copy it
                this.minecraft.keyboardHandler.setClipboard(h.msg.system ? h.msg.text : h.msg.display + ": " + h.msg.text);
                return true;
            }
        }
        return false;
    }

    private void feedback(String key, Object... args) {
        String ch = ChatStore.selected;
        ChatStore.system(ch, Component.translatable(key, args).getString());
    }

    private void submit() {
        String text = input.getValue().trim();
        if (text.isEmpty()) return;
        input.setValue("");
        scroll = 0;
        String sel = ChatStore.selected;

        if (text.startsWith("/") && !text.toLowerCase().startsWith("/me ")) {
            String[] p = text.split("\\s+", 2);
            String cmd = p[0].toLowerCase();
            String arg = p.length > 1 ? p[1].trim() : "";
            switch (cmd) {
                case "/join" -> {
                    String ch = Config.normChannel(arg);
                    if (ch.isEmpty()) return;
                    if (!Config.d.channels.contains(ch)) {
                        Config.d.channels.add(ch);
                        Config.save();
                    }
                    TwitchChat.join(ch);
                    ChatStore.selected = ch;
                    rebuildWidgets();
                }
                case "/part", "/leave" -> {
                    String ch = arg.isEmpty() ? sel : Config.normChannel(arg);
                    if (Config.d.channels.remove(ch)) {
                        Config.save();
                        TwitchChat.part(ch);
                        ChatStore.selected = ChatStore.MENTIONS;
                        rebuildWidgets();
                    }
                }
                case "/clear" -> ChatStore.clear(sel);
                case "/mentions" -> select(ChatStore.MENTIONS);
                case "/reconnect" -> TwitchChat.start();
                case "/user", "/usercard" -> {
                    String u = Config.normChannel(arg);
                    if (!u.isEmpty()) this.minecraft.setScreen(new UserCardScreen(this, sel, u));
                }
                case "/ignore" -> {
                    String u = Config.normChannel(arg);
                    if (!u.isEmpty() && !Config.d.ignoredUsers.contains(u)) {
                        Config.d.ignoredUsers.add(u);
                        Config.save();
                        feedback("gui.eblochat.ignored", u);
                    }
                }
                case "/unignore" -> {
                    String u = Config.normChannel(arg);
                    if (Config.d.ignoredUsers.remove(u)) {
                        Config.save();
                        feedback("gui.eblochat.unignored", u);
                    }
                }
                case "/highlight" -> {
                    String w = arg.toLowerCase();
                    if (!w.isEmpty() && !Config.d.highlightWords.contains(w)) {
                        Config.d.highlightWords.add(w);
                        Config.save();
                        feedback("gui.eblochat.highlight_added", w);
                    }
                }
                default -> feedback("gui.eblochat.err.unknown_command", cmd);
            }
            return;
        }

        if (sel.equals(ChatStore.MENTIONS)) {
            feedback("gui.eblochat.err.pick_channel");
            return;
        }
        String err = TwitchChat.send(sel, text);
        if (err != null) feedback(err);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
