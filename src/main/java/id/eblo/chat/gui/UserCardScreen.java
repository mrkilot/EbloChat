package id.eblo.chat.gui;

import id.eblo.chat.ChatMessage;
import id.eblo.chat.ChatStore;
import id.eblo.chat.Config;
import id.eblo.chat.MessageFormatter;
import id.eblo.chat.net.UserLookup;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;

/** Chatterino-style user card: account info, roles, recent messages, quick actions. */
public class UserCardScreen extends Screen {
    private final Screen parent;
    private final String channel;
    private final String login;
    private volatile UserLookup.Info info;
    private Button ignoreBtn;

    public UserCardScreen(Screen parent, String channel, String login) {
        super(Component.translatable("gui.eblochat.usercard.title"));
        this.parent = parent;
        this.channel = channel;
        this.login = login.toLowerCase();
        UserLookup.fetch(this.login).thenAccept(i -> this.info = i);
    }

    private Component ignoreLabel() {
        return Component.translatable(Config.d.ignoredUsers.contains(login) ? "gui.eblochat.usercard.unignore" : "gui.eblochat.usercard.ignore");
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int y = this.height - 48;
        addRenderableWidget(Button.builder(Component.translatable("gui.eblochat.usercard.copy_name"),
                b -> this.minecraft.keyboardHandler.setClipboard(login)).bounds(cx - 155, y, 100, 18).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.eblochat.usercard.copy_logs"),
                b -> this.minecraft.keyboardHandler.setClipboard(
                        "https://logs.ivr.fi/?channel=" + (channel.startsWith("@") ? login : channel) + "&username=" + login))
                .bounds(cx - 50, y, 100, 18).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.eblochat.usercard.copy_link"),
                b -> this.minecraft.keyboardHandler.setClipboard("https://twitch.tv/" + login))
                .bounds(cx + 55, y, 100, 18).build());
        ignoreBtn = addRenderableWidget(Button.builder(ignoreLabel(), b -> {
            if (!Config.d.ignoredUsers.remove(login)) Config.d.ignoredUsers.add(login);
            Config.save();
            b.setMessage(ignoreLabel());
        }).bounds(cx - 155, y + 22, 100, 18).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.eblochat.usercard.back"), b -> onClose())
                .bounds(cx - 50, y + 22, 205, 18).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        super.extractRenderState(g, mx, my, delta);
        int x = this.width / 2 - 155;
        int y = 14;
        g.text(this.font, Component.literal(login).withColor(0x9146FF), x, y, 0xFFFFFFFF, true);
        y += 14;

        UserLookup.Info i = this.info;
        List<Component> rows = new ArrayList<>();
        if (i == null) {
            rows.add(Component.translatable("gui.eblochat.usercard.loading"));
        } else if (!i.ok) {
            rows.add(Component.translatable("gui.eblochat.usercard.failed", i.error));
        } else {
            rows.add(Component.translatable("gui.eblochat.usercard.name", i.display));
            rows.add(Component.translatable("gui.eblochat.usercard.id", i.id));
            rows.add(Component.translatable("gui.eblochat.usercard.created", i.created));
            if (i.followers >= 0) rows.add(Component.translatable("gui.eblochat.usercard.followers", i.followers));
            List<String> roles = new ArrayList<>();
            if (i.staff) roles.add("staff");
            if (i.partner) roles.add("partner");
            if (i.affiliate) roles.add("affiliate");
            if (!roles.isEmpty()) rows.add(Component.translatable("gui.eblochat.usercard.roles", String.join(", ", roles)));
            rows.add(Component.translatable(i.live ? "gui.eblochat.usercard.live" : "gui.eblochat.usercard.offline"));
            if (i.banned) rows.add(Component.translatable("gui.eblochat.usercard.banned"));
            if (!i.bio.isBlank()) rows.add(Component.literal(i.bio));
        }
        for (Component c : rows) {
            for (FormattedCharSequence s : this.font.split(c, 310)) {
                g.text(this.font, s, x, y, 0xFFDDDDDD, true);
                y += 10;
            }
        }

        // recent messages from this user in the selected channel
        y += 6;
        g.text(this.font, Component.translatable("gui.eblochat.usercard.history"), x, y, 0xFFAAAAAA, true);
        y += 12;
        List<ChatMessage> src = ChatStore.list(channel);
        List<ChatMessage> mine = new ArrayList<>();
        for (int k = src.size() - 1; k >= 0 && mine.size() < 8; k--) {
            ChatMessage m = src.get(k);
            if (!m.system && m.login.equalsIgnoreCase(login)) mine.add(0, m);
        }
        int limit = this.height - 56;
        for (ChatMessage m : mine) {
            if (y + 10 > limit) break;
            List<FormattedCharSequence> ls = this.font.split(MessageFormatter.prefix(m, false).append(MessageFormatter.body(m)), 310);
            if (!ls.isEmpty()) g.text(this.font, ls.get(0), x, y, 0xFFFFFFFF, true);
            y += 10;
        }
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
