---
name: memory-task-mcp-host
description: The mod as an MCP client — the mcps layout, why discovery is split from connection, the qualified-name cap, and what is proven versus only compiled.
---

# MCP Host

## 2026-08-23

**Goal.** Let the mod run other people's MCP servers and offer their tools to the agent —
cloning into `{minecraft_dir}/MCAgents/mcps/{owner}/{repository}/`, discovering and
connecting without a game restart, and merging what they offer into one registry. Task 2 of
three; task 3 is the routing engine that turns a model's tool call into a call on one of
these sessions.

**Branch.** `feat/mcp-host`, stacked on `docs/mcp-connector-bootstrap` per
`{shared}/planning/task-workflow.md` §C, because task 3 will touch files this task creates.

## Decisions not to relitigate

**Discovery never starts anything.** `McpDiscovery` reads files and returns
`McpServerSpec`s; `WatchingMcpHost` starts them. That is why "what is installed" is testable
against a directory tree with no child process in the test at all, and why a wrong inference
is diagnosable without watching a server fail.

**A reload is a diff, not a restart.** `McpServerSpec.sameWiring` decides what changed, and
an unchanged server keeps its live process. The first design restarted everything on every
filesystem event, which drops working connections constantly — servers write inside their
own directories as a matter of course.

**The watcher is not recursive, and should not become so.** `WatchService` needs a key per
directory, so watching every checkout means a key per `node_modules` tree. Only the mcps
root and the owner directories are registered; the two-second settle window covers the rest
and also stops a reload landing mid-clone, when the repository is half-written.

**Tool names are `{server}__{tool}`, capped at 64 characters.** Not cosmetic: an
OpenAI-compatible provider rejects a function name outside `[A-Za-z0-9_-]{1,64}` and rejects
the *entire request* with it, so one bad name takes every other tool down. Truncation can
itself collide, which is why `McpContextRegistry.merge` checks — it is the only place that
sees every tool at once.

**Validation is doubled on purpose.** `RepositoryCoordinate` refuses traversal and non-https
URLs; `ClientPaths.mcpRepository` then refuses a resolved path outside the mcps root. The
second check is redundant today and stays anyway: these values come from a language model,
not from a person, and the failure mode is writing anywhere the game process can reach.

**git is shelled out to, not embedded.** A Java git implementation would be megabytes in a
jar already carrying SQLite's native binaries, for something nearly every machine can do.
A machine without git gets a sentence saying so.

**The mod's own transport, not the model client's.** The host holds its own
`JdkHttpTransport`. `HttpMcpSession.close()` deliberately does not close it, because it is
shared across every HTTP server.

## Things learned the hard way

**`join()` hides the reason.** `CompletableFuture.join` wraps failures in a
`CompletionException` whose message is the cause's class name plus its message, and that
string was going straight into a status row a player reads. Two tests caught it as a
`assertThrows(McpException…)` mismatch — the fix was `McpJson.await`, which unwraps at the
source, plus `reasonOf` unwrapping both `ExecutionException` and `CompletionException`.
**A test failing on an exception type was pointing at a message a player would have seen.**

**Escaping Java inside a Java string literal is not worth it.** The fake test server was
first written as a text block full of doubly-escaped quotes; an escaping mistake there fails
as a mysterious protocol error rather than a compile error. It now lives in
`common/src/test/resources/mcp/FakeServer.java.txt` and is read at test time.

**A test must change what it claims to change.** `aReloadReplacesASessionWhoseWiringChanged`
failed because the catalog entry it wrote was byte-identical to the discovered wiring, so
reuse was correct and the test was wrong. It now changes the environment.

## What is proven, and what is not

**Proven, by 57 tests in `common/`** — twelve of them against a real child process spawned
through the JVM's own source launcher:

* a server cloned while the host runs is connected and its tools appear, no restart;
* two servers offering the same tool name both survive, kept apart by their prefixes;
* a server that cannot start is a `FAILED` row while the others stay connected;
* an unchanged server keeps its process across a reload; a changed one is replaced and the
  old process stopped;
* a server removed from disk is disconnected and its process stopped;
* a stray non-JSON line on a server's stdout does not derail the session;
* the HTTP path, against a scripted transport: plain JSON and `text/event-stream`, tool
  errors as results rather than failures, and 401 and 404 producing different advice.

**Not proven.** Nothing has run inside Minecraft. `McpHostFactory` compiles and is wired
into `MCAgentsClientMod`, but no game has started it — the lesson from
`.agents/memory/tasks/gui-play-testing.md` applies, and a green Java suite says nothing
about the mod loading. No real third-party MCP server has been connected either; the fake
server implements the protocol as documented, which is not the same as what servers do.

**Deliberately absent.** No long-lived HTTP stream, so a server pushing unsolicited
notifications is not heard — the registry refreshes on reload and on the directory watch
instead. Adding one would mean a thread per server for a signal nothing consumes yet.

## Next

Task 3, `feat/mcp-routing`: parse the model's tool calls, route each to the session that owns
it via `McpHost.session(serverId)`, format results back into the loop. The registry is
already an immutable snapshot so a request and the calls it produces see the same tool set.
The cloner is built but not yet offered to the model as a callable tool — that belongs with
the routing work.
