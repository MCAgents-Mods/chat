package io.github.mcagentsmods.chat.common.mcp;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.mcagentsmods.chat.api.MCAgents;
import io.github.mcagentsmods.chat.api.mcp.McpServerSpec;
import io.github.mcagentsmods.chat.api.mcp.McpTransport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Works out which MCP servers exist, from the mcps directory and the optional catalog.
 *
 * <p>Two sources, deliberately unequal. The <b>directory</b> is the source of truth for
 * what is installed: a repository the mod cloned is a server, and the mod works out how to
 * start it, so a player who asked the agent to add one never hand-writes its wiring. The
 * <b>catalog</b> ({@code mcp_servers.json}) approves that wiring — a repository's command
 * does not run until the player has, see {@link McpApproval} — adds hosted servers, and
 * overrides what discovery inferred, so a wrong guess is correctable without editing
 * anything inside the checkout.
 *
 * <p>Discovery never runs anything. It reads files, decides how a server <em>would</em> be
 * started, and returns specs. Starting them is
 * {@link WatchingMcpHost}'s job, which keeps the "what exists" question testable without a
 * single child process.
 */
final class McpDiscovery {

    /**
     * The largest manifest, package.json or catalog read. Each is a few hundred bytes in
     * practice; a repository that ships one far larger is not going to be understood by
     * reading all of it, and every reload would read it again.
     */
    static final long MAX_JSON_BYTES = 1024 * 1024;

    private final Path mcpRoot;

    McpDiscovery(Path mcpRoot) {
        this.mcpRoot = mcpRoot;
    }

    /**
     * Everything currently configured, catalog entries applied over discovered ones.
     *
     * <p>Never throws. A malformed catalog, an unreadable directory, or a repository that
     * cannot be run are all reported as absence rather than as failure — the offline-first
     * mandate covers MCP too, and a broken server file must not stop the mod from loading.
     *
     * @param problems collects one line per thing that could not be understood, so the
     *                 player can be shown why a server they expected is missing
     */
    List<McpServerSpec> discover(List<String> problems) {
        return discover(problems, new LinkedHashMap<>());
    }

    /**
     * Everything currently configured, with what still needs the player's approval.
     *
     * <p>A server whose wiring came from its repository, and whose fingerprint the catalog
     * has not approved, comes back disabled, and {@code awaitingApproval} says why and how
     * to allow it — see {@link McpApproval}. One the player switched off themselves stays
     * plainly disabled.
     *
     * @param problems         as for {@link #discover(List)}
     * @param awaitingApproval collects, per server id, the sentence to show for a server
     *                         that is waiting for approval
     */
    List<McpServerSpec> discover(List<String> problems, Map<String, String> awaitingApproval) {
        Map<String, McpServerSpec> specs = new LinkedHashMap<>();
        Set<String> fromRepository = new LinkedHashSet<>();
        for (McpServerSpec spec : fromDirectories(problems)) {
            specs.put(spec.id(), spec);
            fromRepository.add(spec.id());
        }
        Map<String, String> approvals = new LinkedHashMap<>();
        applyCatalog(specs, fromRepository, approvals, problems);

        for (String id : fromRepository) {
            McpServerSpec spec = specs.get(id);
            if (spec == null || !spec.enabled()) {
                continue;
            }
            String fingerprint = McpApproval.fingerprint(spec);
            if (!fingerprint.equalsIgnoreCase(approvals.getOrDefault(id, ""))) {
                specs.put(id, spec.withEnabled(false));
                awaitingApproval.put(id, McpApproval.describe(spec, fingerprint));
            }
        }
        return List.copyOf(specs.values());
    }

    /**
     * One spec per {@code {owner}/{repository}} directory that can be run.
     *
     * <p>Ids are repository names, because that is what a person calls a server and short
     * ids leave room under the provider's 64-character tool-name limit. When two owners
     * ship a repository of the same name, both fall back to {@code {owner}-{repository}} —
     * disambiguating only the pair that collides keeps every other id short.
     */
    private List<McpServerSpec> fromDirectories(List<String> problems) {
        List<Path> repositories = repositoryDirectories(problems);

        Set<String> seen = new LinkedHashSet<>();
        Set<String> colliding = new LinkedHashSet<>();
        for (Path repository : repositories) {
            String name = repository.getFileName().toString();
            if (!seen.add(name)) {
                colliding.add(name);
            }
        }

        List<McpServerSpec> specs = new ArrayList<>();
        for (Path repository : repositories) {
            String repositoryName = repository.getFileName().toString();
            String owner = repository.getParent().getFileName().toString();
            String id = colliding.contains(repositoryName)
                    ? owner + "-" + repositoryName
                    : repositoryName;
            describe(id, repository).ifPresentOrElse(
                    specs::add,
                    () -> problems.add(owner + "/" + repositoryName
                            + " — no mcp.json and no runnable package.json, so there is no way "
                            + "to tell how to start it"));
        }
        return specs;
    }

