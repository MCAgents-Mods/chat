# 0.4.0

Released: 2026-10-13

Chat lives in the game only. The website and the web console button are gone, and the chat
screen with them: `G` shows the MCAgents menu, a hologram, and **CHAT** opens a hologram of
three panels standing in front of the player — **Sessions**, **Conversation** and **User
Prompt**. Keys, models, the persona and wallets each have their own button on the menu.

## Upgrading

* **Players: install MCAgents core `0.6.0` and MCAgents agents `0.2.0` or newer.**
  `MCAgentsChatFabric-0.4.0.jar` and `MCAgentsChatNeoforge-0.4.0.jar` need both, for the same
  loader, and core `0.6.0` refuses to load beside a chat older than `0.4.0`. Data under
  `{minecraft_dir}/MCAgents/` is unchanged.
* **Players: press `G`, then CHAT.** Aim at a hologram's button and click it. Point at a
  hologram and hold `Z` to carry it; walk more than two chunks away and it closes. KEYS,
  MODEL, PERSONA and WALLETS open the screens the chat screen's buttons used to.
* **Players: set a platform's address on the key screen.** The console's key page was the only
  place to set the address a platform is called at; it is now the row under the platform box.
  Addresses already stored are kept.
* **Players: no browser pages.** The Knowledge Pack pages have no in-game replacement yet;
  packs already stored are still used for retrieval. Session search went with the chat screen.
* **Anyone depending on chat's jars:** `mcagents-chat-website` is no longer published; its
  last version is `0.3.0`. `platforms/shared` compiles against
  `io.github.mcagentsmods:mcagents-core-client`, read from core's GitHub Packages, or from
  `../core` when a checkout is beside chat.

## Added

* **The chat hologram.** Sessions lists the conversations, newest first, with NEW, RENAME and
  DELETE. Conversation puts the player's name or `Agent` over each line, says `Thinking…`
  while an answer is on its way, and gives the reason when a question is not answered. User
  Prompt takes the question and SEND asks it. Closing the hologram and opening it again keeps
  the conversation, and an answer still on its way lands in it.
* **`ChatController`** in `common.ui`: what the hologram shows and does, with no Minecraft in
  it, and 15 tests on the real store.
* **CHAT, KEYS, MODEL, PERSONA and WALLETS** on the agents menu. A button whose screen needs
  storage says why on the action bar while there is none.
* **The key screen's address row**, which takes only `https://` or a loopback address, and a
  line saying whether stored keys are encrypted.

## Changed

* Chat builds against core `0.6.0` and agents `0.2.0`, and both manifests require them.
* The `AGENTS.md` written for a new player points at PERSONA instead of the chat screen.
* A question asked with no key now ends "No usable API key. Add one with KEYS on the MCAgents
  menu." The reason stays with the conversation it was asked in until the next question.

## Removed

* `website/` (`mcagents-chat-website`), `WebConsoleScreen`, `WebConsoleHolder` and the
  console button.
* `AgentChatScreen`, `SessionListPanel`, `ConversationPanel`, `ChatButton` and
  `ChatScreenLauncher`, and session search with them.
* `AgentFactory.modelClient`, which only the console used.
* `.agents/design/web-design-system.md` and `wiki/information/web-console.md`.
