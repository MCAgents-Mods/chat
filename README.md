# MCAgentsClient

A client-side only Minecraft mod for the Fabric loader that puts an AI assistant inside the
game. The player asks a question in an in-game chat screen; an LLM answers using the
player's own OpenRouter API key, the conversation so far, and whichever downloaded
knowledge is relevant. Nothing is installed on the Minecraft server.

## Features

* **In-game AI chat** with persistent, titled sessions and full history.
* **Knowledge Packs** — downloadable guides, server rules, and strategies, retrieved by
  embedding similarity so only the relevant part reaches the model.
* **A marketplace** for free and premium packs, hosted at `mcagents.mcrealms.space`.
* **Local-first storage** — sessions, API keys, and packs live in
  `{minecraft_dir}/MCAgents/data.db` on the player's machine.
* **Player-defined personas** in `{minecraft_dir}/MCAgents/AGENTS.md`.
* **Works offline.** The central service is a content source, not a dependency: with it
  unreachable, the mod still starts, opens, and uses everything already downloaded.

## Quick start

```sh
./gradlew build                      # build every module
./gradlew :mcagents-fabric:runClient # launch Minecraft with the mod
```

Builds Minecraft 26.2 with Fabric Loader 0.19.3 on a Java 25 toolchain. The mod jar is
written to `platforms/fabric/build/libs/`.

> The build is in place; the features above are being implemented on top of it.

## Documentation

* [Overview](wiki/information/overview.md) — what the mod is and how the pieces fit.
* [Architecture](wiki/information/architecture.md) — the three-module split and the
  asynchronous I/O rule.
* [Development setup](wiki/environments/setup.md) — toolchain, commands, runtime files.

The full documentation map is [`.agents/index/project-wiki-index.md`](.agents/index/project-wiki-index.md).

## Working with agents

Agents working in this repository start at [`AGENTS.md`](AGENTS.md). Shared organization
conventions are served by the `lxagents-agents-base` MCP connector and are not copied here.

## License

Proprietary and commercial. Copyright (c) 2026 MCAgents. Use is restricted to the three
categories of authorized licensee set out in [`LICENSE`](LICENSE); resale,
redistribution, reverse engineering, and claims of authorship are prohibited. Verify that
any download from CurseForge, Modrinth, or a similar platform is published by the MCAgents
organization before installing it.
