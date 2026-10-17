# Architecture

MCAgentsClient is split into six Gradle modules with a one-way dependency chain. The
split exists so that the logic that matters — storage, retrieval, the model client — can be
compiled, tested, and reasoned about without Minecraft anywhere near it.

```
  api/                      contracts only
   ^   ^   ^
   |   |   |
common/  |  |               implementations, no Minecraft
   ^     |  |
   |     |  |
platforms/shared/           the mod on vanilla Minecraft: start-up, the chat hologram, the menu buttons
   ^               ^                    ^
   |               |                    |
platforms/fabric/  platforms/neoforge/  platforms/forge/    each loader's entry point, the
                                                            buttons, events, paths — one
                                                            jar per loader
```

| Directory | Gradle module | Depends on | Contains |
|---|---|---|---|
| `api/` | `chat-api` | the JDK only | Interfaces, records, enums, abstract classes. No implementation, no I/O, no third-party libraries. |
| `common/` | `chat-common` | `api/` | SQLite access, the OpenRouter client, embedding and retrieval, the wallet cryptography, the MCP host that runs external servers, and `ChatSessions` and `ChatConversation`, what the chat holograms show and do. Plain Java. |
| `platforms/shared/` | `chat-shared` | all of the above, vanilla Minecraft | `ChatRuntime` (the client start-up every loader calls), the chat hologram, the page holograms (keys, models, persona, wallets) and their dialogs, chat's buttons on core's agents menu, the factories that wire `common/` together. No loader type. |
| `platforms/fabric/` | `chat-fabric` | all of the above, Fabric | `MCAgentsClientMod`: adds chat's menu buttons, registers the connection events, and passes the game directory to `ChatRuntime`. |
| `platforms/neoforge/` | `chat-neoforge` | all of the above, NeoForge | `MCAgentsNeoForgeClient`, client distribution only: the same registrations through NeoForge's events, and `FMLPaths.GAMEDIR`. |
| `platforms/forge/` | `chat-forge` | all of the above, Forge | `MCAgentsForgeMod`, which starts `MCAgentsForgeClient` on the client only: the same registrations through Forge's events, and `FMLPaths.GAMEDIR`. Its version folder keeps the `pack.mcmeta`, which Forge does not make for a mod. |

