# 0.0.0

Initial repository scaffold — the conventions, documentation, licensing, and build the
project is developed under.

## Added

* **Multi-module Gradle build.** `mcagents-fabric-api` (`api/`),
  `mcagents-fabric-common` (`common/`), `mcagents-fabric-website` (`website/`), and
  `mcagents-fabric` (`platforms/fabric/`), wired by `settings.gradle` and built with Fabric
  Loom 1.17.19 on Gradle 9.7.0 and a Java 25 toolchain. `./gradlew build` produces a
  loadable mod jar carrying every module's classes and resources, `fabric.mod.json`, and
  the license.
* **Version catalog.** `gradle/libs.versions.toml` pins Minecraft 26.2, Fabric Loader
  0.19.3, Fabric API 0.157.0+26.2, Fabric Loom 1.17.19, sqlite-jdbc 3.53.2.1, and Java 25.
  `gradle.properties` carries the project's identity only.
* **First classes.** `MCAgents` — mod identity and the layout it owns on disk;
  `ClientPaths` — resolves that layout from a game directory it is handed, with no
  knowledge of Minecraft; `MCAgentsClientMod` — the `ClientModInitializer`, which resolves
  the game directory once at startup and contacts nothing over the network.
* **Local storage.** The whole SQLite layer at `{minecraft_dir}/MCAgents/data.db`:
  * `api/` — the `ApiKey`, `ChatSession`, `ChatMessage`, `ContentCategory`,
    `KnowledgeContent`, and `ContentQuery` records, and the `LocalDatabase`,
    `ApiKeyRepository`, `ChatRepository`, and `ContentRepository` contracts. Every method
    that touches storage returns a `CompletableFuture`, with no synchronous variant.
  * `common/` — the SQLite implementation, on one connection driven by one daemon thread,
    with the schema applied idempotently at startup, foreign keys enforced so a deleted
    conversation takes its messages with it, and search text escaped before it reaches a
    `LIKE` clause.
  * `platforms/fabric/` — the mod opens the database asynchronously and carries on if it
    cannot; a player whose storage is unavailable keeps a working game and gets a logged
    explanation.
  * 33 tests in `common/`, run with `./gradlew :mcagents-fabric-common:test`.
* **`org.xerial:sqlite-jdbc` 3.53.2.1**, bundled into the mod jar with Loom's `include` so
  the mod stores data on a machine that has never been online.
* **The chat screen**, opened with `G`:
  * a session list on the left with a search box and a "new chat" button, a conversation
    on the right, and a prompt box with Send along the bottom;
  * an animated `Waiting.` / `Waiting..` / `Waiting...` state that runs while a
    `CompletableFuture` is pending and stops when it settles, whether it succeeded or
    failed;
  * results applied on the client thread through `Minecraft#execute`, and discarded if the
    player has closed the screen in the meantime;
  * the model call behind the `Agent` contract, so the screen never knows about models,
    keys, or retries — only that it asked something and an answer will arrive.
* **Model selection.** A `settings` table appended to the schema, `SettingsRepository`,
  `ModelPreferences`, and `ModelCatalog`. The settings screen is reached from the chat
  screen and offers both a chat model and an embedding model through `EditableDropdown` — a
  text field with a suggestion list attached, so the player can pick a known model **or type
  any id at all**. The lists are suggestions, never a whitelist: a model the mod has not
  heard of is stored as typed, and a blank field resolves to the catalog default rather than
  to no model.
* **The OpenRouter client.** Contracts in `api.agent`, implementation in `common.agent`
  behind a swappable `HttpTransport`. Rotates past a key the provider rejects — including
  402 out of credit — so another key still serves the turn. Retries rate limits, provider
  failures, and transport failures with jittered backoff; never retries a bad request, and
  never retries a malformed 200, because that request was already billed.
