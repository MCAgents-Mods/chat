# Architecture

MCAgentsClient is split into three Gradle modules with a one-way dependency chain. The
split exists so that the logic that matters — storage, retrieval, the model client — can be
compiled, tested, and reasoned about without Minecraft anywhere near it.

```
  api/                      contracts only
   ^        ^
   |        |
common/     |               implementations, no Minecraft
   ^        |
   |        |
platforms/fabric/           the mod: GUI, mixins, events, paths
```

| Directory | Gradle module | Depends on | Contains |
|---|---|---|---|
| `api/` | `mcagents-fabric-api` | the JDK only | Interfaces, records, enums, abstract classes. No implementation, no I/O, no third-party libraries. |
| `common/` | `mcagents-fabric-common` | `api/` | SQLite access, the OpenRouter client, embedding and retrieval. Plain Java. |
| `platforms/fabric/` | `mcagents-fabric` | `api/`, `common/` | Mod initializer, screens, key bindings, mixins, event listeners, game-directory resolution. |

The directory names and the Gradle module names differ on purpose: `settings.gradle` maps
`mcagents-fabric-api` to `api/`, `mcagents-fabric-common` to `common/`, and
`mcagents-fabric` to `platforms/fabric/`. The `platforms/` folder is where a second loader
— Forge, NeoForge, Quilt — would go without disturbing anything else.

## Rules the split enforces

* **`api/` never implements.** If a type in `api/` has a method body doing real work, it
  belongs in `common/`.
* **`common/` never sees Minecraft.** No `net.minecraft.*`, no `net.fabricmc.*`. When
  `common/` needs a value that only the game knows — the game directory, the player's
  inventory, the block being looked at — that value arrives through an `api/` contract that
  `platforms/fabric/` implements.
* **`platforms/fabric/` never re-implements.** It wires, renders, and translates. Logic
  lives one layer down.

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
3. The persona and instructions from `{minecraft_dir}/MCAgents/AGENTS.md`, the session's
   prior messages from the local database, the retrieved content, and the prompt are
   assembled into a request (`common/`).
4. The request goes to OpenRouter with the player's key. The screen shows an animated
   waiting state while the future is pending.
5. The answer is written to the local database as the next message in the session and
   rendered.

Steps 1, 3, and 5 work with no internet. Step 2 works with whatever was already
downloaded. Only step 4 needs OpenRouter, and only downloading *new* packs needs the
central service.

## Local database

SQLite at `{minecraft_dir}/MCAgents/data.db`, via `org.xerial:sqlite-jdbc`. Eight tables:
`api_keys`, `content_categories`, `contents`, `chat_sessions`, `chat_messages`, `settings`,
`player_wallets`, and `wallet_platform_addresses`. Chat
messages are stored one row per turn with a `role` of `user` or `assistant`, which is the
shape the OpenRouter API expects, so the history needs no reshaping before it is sent.

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
