---
name: memory-state-repository-state
description: Current state of MCAgentsClient — what is built, what is not, and the next step. Overwritten in place, always current.
---

# Repository State

**Version:** 0.0.0 · **Default branch:** `master` · **Mode:** B (consumer)

This file is **state, not history**. It is overwritten, never appended to. What happened and
why belongs in `.agents/memory/tasks/` and in `wiki/logs/`.

## What works, confirmed in a real client

Run on Minecraft 26.2 against a live multiplayer server, and observed working:

* **The chat screen**, opened with `G`. Session list, search, conversation, prompt, Send.
* **The API key screen** — a key was pasted, stored, and listed masked.
* **The model settings screen** — both dropdowns, editable and selectable.
* **A full exchange.** The agent answers. OpenRouter is reached with the player's key and
  the reply is stored and rendered.
* **Storage.** `sqlite-jdbc` loads from the Jar-in-Jar nested jar on Windows and the
  database opens at `{minecraft_dir}/MCAgents/data.db`.

## What is built but unproven in a real client

* Renaming a conversation, and deleting one behind its confirmation.
* **The whole web console.** A loopback server the player starts from the chat screen,
  serving the knowledge library, the chat, and the API keys as Silver Glass pages. Driven in
  Chromium against a seeded store — paging, live search, filters, sending a message, adding
  and removing keys — but no screen of it has been opened in a real client. See
  `.agents/memory/tasks/web-console.md`.
* **The whole wallet feature.** Compile-verified against Minecraft 26.2 and covered by tests
  in `common/`, but no screen has been opened in a real client and **no signature from the
  real MetaMask extension has ever been checked** — the tests sign with their own secp256k1
  signer. The multi-address handshake makes that gap sharper: nothing has confirmed how many
  accounts a real extension shares by default, only that the mod binds however many it is
  given. The `/wallets/` page has been driven in Chromium. See
  `.agents/memory/tasks/wallet-binding.md` and
  `.agents/memory/tasks/wallet-accounts.md`.
* Key rotation, retry with backoff, and the failure messages for each `ModelException` kind
  — all covered by tests against a stub transport, none seen against real OpenRouter.
* Prompt caching. The breakpoints and the request JSON are verified; **no `cached_tokens`
  figure has ever been read back**, so whether the provider actually reuses the prefix is
  still unknown. `usage.include` is requested so the first person to look will see it.

## What does not exist

* Streaming. An answer arrives whole.
* Embedding and retrieval. The agent answers from the conversation only; downloaded
  Knowledge Packs are stored but never consulted.
* Any download path from the central service.
* The content browser.
* Anything that tells a new player the key is `G`.
* Any CI workflow.

## Architecture as built

* `api/` contracts, `common/` implementations, `platforms/fabric/` the mod. One-way
  dependency chain; no Minecraft in `api/` or `common/`.
* Every I/O method returns `CompletableFuture`. 282 tests, all in `common/`.
* Storage: SQLite, one connection on one daemon thread, `SQLiteDataSource` rather than
  `DriverManager`. `CachingContentRepository` wraps it.
* Model access: `OpenRouterClient` behind a swappable `HttpTransport`, wrapped by
  `CachingModelClient` (embeddings cached, completions deliberately not).
* Knowledge: published as text with no vector. `KnowledgeSync` downloads — the whole
  difference, or one pack by id — and `EmbeddingBackfill` gives stored packs a vector using
  the player's own embedding model, because a vector is only comparable inside the space of
  the model that made it. Startup fetches and version-checks `mcagents-info` and nothing
  else; the whole library is checked when `/chat/` opens, and one pack from its own page.
  **No button fetches a pack the player did not choose**: update brings stored packs up to
  date, download takes one pack, and refresh downloads nothing at all.
  **An update drops that pack's embedding** — new version, new text, so the old vector
  describes something that is no longer there — while re-downloading the same version keeps
  it.
* Retrieval: `KnowledgeRetriever` embeds the newest question, ranks stored packs by
  cosine similarity, and `PromptAssembler` places the best few after the last cache
  breakpoint and before the question. Governed by one setting,
  `model.embedding.enabled`, carried on `ModelPreferences` and shared by the in-game
  screen and the console. Off does no work at all rather than filtering afterwards, and
  a failed embedding call yields an ordinary answer rather than an error.
