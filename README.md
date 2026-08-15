# MCAgentsClient

A **client-side-only** Minecraft mod for the Fabric loader that puts an AI assistant inside
the game. You ask a question in an in-game chat screen; a language model answers using your
own API key, the conversation so far, and whichever downloaded knowledge is relevant to what
you asked.

Nothing is installed on the Minecraft server, and nothing is required of a server operator.
It works in single-player and on any multiplayer server you are allowed to use it on.

## How it works

Everything the mod remembers lives on your own machine, in a SQLite database at
`.minecraft/MCAgents/data.db`: your conversations, your API keys, your bound wallets, and
the Knowledge Packs you have downloaded. When you ask a question, the mod turns it into an
embedding, compares it against the stored packs, and sends only the closest content to the
model — never your whole library.

The mod talks to a central service at [`https://mcagents.mcrealms.space`](https://mcagents.mcrealms.space),
which catalogs and serves those packs. **That service is a content source, not a
dependency.** With it unreachable — asleep, down, blocked, or never contacted — the mod
still starts, still opens, still shows every stored conversation, and still answers from
what is already downloaded. Only browsing the catalog and downloading new packs stop.

Built-in knowledge grows over time, and **it updates only when you press the update button.**
Nothing is fetched behind your back, with one exception: a small pack describing the mod
itself is fetched once at startup so the assistant can answer questions about what it is.

## Your API key

Your key is stored on your own machine and sent to nobody but your model provider. It is
never uploaded to the central service, never written to a file that travels with a world,
and shown in the interface only in shortened form.

The consequence is worth stating plainly: **because the key never leaves your machine, the
risk of leaking it is yours alone to manage.** Do not paste it where others can see it, and
do not share your `data.db`.

## Features

* **Conversations that continue** — every chat kept, titled, and searchable, by title and by
  what was said inside it.
* **Retrieval by embedding** — questions matched against stored packs by meaning rather than
  by keyword, with a switch to turn it off. Off means the question is sent on its own: no
  packs, no embedding call, nothing billed for it, and chat otherwise unchanged.
* **Knowledge Packs**, free or paid, listed side by side. Packs are versioned; an update
  publishes a new version rather than editing one in place.
* **A local web console** — the mod can serve your own data to your own browser on
  `127.0.0.1`: your packs, your chats, your API keys, and your model choice. It is for the
  jobs a Minecraft screen is bad at. Chat works both ways, so a message typed in the browser
  appears in the game and the other way round.
* **Several API keys, with rotation** — a key the provider rejects is deactivated and the
  next takes over without interrupting the conversation.
* **Model choice** — pick a chat model and an embedding model, or type any id your provider
  serves. The lists are suggestions, not limits.
* **Wallet binding** — record the wallets you own, across several accounts and chains, and
  prove an EVM address by signing in MetaMask. Public addresses only; there is no field for
  a private key or a recovery phrase.
* **Player-defined personas** in `.minecraft/MCAgents/AGENTS.md`.
* **Offline-first throughout.**

## Quick start

Press `G` in game. The chat screen opens with your conversations on the left and a prompt
along the bottom. Add an OpenRouter key with the key button before asking anything; the
gear picks models, the globe opens the web console, and **Wallets** is in the bottom-left
corner.

## Where the project lives

The MCAgents organization exists on GitHub and GitLab, and on those cloud hosts only:

* [github.com/MCAgents](https://github.com/MCAgents)
* [gitlab.com/MCAgents](https://gitlab.com/MCAgents)

The author is Jetsada Wijit — [github.com/JetsadaWijit](https://github.com/JetsadaWijit),
[gitlab.com/JetsadaWijit](https://gitlab.com/JetsadaWijit) — who also owns the domain
`mcrealms.space`.

## License

Proprietary. See [`LICENSE`](LICENSE), which governs; the following is only a summary.

Use is licensed to people in exactly one of three categories: the owner and members of the
MCAgents organization; anyone given express written permission by the organization; and
anyone who has lawfully purchased a license, for the duration and scope of that purchase.
Use outside those three is unlicensed.

The mod may also be published through platforms such as CurseForge and Modrinth. Those
listings are authorized **only** for builds published by the MCAgents organization itself —
so if you download it from a third-party platform, **check the publisher, not the platform.**
A build published by any other account may have been modified, may carry malware, and is
covered by neither the license nor any support.

Reselling, redistributing, mirroring, reverse engineering, and creating derivative works are
not permitted, except where mandatory law says otherwise.

## Documentation

* [`wiki/information/overview.md`](wiki/information/overview.md) — what it is, in full.
* [`wiki/information/architecture.md`](wiki/information/architecture.md) — how the modules
  are split and why.
* [`wiki/information/knowledge-retrieval.md`](wiki/information/knowledge-retrieval.md) —
  retrieval, and the Embedding switch.
* [`wiki/information/web-console.md`](wiki/information/web-console.md) — the browser view.
* [`wiki/information/wallet-binding.md`](wiki/information/wallet-binding.md) — wallets.
* [`wiki/environments/setup.md`](wiki/environments/setup.md) — building and running it.
* `AGENTS.md` — the entry point for agents working on this repository.
