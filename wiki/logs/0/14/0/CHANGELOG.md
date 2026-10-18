# 0.14.0

Released: 2026-10-18

The mod jars no longer carry SQLite. They shrink from about 12.3 MB to about 360 KB, and a game
without an SQLite driver now tells the player chat cannot save anything.

## Upgrading

- **Players: chat needs SQLite from elsewhere.** The `v0.14.0` release carries
  `MCAgentsChatFabric-0.14.0+26.3.jar`, `MCAgentsChatNeoforge-0.14.0+26.3.jar` and
  `MCAgentsChatForge-0.14.0+26.3.jar`, none of which nests `sqlite-jdbc`. Minecraft and the
  loaders do not provide it, and a plain `sqlite-jdbc` jar in `mods/` is skipped as not a mod,
  so until another mod provides `org.sqlite`, chat history, API keys and packs cannot be stored:
  the CHAT hologram and the menu buttons say "SQLite is not installed, so chat cannot save
  anything." The game does not crash. It still needs core 0.13.0 or newer beside it.
- **Nothing to change in a build that depends on chat.** Artifact ids and the published modules
  are unchanged; `chat-common` already only compiled against `sqlite-jdbc`.

## Changed

- **No jar bundles `sqlite-jdbc`.** The Fabric module drops `include libs.sqlite.jdbc`, and the
  NeoForge and Forge modules drop `jarJar libs.sqlite.jdbc`. It is declared only with
  `compileOnly` in `common/` (with `testImplementation` for its tests) and with `runtimeOnly` in
  each loader module, so `runClient` still opens the database.
- **Forge's Jar-in-Jar plugin is gone.** It existed only to nest SQLite: the plain `jar` task
  builds `MCAgentsChatForge-{version}+26.3.jar` again, with no `build/slim/` jar beside it, and
  the catalog drops `forge-jarjar`.

## Added

- **A storage state for a missing driver.** `ChatRuntime.StorageState.NO_SQLITE` is entered
  when opening the database fails because `org.sqlite` classes are missing, and the holograms
  and the action bar say "SQLite is not installed, so chat cannot save anything." instead of a
  cut-off class name.
