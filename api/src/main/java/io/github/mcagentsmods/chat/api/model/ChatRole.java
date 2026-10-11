package io.github.mcagentsmods.chat.api.model;

import java.util.Locale;

/**
 * Who produced a chat message.
 *
 * <p>The wire names match what the OpenRouter chat completions API expects, so a stored
 * conversation can be sent as-is without a translation step.
 */
public enum ChatRole {

    /** The player. */
    USER("user"),

    /** The agent. */
    ASSISTANT("assistant"),

    /** Instructions assembled from the player's personas and the retrieved context. */
    SYSTEM("system"),

    /**
     * The result of a tool the model asked for.
     *
     * <p>A wire role only. Tool results are part of one exchange with the provider and are
     * never written to the database: they are the working-out, not the answer, and a
     * conversation replayed from storage must read as the conversation the player had.
     */
    TOOL("tool");

    private final String wireName;

    ChatRole(String wireName) {
        this.wireName = wireName;
    }

    /** The value stored in the database and sent to the model API. */
    public String wireName() {
        return wireName;
    }

    /**
     * Resolves a stored or received role name.
     *
     * @throws IllegalArgumentException if the name matches no role
     */
    public static ChatRole fromWireName(String wireName) {
        String normalised = wireName == null ? "" : wireName.trim().toLowerCase(Locale.ROOT);
        for (ChatRole role : values()) {
            if (role.wireName.equals(normalised)) {
                return role;
            }
        }
        throw new IllegalArgumentException("Unknown chat role: " + wireName);
    }
}
