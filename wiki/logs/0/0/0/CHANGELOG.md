# 0.0.0

Initial repository scaffold. No code yet — this version establishes the conventions,
documentation, and licensing the project is built under.

## Added

* **Agent instruction system.** Root `AGENTS.md` as the entry point, with the
  `lxagents-agents-base` connector bootstrap, the auto-activation contract, and the trigger
  table. The shared instruction set is resolved over the connector and never copied here.
* **Local instruction set.** `.agents/rules/repository.md` — the offline-first mandate, the
  three-module boundary, the rule that every I/O method returns `CompletableFuture<T>`,
  local data handling, and English as the repository's only language.
* **Index system.** `.agents/index/` with the root router and scope indexes for the
  instruction set, both wikis, memory, and the change logs.
* **Agent knowledge.** `.agents/wiki/context/repository-map.md` — orientation, the target
  platform versions, the commands, the generated paths, and the gotchas.
* **Memory seed.** `.agents/memory/state/repository-state.md` and
  `.agents/memory/tasks/agents-setup.md`.
* **Human documentation.** `wiki/information/overview.md`,
  `wiki/information/architecture.md`, and `wiki/environments/setup.md`.
* **License.** `LICENSE` — the MCAgents commercial license, copyright 2026 MCAgents.
  Proprietary; three categories of authorized licensee; publisher-verification warning for
  third-party distribution platforms.
* **README.** Rewritten as an overview pointing at the wiki.

## Notes

* Target platform recorded for the build that follows: Minecraft 26.2, Fabric Loader
  0.19.3, Fabric API 0.157.0+26.2, Java 25, group `io.github.mcagents`, base package
  `io.github.mcagents.clientfabric`.
* Minecraft 26.x publishes no Yarn mappings and no intermediary; Fabric Loom uses Mojang's
  official mappings. Verified against `meta.fabricmc.net`.
