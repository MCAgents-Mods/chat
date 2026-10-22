package io.github.mcagentsmods.chat.api;

/**
 * The mod's identity and the layout it owns on the player's disk.
 *
 * <p>These constants are shared by every module. The paths are expressed as names rather
 * than as a resolved {@link java.nio.file.Path} on purpose: only the platform module knows
 * where the Minecraft game directory is, so it resolves them and hands the result down.
 */
public final class MCAgents {

    /** Mod id, as declared in each loader's mod manifest. */
    public static final String MOD_ID = "mcagents";

    /** Human-readable mod name, used for logging and in the interface. */
    public static final String MOD_NAME = "MCAgentsClient";

    /** Directory every MCAgents mod keeps its files under, inside the Minecraft game directory. */
    public static final String DATA_DIRECTORY = "MCAgents";

    /**
     * This mod's own directory inside {@link #DATA_DIRECTORY}, named for its repository. Every
     * MCAgents mod keeps its files in {@code MCAgents/{mod}/}; chat kept them loose in
     * {@code MCAgents/} until 0.15.0.
     */
    public static final String MOD_DIRECTORY = "chat";

    /** SQLite database holding chat history, API keys, and downloaded Knowledge Packs. */
    public static final String DATABASE_FILE = "data.db";

    /** Player-authored agent personas and instructions, read at runtime. */
    public static final String INSTRUCTIONS_FILE = "AGENTS.md";

    /**
     * Directory holding external MCP servers, laid out as {@code mcps/{owner}/{repository}/}.
     *
     * <p>Not to be confused with the repository's own {@code ./mcps/}, which holds servers
     * for the agent working on this source tree. This one is on a player's machine and is
     * filled by the mod at runtime.
     */
    public static final String MCP_DIRECTORY = "mcps";

    /**
     * The MCP server catalog, inside {@link #MCP_DIRECTORY}.
     *
     * <p>Optional. A player with no such file still gets every server that discovery finds
     * on disk; the file exists to add hosted servers and to switch discovered ones off.
     */
    public static final String MCP_SERVERS_FILE = "mcp_servers.json";

    /**
     * Manifest a cloned repository may carry to describe how to run itself.
     *
     * <p>Read from the root of each {@code mcps/{owner}/{repository}/} checkout. Without it
     * the loader falls back to what it can infer from {@code package.json}.
     */
    public static final String MCP_MANIFEST_FILE = "mcp.json";

    private MCAgents() {
        throw new AssertionError("No instances.");
    }
}
