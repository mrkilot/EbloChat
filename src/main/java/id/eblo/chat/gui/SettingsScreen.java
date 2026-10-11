package id.eblo.chat.gui;

import id.eblo.chat.ChatStore;
import id.eblo.chat.Config;
import id.eblo.chat.net.TwitchChat;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public class SettingsScreen extends Screen {
    private record Field(String labelKey, EditBox box, int y) {}

    private final Screen parent;
    private final List<Field> fields = new ArrayList<>();
    private EditBox channels, nick, oauth, highlight, ignored, blocked;
    private String oldChannels = "", oldNick = "", oldOauth = "";

    public SettingsScreen(Screen parent) {
        super(Component.translatable("gui.eblochat.settings.title"));
        this.parent = parent;
    }

    private static Component label(String key, boolean on) {
        return Component.translatable(key).append(": ").append(Component.translatable(on ? "gui.eblochat.on" : "gui.eblochat.off"));
    }

    private void toggle(int x, int y, String key, BooleanSupplier get, Consumer<Boolean> set) {
        addRenderableWidget(Button.builder(label(key, get.getAsBoolean()), b -> {
            set.accept(!get.getAsBoolean());
            b.setMessage(label(key, get.getAsBoolean()));
        }).bounds(x, y, 150, 18).build());
    }

    private EditBox field(int x, int y, String labelKey, String value, int max) {
        EditBox b = new EditBox(this.font, x, y + 10, 150, 16, Component.translatable(labelKey));
        b.setMaxLength(max);
        b.setValue(value);
        addRenderableWidget(b);
        fields.add(new Field(labelKey, b, y));
        return b;
    }

    @Override
    protected void init() {
        fields.clear();
        int lx = this.width / 2 - 155, rx = this.width / 2 + 5;
        int step = Math.max(19, Math.min(22, (this.height - 80) / 10));
        int y = 30;
        Config.Data d = Config.d;
        toggle(lx, y, "gui.eblochat.s.timestamps", () -> d.showTimestamps, v -> d.showTimestamps = v); y += step;
        toggle(lx, y, "gui.eblochat.s.h24", () -> d.use24h, v -> d.use24h = v); y += step;
        toggle(lx, y, "gui.eblochat.s.badges", () -> d.showBadges, v -> d.showBadges = v); y += step;
        toggle(lx, y, "gui.eblochat.s.ingame", () -> d.showInGameChat, v -> d.showInGameChat = v); y += step;
        toggle(lx, y, "gui.eblochat.s.mentions", () -> d.highlightMentions, v -> d.highlightMentions = v); y += step;
        toggle(lx, y, "gui.eblochat.s.sound", () -> d.mentionSound, v -> d.mentionSound = v); y += step;
        toggle(lx, y, "gui.eblochat.s.modactions", () -> d.showModActions, v -> d.showModActions = v); y += step;
        toggle(lx, y, "gui.eblochat.s.events", () -> d.showEvents, v -> d.showEvents = v); y += step;
        toggle(lx, y, "gui.eblochat.s.history", () -> d.loadHistory, v -> d.loadHistory = v); y += step;
        toggle(lx, y, "gui.eblochat.s.hide_deleted", () -> d.hideDeleted, v -> d.hideDeleted = v);

        int fstep = Math.max(32, Math.min(36, (this.height - 80) / 6));
        int fy = 28;
        oldChannels = Config.join(d.channels);
        oldNick = d.nick;
        oldOauth = d.oauth;
        channels = field(rx, fy, "gui.eblochat.s.channels", oldChannels, 512); fy += fstep;
        nick = field(rx, fy, "gui.eblochat.s.nick", d.nick, 40); fy += fstep;
        oauth = field(rx, fy, "gui.eblochat.s.oauth", d.oauth, 120); fy += fstep;
        highlight = field(rx, fy, "gui.eblochat.s.highlight", Config.join(d.highlightWords), 512); fy += fstep;
        ignored = field(rx, fy, "gui.eblochat.s.ignored", Config.join(d.ignoredUsers), 512); fy += fstep;
        blocked = field(rx, fy, "gui.eblochat.s.blocked", Config.join(d.blockedTerms), 512);

        addRenderableWidget(Button.builder(Component.translatable("gui.eblochat.done"), b -> onClose())
                .bounds(this.width / 2 - 75, this.height - 24, 150, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        super.extractRenderState(g, mx, my, delta);
        g.text(this.font, this.title, this.width / 2 - this.font.width(this.title) / 2, 10, 0xFFFFFFFF, true);
        for (Field f : fields) {
            g.text(this.font, Component.translatable(f.labelKey), f.box.getX(), f.y, 0xFFAAAAAA, true);
        }
    }

    @Override
    public void onClose() {
        Config.Data d = Config.d;
        d.channels = Config.parseList(channels.getValue());
        d.nick = nick.getValue().trim().toLowerCase();
        d.oauth = oauth.getValue().trim();
        d.highlightWords = Config.parsePhrases(highlight.getValue());
        d.ignoredUsers = Config.parseList(ignored.getValue());
        d.blockedTerms = Config.parsePhrases(blocked.getValue());
        Config.save();
        boolean reconnect = !Config.join(d.channels).equals(oldChannels) || !d.nick.equals(oldNick) || !d.oauth.equals(oldOauth);
        if (reconnect) TwitchChat.start();
        if (!d.channels.contains(ChatStore.selected)) ChatStore.selected = ChatStore.MENTIONS;
        this.minecraft.setScreen(parent instanceof EbloChatScreen ? new EbloChatScreen() : parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
