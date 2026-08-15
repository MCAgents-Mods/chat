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
history, and the retrieved context to OpenRouter and shows the answer in the chat screen —
whole, when it is ready; responses are not streamed yet. Multiple keys can be stored, and a
key the provider rejects is deactivated so the next one takes over without interrupting the
conversation.

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

### Wallets

The mod can record the crypto wallets a player owns — several accounts, several wallets per
account, and one address per chain in each, the way MetaMask's own multichain list is
arranged. EVM addresses can be *proved*: the mod opens a page on `127.0.0.1`, MetaMask signs
a one-off message, and the mod recovers the signer from the signature. Signing costs nothing
and authorises nothing. Bitcoin, Solana, and Tron addresses are typed in and shown as
recorded rather than proved, because the extension gives a web page no way to sign with
them.

**Public addresses only.** The mod has no field for a private key or a recovery phrase, and
refuses anything that looks like one. See [`wallet-binding.md`](wallet-binding.md).

### The web console

The mod can serve your own data to your own browser: your Knowledge Packs, your chats, and
your API keys, on `127.0.0.1` and nowhere else. It is for the jobs a Minecraft screen is bad
at — reading a long pack, scanning a year of conversations, pasting a key. Chat works both
ways, so a message typed in the browser appears in the game and the other way round.

Keys are shown shortened and never sent to the browser in full. See
[`web-console.md`](web-console.md).

### Local storage

Everything the mod remembers lives on the player's own machine:

| What | Where |
|---|---|
| Chat sessions and messages | `{minecraft_dir}/MCAgents/data.db` |
| OpenRouter API keys | `{minecraft_dir}/MCAgents/data.db` |
| Downloaded Knowledge Packs and their embeddings | `{minecraft_dir}/MCAgents/data.db` |
| Bound wallets and their public chain addresses | `{minecraft_dir}/MCAgents/data.db` |
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

Hover a conversation to reveal two controls: a pencil to rename it, and a `×` to delete it.
Deleting asks first, because it takes every message in that conversation with it and cannot
be undone.

If the agent cannot answer — no key, a model the provider does not serve, a rate limit — the
reason appears in the conversation itself, in the provider's own words.

The key button opens API key management — add your OpenRouter key there; stored keys are
only ever shown masked. The gear button opens the model settings: pick a chat model and an
embedding model from the suggested lists, or type any OpenRouter model id you like. The
lists are a convenience, not a limit, because OpenRouter adds models faster than this mod
ships.

The globe button beside the key button starts the web console and opens your browser at it.
It keeps running when you close the screen, so you can read in a browser while you play.

The Wallets button in the bottom-left corner opens wallet management: accounts across the
top, wallets down the left, and the selected wallet's chain addresses on the right. Click any
address to copy it in full.

If local storage cannot be opened, the screen still opens and says so, with a Retry button.
It never simply fails to appear.

## What is planned but not built

Retrieval is not wired up yet, so the agent answers from your conversation rather than from
downloaded Knowledge Packs. The content browser is still to come, as is streaming, and so is
context injection — inventory, the block you are looking at, chat logs and scoreboard. See
[`architecture.md`](architecture.md) for where each will live.

## Related

* [`architecture.md`](architecture.md) — how the modules are split and why.
* [`wallet-binding.md`](wallet-binding.md) — how wallets are held and proved.
* [`web-console.md`](web-console.md) — the browser view of your own data.
* [`../environments/setup.md`](../environments/setup.md) — building and running it.
* `MCAgents/server-fabric-expressjs` — the central Knowledge Pack service.
