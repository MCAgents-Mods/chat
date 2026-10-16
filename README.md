# MCAgentsClient

A **client-side-only** Minecraft mod for the Fabric, NeoForge and Forge loaders that puts an AI assistant inside
the game. You ask a question on a hologram in front of you; a language model answers using your
own API key, the conversation so far, and whichever stored knowledge is relevant to what
you asked.

Nothing is installed on the Minecraft server, and nothing is required of a server operator.
It works in single-player and on any multiplayer server you are allowed to use it on.

## How it works

Everything the mod remembers lives on your own machine, in a SQLite database at
`.minecraft/MCAgents/data.db`: your conversations, your API keys, your bound wallets, and
the Knowledge Packs you have stored. When you ask a question, the mod turns it into an
embedding, compares it against the stored packs, and sends only the closest content to the
model — never your whole library.

The mod has no server of its own. With no network it still starts, still opens, still shows
every stored conversation, and still answers from the packs already stored. The calls it does
make are to the model provider you chose, and to a git host when you ask it to clone an MCP
server. Nothing is fetched behind your back.

## Your API keys

A key is stored on your own machine and sent to nobody but the platform you stored it for. It
is never written to a file that travels with a world, and shown in the interface only in
shortened form.

Set `MCAGENTS_CHAT_TOKEN_ENCRYPT_KEY` in your environment and the keys are encrypted
in the database as well, with the same key encrypting differently every time it is written.
Leave it unset and they are stored as you pasted them, which is what the mod did before.
Either way the key screen tells you which.

The consequence is worth stating plainly: **because the key never leaves your machine, the
risk of leaking it is yours alone to manage.** Do not paste it where others can see it, and
do not share your `data.db`.

## Features

* **Conversations that continue** — every chat kept and titled, and picked, renamed or
  deleted from the chat hologram's Sessions panel.
* **Retrieval by embedding** — questions matched against stored packs by meaning rather than
  by keyword, with a switch to turn it off. Off means the question is sent on its own: no
  packs, no embedding call, nothing billed for it, and chat otherwise unchanged.
* **Knowledge Packs**, stored on your machine and matched against each question. This
  release has no page that lists them: the browser pages that did were removed in 0.4.0.
* **Embedding on your own machine.** Packs are stored as text and embedded by your
  client with your embedding model, so your library lives in the same vector space as your
  questions rather than in whichever one produced a pack.
* **Several API keys, with rotation** — a key the provider rejects is deactivated and the
  next takes over without interrupting the conversation. Each key records the platform it
  opens, and is only ever sent there.
* **OpenRouter, or a platform directly** — store a key for `openai`, `deepseek`, `groq` or a
  gateway you run yourself, with its address set on the key screen, and questions for that
  platform's models go straight to it.
  Anything you have not set up goes through OpenRouter, as it always did.
* **Model choice** — pick a chat model and an embedding model, or type any id your provider
  serves. The lists are suggestions, not limits.
* **Wallet binding** — record the wallets you own, across several accounts and chains, and
  prove an EVM address by signing in MetaMask. Public addresses only; there is no field for
  a private key or a recovery phrase.
* **External tools over MCP** — ask the agent to add a Model Context Protocol server and it
  clones, runs, and uses it from your next question, with no restart. Servers live in
  `.minecraft/MCAgents/mcps/{owner}/{repository}/`.
* **Player-defined personas** in `.minecraft/MCAgents/AGENTS.md`.
* **Offline-first throughout.**

## Quick start

Install the jar for your loader on Minecraft 26.3, the only version chat supports —
`MCAgentsChatFabric-{version}+{minecraft}.jar` with Fabric API,
`MCAgentsChatNeoforge-{version}+{minecraft}.jar`, or
`MCAgentsChatForge-{version}+{minecraft}.jar` — and **MCAgents core** (`mcagents_core`, from
`MCAgents-Mods/core`) and **MCAgents agents** (`mcagents_agents`, from `MCAgents-Mods/agents`)
for the same loader beside it. Core carries the model client every MCAgents mod shares and agents the tool calling; without
either the game refuses to load MCAgentsClient.

Press `G` in game for the MCAgents menu, a hologram in front of you. Chat adds five buttons
to it. **CHAT** opens the chat hologram: your conversations under **Sessions**, with **NEW**,
**RENAME** and **DELETE**; the one you picked under **Conversation**; and a box under **User
Prompt** where you type and press **SEND**. Add an API key with **KEYS** before asking
anything — the dropdown there is the platform the key opens, and `openrouter` is the default.
**MODEL** picks the provider and the models, **PERSONA** edits `AGENTS.md`, and **WALLETS**
holds your bound wallets. Hold `Z` while looking at a hologram to move it.

## Where the project lives

The source is on GitHub, at [github.com/MCAgents-Mods/chat](https://github.com/MCAgents-Mods/chat),
under the [MCAgents-Mods](https://github.com/MCAgents-Mods) organization. Issues and pull
requests go there. Every release's mod jars are attached to its GitHub release, and its
library modules are published to that repository's GitHub Packages as
`io.github.mcagentsmods:mcagents-chat-{module}`.

## License

[MIT](LICENSE), copyright 2026 MCAgents. You may use, copy, modify, merge, publish,
distribute, sublicense, and sell copies of the software, provided the copyright notice and
the license text travel with it. The software is provided as is, without warranty. See
[`LICENSE`](LICENSE), which governs; the above is only a summary.

## Documentation

* [`wiki/information/overview.md`](wiki/information/overview.md) — what it is, in full.
* [`wiki/information/architecture.md`](wiki/information/architecture.md) — how the modules
  are split and why.
* [`wiki/information/knowledge-retrieval.md`](wiki/information/knowledge-retrieval.md) —
  retrieval, and the Embedding switch.
* [`wiki/information/mcp-servers.md`](wiki/information/mcp-servers.md) — external MCP
  servers.
* [`wiki/information/wallet-binding.md`](wiki/information/wallet-binding.md) — wallets.
* [`wiki/environments/setup.md`](wiki/environments/setup.md) — building and running it.
* `AGENTS.md` — the entry point for agents working on this repository.
