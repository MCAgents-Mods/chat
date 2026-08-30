# Architecture

MCAgentsClient is split into four Gradle modules with a one-way dependency chain. The
split exists so that the logic that matters — storage, retrieval, the model client — can be
compiled, tested, and reasoned about without Minecraft anywhere near it.

```
  api/                      contracts only
   ^   ^   ^
   |   |   |
common/  |  |               implementations, no Minecraft
   ^     |  |
   |     |  |
website/ |  |               everything a browser sees
   ^     |  |
   |     |  |
platforms/fabric/           the mod: GUI, mixins, events, paths
```

| Directory | Gradle module | Depends on | Contains |
|---|---|---|---|
| `api/` | `mcagents-fabric-api` | the JDK only | Interfaces, records, enums, abstract classes. No implementation, no I/O, no third-party libraries. |
| `common/` | `mcagents-fabric-common` | `api/` | SQLite access, the OpenRouter client, embedding and retrieval, the wallet cryptography, and the MCP host that runs external servers. Plain Java. |
| `website/` | `mcagents-fabric-website` | `api/`, `common/` | The loopback console: its server, its routes, the JSON it writes, and the pages under `resources/mcagents/web/`. |
| `platforms/fabric/` | `mcagents-fabric` | all of the above | Mod initializer, screens, key bindings, mixins, event listeners, game-directory resolution. |

The directory names and the Gradle module names differ on purpose: `settings.gradle` maps
`mcagents-fabric-api` to `api/`, `mcagents-fabric-common` to `common/`,
`mcagents-fabric-website` to `website/`, and `mcagents-fabric` to `platforms/fabric/`. The
`platforms/` folder is where a second loader — Forge, NeoForge, Quilt — would go without
disturbing anything else.

### Why the console is a module and not a package

It was a package in `common/`, sitting beside storage, the model client and the wallet
cryptography — four unrelated purposes sharing a module because they happened to share one
constraint, that none of them needs Minecraft. "Does not need Minecraft" is not a purpose.

It is a module because it has a boundary worth naming: **everything a browser ever sees,
and nothing else.** Moving it cost nothing to untangle, because nothing in `common/`
outside it had ever referenced it — which is the sign that it was always a separate thing.

It sits *above* `common/`, not beside it. Serving a page means reading storage, driving the
agent, syncing knowledge, and filing wallets, so the console depends on those
implementations. Nothing in `common/` may depend back on it, and the day something wants to
is the day that logic belongs in `common/` instead.

**`WebConsole` stays in `api/`** while `LocalWebConsole` lives in `website/`. A contract
that follows its implementation out of the contracts module is a rule that has quietly
stopped applying.

`platforms/fabric` merges all three lower modules into the mod jar rather than nesting them
as separate Fabric mods, so the split is a build-time and reading-time boundary; the shipped
jar is unchanged by it.

## Where external MCP servers sit

The mod is an MCP **client**: it runs servers other people wrote and offers their tools to
the agent. Player-facing documentation is [`mcp-servers.md`](mcp-servers.md).

It obeys the same split as everything else. `api/` holds the contracts — `McpHost`,
`McpSession`, the descriptors, and the tool-calling types the agent loop speaks.
`common/` holds the implementations: discovery, the stdio and HTTP transports, the
directory watch, the git cloner, and the router that resolves a tool name back to the
server that owns it. `platforms/fabric/` resolves the game directory and owns the host's
lifetime, as it does for storage.

Two consequences are worth stating because they are easy to undo:

**An external server is a child process, and it is still below the Minecraft line.** The
host lives in `common/`, so it is testable by spawning real servers with no game running —
which is how it is tested. Nothing about a server may need a Minecraft type.

**Nothing about MCP may fail the mod.** A server that will not start becomes a status row
and the rest carry on. This is the offline-first rule of this document applied to a second
optional thing: the central service is one, an MCP server is another, and neither is
allowed onto the path of anything local.

## Rules the split enforces

* **`api/` never implements.** If a type in `api/` has a method body doing real work, it
  belongs in `common/`.
* **`common/` never sees Minecraft.** No `net.minecraft.*`, no `net.fabricmc.*`. When
  `common/` needs a value that only the game knows — the game directory, the player's
  inventory, the block being looked at — that value arrives through an `api/` contract that
  `platforms/fabric/` implements.
* **`website/` never sees Minecraft either**, and `common/` never sees `website/`. The
  arrow points one way: a page may read anything below it, and nothing below it knows a
  browser exists.
* **`platforms/fabric/` never re-implements.** It wires, renders, and translates. Logic
  lives one layer down.

## Test doubles shared between modules

A Gradle test source set is private to its own project, so a double two modules need cannot
simply be imported across. `common/` carries `java-test-fixtures` and keeps those doubles in
`src/testFixtures/` — currently `StubTransport`, which answers HTTP from a script, and
`TestSigner`, which signs the way MetaMask would.

The alternative is a copy in each module, and a duplicated double drifts until the two are
testing against different fictions.

## Everything that does I/O is asynchronous

Minecraft renders on the client thread. A blocking database read or an HTTP call on that
thread freezes the game.

**Every `api/` method that touches the database, the network, or the filesystem returns
`CompletableFuture<T>`.** There is no synchronous variant, not even a convenience one —
a convenience method is how blocking calls get back in.

