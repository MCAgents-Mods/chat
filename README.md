# MCAgentsClient

A **client-side-only** Minecraft mod for the Fabric loader that puts an AI assistant inside
the game. You ask a question in an in-game chat screen; a language model answers using your
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
Either way the console's key page tells you which.

The consequence is worth stating plainly: **because the key never leaves your machine, the
risk of leaking it is yours alone to manage.** Do not paste it where others can see it, and
do not share your `data.db`.

## Features

* **Conversations that continue** — every chat kept, titled, and searchable, by title and by
  what was said inside it.
* **Retrieval by embedding** — questions matched against stored packs by meaning rather than
  by keyword, with a switch to turn it off. Off means the question is sent on its own: no
  packs, no embedding call, nothing billed for it, and chat otherwise unchanged.
* **Knowledge Packs**, stored on your machine and listed in the console, where you can read
  and search them and delete the ones you no longer want.
* **Embedding on your own machine.** Packs are stored as text and embedded by your
  client with your embedding model, so your library lives in the same vector space as your
  questions rather than in whichever one produced a pack.
* **A local web console** — the mod can serve your own data to your own browser on
  `127.0.0.1`: your packs, your chats, your API keys, and your model choice. It is for the
  jobs a Minecraft screen is bad at. Chat works both ways, so a message typed in the browser
  appears in the game and the other way round.
* **Several API keys, with rotation** — a key the provider rejects is deactivated and the
  next takes over without interrupting the conversation. Each key records the platform it
  opens, and is only ever sent there.
* **OpenRouter, or a platform directly** — store a key for `openai`, `deepseek`, `groq` or a
  gateway you run yourself, and questions for that platform's models go straight to it.
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

Press `G` in game. The chat screen opens with your conversations on the left and a prompt
along the bottom. Add an API key with the key button before asking anything — the dropdown
beside it is the platform the key opens, and `openrouter` is the default. The gear picks the
provider and the models, the globe opens the web console, and **Wallets** is in the bottom-left
corner.

## Where the project lives

The source is on GitHub, at [github.com/MCAgents-Mods/chat](https://github.com/MCAgents-Mods/chat),
under the [MCAgents-Mods](https://github.com/MCAgents-Mods) organization. Issues and pull
requests go there.

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
* [`wiki/information/web-console.md`](wiki/information/web-console.md) — the browser view.
* [`wiki/information/mcp-servers.md`](wiki/information/mcp-servers.md) — external MCP
  servers.
* [`wiki/information/wallet-binding.md`](wiki/information/wallet-binding.md) — wallets.
* [`wiki/environments/setup.md`](wiki/environments/setup.md) — building and running it.
* `AGENTS.md` — the entry point for agents working on this repository.