* **Prompt caching.** Two `cache_control: ephemeral` breakpoints per request: after the
  persona, and after the newest completed answer. Retrieved Knowledge Pack content is kept
  out of the cached prefix and travels with the question instead — putting it first would
  invalidate the whole conversation behind it on every turn. `usage.include` is requested so
  `cachedTokens` reports what was actually reused.
* **A local embedding cache**, since the same text and model always give the same vector.
  Completions are deliberately not cached: asking again should be able to answer differently.
* **`en_us` translations** for every string the screens show.
* **A cache layer.** `TtlCache` — a bounded LRU whose entries also expire — behind the
  `Cache` contract, and `CachingContentRepository` decorating the SQLite repository. Pack
  bodies and the category list are cached because they are read far more often than they
  change; searches, the stored-version map, and absent packs deliberately are not. Writes
  invalidate in the same call, so a downloaded pack is visible immediately rather than when
  a timer expires. The database stays the record: everything cached can be dropped at any
  moment without changing what the mod knows.
* **The local web console.** A loopback server the player starts from the chat screen, so
  their own data can be read in a browser — the jobs a Minecraft screen is bad at:
  * `api/` — `WebConsole`, `ChangeFeed`, and `Page`/`PageRequest`/`KnowledgeSummary`.
  * `common/` — `LocalWebConsole` on `127.0.0.1`, behind a 256-bit per-run token, a loopback
    `Host` check that closes DNS rebinding, no CORS headers, and bounded request bodies.
    **Long-lived on purpose, unlike the wallet bridge**, which stops the instant a signature
    verifies; a browser tab whose server vanished is a page that stops working while it is
    being read.
  * Three pages on the Silver Glass system — the knowledge library, a read-only pack detail,
    and chat — plus `/keys/`. Each list reads a window at a time inside its own scrolling
    panel, so the toolbar stays put however large the library grows.
  * Chat writes as well as reads, through the same store, agent, and key the game uses. A
    counter in `RevisionedChatRepository` moves on any conversation write from **either**
    side, so the game and the browser stay in step; the counter lives in the repository
    rather than the console precisely so it sees both.
  * `/keys/` lists keys as masks and opaque SHA-256 handles. `key_value` is the primary key,
    so a naive delete button would have sent the player's OpenRouter key to the browser and
    back; the handle names a key without being one and is resolved inside the mod.
  * `platforms/fabric/` — a globe button beside the key button, a screen that starts, opens,
    copies, and stops the console, and a live refresh so a message sent from the browser is
    not silently missing from the conversation on screen.
  * Paged reads throughout, with the total counted rather than inferred and every paged
    ordering made total — `LIMIT`/`OFFSET` over an ambiguous sort shows some rows twice and
    never shows others.
  * 51 tests in `common/`, taking the suite to 197.
* **Silver Glass**, the design system for anything this mod serves in a browser, in
  `.agents/design/web-design-system.md` and routed to from the AGENTS.md trigger table.

* **Wallet binding.** MetaMask wallets, client-side, with the shape MetaMask's own
  multichain address list has — several accounts, several wallets per account, one address
  per chain in each:
  * `api/` — `ChainPlatform`, `ChainAddress`, `Wallet`, `WalletBinding`, the `WalletBridge`
    contract, and `WalletRepository`. An address records whether it was **proved** by a
    signature or **recorded** by hand, and the two never look alike afterwards.
  * `common/` — `player_wallets` and `wallet_platform_addresses` appended to the schema,
    with `UNIQUE(wallet_id, platform)` so re-binding corrects an address rather than adding
    a second one for the same chain, and `ON DELETE CASCADE` so a deleted wallet leaves
    nothing behind. Accounts have no table: one exists as long as a wallet names it.
  * `common/` — `LocalWalletBridge`, which serves one page on `127.0.0.1` for one handshake.
    MetaMask is a browser extension, so `window.ethereum` exists on a web page and nowhere
    else; the mod becomes a web server for as long as that takes and **stops the moment the
    signature verifies**, and on cancel, on timeout, and on screen close. Loopback only, on
    an operating-system-assigned port, behind a 256-bit one-time token and a loopback `Host`
    check that closes DNS rebinding.
  * `common/` — Keccak-256 and secp256k1 public key recovery, written out rather than taken
    from a library. The JDK's SHA3-256 is **a different function** — Ethereum standardised on
    Keccak before NIST changed the padding byte — and substituting it recovers a wrong
    address while looking entirely plausible; a general-purpose cryptography jar would be
    megabytes shipped to every player for one hash and one curve operation. Pinned to
    published vectors: the address of private key 1, all four EIP-55 cases, and Keccak's own
    digests.
  * The claimed address is never trusted. The signer is recovered from the signature, and a
    mismatch fails the binding.
  * `platforms/fabric/` — the wallet screen in the previously empty bottom-left corner of
    the chat screen, with the account dropdown, the wallet list, the chain address list,
    copy-to-clipboard, add and delete for both, an add-address screen that checks the shape
    of what is typed as it is typed, and the connect screen.
  * **Public addresses only.** No screen has a field for a private key or a recovery phrase,
    and an input that looks like one is refused with an explanation rather than stored.
  * 55 tests in `common/`, taking the suite to 146.
