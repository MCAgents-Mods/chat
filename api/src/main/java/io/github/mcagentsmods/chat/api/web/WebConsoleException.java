package io.github.mcagentsmods.chat.api.web;

/**
 * The web console could not be started, or stopped unexpectedly.
 *
 * <p>Never fatal to the mod. The console is a convenience over data the game can already
 * show; failing to open it costs the player a browser tab and nothing else.
 *
 * <p>Never carries a token, a key, or the contents of a conversation.
 */
public class WebConsoleException extends RuntimeException {

    public WebConsoleException(String message) {
        super(message);
    }

    public WebConsoleException(String message, Throwable cause) {
        super(message, cause);
    }
}
