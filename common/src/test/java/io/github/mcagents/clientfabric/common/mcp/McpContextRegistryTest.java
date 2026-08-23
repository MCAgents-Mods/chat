package io.github.mcagents.clientfabric.common.mcp;

import io.github.mcagents.clientfabric.api.mcp.McpContextRegistry;
import io.github.mcagents.clientfabric.api.mcp.McpPromptDescriptor;
import io.github.mcagents.clientfabric.api.mcp.McpResourceDescriptor;
import io.github.mcagents.clientfabric.api.mcp.McpToolDescriptor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Merging several servers into the one list a model is shown.
 *
 * <p>The interesting cases are all collisions. A registry that hands a model two tools it
 * cannot tell apart has produced a call that cannot be routed, and the model will be blamed
 * for it.
 */
class McpContextRegistryTest {

    @Test
    void qualifiesEveryToolNameWithItsServer() {
        McpToolDescriptor tool = new McpToolDescriptor("weather", "forecast", "", "");
        assertEquals("weather__forecast", tool.qualifiedName());
        assertTrue(McpToolDescriptor.isProviderSafe(tool.qualifiedName()));
    }

    @Test
    void replacesCharactersAProviderWouldReject() {
        McpToolDescriptor tool =
                new McpToolDescriptor("shared-instruction", "agents/read.file", "", "");
        assertTrue(McpToolDescriptor.isProviderSafe(tool.qualifiedName()),
                tool.qualifiedName() + " must match what OpenRouter accepts");
    }

    @Test
    void neverExceedsTheProviderNameLimit() {
        McpToolDescriptor tool = new McpToolDescriptor("a".repeat(60), "b".repeat(60), "", "");
        assertEquals(64, tool.qualifiedName().length());
        assertTrue(McpToolDescriptor.isProviderSafe(tool.qualifiedName()));
    }

    @Test
    void twoServersMayBothOfferTheSameToolName() {
        McpContextRegistry registry = McpContextRegistry.merge(
                List.of(new McpToolDescriptor("alpha", "search", "", ""),
                        new McpToolDescriptor("beta", "search", "", "")),
                List.of(), List.of(), List.of(), null);

        assertEquals(2, registry.tools().size());
        assertTrue(registry.findTool("alpha__search").isPresent());
        assertTrue(registry.findTool("beta__search").isPresent());
    }

    @Test
    void aCollidingToolIsDroppedAndReported() {
        List<String> dropped = new ArrayList<>();
        McpContextRegistry registry = McpContextRegistry.merge(
                List.of(new McpToolDescriptor("alpha", "search", "first", ""),
                        new McpToolDescriptor("alpha", "search", "second", "")),
                List.of(), List.of(), List.of(), dropped);

        assertEquals(1, registry.tools().size());
        assertEquals("first", registry.tools().getFirst().description());
        assertEquals(1, dropped.size(), "the drop must be reported, not silent");
        assertTrue(dropped.getFirst().contains("alpha/search"));
    }

    @Test
    void truncationCollisionsAreCaughtToo() {
        // Both qualify to the same 64 characters once truncated. Without the collision
        // check the second would shadow the first and one server's tool would vanish.
        List<String> dropped = new ArrayList<>();
        McpContextRegistry registry = McpContextRegistry.merge(
                List.of(new McpToolDescriptor("server", "x".repeat(70) + "one", "", ""),
                        new McpToolDescriptor("server", "x".repeat(70) + "two", "", "")),
                List.of(), List.of(), List.of(), dropped);

        assertEquals(1, registry.tools().size());
        assertEquals(1, dropped.size());
    }

    @Test
    void resourcesAreKeptByUriAndNotQualified() {
        McpContextRegistry registry = McpContextRegistry.merge(
                List.of(),
                List.of(new McpResourceDescriptor("shared", "agents://manifest.json", "m", "", "")),
                List.of(), List.of(), null);

        assertTrue(registry.findResource("agents://manifest.json").isPresent());
        assertEquals("shared", registry.findResource("agents://manifest.json").get().serverId());
    }

    @Test
    void promptsQualifyLikeTools() {
        McpPromptDescriptor prompt = new McpPromptDescriptor("shared", "agents-setup", "");
        assertEquals("shared__agents-setup", prompt.qualifiedName());
    }

    @Test
    void anEmptyRegistryIsAValidState() {
        assertTrue(McpContextRegistry.empty().isEmpty());
        assertFalse(McpContextRegistry.empty().refreshedAt().toString().isEmpty());
    }
}
