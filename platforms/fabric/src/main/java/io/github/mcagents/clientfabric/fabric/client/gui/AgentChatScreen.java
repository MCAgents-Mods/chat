package io.github.mcagents.clientfabric.fabric.client.gui;

import io.github.mcagents.clientfabric.api.agent.Agent;
import io.github.mcagents.clientfabric.api.agent.ModelException;
import io.github.mcagents.clientfabric.api.model.ChatMessage;
import io.github.mcagents.clientfabric.api.model.ChatRole;
import io.github.mcagents.clientfabric.api.model.ChatSession;
import io.github.mcagents.clientfabric.api.storage.ChatRepository;
import io.github.mcagents.clientfabric.api.storage.LocalDatabase;
import io.github.mcagents.clientfabric.fabric.MCAgentsClientMod;
import io.github.mcagents.clientfabric.fabric.client.AgentFactory;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * The chat screen: conversations on the left, the selected conversation on the right, and
 * the prompt box along the bottom.
 *
 * <h2>Two rules govern everything here</h2>
 *
 * <p><strong>Nothing blocks.</strong> Every call into storage returns a
 * {@link CompletableFuture}, and this screen never waits on one. Results come back through
 * {@code whenComplete} and are applied on the client thread via {@link Minecraft#execute},
 * because touching screen state from the database thread is a race the player would
 * experience as a flicker or a crash.
 *
 * <p><strong>Nothing here needs the network.</strong> The conversation list, the history,
 * and the prompt box all work with no internet at all. Only producing a new answer does,
 * and that goes through {@link Agent}, which reports its own failure without taking the
 * screen down.
 */
public final class AgentChatScreen extends Screen {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgentChatScreen.class);

    private static final int MARGIN = 8;
    private static final int HEADER_HEIGHT = 22;
    private static final int FOOTER_HEIGHT = 24;
    private static final int SIDEBAR_WIDTH = 118;
    private static final int GAP = 6;
    private static final int LINE = 20;
    private static final int PROMPT_LIMIT = 4000;

    /** Null while storage is opening, or if it failed. The screen still opens either way. */
    private LocalDatabase database;
    private Agent agent;
    private final WaitingIndicator waiting = new WaitingIndicator();

    private SessionListPanel sessionList;
    private ConversationPanel conversation;
    private EditBox searchBox;
    private EditBox promptBox;
    private Button sendButton;

    private String activeSessionId;

    /** Text kept across a resize, which rebuilds every widget from scratch. */
    private String pendingSearch = "";
    private String pendingPrompt = "";

    public AgentChatScreen() {
        super(Component.translatable("gui.mcagents.title"));
        bindStorage();
    }

    /**
     * Picks up storage if it is available.
     *
     * <p>Called on construction and again after a retry, so a player whose database opened
     * late — or opened only after they pressed Retry — gets a working screen without
     * reopening it.
     */
    private void bindStorage() {
        LocalDatabase opened = MCAgentsClientMod.database().orElse(null);
        this.database = opened;
        this.agent = opened == null
                ? null
                : AgentFactory.create(opened, MCAgentsClientMod.paths());
    }

    private boolean hasStorage() {
        return database != null;
    }

    @Override
    protected void init() {
        int contentTop = MARGIN + HEADER_HEIGHT;
        int contentBottom = height - MARGIN - FOOTER_HEIGHT - GAP;
        int contentHeight = Math.max(LINE, contentBottom - contentTop);
        int sidebarWidth = Math.min(SIDEBAR_WIDTH, width / 3);
        int conversationLeft = MARGIN + sidebarWidth + GAP;
        int conversationWidth = Math.max(LINE, width - MARGIN - conversationLeft);

        searchBox = new EditBox(font, MARGIN, MARGIN, sidebarWidth - LINE - 2, LINE,
                Component.translatable("gui.mcagents.search"));
        searchBox.setHint(Component.translatable("gui.mcagents.search"));
        searchBox.setValue(pendingSearch);
        searchBox.setResponder(text -> {
            pendingSearch = text;
            loadSessions(text);
        });
        addRenderableWidget(searchBox);

        addRenderableWidget(Button.builder(
                        Component.literal("+"),
                        button -> createSession())
                .bounds(MARGIN + sidebarWidth - LINE, MARGIN, LINE, LINE)
                .tooltip(net.minecraft.client.gui.components.Tooltip.create(
                        Component.translatable("gui.mcagents.session.new")))
                .build());

        addRenderableWidget(Button.builder(
                        Component.literal("🔑"),
                        button -> openKeySettings())
                .bounds(width - MARGIN - LINE * 2 - 4, MARGIN, LINE, LINE)
                .tooltip(net.minecraft.client.gui.components.Tooltip.create(
                        Component.translatable("gui.mcagents.keys.title")))
                .build());

        addRenderableWidget(Button.builder(
                        Component.literal("⚙"),
                        button -> openModelSettings())
                .bounds(width - MARGIN - LINE, MARGIN, LINE, LINE)
                .tooltip(net.minecraft.client.gui.components.Tooltip.create(
                        Component.translatable("gui.mcagents.models.title")))
                .build());

        sessionList = new SessionListPanel(
                font, this::openSession, this::beginRename, this::confirmDelete);
        sessionList.setBounds(MARGIN, contentTop, sidebarWidth, contentHeight);

        conversation = new ConversationPanel(font);
        conversation.setBounds(conversationLeft, contentTop, conversationWidth, contentHeight);

        int sendWidth = 44;
        promptBox = new EditBox(font, conversationLeft, height - MARGIN - FOOTER_HEIGHT,
                conversationWidth - sendWidth - GAP, LINE,
                Component.translatable("gui.mcagents.prompt"));
        promptBox.setHint(Component.translatable("gui.mcagents.prompt"));
        promptBox.setMaxLength(PROMPT_LIMIT);
        promptBox.setValue(pendingPrompt);
        promptBox.setResponder(text -> {
            pendingPrompt = text;
            updateSendState();
        });
        addRenderableWidget(promptBox);

        sendButton = addRenderableWidget(Button.builder(
                        Component.translatable("gui.mcagents.send"),
                        button -> send())
                .bounds(conversationLeft + conversationWidth - sendWidth,
                        height - MARGIN - FOOTER_HEIGHT, sendWidth, LINE)
                .build());

        if (!hasStorage()) {
            // The one control that matters when storage is down. Placed where the send
            // button would be, because that is where the player is already looking.
            addRenderableWidget(Button.builder(
                            Component.translatable("gui.mcagents.storage.retry"),
                            button -> retryStorage())
                    .bounds(conversationLeft, height - MARGIN - FOOTER_HEIGHT, 90, LINE)
                    .build());
        }

        setInitialFocus(promptBox);
        updateSendState();

        loadSessions(pendingSearch);
        if (activeSessionId != null) {
            loadMessages(activeSessionId);
        }
    }

    // ---------------------------------------------------------------- data

    private void loadSessions(String search) {
        if (!hasStorage()) {
            sessionList.setSessions(List.of());
            return;
        }
        sessionList.setLoading();
        ChatRepository chat = database.chat();
        CompletableFuture<List<ChatSession>> request = search == null || search.isBlank()
                ? chat.findAllSessions()
                : chat.searchSessions(search);

        onClientThread(waiting.track(request), sessions -> {
            sessionList.setSessions(sessions);
            if (activeSessionId != null) {
                sessionList.select(activeSessionId);
            }
        }, "Could not read the conversation list");
    }

    private void loadMessages(String sessionId) {
        if (!hasStorage()) {
            conversation.clear();
            return;
        }
        conversation.setLoading();
        onClientThread(waiting.track(database.chat().findMessages(sessionId)), messages -> {
            // A slower earlier request must not overwrite the conversation the player has
            // since clicked on.
            if (sessionId.equals(activeSessionId)) {
                conversation.setMessages(messages);
            }
        }, "Could not read the conversation");
    }

    private void openSession(ChatSession session) {
        activeSessionId = session.id();
        loadMessages(session.id());
        updateSendState();
    }

    /**
     * Tries to open the database again, then rebuilds the screen around the result.
     *
     * <p>A player whose storage failed at startup can recover without restarting the game,
     * which matters because the usual causes — a locked file, a directory that did not
     * exist yet — clear themselves.
     */
    private void retryStorage() {
        onClientThread(waiting.track(MCAgentsClientMod.reopenDatabase()), opened -> {
            bindStorage();
            rebuildWidgets();
            if (hasStorage()) {
                loadSessions(pendingSearch);
            }
        }, "Could not reopen the local database");
    }

    // ------------------------------------------------- renaming and deleting

    /**
     * Puts an edit box over the conversation's row in the list.
     *
     * <p>Renaming in place rather than on another screen: the title is one short line, and
     * bouncing the player to a separate screen to change it costs more than it explains.
     */
    private void beginRename(ChatSession session) {
        if (!hasStorage() || minecraft == null) {
            return;
        }
        minecraft.setScreenAndShow(new RenameSessionScreen(session, this,
                title -> renameSession(session.id(), title)));
    }

    private void renameSession(String sessionId, String title) {
        onClientThread(waiting.track(database.chat().renameSession(sessionId, title)),
                renamed -> loadSessions(pendingSearch), "Could not rename the conversation");
    }

    /**
     * Asks before deleting.
     *
     * <p>Deleting a conversation cascades to every message in it and cannot be undone, so
     * it is the one action here that does not happen on a single click.
     */
    private void confirmDelete(ChatSession session) {
        if (minecraft == null || !hasStorage()) {
            return;
        }
        minecraft.setScreenAndShow(new ConfirmScreen(
                confirmed -> {
                    if (confirmed) {
                        deleteSession(session);
                    }
                    if (minecraft != null) {
                        minecraft.setScreenAndShow(this);
                    }
                },
                Component.translatable("gui.mcagents.session.delete.title"),
                Component.translatable("gui.mcagents.session.delete.message", session.title()),
                Component.translatable("gui.mcagents.session.delete.confirm"),
                Component.translatable("gui.cancel")));
    }

    private void deleteSession(ChatSession session) {
        onClientThread(waiting.track(database.chat().deleteSession(session.id())), deleted -> {
            if (session.id().equals(activeSessionId)) {
                activeSessionId = null;
                conversation.clear();
            }
            loadSessions(pendingSearch);
        }, "Could not delete the conversation");
    }

    private void openKeySettings() {
        if (minecraft != null && hasStorage()) {
            minecraft.setScreenAndShow(new ApiKeyScreen(database, this));
        }
    }

    private void openModelSettings() {
        if (minecraft != null && hasStorage()) {
            // Passing this screen as the parent means closing the settings returns here
            // with the conversation still open.
            minecraft.setScreenAndShow(new ModelSettingsScreen(database, this));
        }
    }

    private void createSession() {
        if (!hasStorage()) {
            return;
        }
        onClientThread(waiting.track(database.chat().createSession(null)), session -> {
            activeSessionId = session.id();
            conversation.clear();
            sessionList.select(session.id());
            loadSessions(pendingSearch);
            updateSendState();
            setFocused(promptBox);
        }, "Could not start a new conversation");
    }

    private void send() {
        String prompt = promptBox.getValue().trim();
        if (prompt.isEmpty() || waiting.isWaiting() || !hasStorage()) {
            return;
        }

        promptBox.setValue("");
        pendingPrompt = "";
        updateSendState();

        // No conversation selected yet: the first message starts one, so the player never
        // has to press "new" before they can type.
        CompletableFuture<String> session = activeSessionId != null
                ? CompletableFuture.completedFuture(activeSessionId)
                : database.chat().createSession(null).thenApply(ChatSession::id);

        CompletableFuture<String> exchange = session
                .thenCompose(sessionId -> database.chat()
                        .appendMessage(sessionId, ChatRole.USER, prompt)
                        .thenApply(stored -> sessionId))
                .thenCompose(sessionId -> {
                    onClientThread(sessionId, id -> {
                        activeSessionId = id;
                        loadMessages(id);
                        // Refresh the sidebar here, not after the answer arrives. The
                        // conversation exists the moment the message is stored, and tying
                        // its appearance to a successful answer meant a failed request left
                        // the list showing "No chats yet" for a chat that was already saved.
                        loadSessions(pendingSearch);
                    });
                    return agent.respond(sessionId, prompt)
                            .thenCompose(answer -> database.chat()
                                    .appendMessage(sessionId, ChatRole.ASSISTANT, answer)
                                    .thenApply(ChatMessage::sessionId));
                });

        waiting.track(exchange).whenComplete((sessionId, failure) -> {
            if (minecraft == null) {
                return;
            }
            minecraft.execute(() -> {
                if (minecraft == null || minecraft.gui.screen() != this) {
                    return;
                }
                if (failure != null) {
                    // The provider's own wording, shown where the answer would have been.
                    // Logging alone left the player watching a conversation that simply
                    // stopped, with no way to tell a bad key from a bad model id.
                    LOGGER.error("The exchange failed", failure);
                    conversation.showError(describe(failure));
                    loadSessions(pendingSearch);
                    return;
                }
                loadMessages(sessionId);
                loadSessions(pendingSearch);
            });
        });
    }

    /**
     * Turns a failure into something worth reading.
     *
     * <p>A {@link ModelException} already carries the provider's own message, which is more
     * useful than anything this mod could invent — an unserved model id, a rejected key, a
     * rate limit. Anything else falls back to the exception's own text.
     */
    private static String describe(Throwable failure) {
        Throwable cause = failure instanceof java.util.concurrent.CompletionException
                ? failure.getCause()
                : failure;
        if (cause instanceof ModelException modelFailure) {
            return switch (modelFailure.kind()) {
                case NO_KEY -> "No usable OpenRouter API key. Add one with the key button.";
                case AUTHENTICATION -> "The provider rejected every stored key: "
                        + modelFailure.getMessage();
                case RATE_LIMIT -> "Rate limited by the provider: " + modelFailure.getMessage();
                default -> modelFailure.getMessage();
            };
        }
        String message = cause == null ? null : cause.getMessage();
        return message == null || message.isBlank()
                ? cause == null ? "Unknown failure" : cause.getClass().getSimpleName()
                : message;
    }

    /**
     * Applies {@code onSuccess} on the client thread once {@code request} settles, and logs
     * a failure instead of letting it disappear into an unobserved future.
     */
    private <T> void onClientThread(CompletableFuture<T> request, java.util.function.Consumer<T> onSuccess,
                                    String failureMessage) {
        request.whenComplete((result, failure) -> {
            if (failure != null) {
                LOGGER.error("{}", failureMessage, failure);
                return;
            }
            onClientThread(result, onSuccess);
        });
    }

    /** Hands a value back to the client thread, where screen state may be touched. */
    private <T> void onClientThread(T value, java.util.function.Consumer<T> action) {
        Executor client = this.minecraft;
        if (client == null) {
            return;
        }
        client.execute(() -> {
            // The player may have closed the screen while the request was in flight.
            // Minecraft 26.x keeps the current screen on Gui, not on Minecraft itself.
            if (this.minecraft != null && this.minecraft.gui.screen() == this) {
                action.accept(value);
            }
        });
    }

    private void updateSendState() {
        if (sendButton != null && promptBox != null) {
            sendButton.active =
                    hasStorage() && !promptBox.getValue().isBlank() && !waiting.isWaiting();
        }
        if (promptBox != null) {
            promptBox.active = hasStorage();
        }
    }

    // ---------------------------------------------------------- rendering

    @Override
    public void tick() {
        super.tick();
        waiting.tick();
        updateSendState();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        // Panels first: the widgets registered above are drawn over them by super.
        sessionList.render(graphics, mouseX, mouseY);
        conversation.render(graphics, mouseX, mouseY);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);

        int statusX = MARGIN + Math.min(SIDEBAR_WIDTH, width / 3) + GAP;
        graphics.text(font, headerText(), statusX, MARGIN + 6, headerColour());

        if (!hasStorage()) {
            // Say what is wrong where the conversation would be, so the screen explains
            // itself instead of just looking empty.
            MCAgentsClientMod.storageFailure().ifPresent(failure -> graphics.text(font,
                    font.plainSubstrByWidth(failure, width - statusX - MARGIN),
                    statusX, MARGIN + HEADER_HEIGHT + 8, 0xFFFF8080));
        }
    }

    private Component headerText() {
        if (waiting.isWaiting()) {
            return waiting.label(Component.translatable("gui.mcagents.waiting"));
        }
        if (hasStorage()) {
            return title;
        }
        return switch (MCAgentsClientMod.storageState()) {
            case OPENING -> Component.translatable("gui.mcagents.storage.opening")
                    .withStyle(ChatFormatting.YELLOW);
            default -> Component.translatable("gui.mcagents.storage.failed")
                    .withStyle(ChatFormatting.RED);
        };
    }

    private int headerColour() {
        return hasStorage() ? 0xFFFFFFFF : 0xFFFFD080;
    }

    // ------------------------------------------------------------- input

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (sessionList.click(event.x(), event.y(), doubleClick)) {
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (sessionList.isOver(mouseX, mouseY) && sessionList.scrollBy(scrollY)) {
            return true;
        }
        if (conversation.isOver(mouseX, mouseY) && conversation.scrollBy(scrollY)) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        // Enter sends from the prompt box; every other key belongs to the focused widget.
        if (promptBox.isFocused() && isEnter(event.key())) {
            send();
            return true;
        }
        return super.keyPressed(event);
    }

    private static boolean isEnter(int key) {
        return key == com.mojang.blaze3d.platform.InputConstants.KEY_RETURN
                || key == com.mojang.blaze3d.platform.InputConstants.KEY_NUMPADENTER;
    }

    @Override
    public boolean isPauseScreen() {
        // The world keeps running behind the screen; a player asking a question in a
        // multiplayer server is not paused, and pretending otherwise is misleading.
        return false;
    }

    /** The conversation currently open, if any. */
    public Optional<String> activeSession() {
        return Optional.ofNullable(activeSessionId);
    }
}
