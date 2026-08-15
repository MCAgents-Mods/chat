package io.github.mcagents.clientfabric.api;

/**
 * The mod's identity and the layout it owns on the player's disk.
 *
 * <p>These constants are shared by every module. The paths are expressed as names rather
 * than as a resolved {@link java.nio.file.Path} on purpose: only the platform module knows
 * where the Minecraft game directory is, so it resolves them and hands the result down.
 */
public final class MCAgents {

    /** Mod id, as declared in {@code fabric.mod.json}. */
    public static final String MOD_ID = "mcagents";

    /** Human-readable mod name, used for logging and in the interface. */
    public static final String MOD_NAME = "MCAgentsClient";

    /** Directory the mod creates inside the Minecraft game directory. */
    public static final String DATA_DIRECTORY = "MCAgents";

    /** SQLite database holding chat history, API keys, and downloaded Knowledge Packs. */
    public static final String DATABASE_FILE = "data.db";

    /** Player-authored agent personas and instructions, read at runtime. */
    public static final String INSTRUCTIONS_FILE = "AGENTS.md";

    /**
     * The central Knowledge Pack service.
     *
     * <p>A content source and never a dependency: everything the mod does locally works
     * with this unreachable. See {@code .agents/rules/repository.md}.
     */
    public static final String SERVICE_URL = "https://mcagents.mcrealms.space";

    /**
     * The pack describing the mod itself, fetched once at startup so the agent can answer
     * questions about what it is running inside.
     *
     * <p>The id and the version are a contract with the service, not a detail.
     */
    public static final String BASE_KNOWLEDGE_ID = "mcagents-info";

    private MCAgents() {
        throw new AssertionError("No instances.");
    }
}
