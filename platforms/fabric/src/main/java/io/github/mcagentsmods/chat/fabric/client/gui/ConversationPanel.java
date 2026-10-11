package io.github.mcagentsmods.chat.fabric.client.gui;

import io.github.mcagentsmods.chat.api.model.ChatMessage;
import io.github.mcagentsmods.core.api.chat.ChatRole;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;

/**
 * The right panel: the messages of the selected conversation.
 *
 * <p>Messages are wrapped to the panel's width once, when they change or the panel is
 * resized, rather than on every frame — wrapping is the expensive part and the result only
 * changes when one of those two things does.
 */
final class ConversationPanel extends ScrollablePanel {

    /** Blank space between one message and the next. */
    private static final int MESSAGE_GAP = 4;

    private final Font font;

    private List<ChatMessage> messages = List.of();
    private final List<Line> lines = new ArrayList<>();
    private boolean loading;
    private int wrappedWidth = -1;

    /**
     * A failure to show at the end of the conversation, or null.
     *
     * <p>It lives here rather than in the log because the log is not where the player is
     * looking. An agent that cannot answer must say why in the place the answer would have
     * appeared.
     */
    private String errorText;

    /** One rendered line: the text, and the colour its speaker is drawn in. */
    private record Line(FormattedCharSequence text, int colour) {
    }

    ConversationPanel(Font font) {
        this.font = font;
    }

    void setMessages(List<ChatMessage> messages) {
        this.messages = List.copyOf(messages);
        this.loading = false;
        this.errorText = null;
        rewrap();
        scrollToBottom();
    }

    /** Shows a failure after the last message, in red, without storing it. */
    void showError(String text) {
        this.errorText = text;
        this.loading = false;
        rewrap();
        scrollToBottom();
    }

    void setLoading() {
        this.loading = true;
        this.messages = List.of();
        this.lines.clear();
    }

    /** Shows the panel with nothing in it — a new conversation with no messages yet. */
    void clear() {
        this.loading = false;
        this.errorText = null;
        this.messages = List.of();
        this.lines.clear();
    }

    @Override
    void setBounds(int x, int y, int width, int height) {
        super.setBounds(x, y, width, height);
        // Wrapping is the expensive part, so a resize that did not change the usable width
        // — a taller window, a moved panel — reuses what is already wrapped.
        if (availableWidth() != wrappedWidth) {
            rewrap();
        }
    }

    @Override
    protected int contentHeight() {
        // Every message contributes a trailing blank line, so the gaps are already counted.
        return lines.size() * font.lineHeight;
    }

    @Override
    protected void renderContent(GuiGraphicsExtractor graphics, int contentTop, int mouseX, int mouseY) {
        if (lines.isEmpty() && errorText == null) {
            Component message = loading
                    ? Component.translatable("gui.mcagents.conversation.loading")
                    : Component.translatable("gui.mcagents.conversation.empty");
            graphics.text(font, message, x + PADDING, contentTop, TEXT_DIM);
            return;
        }

        int lineTop = contentTop;
        for (Line line : lines) {
            // Skip anything scrolled out of view; a long conversation is mostly off-screen.
            if (lineTop + font.lineHeight >= y && lineTop <= y + height) {
                graphics.text(font, line.text(), x + PADDING, lineTop, line.colour());
            }
            lineTop += font.lineHeight;
        }
    }

    private int availableWidth() {
        return width - PADDING * 2 - MESSAGE_GAP;
    }

    private void rewrap() {
        lines.clear();

        int available = availableWidth();
        if (available <= 0) {
            // The panel has no usable width yet; leave wrappedWidth unset so the next
            // setBounds wraps rather than assuming this pass succeeded.
            wrappedWidth = -1;
            return;
        }
        wrappedWidth = available;

        for (ChatMessage message : messages) {
            int colour = colourFor(message.role());
            Component prefix = Component.translatable(labelKeyFor(message.role()));
            Component body = Component.literal(prefix.getString() + ": " + message.content());
            for (FormattedCharSequence wrapped : font.split(body, available)) {
                lines.add(new Line(wrapped, colour));
            }
            // A blank line between messages, so a wall of text reads as a conversation.
            lines.add(new Line(FormattedCharSequence.EMPTY, colour));
        }

        if (errorText != null) {
            Component body = Component.literal("! " + errorText);
            for (FormattedCharSequence wrapped : font.split(body, available)) {
                lines.add(new Line(wrapped, ERROR));
            }
        }
    }

    private static final int ERROR = 0xFFFF8080;

    private static int colourFor(ChatRole role) {
        return switch (role) {
            case USER -> 0xFF9CC4FF;
            case ASSISTANT -> TEXT;
            // A tool result is never stored, so it never reaches this panel. It is
            // handled rather than defaulted so that adding a role stays a compile error.
            case SYSTEM, TOOL -> TEXT_DIM;
        };
    }

    private static String labelKeyFor(ChatRole role) {
        return switch (role) {
            case USER -> "gui.mcagents.role.user";
            case ASSISTANT -> "gui.mcagents.role.assistant";
            case SYSTEM, TOOL -> "gui.mcagents.role.system";
        };
    }
}
