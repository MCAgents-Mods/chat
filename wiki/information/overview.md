# MCAgentsClient — Overview

MCAgentsClient is a **client-side only** Minecraft mod for the Fabric loader. It puts an
AI assistant inside the game: the player opens a chat screen, asks a question, and an LLM
answers using the player's own OpenRouter API key, the conversation so far, and whichever
downloaded knowledge is relevant to the question.

Nothing is installed on the Minecraft server. Nothing is required of the server operator.
The mod works on single-player worlds and on any multiplayer server the player is allowed
to use it on.

## Who it is for

Players who want an in-game assistant that actually knows the server they are on — its
rules, its economy, its progression, its boss strategies — instead of a general-purpose
chatbot that has never heard of it.

## The pieces

### Local agent

The player supplies an OpenRouter API key. The mod sends the prompt, the conversation
history, and the retrieved context to OpenRouter and streams the answer back into the chat
screen. Multiple keys can be stored; an expired key is deactivated and the next one is used.

### Knowledge Packs and retrieval

A Knowledge Pack is a unit of downloadable content — a server's rules, a progression guide,
a farming strategy. Packs are downloaded from the central service and stored in the local
database together with their embeddings. When the player asks a question, the mod embeds
the question, finds the closest stored content, and sends only that to the model. The model
never receives the whole library.

Packs are versioned. An update replaces only the packs that actually changed.

### The marketplace

Players can author packs, publish them, and sell them. Free and premium packs are listed
side by side in the mod's content browser, with search and a free/premium filter. The
central service at `mcagents.mcrealms.space` hosts the catalog and handles purchases.

### Local storage

Everything the mod remembers lives on the player's own machine:

| What | Where |
|---|---|
| Chat sessions and messages | `{minecraft_dir}/MCAgents/data.db` |
| OpenRouter API keys | `{minecraft_dir}/MCAgents/data.db` |
| Downloaded Knowledge Packs and their embeddings | `{minecraft_dir}/MCAgents/data.db` |
| Agent personas and player instructions | `{minecraft_dir}/MCAgents/AGENTS.md` |

`data.db` is SQLite. `AGENTS.md` is a plain markdown file the player edits to define how
the agent should behave.

## Offline behaviour

**The central service is a content source, not a dependency.** When
`mcagents.mcrealms.space` is unreachable — down, blocked, or never contacted — the mod
still starts, still opens, still shows every stored session, and still answers questions
using the Knowledge Packs already downloaded. Only two things stop working: browsing the
catalog and downloading new packs.

The OpenRouter API is a separate matter: without a key or without internet, the mod cannot
produce new answers, but the interface, the history, and the stored knowledge remain fully
accessible.

## What is planned but not built

Context injection — inventory, the block the player is looking at, chat logs and
scoreboard — is part of the design and is not implemented yet. See
[`architecture.md`](architecture.md) for where it will live.

## Related

* [`architecture.md`](architecture.md) — how the modules are split and why.
* [`../environments/setup.md`](../environments/setup.md) — building and running it.
* `MCAgents/server-fabric-expressjs` — the central Knowledge Pack service.