    private List<Path> repositoryDirectories(List<String> problems) {
        if (!Files.isDirectory(mcpRoot)) {
            return List.of();
        }
        List<Path> repositories = new ArrayList<>();
        try (Stream<Path> owners = Files.list(mcpRoot)) {
            for (Path owner : owners.filter(Files::isDirectory).sorted().toList()) {
                try (Stream<Path> children = Files.list(owner)) {
                    children.filter(Files::isDirectory).sorted().forEach(repositories::add);
                }
            }
        } catch (IOException cannotRead) {
            problems.add("Could not read " + mcpRoot + ": " + cannotRead.getMessage());
        }
        return repositories;
    }

    /**
     * How to start the server in {@code repository}, if it says.
     *
     * <p>{@code mcp.json} is asked first because it is the repository stating its own
     * answer. Only when there is none does this infer one from {@code package.json}, and
     * inference is limited to what can be known for certain: a declared bin, or the
     * package main. Guessing beyond that produces a server that starts and then fails in a
     * way no one can diagnose.
     */
    Optional<McpServerSpec> describe(String id, Path repository) {
        String relative = mcpRoot.relativize(repository).toString().replace('\\', '/');

        Optional<JsonObject> manifest = readJson(repository.resolve(MCAgents.MCP_MANIFEST_FILE));
        if (manifest.isPresent()) {
            return fromManifest(id, manifest.get(), relative);
        }
        return fromPackageJson(id, repository, relative);
    }

    private Optional<McpServerSpec> fromManifest(String id, JsonObject manifest, String relative) {
        String transport = McpJson.string(manifest, "transport");
        if (transport.equalsIgnoreCase("http")) {
            String url = McpJson.string(manifest, "url");
            // A repository chooses this URL, and tool calls carry the conversation. Plain
            // http would put that on the wire in the clear to whatever host it names.
            return url.isBlank() || !isHttpsOrLoopback(url)
                    ? Optional.empty()
                    : Optional.of(McpServerSpec.http(id, url));
        }
        String command = McpJson.string(manifest, "command");
        if (command.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new McpServerSpec(
                id,
                McpTransport.STDIO,
                command,
                stringList(manifest, "args"),
                "",
                stringMap(manifest, "env"),
                relative,
                true));
    }

    /**
     * Infers a stdio spec from {@code package.json}.
     *
     * <p>A {@code bin} entry is preferred over {@code main}: a package that declares a
     * binary is telling you which file is meant to be executed, while {@code main} is what
     * an importer would load and is frequently a library entry point rather than a server.
     */
    private Optional<McpServerSpec> fromPackageJson(String id, Path repository, String relative) {
        Optional<JsonObject> packageJson = readJson(repository.resolve("package.json"));
        if (packageJson.isEmpty()) {
            return Optional.empty();
        }
        JsonObject json = packageJson.get();

        String entry = "";
        if (json.has("bin")) {
            JsonElement bin = json.get("bin");
            if (bin.isJsonPrimitive()) {
                entry = bin.getAsString();
            } else if (bin.isJsonObject()) {
                JsonObject bins = bin.getAsJsonObject();
                // A package declaring several bins names the server one after itself where
                // it can; falling back to the first keeps a single-bin rename working.
                entry = bins.has(id) ? McpJson.string(bins, id)
                        : bins.keySet().stream().findFirst().map(k -> McpJson.string(bins, k)).orElse("");
            }
        }
        if (entry.isBlank()) {
            entry = McpJson.string(json, "main");
        }
        if (entry.isBlank() || !Files.isRegularFile(repository.resolve(entry))) {
            return Optional.empty();
        }
        return Optional.of(McpServerSpec.stdio(id, "node", List.of(entry), relative));
    }

