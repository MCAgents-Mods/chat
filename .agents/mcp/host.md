---
name: mcp-host-rules
description: Rules for the mod's MCP client — the mcps layout, the doubled path validation, the qualified-name cap, and why a failing server is never fatal.
---

# MCP Host Rules

MCAgentsClient is an MCP **client**: it runs servers other people wrote and offers their
tools to the agent. Player-facing documentation is
[`../../wiki/information/mcp-servers.md`](../../wiki/information/mcp-servers.md); the
orientation an agent reads first is
[`../wiki/context/repository-map.md`](../wiki/context/repository-map.md). This file is the
set of rules a change to `api.mcp`, `common.mcp`, or `McpHostFactory` must not break.

## An MCP server is optional, always

This is the offline-first mandate in
[`../rules/repository.md`](../rules/repository.md), applied to MCP, and it outranks every
other rule here.

* **A mod with no MCP servers is in a normal state**, not a degraded one. Discovery finding
  nothing is not a warning, an error, or a prompt to configure anything.
* **No MCP failure may fail the mod.** A server that will not start, breaks the protocol,
  or hangs becomes an `McpServerStatus.State.FAILED` row carrying a readable reason. The
  other servers stay connected and the game is unaffected.
* **Nothing waits on MCP.** Not startup, not the chat hologram, not a question. The host
  starts asynchronously and the registry is read as it currently stands.
* A failure's reason must be a **sentence a player can act on**. Never a wrapper class name
  — unwrap `ExecutionException` and `CompletionException` before reporting, because
  `join()` puts the cause's class name into its own message.

## The directory layout is fixed

```
{minecraft_dir}/MCAgents/mcps/{owner}/{repository}/
```

* One directory per repository, grouped by owner. Nothing else is written to the `mcps`
  root except `mcp_servers.json`.
* **This is player data.** It is never committed, never copied into this repository, and
  never included in a bug report — `mcp_servers.json` holds server environments, which is
  where a server's credentials sit.
* Not to be confused with this repository's own `./mcps/`, which holds servers for the
  agent working on **this source tree**. The path shape is deliberately the same and the
  two never mix; see the table in [`../../AGENTS.md`](../../AGENTS.md).

## Path validation is doubled, and stays doubled

`RepositoryCoordinate` refuses traversal and every scheme but `https`.
`ClientPaths.mcpRepository` then refuses a resolved path that escapes the `mcps` root.

**The second check is redundant today and must not be removed for that reason.** These
values are frequently chosen by a language model rather than by a person, and the failure
mode is writing anywhere the game process can reach. Two independent locks on one door is
the intended design.

Never let a caller — model, screen, or route — choose the destination directory. It is
derived from the clone URL and from nothing else.

## Discovery never starts anything

`McpDiscovery` reads files and returns `McpServerSpec`s. `WatchingMcpHost` starts them.

Keep the split. It is why "what is installed" is testable against a directory tree with no
child process anywhere in the test, and why a wrong inference is diagnosable without
watching a server fail.

**Do not guess how to run a repository.** `mcp.json` first, then `package.json`'s `bin`,
then its `main`. A repository that says none of those is reported as a problem, never
started on a guess: a wrong guess produces a server that starts and then fails in a way
nobody can diagnose.

## A reload is a diff, not a restart

`McpServerSpec.sameWiring` decides what actually changed. A server whose wiring is unchanged
**keeps its live process**.

Restarting everything on every filesystem event drops working connections constantly —
servers write inside their own directories as a matter of course. A changed server is
replaced and its old process stopped; a server no longer configured is disconnected and its
process stopped. Never leak a child process past the configuration that asked for it.

## The watcher is not recursive

Only the `mcps` root and the owner directories are registered. `WatchService` needs a key
per directory, so watching every checkout means a key per `node_modules` tree — thousands
of watches for something the settle window already covers.

**Keep the settle window.** A clone writes hundreds of files and each is an event;
reloading on the first connects to a half-written repository.

## Tool names are qualified and capped

Every tool is exposed to the model as `{server}__{tool}`, sanitized to `[A-Za-z0-9_-]` and
truncated to **64 characters**.

