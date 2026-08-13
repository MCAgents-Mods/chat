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

## Using it

Press `G` in game to open the chat screen: conversations on the left with a search box and
a "new chat" button, the selected conversation on the right, and a prompt box along the
bottom. Every conversation is kept, titled, and searchable — including by what was said in
it, not only by its title.

The gear button opens the model settings. Pick a chat model and an embedding model from the
suggested lists, or type any OpenRouter model id you like — the lists are a convenience, not
a limit, because OpenRouter adds models faster than this mod ships.

## What is planned but not built

The mod does not yet call OpenRouter, so the agent stores what you ask and replies that it
is not connected. The content browser, key-management screen, and retrieval are also still
to come, as is context injection — inventory, the block you are looking at, chat logs and
scoreboard. See [`architecture.md`](architecture.md) for where each will live.

## Related

* [`architecture.md`](architecture.md) — how the modules are split and why.
* [`../environments/setup.md`](../environments/setup.md) — building and running it.
* `MCAgents/server-fabric-expressjs` — the central Knowledge Pack service.