    /**
     * Applies {@code mcp_servers.json} over what discovery found.
     *
     * <p>An entry naming a discovered id edits that server; an entry naming an unknown id
     * adds one. Both matter: the first is how a player corrects an inferred command or
     * switches a server off, the second is how a hosted server gets in at all.
     */
    private void applyCatalog(Map<String, McpServerSpec> specs, Set<String> fromRepository,
                              Map<String, String> approvals, List<String> problems) {
        Path catalogFile = mcpRoot.resolve(MCAgents.MCP_SERVERS_FILE);
        Optional<JsonObject> catalog = readJson(catalogFile);
        if (catalog.isEmpty()) {
            if (Files.exists(catalogFile)) {
                problems.add(MCAgents.MCP_SERVERS_FILE + " is not valid JSON, or is over 1 MB, and was ignored");
            }
            return;
        }
        JsonObject servers = catalog.get().has("mcpServers")
                && catalog.get().get("mcpServers").isJsonObject()
                ? catalog.get().getAsJsonObject("mcpServers")
                : catalog.get();

        for (String id : servers.keySet()) {
            if (!servers.get(id).isJsonObject()) {
                continue;
            }
            JsonObject entry = servers.getAsJsonObject(id);
            String approved = McpJson.string(entry, McpApproval.FIELD).trim();
            if (!approved.isEmpty()) {
                approvals.put(id, approved);
            }
            // Wiring the player wrote is theirs, and needs no approval; an entry that only
            // switches a discovered server on or off leaves the repository's wiring in place.
            if (!McpJson.string(entry, "command").isBlank() || !McpJson.string(entry, "url").isBlank()) {
                fromRepository.remove(id);
            }
            boolean enabled = !entry.has("enabled")
                    || !entry.get("enabled").isJsonPrimitive()
                    || entry.get("enabled").getAsBoolean();
            McpServerSpec existing = specs.get(id);
            try {
                McpServerSpec merged = mergeEntry(id, entry, existing, enabled);
                if (merged != null) {
                    specs.put(id, merged);
                }
            } catch (RuntimeException rejected) {
                problems.add(MCAgents.MCP_SERVERS_FILE + " entry '" + id + "' ignored: "
                        + rejected.getMessage());
            }
        }
    }

    private McpServerSpec mergeEntry(
            String id, JsonObject entry, McpServerSpec existing, boolean enabled) {

        String url = McpJson.string(entry, "url");
        String command = McpJson.string(entry, "command");
        String declared = McpJson.string(entry, "type");
        if (declared.isBlank()) {
            declared = McpJson.string(entry, "transport");
        }

        boolean http = declared.equalsIgnoreCase("http")
                || declared.equalsIgnoreCase("sse")
                || (declared.isBlank() && !url.isBlank());

        if (http) {
            if (url.isBlank()) {
                throw new IllegalArgumentException("an http server needs a url");
            }
            return new McpServerSpec(
                    id, McpTransport.HTTP, "", List.of(), url,
                    stringMap(entry, "env"), "", enabled);
        }

        if (command.isBlank()) {
            // No wiring of its own: this entry only adjusts a server discovery found.
            if (existing == null) {
                throw new IllegalArgumentException(
                        "names no command or url, and no cloned repository matches it");
            }
            return existing.withEnabled(enabled);
        }

        String workingDirectory = McpJson.string(entry, "cwd");
        if (workingDirectory.isBlank() && existing != null) {
            workingDirectory = existing.workingDirectory();
        }
        return new McpServerSpec(
                id, McpTransport.STDIO, command, stringList(entry, "args"), "",
                stringMap(entry, "env"), workingDirectory, enabled);
    }

    private static List<String> stringList(JsonObject object, String field) {
        if (!object.has(field) || !object.get(field).isJsonArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        object.getAsJsonArray(field).forEach(element -> {
            if (element.isJsonPrimitive()) {
                values.add(element.getAsString());
            }
        });
        return values;
    }

    private static Map<String, String> stringMap(JsonObject object, String field) {
        if (!object.has(field) || !object.get(field).isJsonObject()) {
            return Map.of();
        }
        Map<String, String> values = new LinkedHashMap<>();
        JsonObject source = object.getAsJsonObject(field);
        source.keySet().forEach(key -> values.put(key, McpJson.string(source, key)));
        return values;
    }

    /** Whether {@code url} is https, or plain http to this machine only. */
    static boolean isHttpsOrLoopback(String url) {
        java.net.URI uri;
        try {
            uri = new java.net.URI(url.trim());
        } catch (java.net.URISyntaxException malformed) {
            return false;
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(java.util.Locale.ROOT);
        if (host.isEmpty() || uri.getRawUserInfo() != null) {
            return false;
        }
        return scheme.equals("https")
                || scheme.equals("http")
                        && (host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]"));
    }

    private Optional<JsonObject> readJson(Path file) {
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            if (Files.size(file) > MAX_JSON_BYTES) {
                return Optional.empty();
            }
            String text = Files.readString(file, StandardCharsets.UTF_8);
            JsonElement parsed = JsonParser.parseString(text);
            return parsed.isJsonObject() ? Optional.of(parsed.getAsJsonObject()) : Optional.empty();
        } catch (IOException | RuntimeException unreadable) {
            return Optional.empty();
        }
    }
}
