package io.github.mcagentsmods.chat.shared.client.gui;

import io.github.mcagentsmods.chat.api.model.ChatSession;
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

    /** Width of each control at the right of a row: rename, then delete. */
    private static final int CONTROL_WIDTH = 10;

    private final Font font;
    private final Consumer<ChatSession> onSelect;
    private final Consumer<ChatSession> onRename;
    private final Consumer<ChatSession> onDelete;

    private List<ChatSession> sessions = List.of();
    private String selectedId;
    private boolean loading = true;

    SessionListPanel(Font font, Consumer<ChatSession> onSelect,
                     Consumer<ChatSession> onRename, Consumer<ChatSession> onDelete) {
        this.font = font;
        this.onSelect = onSelect;
        this.onRename = onRename;
        this.onDelete = onDelete;
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

            // The delete control only appears under the cursor: a permanent row of crosses
            // beside every conversation invites the one click that cannot be undone.
            int available = isHovered ? textWidth - CONTROL_WIDTH * 2 : textWidth;
            String title = font.plainSubstrByWidth(session.title(), available);
            graphics.text(font, title, x + PADDING, rowTop, isSelected ? 0xFFFFFFFF : TEXT);

            if (isHovered) {
                boolean overRename = mouseX >= renameLeft() && mouseX < deleteLeft();
                boolean overDelete = mouseX >= deleteLeft() && mouseX < x + width - 2;
                graphics.text(font, Component.literal("\u270e"),
                        renameLeft(), rowTop, overRename ? 0xFF9CC4FF : TEXT_DIM);
                graphics.text(font, Component.literal("\u00d7"),
                        deleteLeft(), rowTop, overDelete ? 0xFFFF6060 : TEXT_DIM);
            }
        }
    }

    private int deleteLeft() {
        return x + width - CONTROL_WIDTH - 4;
    }

    private int renameLeft() {
        return deleteLeft() - CONTROL_WIDTH;
    }

    /**
     * Acts on the conversation under the cursor.
     *
     * <p>A click on the cross deletes, a double-click renames, and anything else selects.
     *
     * @return whether the click was consumed
     */
    boolean click(double mouseX, double mouseY, boolean doubleClick) {
        if (!isOver(mouseX, mouseY) || sessions.isEmpty()) {
            return false;
        }
        int index = (int) ((mouseY - (y + PADDING) + scrollOffset()) / ROW_HEIGHT);
        if (index < 0 || index >= sessions.size()) {
            return false;
        }
        ChatSession session = sessions.get(index);

        if (mouseX >= deleteLeft() && mouseX < x + width - 2) {
            onDelete.accept(session);
            return true;
        }
        if (mouseX >= renameLeft() && mouseX < deleteLeft()) {
            onRename.accept(session);
            return true;
        }

        selectedId = session.id();
        onSelect.accept(session);
        if (doubleClick) {
            onRename.accept(session);
        }
        return true;
    }

    /**
     * Where a conversation's row is on screen, so a rename box can be placed over it.
     *
     * @return the row's top edge, or empty if it is not currently visible
     */
    java.util.OptionalInt rowTop(String sessionId) {
        for (int i = 0; i < sessions.size(); i++) {
            if (sessions.get(i).id().equals(sessionId)) {
                int top = y + PADDING - scrollOffset() + i * ROW_HEIGHT;
                boolean visible = top >= y && top + ROW_HEIGHT <= y + height;
                return visible ? java.util.OptionalInt.of(top) : java.util.OptionalInt.empty();
            }
        }
        return java.util.OptionalInt.empty();
    }

    int rowHeight() {
        return ROW_HEIGHT;
    }
}
