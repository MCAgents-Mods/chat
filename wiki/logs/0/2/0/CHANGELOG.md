# 0.2.0

Released: 2026-10-12

Chat ships a NeoForge jar beside the Fabric one, every module is published under an
`mcagents-chat-{module}` name, and releases now publish themselves to GitHub Packages. This is
the first chat release published as a package.

## Upgrading

* **Players: pick the jar for your loader.** `MCAgentsChatFabric-0.2.0.jar` needs Fabric API;
  `MCAgentsChatNeoforge-0.2.0.jar` is the NeoForge build. Either needs MCAgents core `0.5.0` or
  newer for the same loader. Data under `{minecraft_dir}/MCAgents/` is unchanged.
* **Anyone depending on chat's jars: change the coordinates.** Modules publish as
  `io.github.mcagentsmods:mcagents-chat-{api, common, website, shared, fabric, neoforge}`, from
  `https://maven.pkg.github.com/MCAgents-Mods/chat`, and the jars are named
  `MCAgentsChat{Module}`. GitHub Packages asks for a token with `read:packages` even for a
  public package.
* **Building chat without a core checkout beside it** now reads core from
  `https://maven.pkg.github.com/MCAgents-Mods/core`, so export `GITHUB_ACTOR` and a
  `GITHUB_TOKEN` with `read:packages` first. A build with `../core` needs neither.

## Added

* **A NeoForge jar**, `MCAgentsChatNeoforge`, built in `platforms/neoforge` with ModDevGradle
  on NeoForge 26.2.0.90. It registers the key on `RegisterKeyMappingsEvent`, nests
  `sqlite-jdbc` with `jarJar`, and requires `mcagents_core` at the core version it was built
  against. Not yet run in a real game.
* **`platforms/shared`** (`mcagents-chat-shared`), compiled against vanilla Minecraft alone,
  holding the screens, the key, the factories and `ChatRuntime`, the start-up each loader
  calls with its game directory.
* **`.github/workflows/publish.yml`.** On every push to `master` it checks for the
  `v{version}` tag; when it is missing it builds, publishes all six modules to GitHub
  Packages, and then tags the commit.

## Changed

* Every jar is named `MCAgentsChat{Module}` and every artifact `mcagents-chat-{module}`, and
  all six modules publish.
* Chat depends on core's renamed coordinates, `mcagents-core-api` and `mcagents-core-common`.
* `platforms/fabric` keeps only Fabric's entry point, key and event registration; the status
  line reads the bound key through vanilla `KeyMapping`.
* Without a `../core` checkout, the build resolves core from core's GitHub Packages
  repository instead of Maven Central, where core is not published.