`common/` runs the work on its own executor. GUI code attaches a callback and hands the
result back to the client thread before touching any Minecraft state. `join()`, `get()`,
and `getNow()` are never called from render or tick code.

## Data flow of one question

1. The player types a prompt in the chat screen (`platforms/fabric/`).
2. The prompt is embedded and matched against the stored Knowledge Pack embeddings
   (`common/`), returning only the closest content.
3. The persona and instructions from `{minecraft_dir}/MCAgents/AGENTS.md`, where the player
   is playing, the session's prior messages from the local database, the retrieved content,
   and the prompt are assembled into a request (`common/`).
4. The request goes to OpenRouter with the player's key — or straight to the platform's own
   API, if the player has stored a key for the platform the model belongs to. The screen
   shows an animated waiting state while the future is pending.
5. The answer is written to the local database as the next message in the session and
   rendered.

Steps 1, 3, and 5 work with no internet. Step 2 works with whatever was already
downloaded. Only step 4 needs a model provider, and only downloading *new* packs needs the
central service.

### Which provider a request reaches

A stored model id is `{platform}/{model}` — the shape OpenRouter itself uses — so the same
stored value serves both routes. A request goes **directly** to a platform when two things
are true at once: an address is known for that platform, and the player has stored a key for
it. Otherwise it goes through OpenRouter, which is what every request did before direct
routes existed, and what a player who configures nothing keeps getting.

The difference on the wire is small and total: the gateway is sent the whole
`openai/gpt-4o-mini`, a platform is sent `gpt-4o-mini`, and the gateway's own extensions —
`usage.include`, the cache-control marker, the attribution headers — are left out of a direct
call, because a platform's API rejects the whole request over an argument it does not know.

## The player's own two inputs

Two things in that request come from the player rather than from a database, and both cross
the module boundary the same way — as a contract in `api/` that `platforms/fabric`
implements or supplies.

**The persona.** `PersonaStore` is `AGENTS.md`, and `FilePersonaStore` in `common/` is the
only thing that reads or writes it. The mod writes a starting file the first time it runs,
so a player who has never heard of the file still has one to edit; after that it is theirs,
and nothing in the mod rewrites it. It can be edited from the in-game screen, from the
console's chat page, or in a text editor, and all three agree because **there is one store
per game session** and every screen is handed the same instance. It caches the text and
re-reads whenever the file's timestamp or size has moved, which is what keeps the text
editor working without a disk read per question.

**Where the player is.** `EnvironmentSource` answers "single player, a server, or not in a
world", and `platforms/fabric` records the answer from the game's connection events. It
reaches the model as one short system message after the persona rather than inside it: the
persona is the player's file, and it is also the cached prefix of every request — a player
walking from one server to another should not invalidate it. Single player is reported as
one exact sentence, `Player is playing in single player.`, because the generated `AGENTS.md`
quotes that sentence back and instructions written against it have to keep matching.

Nothing else about the game is sent. Inventory, coordinates, and chat logs stay on the
machine.

## Local database

SQLite at `{minecraft_dir}/MCAgents/data.db`, via `org.xerial:sqlite-jdbc`. Eight tables:
`api_keys`, `content_categories`, `contents`, `chat_sessions`, `chat_messages`, `settings`,
`player_wallets`, and `wallet_platform_addresses`. Chat
messages are stored one row per turn with a `role` of `user` or `assistant`, which is the
shape the OpenRouter API expects, so the history needs no reshaping before it is sent.

`api_keys` carries the platform each key opens, and the key itself is encrypted with
AES-GCM when `MCAGENTS_CLIENTFABRIC_TOKEN_ENCRYPT_KEY` is set in the environment — under a
fresh nonce each write, so one key stored twice leaves two unrelated rows. The encryption key
is never in the jar, the repository, or the database. With the variable unset, keys are stored
as the player pasted them.

The schema is created idempotently at startup. The game directory is resolved in
`platforms/fabric/`; `common/` is handed a path and knows nothing about Minecraft.

## The wallet bridge

Binding a MetaMask wallet is the one place the mod listens rather than asks. MetaMask is a
browser extension, so `window.ethereum` exists on a web page and nowhere else — there is no
Java API to call. For the length of one handshake the mod serves a page on `127.0.0.1`, the
page has MetaMask sign a one-off message, and the mod recovers the signing address from the
signature rather than trusting the address the page claims.

**The listener stops the moment the signature verifies**, and on cancel, on timeout, and
whenever the screen closes. It binds to loopback on an operating-system-assigned port and
answers only requests carrying a one-time token, so nothing off the machine can reach it and
nothing on it can guess its way in. It never touches the network, so offline-first is
untouched: wallets already bound stay readable with no internet at all.

The cryptography — Keccak-256 and secp256k1 public key recovery — is written out in
`common/` rather than pulled from a library, because a general-purpose cryptography jar
would be megabytes shipped to every player for one hash and one curve operation, and the
JDK's SHA3-256 is a *different function* that would recover the wrong address without
failing. See [`wallet-binding.md`](wallet-binding.md).

## The central service

`mcagents.mcrealms.space`, implemented in `MCAgents/server-fabric-expressjs`, serves the
Knowledge Pack catalog and content. The client downloads a pack and writes it straight into
`contents` — there is no intermediate pack file on disk to manage.

**It is optional at every point.** The mod is designed so that a player who never reaches
it has a working mod with an empty content library, and a player who reaches it once has a
working mod with a full library forever after.
