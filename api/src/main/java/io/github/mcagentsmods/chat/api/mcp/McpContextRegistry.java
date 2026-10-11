package io.github.mcagentsmods.chat.api.mcp;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Every tool, resource and prompt currently reachable, merged into one view.
 *
 * <p>This is what the LLM is shown. It is an immutable snapshot rather than a live
 * collection on purpose: a request assembled from a registry must describe the same set of
 * tools that the resulting tool calls are routed against. A server that disconnects
 * mid-request would otherwise make the model's call unroutable through no fault of its own,
 * and the model would be told it hallucinated a tool it was genuinely offered.
 *
 * <p>Building one resolves qualified-name collisions. Two servers can offer the same tool
 * name, and truncation to the provider's 64-character limit can make two different
 * qualified names identical. Either way the first one wins and the loser is dropped, so the
 * registry never contains two entries a model could not tell apart.
 *
 * @param tools      every tool, in server order
 * @param resources  every resource
 * @param prompts    every prompt
 * @param servers    what happened to each configured server, connected or not
 * @param refreshedAt when this snapshot was taken
 */
public record McpContextRegistry(
        List<McpToolDescriptor> tools,
        List<McpResourceDescriptor> resources,
        List<McpPromptDescriptor> prompts,
        List<McpServerStatus> servers,
        Instant refreshedAt) {

    public McpContextRegistry {
        tools = List.copyOf(tools);
        resources = List.copyOf(resources);
        prompts = List.copyOf(prompts);
        servers = List.copyOf(servers);
        Objects.requireNonNull(refreshedAt, "refreshedAt");
    }

    /** The registry of a mod with no MCP servers — the normal state, and never an error. */
    public static McpContextRegistry empty() {
        return new McpContextRegistry(List.of(), List.of(), List.of(), List.of(), Instant.now());
    }

    /**
     * Merges what several servers reported, dropping any tool or prompt whose qualified
     * name is already taken.
     *
     * @param dropped receives one line per dropped entry, so a collision is reported rather
     *                than silently swallowed; may be {@code null}
     */
    public static McpContextRegistry merge(
            List<McpToolDescriptor> tools,
            List<McpResourceDescriptor> resources,
            List<McpPromptDescriptor> prompts,
            List<McpServerStatus> servers,
            List<String> dropped) {

        Map<String, McpToolDescriptor> byName = new LinkedHashMap<>();
        for (McpToolDescriptor tool : tools) {
            McpToolDescriptor existing = byName.putIfAbsent(tool.qualifiedName(), tool);
            if (existing != null && dropped != null) {
                dropped.add("tool " + tool.serverId() + "/" + tool.name()
                        + " collides with " + existing.serverId() + "/" + existing.name()
                        + " as '" + tool.qualifiedName() + "'");
            }
        }

        Map<String, McpPromptDescriptor> promptsByName = new LinkedHashMap<>();
        for (McpPromptDescriptor prompt : prompts) {
            McpPromptDescriptor existing = promptsByName.putIfAbsent(prompt.qualifiedName(), prompt);
            if (existing != null && dropped != null) {
                dropped.add("prompt " + prompt.serverId() + "/" + prompt.name()
                        + " collides with " + existing.serverId() + "/" + existing.name());
            }
        }

        return new McpContextRegistry(
                new ArrayList<>(byName.values()),
                resources,
                new ArrayList<>(promptsByName.values()),
                servers,
                Instant.now());
    }

    /** Looks a tool up by the qualified name the model used. */
    public Optional<McpToolDescriptor> findTool(String qualifiedName) {
        return tools.stream().filter(t -> t.qualifiedName().equals(qualifiedName)).findFirst();
    }

    /** Looks a resource up by URI. */
    public Optional<McpResourceDescriptor> findResource(String uri) {
        return resources.stream().filter(r -> r.uri().equals(uri)).findFirst();
    }

    /** Whether there is anything at all to offer a model. */
    public boolean isEmpty() {
        return tools.isEmpty() && resources.isEmpty() && prompts.isEmpty();
    }

    /** Servers that are connected right now. */
    public List<McpServerStatus> connectedServers() {
        return servers.stream().filter(McpServerStatus::isConnected).toList();
    }
}