* Reading: long lists come back a window at a time through `Page` and `PageRequest`, with
  the total counted rather than inferred. Any ordering a window is taken from is total, or
  the reader sees rows twice and never sees others.
* The web console: `LocalWebConsole` on loopback, guarded by a per-run token and a loopback
  `Host` check, serving pages from `mcagents/web/` inside the jar. **Long-lived on purpose,
  unlike the wallet bridge** — do not change either to match the other. A chat write from
  either side moves one counter in `RevisionedChatRepository`, which is what keeps the game
  and the browser in step.
* Wallets: `player_wallets` and `wallet_platform_addresses` in the same store. One handshake
  proves **every** address the browser shares, each over its own message and single-use
  nonce, and each becomes a wallet of its own. `LocalWalletBridge` serves one page on
  `127.0.0.1` for the in-game path and stops when the page says it has finished; the
  console's `/wallets/` page needs no bridge, because it is already a page in the browser.
  `AccountWallets` is the only way into the store, and it checks that a wallet belongs to
  the account acting on it — the boundary is in the mod, never in the interface. Keccak-256
  and secp256k1 recovery are written out in `common.wallet` — the JDK's SHA3-256 is a
  different function and would recover the wrong address.

## Stack

Minecraft 26.2 · Fabric Loader 0.19.3 · Fabric API 0.157.0+26.2 · Fabric Loom 1.17.19 ·
Java 25 · Gradle 9.7.0 via the committed wrapper · group `io.github.mcagents` · base package
`io.github.mcagents.clientfabric`.

Facts that cost time to establish, and will again if forgotten:

* Minecraft 26.x has **no Yarn mappings and no intermediary**, and **no `remapJar` runs** —
  `jar` produces the final artifact.
* `implementation` in Loom bundles nothing; only `include` does. Gson is `compileOnly`
  because Minecraft provides it; `sqlite-jdbc` is the only bundled library. Fabric has no
  equivalent of Spigot's runtime Maven `libraries:`.
* The 26.x client GUI API is reworked — see `.agents/wiki/context/repository-map.md`.
* `gradle.properties` holds project identity only; every coordinate lives in
  `gradle/libs.versions.toml`.

## Shared instruction set

Resolved through the `lxagents-agents-base` MCP connector. Nothing from it is copied here;
the override table in `.agents/index/root-index.md` is empty.

## Reaching the central service

`CentralService` is the one class that talks to `mcagents.mcrealms.space`, which is what
makes "the mod works offline" checkable. `KnowledgeSync` compares published versions against
stored ones and downloads only the difference, on the refresh button, the update button, or
the one startup fetch of `mcagents-info`. A sleeping service is a state, not an error: the
console shows a banner, retries once a minute, and stops after ten.

**The live deployment answers, and a refresh from the console really downloads its catalog.**
It does not yet publish `mcagents-info` — the server branch adding it is pushed and not
deployed.

## Next step

**Play-test in a real client, and publish real embeddings.**

The server side is deployed: `mcagents-info` is served at version `0.0.0` and the catalog
answers with `RateLimit-*` headers.

Two things are outstanding, and the second is the more interesting.

1. Wallets — now a screen of account columns rather than one account behind a dropdown —
   the web console, the console button, and the embedding toggle on
   `ModelSettingsScreen` are compile-verified and unopened. `platforms/fabric` has no test
   source set, so nothing here can prove any of them. One session in a real client would
   settle them all, plus a binding through the real MetaMask extension and a message typed
   in game appearing in a browser tab. **A screenshot found two collisions on
   `WebConsoleScreen` that had survived several rounds** — the arithmetic behind that screen
   is under test now, in `common/ui/StackedRows`, but what a screen *looks* like still is
   not.
2. **No pack has been embedded against a real provider.** The harness carries fake keys, so
   the failure path is exercised and the success path is not. One press with a real key
   would settle it — and would be the first end-to-end proof that a downloaded pack can be
   retrieved against.

The `embeddedData: "[]"` gap recorded earlier is closed, and was never a content bug: the
service publishes text on purpose and the client embeds it. The placeholder `example-*`
packs are gone, replaced by five written from the README and wiki.

Then wire retrieval, so downloaded Knowledge Packs actually reach the prompt, and after that
the content browser and its download path against `MCAgents/server-fabric-expressjs`.
