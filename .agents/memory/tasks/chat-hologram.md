---
name: memory-task-chat-hologram
status: in-progress
description: Removing chat's website and web console button, moving the chat screen onto core's hologram as three panels (Sessions, Conversation, User Prompt), chat's buttons on the agents menu, and the 0.4.0 release.
---

# Chat hologram

## 2026-10-13

**Goal.** The owner asked for MCAgents to be used in game only: "Get rid of website, and in
game interact only." Core 0.6.0 removed its web console and added holograms, panels standing
in the world that the player aims at and clicks. Agents 0.2.0 turned G into a hologram menu.
Chat is the last mod that still opens a browser and a flat screen. The owner's second picture
shows the chat hologram: three panels side by side, **Sessions** (a scrollable list with the
chosen one highlighted), **Conversation** (a scrollable `<Player>` / `<Agent>` transcript) and
**User Prompt** (a text box and a SEND button).

**Objective.**
* `website/`, its tests, the console screen and the console button are gone. Chat builds
  against core 0.6.0 and agents 0.2.0, and its manifests require them.
* The CHAT button on the agents menu opens the chat hologram. The player picks, starts,
  renames and deletes conversations, reads them, and asks a question, all in the world.
* KEYS, MODEL, PERSONA and WALLETS are chat's own buttons on the agents menu, opening the
  screens that already exist.
* `AgentChatScreen` is deleted. Chat is released as 0.4.0.

**Detail.**
* Core draws the panels and takes the clicks and typing. Chat only hands it the rows, the
  transcript and the callbacks, read through suppliers every frame.
* The MetaMask wallet handshake page stays. It is how a wallet signs, not the console.
* The knowledge pages go without an in-game copy, since nothing can add a pack today.

## Decisions

The owner was away and asked for every question to be settled by taking the recommended
option. The plan for all four repositories is kept outside this repository.

* **The send and session logic moves into `ChatController`,** in `common/`, with no
  Minecraft. It holds the sessions, the chosen one, its messages, whether a question is
  waiting and the last failure. Results come back to the client thread through an executor it
  is given. The hologram reads it, so the logic is tested without a game.
* **Its tests run on the real store,** SQLite in a temporary directory, as the repository's
  own tests do, with an agent that answers from a script. The plan said an in-memory store;
  the real one is what the game uses, and a fake `ChatRepository` would be one more fiction to
  keep in step.
* **One controller for the whole game.** Closing and reopening the chat hologram keeps the
  chosen conversation, and an answer still on its way lands in it.
* **Speakers are the player's name and "Agent".** "Player" stands in when there is no name.
* **While a question waits,** the transcript ends with `<Agent> Thinking…`. **A failed
  question** ends it with the reason, as the agent's line, until the next question. The reason
  belongs to the conversation it was asked in: choosing another hides it, and choosing that
  one again brings it back.
* **Without storage,** the Sessions list is empty, the transcript says whether the database
  is opening or failed and why, SEND is greyed, and NEW tries to open it again.
* **RENAME and DELETE act on the chosen conversation.** RENAME opens the rename screen and
  DELETE the confirm screen, as before; both go back to the game, where the hologram still
  stands. With nothing chosen they do nothing.
* **Session search goes,** as the plan said. The list shows the newest conversation first.
* **No change watch.** The screen reloaded when the chat revision moved, because the console
  could write to the same store. With the console gone, every write is the controller's own.
* **Menu order:** CHAT, KEYS, MODEL, PERSONA, WALLETS, then agents' SETTINGS. A button that
  needs storage, pressed without it, says why on the action bar instead of doing nothing.
* **`.agents/design/web-design-system.md` is deleted,** with its trigger row and index row, as
  the plan said. It is mostly about the console's shared page shell. The wallet page keeps its
  own inline styles, and the offline-first rule still forbids anything it would fetch.