* **`RenameSessionScreen` became `RenameScreen`**, parameterised by title and subject, so
  wallets reuse it rather than growing a near-identical copy.

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
* **Server context.** The agent is told where the player is playing: the address of the
  server they are connected to, or exactly `Player is playing in single player.`
  `PlayerEnvironment` and `EnvironmentSource` in `api/agent` are the contract;
  `GameEnvironment` in `platforms/fabric` records the value from the Fabric connection
  events and clears it on disconnect; `PromptAssembler` sends it as one system message
  **after** the persona rather than inside it, so a player who changes servers does not
  invalidate the cached prefix of every request, and so the mod never appends words to a
  file the player wrote. Not knowing where they are adds nothing to the prompt at all —
  "unknown" is a line a model would try to use.
* **`AGENTS.md`, written by the mod and edited from either interface.**
  * `PersonaStore` in `api/agent`, `FilePersonaStore` and `DefaultPersona` in
    `common/agent`. Every method returns a `CompletableFuture`; the last synchronous read in
    the request path — the persona, read with `Files.readString` on whatever thread got
    there — is gone.
  * **Generated on first run** with a starting persona, the roleplay section left for the
    player to write, and an honest account of what each question sends. Never rewritten
    afterwards, including when the player has emptied it, which is how they ask for the
    built-in persona back.
  * **One store per game session**, shared by the agent, the in-game editor, and the
    console. A save is in force by the very next question, from either side; a second store
    is exactly how a save in the browser and a question in the game would disagree.
  * The cache re-reads whenever the file's timestamp or size has moved, so editing the file
    in a text editor still works — without a disk read per question.
  * `PersonaScreen` in game, behind an AGENTS.md button on the chat screen, on a
    `MultiLineEditBox` with Save and Cancel. Cancel and Escape write nothing.
  * `GET` and `PUT /api/agent/persona` in the console, behind an AGENTS.md button on
    `/chat/` opening a wide modal. Blank saves; *absent* is a 400, because a body that says
    nothing about the text must not be read as "make it blank".
  * The console's dialog grew a multiline variant rather than a second dialog. Enter is a
    new line there and never a save.

