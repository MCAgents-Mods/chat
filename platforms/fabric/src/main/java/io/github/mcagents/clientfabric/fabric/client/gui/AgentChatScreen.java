package io.github.mcagents.clientfabric.fabric.client.gui;

import io.github.mcagents.clientfabric.api.agent.Agent;
import io.github.mcagents.clientfabric.api.model.ChatMessage;
import io.github.mcagents.clientfabric.api.model.ChatRole;
import io.github.mcagents.clientfabric.api.model.ChatSession;
import io.github.mcagents.clientfabric.api.storage.ChatRepository;
import io.github.mcagents.clientfabric.api.storage.LocalDatabase;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
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

    private final LocalDatabase database;
    private final Agent agent;
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

    public AgentChatScreen(LocalDatabase database, Agent agent) {
        super(Component.translatable("gui.mcagents.title"));
        this.database = database;
        this.agent = agent;
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
                        Component.literal("⚙"),
                        button -> openModelSettings())
                .bounds(width - MARGIN - LINE, MARGIN, LINE, LINE)
                .tooltip(net.minecraft.client.gui.components.Tooltip.create(
                        Component.translatable("gui.mcagents.models.title")))
                .build());

        sessionList = new SessionListPanel(font, this::openSession);
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

        setInitialFocus(promptBox);
        updateSendState();

        loadSessions(pendingSearch);
        if (activeSessionId != null) {
            loadMessages(activeSessionId);
        }
    }

    // ---------------------------------------------------------------- data

    private void loadSessions(String search) {
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

    private void openModelSettings() {
        if (minecraft != null) {
            // Passing this screen as the parent means closing the settings returns here
            // with the conversation still open.
            minecraft.setScreenAndShow(new ModelSettingsScreen(database, this));
        }
    }

    private void createSession() {
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
        if (prompt.isEmpty() || waiting.isWaiting()) {
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
                    });
                    return agent.respond(sessionId, prompt)
                            .thenCompose(answer -> database.chat()
                                    .appendMessage(sessionId, ChatRole.ASSISTANT, answer)
                                    .thenApply(ChatMessage::sessionId));
                });

        onClientThread(waiting.track(exchange), sessionId -> {
            loadMessages(sessionId);
            loadSessions(pendingSearch);
        }, "Could not complete the exchange");
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
            sendButton.active = !promptBox.getValue().isBlank() && !waiting.isWaiting();
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

        Component status = waiting.isWaiting()
                ? waiting.label(Component.translatable("gui.mcagents.waiting"))
                : title;
        int statusX = MARGIN + Math.min(SIDEBAR_WIDTH, width / 3) + GAP;
        graphics.text(font, status, statusX, MARGIN + 6, 0xFFFFFFFF);
    }

    // ------------------------------------------------------------- input

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (sessionList.click(event.x(), event.y())) {
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