This is not cosmetic. An OpenAI-compatible provider rejects a function name outside
`[A-Za-z0-9_-]{1,64}` and rejects **the entire request** with it — so one bad name takes
every other server's tools down too. Qualifying is also what lets two servers both offer
`search` without either shadowing the other.

Truncation can itself collide. `McpContextRegistry.merge` is the only place that sees every
tool at once, so it is where a collision is detected, dropped, and **reported** rather than
left to shadow silently.

## The registry handed to a model is a snapshot

`McpContextRegistry` is immutable. A request assembled from one must be routed against the
same one.

A server that disconnects mid-request would otherwise make the model's call unroutable
through no fault of its own, and the model would be blamed for a tool it was genuinely
offered.

## Transport rules

* **On stdio, standard output is the protocol.** One JSON-RPC message per line. A server
  logging there is a real and common bug: skip non-JSON lines rather than failing.
* **Drain standard error and discard it.** A child whose error pipe fills blocks forever,
  inside a write nothing observes. That stream is also exactly where a server prints the
  credentials it was handed, so it is never logged.
* **Every request carries a deadline.** A server that accepts a request and never answers
  must cost one request, not a future nobody completes.
* **On HTTP, accept both reply shapes** — `application/json` and `text/event-stream`. A
  client handling only one works against half the ecosystem.
* **A URL without its MCP path is the likeliest cause of a rejection.** Say so: servers
  commonly report it as an authentication failure.
* The HTTP transport belongs to the host and is shared across servers. A session never
  closes it.

## Routing: a name is resolved, never guessed

Tools are offered through the agents mod's registry, not by chat. `McpTools` registers
every tool the host's registry holds there, under its qualified `{server}__{tool}` name, and
follows the host: tools are added when a server connects, removed when it goes, and replaced
when they change. Its body finds the session for the server and calls the tool by **its own
name** — the qualification is stripped, because a server has never heard of it.

Three places must agree on that name and they are edited separately: what `McpDiscovery`
calls a server, what `McpContextRegistry` qualifies its tools as, and what `McpTools`
strips. A change to any one of them is a change to all three.

**Each server is a category**, named by `McpTools.categoryOf(serverId)`, so the agent's
first step picks servers before any tool is described. `mcp_clone_repository` sits in its
own category, `mcp`, and is registered before discovery runs: it is how a player with no
servers gets their first, and a tool served by a server they do not have yet is a loop with
no entry. The agents registry refuses a duplicate name, so no server can shadow it.

A name or schema the agents registry refuses leaves that one tool out with a warning; a
provider would refuse the whole request over it, silencing every other server's tools.

## A tool call never fails a question

Every failure comes back as a result with `isError` set, carrying text the model reads:

* a tool that does not exist, and arguments that will not parse — answered by the agents
  mod's executor;
* a server that has disconnected since its tools were offered;
* a server that threw, unwrapped to its reason.

Failing the request instead turns a mistake the model could have recovered from into a dead
question the player has to ask again. Never convert a result into a thrown failure on the
way back.

## The exchange belongs to the agents mod

The agents mod's agent runs the exchange: it echoes the assistant turn and one result per
call back to the provider, runs a server's tools together when the model asks for several
(`CONCURRENT`) and the clone tool alone (`WAIT`), and stops after the player's number of
tool rounds, 1 unless they set more. Chat hands it the assembled conversation and the
newest question, and keeps one rule of its own:

* **Tool messages are never stored.** They are the working-out; a replayed conversation
  must read as the conversation the player had.

## `isError` is not a failed future

A failed future means the server could not be reached or broke the protocol. `isError` is
the server saying the call itself went wrong.

The model can act on the second and must be told about it. Never convert one into the
other.

## git is shelled out to, never embedded

A Java git implementation would be megabytes inside a jar already carrying SQLite's native
binaries, for something nearly every machine can already do. A machine without git gets a
sentence saying so, which is a better outcome than a permanently larger mod.

## Threads

Every thread the host owns is a **daemon**. An MCP server that will not stop must never be
the reason Minecraft does not exit. One host per game session — a second would start every
server twice.
