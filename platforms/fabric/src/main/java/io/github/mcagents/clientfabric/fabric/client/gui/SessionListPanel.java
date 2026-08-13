package io.github.mcagents.clientfabric.fabric.client.gui;

import io.github.mcagents.clientfabric.api.model.ChatSession;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The left panel: every conversation, most recently used first.
 *
 * <p>It is drawn by hand rather than built from a widget list because the rows are one line
 * of text each and are replaced wholesale whenever the search box changes. A list of
 * widgets would mean rebuilding and re-registering them on every keystroke.
 */
final class SessionListPanel extends ScrollablePanel {

    private static final int ROW_HEIGHT = 12;

    private final Font font;
    private final Consumer<ChatSession> onSelect;

    private List<ChatSession> sessions = List.of();
    private String selectedId;
    private boolean loading = true;

    SessionListPanel(Font font, Consumer<ChatSession> onSelect) {
        this.font = font;
        this.onSelect = onSelect;
    }

    /** Replaces the visible conversations, keeping the selection if it is still present. */
    void setSessions(List<ChatSession> sessions) {
        this.sessions = List.copyOf(sessions);
        this.loading = false;
        if (sessions.stream().noneMatch(session -> session.id().equals(selectedId))) {
            selectedId = null;
        }
        scrollToTop();
    }

    void setLoading() {
        this.loading = true;
    }

    void select(String sessionId) {
        this.selectedId = sessionId;
    }

    Optional<ChatSession> selected() {
        return sessions.stream().filter(session -> session.id().equals(selectedId)).findFirst();
    }

    @Override
    protected int contentHeight() {
        return sessions.size() * ROW_HEIGHT;
    }

    @Override
    protected void renderContent(GuiGraphicsExtractor graphics, int contentTop, int mouseX, int mouseY) {
        if (sessions.isEmpty()) {
            Component message = loading
                    ? Component.translatable("gui.mcagents.sessions.loading")
                    : Component.translatable("gui.mcagents.sessions.empty");
            graphics.text(font, message, x + PADDING, contentTop, TEXT_DIM);
            return;
        }

        int textWidth = width - PADDING * 2 - 4;
        for (int i = 0; i < sessions.size(); i++) {
            int rowTop = contentTop + i * ROW_HEIGHT;
            if (rowTop + ROW_HEIGHT < y || rowTop > y + height) {
                continue;
            }
            ChatSession session = sessions.get(i);
            boolean isSelected = session.id().equals(selectedId);
            boolean isHovered = isOver(mouseX, mouseY)
                    && mouseY >= rowTop && mouseY < rowTop + ROW_HEIGHT;

            if (isSelected) {
                graphics.fill(x + 1, rowTop - 1, x + width - 1, rowTop + ROW_HEIGHT - 1, HIGHLIGHT);
            } else if (isHovered) {
                graphics.fill(x + 1, rowTop - 1, x + width - 1, rowTop + ROW_HEIGHT - 1, HOVER);
            }

            String title = font.plainSubstrByWidth(session.title(), textWidth);
            graphics.text(font, title, x + PADDING, rowTop, isSelected ? 0xFFFFFFFF : TEXT);
        }
    }

    /**
     * Selects the conversation under the cursor.
     *
     * @return whether a conversation was clicked
     */
    boolean click(double mouseX, double mouseY) {
        if (!isOver(mouseX, mouseY) || sessions.isEmpty()) {
            return false;
        }
        int index = (int) ((mouseY - (y + PADDING) + scrollOffset()) / ROW_HEIGHT);
        if (index < 0 || index >= sessions.size()) {
            return false;
        }
        ChatSession session = sessions.get(index);
        selectedId = session.id();
        onSelect.accept(session);
        return true;
    }
}