* **MCP client (host) capability.** The mod can run other people's MCP servers and offer
  their tools to the agent:
  * `api/` — `api.mcp`: `McpHost`, `McpSession`, `McpServerSpec`, `McpToolDescriptor`,
    `McpResourceDescriptor`, `McpPromptDescriptor`, `McpToolResult`, `McpServerStatus`,
    `McpContextRegistry`, `McpTransport`, `McpException`, `McpRepositoryCloner`, and
    `RepositoryCoordinate`, which validates an owner and repository before either becomes
    a path.
  * `common/` — `common.mcp`: `McpDiscovery` reads `mcp.json`, `package.json`, and
    `mcp_servers.json` and returns specs without starting anything; `StdioMcpSession` runs
    a server as a child process over newline-delimited JSON-RPC; `HttpMcpSession` posts to
    a hosted server and reads plain JSON or `text/event-stream` replies;
    `WatchingMcpHost` connects, watches `MCAgents/mcps/` and reloads as a diff so an
    unchanged server keeps its process; `GitRepositoryCloner` clones with the machine's own
    git into `MCAgents/mcps/{owner}/{repository}/`.
  * `platforms/fabric/` — `McpHostFactory` owns one host per game session on daemon
    threads, started beside the persona file and stopped with the game.
  * Tools are merged into one registry as `{server}__{tool}`, capped at 64 characters
    because OpenAI-compatible providers reject the whole request over a longer function
    name. Collisions are dropped and reported rather than left to shadow each other.
  * A server that will not start is a status row, not a failure: the mod loads, the other
    servers stay connected, and a copy with no servers at all is the normal case.
  * 57 tests in `common/`, twelve of them spawning a real child process — among them one
    that adds a server while the host is running and asserts its tools appear without a
    restart. The fake server they drive misbehaves on purpose at startup, logging to
    standard output where only protocol belongs.

* **LLM routing and execution.** The agent can call the tools those servers offer:
  * `api/` — `ToolBroker`, `ToolDefinition`, `ToolCall`, and `ToolOutcome` in `api.agent`;
    `ChatRequest` carries the tools offered, `ChatCompletion` carries the calls the model
    made, `PromptMessage` carries an assistant turn's `tool_calls` and a result's
    `tool_call_id`, and `ChatRole` gains `TOOL` as a wire-only role.
  * `common/` — `McpToolRouter` resolves a `{server}__{tool}` name to the session that owns
    it and strips the qualification before calling; `CloneRepositoryTool` offers the clone
    capability to the model as `mcp_clone_repository`, built in because a player with no
    servers cannot be given a tool by one; `OpenRouterPayloads` sends the `tools` array and
    reads `tool_calls`; `OpenRouterAgent` runs the loop, executing a turn's calls
    concurrently and re-asking until the model answers.
  * `platforms/fabric/` — `AgentFactory` builds the broker per request, so a server
    connected since the screen opened is offered without reopening anything.
  * Every tool failure is a message the model reads — an unknown tool answers with the real
    names, unparseable arguments go back to the model that wrote them, and a disconnected
    server says so. A question is never failed by a tool call.
  * The loop is bounded at eight rounds, and the stop returns whatever the model had
    produced rather than an error about the limit.
  * Tool messages are never written to `chat_messages`: a replayed conversation reads as
    the conversation the player had.
  * 40 further tests, among them five driving a model's call through to a real MCP server
    process and back.

* **Model identifiers stored as `{platform}/{model}`.** `ModelName` parses, builds and
  normalises the shared instruction set's naming convention, lowercased before every write so
  one model has exactly one spelling whichever route reached it. `ModelPlatform` carries the
  gateway constant, the addresses this release knows for the platforms whose API is
  OpenAI-compatible, the `api.base-url.{platform}` setting that overrides one, and the
  https-or-loopback test that decides whether a key may be sent to an address. An id with no
  platform segment is kept as the player typed it and goes through the gateway — nothing
  invents a platform for one.
* **A platform on every stored key.** `api_keys` gains a `platform` column by
  `ALTER TABLE … ADD COLUMN`, guarded by a read of `pragma_table_info(?)` and defaulting to
  `openrouter`, so every key a player already had keeps working unchanged. A key is offered
  only for its own platform and never borrows another's — a gateway key is not valid at a
  platform's own door, and sending it there would disclose it to somewhere it does not
  belong. Re-adding a key under a different platform moves it rather than storing the secret
  twice.
