package io.github.mcagents.clientfabric.api.model;

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
    SYSTEM("system");

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