* **The platform address editor moves to the key screen.** The plan counted every console
  page except the knowledge pages as already in game, but the key page also set the address a
  platform is called at, which a local model server needs, and said whether keys are
  encrypted. Both come to the key screen before the console goes. The console's rule that an
  address may not move to another host while a key is stored is not carried over: it guarded
  against anyone holding the console's link, and in game only the player can type.
* **Version 0.4.0.** Minor, because the website and the chat screen are removed.

## The task list

| # | Title | Scope | Branch | PR |
|---|---|---|---|---|
| 1 | The record | This file and the memory index | `chore/chat-hologram-plan` | |
| 2 | Website removal | Delete `website/`, its tests, the console screen, its holder and button. Settings, the loader jar lists and the `publish.yml` header. Core 0.6.0 and agents 0.2.0, required in the manifests. Delete `wiki/information/web-console.md` and the design rule with its rows. README and docs. | `refactor/website-removal` | |
| 3 | Chat hologram | `ChatController` and its tests; the chat hologram; the CHAT button opens it; KEYS, MODEL, PERSONA and WALLETS on the menu; `AgentChatScreen`, `SessionListPanel` and `ConversationPanel` deleted; lang; docs | `feat/chat-hologram` | |
| 4 | Release 0.4.0 | Version, changelog, logs index, state, and close this record | `release/0.4.0` | |

### Task 2 — refactor/website-removal

**Landed.** `website/`, `WebConsoleScreen`, `WebConsoleHolder`, the globe button and the
console strings are gone, and so is `AgentFactory.modelClient`, which only the console used.
Chat builds against core 0.6.0 and agents 0.2.0, which the manifests require, and a core
checkout beside chat also stands in for `mcagents-core-client`, which agents 0.2.0 compiles
against. The key screen gained the address row and the encryption line. The AGENTS.md written
for a new player no longer points at the console.

**Checked.** `./gradlew build` with `../core` and `../agents` beside chat: 400 tests in
`common/`, and no `mcagents/web/` file in either loader jar. The key screen was opened in the
Fabric dev client with task 3: the address row refused `http://example.com` and stored
`http://127.0.0.1:8080/v1` as `api.base-url.mygateway`, and the encryption line showed. The
saved note ran past the screen's width and was cut off mid-word, so a fix on this branch
shortens the three notes.

### Task 3 — feat/chat-hologram

**Landed.** `ChatController` in `common.ui`, with 15 tests on the real store. `ChatHologram`
builds the three panels from it; `ChatButtons` adds CHAT, KEYS, MODEL, PERSONA and WALLETS to
the agents menu in place of `ChatButton` and `ChatScreenLauncher`. `AgentChatScreen`,
`SessionListPanel` and `ConversationPanel` are gone, with the strings only they used.
`platforms/shared/` compiles against `mcagents-core-client`. The AGENTS.md written for a new
player now points at PERSONA. Every document that named the chat screen names the hologram.

**Checked.** `./gradlew build`: 415 tests in `common/`. In a Fabric dev client on Minecraft
26.2, with core 0.6.0 and agents 0.2.0 jars in `run/mods/`:
* `G` showed CHAT, KEYS, MODEL, PERSONA, WALLETS, then agents' SETTINGS.
* Clicking CHAT replaced the menu with the three panels. Clicking the prompt box, typing and
  Enter asked the question: "New Chat" appeared chosen, the transcript showed the player's
  name over the question, and, with no key, `Agent` over "Not answered: No usable API key.
  Add one with KEYS on the MCAgents menu."
* NEW started an empty conversation and chose it. RENAME opened the rename screen and the
  new title showed in the list. DELETE asked first, then removed it and chose none; choosing
  the failed conversation again brought its reason back. Each screen went back to the game.
* Closing the hologram and opening it again kept the chosen conversation.
* KEYS, MODEL, PERSONA (with the new AGENTS.md text) and WALLETS opened their screens.

The clicks and the typing were real input. The camera was turned by a scratch harness, never
committed, because the virtual display's mouse does not turn it. NeoForge is compile-verified
only. Seen and not changed: the model screen's Done button is cut off at the bottom of a
1280x720 window, as it was before this task.