The directory names and the Gradle module names differ on purpose: `settings.gradle` maps
`chat-api` to `api/`, `chat-common` to `common/`,
and `chat-{module}` to `platforms/{module}/{minecraft}/`, the subproject
of `shared`, `fabric`, `neoforge` and `forge` for the one Minecraft version chat supports, the
latest release. Each of those four is a folder of shared sources, `platforms/{module}/src/`,
which the version subproject compiles together with its own `src/`, holding only what is
particular to that version; see
[`../environments/setup.md`](../environments/setup.md#the-minecraft-version). A
loader module is a few lines on top of `platforms/shared/`, so another loader is one more
folder under `platforms/`.

`platforms/shared/` is built with Loom for the vanilla game alone. Minecraft 26.x ships
unobfuscated, so the classes it compiles link against any loader's game.

The Gradle module name is also the Maven artifact id, so every library module publishes as
`io.github.mcagentsmods:chat-{module}`; the loader modules publish nothing, and their jars go
to the GitHub release. Every module's jar is named `MCAgentsChat{Module}-{version}.jar` by its
own `archivesName`, or `MCAgentsChat{Module}-{version}+{minecraft}.jar` for a version
subproject. The `chat-` prefix keeps chat's modules apart from core's, which share the group.
Up to 0.7.0 every id started with `mcagents-`, as in `mcagents-chat-common`, and the loader
modules were published too.

Before 0.4.0 another module, `website/`, served chat's pages in a browser through core's web
console. Both are gone: everything the player does with chat happens in the game.

Each loader module merges `api/`, `common/` and `platforms/shared/` into its mod
jar rather than nesting them as separate mods, so the split is a build-time and reading-time boundary; the shipped
jar is unchanged by it.

## Where external MCP servers sit

The mod is an MCP **client**: it runs servers other people wrote and offers their tools to
the agent. Player-facing documentation is [`mcp-servers.md`](mcp-servers.md).

It obeys the same split as everything else. `api/` holds the contracts — `McpHost`,
`McpSession` and the descriptors. `common/` holds the implementations: discovery, the stdio
and HTTP transports, the directory watch, the git cloner, and `McpTools`, which registers
every server's tools in core's tool registry and resolves a tool name back to the server that
owns it. `platforms/shared/` owns the host's lifetime, as it does for storage,
from the game directory the loader module hands it.

Two consequences are worth stating because they are easy to undo:

**An external server is a child process, and it is still below the Minecraft line.** The
host lives in `common/`, so it is testable by spawning real servers with no game running —
which is how it is tested. Nothing about a server may need a Minecraft type.

**Nothing about MCP may fail the mod.** A server that will not start becomes a status row
and the rest carry on. This is the offline-first rule of this document applied to an
optional thing: an MCP server is not allowed onto the path of anything local.

## Where core sits

Chat is built on MCAgents core, which sends the requests and owns tool calling — the registry
every mod adds its tools to, the agent that picks categories, then tools, then answers, the
worker limit, and the menu `G` shows. Up to chat 0.8.0 tool calling was a separate mod,
MCAgents agents; from core 0.10.0 it is part of core. Core is not bundled: `common/` compiles
against it and the `mcagents_core` mod provides it in game. Core also draws the
holograms: chat describes the Chat Sessions list and each conversation's two panels with
core's hologram records, and core places them side by side, takes the clicks and the typing,
and closes them when the player walks away.

Chat keeps what only it knows. `OpenRouterAgent` assembles the persona, where the player is,
the earlier turns and the Knowledge Packs, and hands that conversation to core's
agent with the newest question, which is what the tools are picked by. The MCP servers'
tools reach that agent the way any mod's do: `McpTools` registers them, one category per
server.

## Rules the split enforces

* **`api/` never implements.** If a type in `api/` has a method body doing real work, it
  belongs in `common/`.
* **`common/` never sees Minecraft.** No `net.minecraft.*`, no `net.fabricmc.*`. When
  `common/` needs a value that only the game knows — the game directory, the player's
  inventory, the block being looked at — that value arrives through an `api/` contract that
  `platforms/shared/` implements.
* **`platforms/` never re-implements.** It wires, renders, and translates. Logic lives in
  `common/`, and a loader module holds only what `platforms/shared/` cannot: that loader's
  entry point, registration and events.

## Test doubles shared between modules

A Gradle test source set is private to its own project, so a double two modules need cannot
simply be imported across. `common/` carries `java-test-fixtures` and keeps those doubles in
`src/testFixtures/` — currently `TestSigner`, which signs the way MetaMask would.
`StubTransport` and `StubKeySource`, which answer HTTP from a script and hand out keys, come
from MCAgents core's own test fixtures, because the client they drive is core's.

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

The chat hologram reads `ChatSessions`, the list of conversations, and its own
`ChatConversation`, one conversation's messages and waiting question, every frame, so both
only ever answer from what they already hold. Each action starts the work and hands the result
back through the executor `ChatSessions` was built with — the game's own, in game — and the
next frame shows it. That executor is also why both are tested without Minecraft: a test hands
it one that runs each result at once.

## Data flow of one question

1. The player types a prompt on the chat hologram (`platforms/shared/`), and its
   `ChatConversation` stores it in that conversation (`common/`).
2. The prompt is embedded and matched against the stored Knowledge Pack embeddings
   (`common/`), returning only the closest content.
3. The persona and instructions from `{minecraft_dir}/MCAgents/AGENTS.md`, where the player
   is playing, the session's prior messages from the local database, the retrieved content,
   and the prompt are assembled into a request (`common/`).
4. The request goes to the provider picked for the model on the Models hologram — OpenRouter,
   a platform's own API, or one the player added — with the player's key for it. The
   conversation panel says `Thinking…` while the future is pending.
5. The answer is written to the local database as the next message in the session and
   rendered.

Steps 1, 3, and 5 work with no internet. Step 2 works with whatever packs are
stored. Only step 4 needs a model provider.

### Which provider a request reaches

A stored model id is `{platform}/{model}` — the shape OpenRouter itself uses — and beside it
the player picks the provider it is called through: core's `ModelChoice`, kept in core's
global model (`{minecraft_dir}/MCAgents/agents/models.properties`), which every task without a
model of its own falls back to. The request goes to that provider at its address — the one a
custom provider gives, else the one the mod ships — and nowhere else. A direct provider with
no key stored fails asking for one; it is never quietly sent through OpenRouter. A player who
configures nothing gets OpenRouter, as before. Chat's own older settings move into the global
model once, on the first start of 0.12.0.

A conversation may choose its own chat model and embedding model, each with its provider,
with MODEL on its chat hologram. They are stored on the conversation's row; one it did not
choose is the global model, read again for every question, so a conversation left on the
global model follows the Models page at once and one with its own keeps it. This is core's one
fallback rule, `ModelChoice.orElse(own, global)`.

The difference on the wire is small and total: the gateway is sent the whole
`openai/gpt-4o-mini`, a platform is sent `gpt-4o-mini` (or the whole name, when the model is
another platform's, as on a host like Together), and the gateway's own extensions —
`usage.include`, the cache-control marker, the attribution headers — are left out of a direct
call, because a platform's API rejects the whole request over an argument it does not know.

## The player's own two inputs

Two things in that request come from the player rather than from a database, and both cross
the module boundary the same way — as a contract in `api/` that `platforms/shared`
implements or supplies.

**The persona.** `PersonaStore` is `AGENTS.md`, and `FilePersonaStore` in `common/` is the
only thing that reads or writes it. The mod writes a starting file the first time it runs,
so a player who has never heard of the file still has one to edit; after that it is theirs,
and nothing in the mod rewrites it. It can be edited from the in-game PERSONA hologram or in a
text editor, and both agree because **there is one store per game session** and every page is
handed the same instance. It caches the text and
re-reads whenever the file's timestamp or size has moved, which is what keeps the text
editor working without a disk read per question.

**Where the player is.** `EnvironmentSource` answers "single player, a server, or not in a
world", and `platforms/shared` records the answer from the connection events each loader
forwards to it. It
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
AES-GCM when `MCAGENTS_CHAT_TOKEN_ENCRYPT_KEY` is set in the environment — under a
fresh nonce each write, so one key stored twice leaves two unrelated rows. The encryption key
is never in the jar, the repository, or the database. With the variable unset, keys are stored
as the player pasted them.

The schema is created idempotently at startup. The game directory comes from the loader
module; `common/` is handed a path and knows nothing about Minecraft.

## The wallet bridge

Binding a MetaMask wallet is the one place the mod listens rather than asks. MetaMask is a
browser extension, so `window.ethereum` exists on a web page and nowhere else — there is no
Java API to call. For the length of one handshake the mod serves a page on `127.0.0.1`, the
page has MetaMask sign a one-off message, and the mod recovers the signing address from the
signature rather than trusting the address the page claims.

**The listener stops the moment the signature verifies**, and on cancel, on timeout, and
whenever the Connect MetaMask hologram closes. It binds to loopback on an operating-system-assigned port and
answers only requests carrying a one-time token, so nothing off the machine can reach it and
nothing on it can guess its way in. It never touches the network, so offline-first is
untouched: wallets already bound stay readable with no internet at all.

The cryptography — Keccak-256 and secp256k1 public key recovery — is written out in
`common/` rather than pulled from a library, because a general-purpose cryptography jar
would be megabytes shipped to every player for one hash and one curve operation, and the
JDK's SHA3-256 is a *different function* that would recover the wrong address without
failing. See [`wallet-binding.md`](wallet-binding.md).
