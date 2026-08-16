---
name: memory-task-server-context-and-persona
description: Telling the agent where the player is, and making AGENTS.md a file the mod writes and the player edits from either interface — the decisions, and what a browser proved that the Java suite could not.
---

# Server Context and the Persona File

Two features, one branch, delivered together because both cross the same boundary: a value
that only `platforms/fabric` can know, reaching `common/` through a contract in `api/`.

## What landed

**Server context.** `PlayerEnvironment` (a record: single player, a multiplayer address, or
unknown) and `EnvironmentSource` (a functional interface) in `api/agent`. `GameEnvironment`
in `platforms/fabric/client` records the value from `ClientPlayConnectionEvents.JOIN` and
clears it on `DISCONNECT`. `PromptAssembler` places it as one system message after the
persona; `OpenRouterAgent` asks the source at request time.

**The persona file.** `PersonaStore` in `api/agent`, `FilePersonaStore` and
`DefaultPersona` in `common/agent`. Auto-generated on first run from
`MCAgentsClientMod`, edited in game from `PersonaScreen`, edited in the browser from a modal
on `/chat/` behind `GET`/`PUT /api/agent/persona`.

## Decisions not to relitigate

* **The environment is its own system message, not part of the persona.** The persona is
  the player's own file — appending to it puts words in it they did not write — and it is
  the cached prefix of every request. Changing servers must not invalidate it.
* **No cache breakpoint on the environment note.** It sits between the persona's breakpoint
  and the one on the newest completed answer, which already covers it from the second turn
  on. A third breakpoint buys one line of reuse and costs a write.
* **`Player is playing in single player.` is a constant, not a sentence at a call site.**
  The generated `AGENTS.md` quotes it and a player's own file may too, so it is pinned by a
  test in both places.
* **UNKNOWN adds nothing to the prompt at all.** Not "environment: unknown", which is a line
  a model would try to use. The mod not knowing is not a fact worth sending.
* **One `PersonaStore` per game session**, held by `AgentFactory` beside the model client and
  handed to the console by `WebConsoleHolder`. A second store is how a save in the browser
  and a question in the game end up disagreeing; the routes test pins it.
* **The cache checks mtime and size on every read.** Editing `AGENTS.md` in a text editor is
  a supported way to use it, and a cache that only refreshed on the mod's own writes would
  ignore that editor for the rest of the session.
* **The mod creates the file and never rewrites it** — including when the player has emptied
  it, which is how they ask for the built-in persona back.
* **Absent and blank are different on `PUT`.** Blank saves an empty file; absent is a 400. A
  handler that collapsed them would delete somebody's instructions because a page sent the
  wrong shape.
* **`MAX_BODY_BYTES` went from 64 KiB to 256 KiB.** The largest thing a page sends is now a
  persona rather than a prompt, and a save silently cut in half is the worst failure this
  could have.

## Rule set aside, deliberately, on the user's instruction

`.agents/api/openrouter.md` says context injection is "a feature the player controls, not a
default". The environment note **is** a default and there is no switch for it. The
mitigation chosen was disclosure rather than silence: the generated `AGENTS.md` says exactly
what is sent and what is not, and `GameEnvironment` logs each change. A finding proposing
either a switch or an amendment to that rule was reported to the user; nothing in
`.agents/` was edited to match the code.

## Proven, and how

* `./gradlew :mcagents-fabric-common:test :mcagents-fabric-website:test` — 344 tests green,
  including `PromptEnvironmentTest`, `FilePersonaStoreTest`, and `PersonaRoutesTest`.
* `./gradlew :mcagents-fabric:compileJava` against real Minecraft 26.2 — `MultiLineEditBox`
  is builder-only in 26.2 (`builder().setX().setY().setPlaceholder().build(font, w, h, msg)`),
  `Minecraft#hasSingleplayerServer()` and `#getCurrentServer()` exist, and `ServerData.ip` is
  still a public field. All four were checked with `javap` before being written.
* **Driven in Chromium** against a temporary store: the modal opens with the generated file
  in it, Cancel writes nothing, Save writes the file and the note says so, reopening reads
  the file back, Enter inserts a newline instead of saving, and no page errors. The header
  was checked at 1280, 900, and 620 px — it wraps and the body never scrolls sideways. That
  run also caught the driver's own bug of waiting for a hidden scrim to become *visible*.

## Not proven

* **No screen has been opened in a real client.** `PersonaScreen` is compile-verified only.
  `MultiLineEditBox` fills the window and holds a 64,000-character limit; whether it scrolls
  and wraps a long persona pleasantly is unknown.
* **No join event has ever fired.** `GameEnvironment` is compile-verified. Single player, a
  server, a LAN world joined rather than hosted, and a realm are four cases and none has been
  observed. The realm case is the likely gap: `getCurrentServer()` may be null for one, in
  which case the environment is reported as unknown rather than wrongly.
* No prompt carrying an environment note has been sent to a real provider.