* **Direct platform routes.** `ProviderRoute` and `ProviderRoutes` in `api.agent`, resolved by
  `StoredProviderRoutes` from the same store the keys live in, per request — so a key added
  while the game is running takes effect on the next question. A request goes straight to a
  platform when an address is known for it *and* a key is stored for it; everything else is
  the gateway, exactly as before. The gateway is sent the whole `openai/gpt-4o-mini` and a
  platform is sent `gpt-4o-mini`, both built from one stored name, which is why moving a model
  between routes changes no stored data. A direct request omits `usage.include`, the
  `cache_control` content part and the attribution headers, because a platform's own API
  rejects the entire request over an argument it does not recognise.
* **Stored API keys encrypted at rest.** `TokenCipher` encrypts with AES-256-GCM under a fresh
  nonce per write, so the same key stored twice produces two unrelated strings and nothing in
  the database says two rows hold one secret — the property a hash of any kind cannot give.
  The key comes from `MCAGENTS_CLIENTFABRIC_TOKEN_ENCRYPT_KEY` in the environment and from
  nowhere else: nothing ships in the jar or the repository. Keys already held in the clear are
  encrypted once, at open, in a transaction. **An unset variable is not an error** — keys are
  then stored as they always were, and the console says which of the two states the machine is
  in rather than implying the stronger one. Because the ciphertext is randomised, no key is
  looked up in SQL: the repository scans and decrypts, and a deterministic column to search on
  is forbidden precisely because it would be a fingerprint of the key. A row this machine
  cannot open is skipped with one warning and **never deleted** — putting the right variable
  back brings it back.
  * This replaces the paragraph in `.agents/security/api-keys.md` that forbade encryption
    outright. That rule was about a key shipped in the same jar; this key is not. The override
    is recorded in `.agents/memory/decisions/token-encryption-at-rest.md`.
* **The console's key page became the page about reaching a platform.** A platform box beside
  the key box, the platform shown against each stored key, a panel giving every platform's
  address and whether it is on the direct route or the gateway, and a note saying whether keys
  are encrypted on this machine. An address that is neither https nor loopback is refused
  rather than stored. In game, the key screen gained the same platform box.
* **71 further tests** across `common/` and `website/` — 512 in total — among them the
  database file being read with its own connection to prove no token is legible in it, and the
  same token encrypting differently on two writes.

* **The agent entry point now states the activation protocol the shared set defines.**
  `AGENTS.md` was two shared releases behind: its session-start sequence had five steps where
  the shared one has six, so the four mandatory standard files — the task workflow, the
  branching strategy, the commit conventions and the discovery protocol — were never named and
  read as trigger-fired rather than always-on. A branch convention that loads on "create a
  branch" loads after a harness has already created one, which is exactly how this repository
  acquired a branch named `claude/…` carrying a whole feature. Release `0/8/0`'s consumer
  action is now applied in both halves, `0/12/0`'s names `agents_auto_activation` and the three
  steps it cannot cover, and the conflict paragraph now says that a harness, hook, IDE
  integration or commit template ranks below both sets and cannot authorise setting a rule
  aside. A duplicate-instruction audit against the connector's manifest ran at the same time:
  five local instruction files, none shadowing a shared `name`, path or body, nothing to
  remove.

* **`.claude/CLAUDE.md`, as one import and nothing else.** Claude Code reads `CLAUDE.md` and
  does not read `AGENTS.md`; every other agent reads `AGENTS.md` directly. The file imports
  `../AGENTS.md` so both read the same one, which is the only arrangement in which they cannot
  disagree. It carries no instruction content of its own — a second copy is what the shared
  set's duplicate-instruction audit exists to catch, and it would arrive here through a door
  that audit does not watch. The path is relative to the file holding it, so a bare
  `@AGENTS.md` would name `.claude/AGENTS.md` and fail silently, with no file, no error, and no
  instructions loaded; the reason is written into the file rather than left to be rediscovered.

## Notes

* Group `io.github.mcagents`, base package `io.github.mcagents.clientfabric`.
* Minecraft 26.x publishes no Yarn mappings and no intermediary; Fabric Loom uses Mojang's
  official mappings and **no `remapJar` task runs** — `jar` produces the final artifact.
