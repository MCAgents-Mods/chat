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
* **The whole wallet feature.** Compile-verified against Minecraft 26.2 and covered by 55
  tests in `common/`, but no screen has been opened in a real client and no signature from
  the real MetaMask extension has ever been checked — the tests sign with their own
  secp256k1 signer. See `.agents/memory/tasks/wallet-binding.md`.
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
* Every I/O method returns `CompletableFuture`. 197 tests, all in `common/`.
* Storage: SQLite, one connection on one daemon thread, `SQLiteDataSource` rather than
  `DriverManager`. `CachingContentRepository` wraps it.
* Model access: `OpenRouterClient` behind a swappable `HttpTransport`, wrapped by
  `CachingModelClient` (embeddings cached, completions deliberately not).
* Reading: long lists come back a window at a time through `Page` and `PageRequest`, with
  the total counted rather than inferred. Any ordering a window is taken from is total, or
  the reader sees rows twice and never sees others.
* The web console: `LocalWebConsole` on loopback, guarded by a per-run token and a loopback
  `Host` check, serving pages from `mcagents/web/` inside the jar. **Long-lived on purpose,
  unlike the wallet bridge** — do not change either to match the other. A chat write from
  either side moves one counter in `RevisionedChatRepository`, which is what keeps the game
  and the browser in step.
* Wallets: `player_wallets` and `wallet_platform_addresses` in the same store;
  `LocalWalletBridge` serves one page on `127.0.0.1` and stops the moment a signature
  verifies. Keccak-256 and secp256k1 recovery are written out in `common.wallet` — the
  JDK's SHA3-256 is a different function and would recover the wrong address.

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

## Next step

**Play-test what has accumulated.** Three features — wallets, the web console, and the
console button on the chat screen — are compile-verified and unopened. One session in a real
client would settle all of them, and would run the one thing nothing here can prove: a
binding through the real MetaMask extension, and a message typed in game appearing in a
browser tab.

Then wire retrieval, so downloaded Knowledge Packs actually reach the prompt, and after that
the content browser and its download path against `MCAgents/server-fabric-expressjs`.
