# 0.15.0

Released: 2026-10-22

Every file chat keeps is now in its own folder, `MCAgents/chat/`, beside core's
`MCAgents/core/`, as every MCAgents mod keeps its files in `MCAgents/{mod}/`. The first start
after the update moves the old files there, so no chat, key, persona or MCP server is lost.

## Upgrading

- **Players: replace the chat jar and update core to 0.14.0.** The `v0.15.0` release carries
  `MCAgentsChatFabric-0.15.0+26.3.jar`, `MCAgentsChatNeoforge-0.15.0+26.3.jar` and
  `MCAgentsChatForge-0.15.0+26.3.jar`; every manifest now asks for core 0.14.0 or newer. On the
  first start chat moves `MCAgents/data.db` (with its `-wal` and `-shm` files),
  `MCAgents/AGENTS.md` and `MCAgents/mcps/` into `MCAgents/chat/`, and core moves its own files
  into `MCAgents/core/`. A file already in the new place is never overwritten; its old copy is
  then left where it was. A cloned MCP server keeps working from its new folder.
- **Nothing to change in a build that depends on chat.** `MCAgents.MOD_DIRECTORY` (`chat`) is
  new, and `ClientPaths` resolves the new folder; artifact ids are unchanged.

## Changed

- **Chat's files live in `MCAgents/chat/`:** `data.db`, `AGENTS.md`, `mcps/{owner}/{repository}/`
  and `mcps/mcp_servers.json`. `ClientPaths.dataDirectory()` is `{game_dir}/MCAgents/chat`.
- **Core 0.14.0.** Chat compiles against it and requires it, for its `ModFiles` and for the
  global model's new place, `MCAgents/core/models.properties`.
- **The default persona** tells the player its file is `MCAgents/chat/AGENTS.md`.

## Added

- **`ClientPaths.moveLegacyFiles()`** and **`legacyDirectory()`**, which `ChatRuntime.start`
  calls before anything opens a file. The database's journal files move only together with it.
  Chat also moves core's files before reading the global model, in case it starts before core.