* Dependency scopes, measured rather than assumed. In Loom, `implementation` bundles
  nothing — only `include` does. Fabric Loader and Fabric API are `implementation` because
  `compileOnly` would strip them from `runtimeClasspath` and break `runClient` in
  development, while producing an identical shipped jar. Gson is `compileOnly` because
  Minecraft provides it. sqlite-jdbc is the only bundled library, and Fabric has no
  equivalent of Spigot's runtime Maven `libraries:` — Jar-in-Jar is the only option.
* `api/` and `common/` are plain `java-library` modules merged into the mod jar rather than
  nested as Jar-in-Jar mods; they are not Fabric mods and carry no `fabric.mod.json`.
* Minecraft 26.x reworked the client GUI API — `GuiGraphicsExtractor` for `GuiGraphics`,
  `extractRenderState` for `render`, the current screen on `Minecraft#gui`, and input as
  `KeyEvent`/`MouseButtonEvent` records. The full list is in the repository map.
* The chat screen and the model settings screen are compile-verified. Neither has been run
  in a real client, because the environment they were built in has no display.
* The console's pages were driven in Chromium against a seeded store — paging, live search,
  filters, a chat message round-tripping, keys added and removed, no JavaScript errors. That
  found three defects the Java suite could not: an observer watching the wrong box so chat
  scrolling was dead, two panels missing `min-height: 0` so they could not scroll at all, and
  a chat layout that scrolled its own composer off-screen. **The in-game half of the console —
  the button, the screen, and the live refresh — has never been run in a real client.**
* The wallet screens are compile-verified against Minecraft 26.2 and nothing more. **No
  signature from the real MetaMask extension has ever been checked** — the tests sign with
  their own secp256k1 signer, so the arithmetic is pinned to published vectors but the round
  trip through the extension has not happened. `SystemBrowser` is untested on Windows and
  macOS; the connect screen shows a copyable link for exactly that case.
* The persona modal was driven in Chromium: the file is generated by the first read, Cancel
  writes nothing, Save writes and says so, reopening reads the file back rather than
  anything the page kept, Enter inserts a newline, and the chat header wraps cleanly at
  1280, 900, and 620 px. **`PersonaScreen` and `GameEnvironment` are compile-verified
  only** — no screen has been opened in a real client, and no join event has ever fired, so
  which of single player, a server, a joined LAN world, and a realm report what is still
  unobserved.
* Stored API keys are encrypted only when `MCAGENTS_CLIENTFABRIC_TOKEN_ENCRYPT_KEY` is set;
  with it unset they are stored as pasted, which is what every earlier version did. That is a
  deliberate fallback, not an oversight — a mod that refused to start without an environment
  variable would be a worse mod for almost every player. What it protects is a database file
  that leaves the machine, never a machine an attacker is already running code on: the mod has
  to send the key to a provider, so anything that can read the environment can read the keys.
* **Direct platform routes have never been exercised against a real provider.** The address,
  the bare model id, the dropped extensions, the per-platform key and the rotation are all
  asserted against a stub transport; no request has actually reached `api.openai.com`. The
  addresses this release ships for six platforms are from their published documentation and
  have not been called.
* The key page was driven in Chromium against a live console — the platform badges, the route
  badges, adding a key turning its platform direct, an address refused and then accepted and
  then cleared, and no horizontal scroll at 1280, 900, 620 and 380 px. That found one defect
  the Java suite could not: the encryption note's forty-character variable name has nothing to
  break on, and pushed the whole page sideways on a narrow screen. **Neither the in-game key
  screen's platform box nor the encryption of a real player's database has been seen in a
  running client.**
* The environment note is sent by default and has no switch, which sets aside the clause in
  `.agents/api/openrouter.md` reserving context injection for something the player turns on.
  It was done on an explicit instruction and mitigated by disclosure rather than by silence:
  the generated `AGENTS.md` states exactly what each question carries and what never leaves
  the machine, and every change of environment is logged.
