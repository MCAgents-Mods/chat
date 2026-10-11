# MCAgentsClient — Overview

MCAgentsClient is a **client-side only** Minecraft mod for the Fabric loader. It puts an
AI assistant inside the game: the player opens a chat screen, asks a question, and an LLM
answers using the player's own OpenRouter API key, the conversation so far, and whichever
stored knowledge is relevant to the question.

Nothing is installed on the Minecraft server. Nothing is required of the server operator.
The mod works on single-player worlds and on any multiplayer server the player is allowed
to use it on.

## Who it is for

Players who want an in-game assistant that actually knows the server they are on — its
rules, its economy, its progression, its boss strategies — instead of a general-purpose
chatbot that has never heard of it.

## The pieces

### Local agent

The player supplies an API key. The mod sends the prompt, the conversation history, and the
retrieved context to the provider and shows the answer in the chat screen — whole, when it is
ready; responses are not streamed yet. Multiple keys can be stored, and a key the provider
rejects is deactivated so the next one takes over without interrupting the conversation.

Each key records the platform it opens, and is only ever sent there. OpenRouter is the
default and the fallback; store a key for `openai`, `deepseek`, `groq`, or a gateway you run
yourself, and questions for that platform's models go straight to it instead. Keys are
encrypted in the database when `MCAGENTS_CHAT_TOKEN_ENCRYPT_KEY` is set in the
environment the game runs in, and stored as pasted when it is not.

### External tools

The agent is not limited to what the mod can do. Ask it to add an **MCP server** — a small
program someone else wrote that publishes a set of tools — and it clones it into
`.minecraft/MCAgents/mcps/`, runs it, and can use its tools from your next question on. No
restart, no configuration file to write.

You never call these tools yourself. The agent decides it needs one, calls it, reads the
result, and carries on until it can answer. If a tool fails or does not exist, that comes
back to the agent as something it can read and recover from, so a broken tool does not break
your question. See [`mcp-servers.md`](mcp-servers.md).

A copy of the mod with no MCP servers is the normal case and behaves exactly as it did
before this existed.

### Knowledge Packs and retrieval

A Knowledge Pack is a unit of stored content — a server's rules, a progression guide, a
farming strategy. Packs are kept in the local database together with their embeddings. When
the player asks a question, the mod embeds the question, finds the closest stored content,
and sends only that to the model. The model never receives the whole library.

The mod has no way to fetch packs: it has no catalog and no download path, and contacts no
server of its own.

Retrieval can be switched off, in the game or in the browser. Off means the question is sent
on its own: no embedding call, no packs attached, nothing charged for it — and chatting is
otherwise unchanged. See [`knowledge-retrieval.md`](knowledge-retrieval.md).

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
| API keys, and the platform each one opens | `{minecraft_dir}/MCAgents/data.db` |
| Knowledge Packs and their embeddings | `{minecraft_dir}/MCAgents/data.db` |
| Bound wallets and their public chain addresses | `{minecraft_dir}/MCAgents/data.db` |
| Agent personas and player instructions | `{minecraft_dir}/MCAgents/AGENTS.md` |

`data.db` is SQLite. `AGENTS.md` is a plain markdown file the player edits to define how
the agent should behave. **The mod writes it the first time it runs** — with a starting
persona and instructions for editing it — and never rewrites it after that. Edit it from
the AGENTS.md button on the chat screen, from the same button on the console's chat page,
or in any text editor: a save takes effect on the next question, whichever of the three
made it.

The agent is also told **where you are playing** — the address of the server you are on, or
that you are in single player — so an answer can take the server into account. Nothing else
about your game is sent: not your inventory, not your coordinates, not your chat log.

## Offline behaviour

**The mod has no server of its own.** With no network it still starts, still opens, still
shows every stored session, and still answers questions using the Knowledge Packs already
stored. Only what asks a model provider for something stops working, and cloning an MCP
server needs a connection to the git host.

Without a key or without internet, the mod cannot
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

The key button opens API key management — add your key there, and pick the platform it opens
from the dropdown beside it (`openrouter` unless you say otherwise). The list holds the
platforms the mod knows and the ones you already hold keys for, and the field still takes
anything you type, so a platform this release has never heard of needs no update to use.
Stored keys are only ever shown masked. Where each platform is called is configured in the web
console. The gear button opens the model settings: a **Provider** row, then a chat model and an
embedding model, each from the suggested lists or typed as any id you like. The lists are a
convenience, not a limit, because OpenRouter adds models faster than this mod ships.

Picking a provider rewrites the chat model, because a model id names both who serves it and
which model it is: staying on the provider you are already on changes nothing, a provider that
cannot serve your current model moves you to its own suggested one, and a provider the mod
suggests nothing for carries your model id over for that provider to accept or refuse. The line
under the row says whether that provider is **called directly** or **through OpenRouter**, and
it is answered by the same code the real request goes through. Typing a model id directly moves
the provider row to match it and keeps the id you typed. The **Embedding** button on that screen turns retrieval on and off — green for on,
red for off.

The globe button beside the key button starts the web console and opens your browser at it.
It keeps running when you close the screen, so you can read in a browser while you play.

The Wallets button in the bottom-left corner opens wallet management: accounts across the
top, wallets down the left, and the selected wallet's chain addresses on the right. Click any
address to copy it in full.

If local storage cannot be opened, the screen still opens and says so, with a Retry button.
It never simply fails to appear.

## What is planned but not built

Streaming is still to come, and so is context injection — inventory, the block you are
looking at, chat logs and scoreboard. See [`architecture.md`](architecture.md) for where
each will live.

## Related

* [`architecture.md`](architecture.md) — how the modules are split and why.
* [`mcp-servers.md`](mcp-servers.md) — external MCP servers: adding them, running them, and
  what happens when one fails.
* [`knowledge-retrieval.md`](knowledge-retrieval.md) — how packs reach a prompt, and the
  switch that stops them.
* [`wallet-binding.md`](wallet-binding.md) — how wallets are held and proved.
* [`web-console.md`](web-console.md) — the browser view of your own data.
* [`../environments/setup.md`](../environments/setup.md) — building and running it.
