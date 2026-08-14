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
* Every I/O method returns `CompletableFuture`. 146 tests, all in `common/`.
* Storage: SQLite, one connection on one daemon thread, `SQLiteDataSource` rather than
  `DriverManager`. `CachingContentRepository` wraps it.
* Model access: `OpenRouterClient` behind a swappable `HttpTransport`, wrapped by
  `CachingModelClient` (embeddings cached, completions deliberately not).
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

Open the wallet screens in a real client and run one binding through the real MetaMask
extension — the one thing about that feature nothing here can prove. Then wire retrieval, so
downloaded Knowledge Packs actually reach the prompt, and after that the content browser and
its download path against `MCAgents/server-fabric-expressjs`.
