# MCAgentsClient — Overview

MCAgentsClient is a **client-side only** Minecraft mod for the Fabric, NeoForge and Forge
loaders. It puts an AI assistant inside the game: the player opens a chat hologram, asks a
question, and an LLM answers using the player's own OpenRouter API key, the conversation so
far, and whichever stored knowledge is relevant to the question.

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
retrieved context to the provider and shows the answer on the chat hologram — whole, when it is
ready; responses are not streamed yet. Multiple keys can be stored, and a key the provider
rejects is deactivated so the next one takes over without interrupting the conversation.

Each key records the platform it opens, and is only ever sent there. Each model is called
through the provider picked for it on the Models hologram: OpenRouter by default, or
`openai`, `deepseek`, `groq`, or any OpenAI-compatible API you add there, called at its own
address. Keys are
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

Retrieval can be switched off on the Models hologram. Off means the question is sent
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

### Local storage

Everything the mod remembers lives on the player's own machine:

| What | Where |
|---|---|
| Chat sessions and messages | `{minecraft_dir}/MCAgents/data.db` |
| API keys, and the platform each one opens | `{minecraft_dir}/MCAgents/data.db` |
| The global model: chat and embedding models, their providers, custom providers | `{minecraft_dir}/MCAgents/agents/models.properties` (core's) |
| Knowledge Packs and their embeddings | `{minecraft_dir}/MCAgents/data.db` |
| Bound wallets and their public chain addresses | `{minecraft_dir}/MCAgents/data.db` |
| Agent personas and player instructions | `{minecraft_dir}/MCAgents/AGENTS.md` |

`data.db` is SQLite. `AGENTS.md` is a plain markdown file the player edits to define how
the agent should behave. **The mod writes it the first time it runs** — with a starting
persona and instructions for editing it — and never rewrites it after that. Edit it with
**PERSONA** on the MCAgents menu or in any text editor: a save takes effect on the next
question, whichever of the two made it.

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

Everything happens in the game; there are no pages in a browser. Press `G` for the MCAgents
menu, a hologram standing in front of you with its buttons five to a row, and chat adds five
of them. Point at a hologram and hold `Z` to carry it with you; let go and it stays where it
is. Walk more than two chunks away from it and it closes.

**CHAT** opens the **Chat Sessions** hologram in place of the menu: every conversation, the
most recently used first, with **NEW CHAT** on the right of the bar across its top. It stays
open while you pick.

* **Click a conversation** to open its chat hologram beside the list. While that hologram
  stands, the conversation stays in the list but is faded, and clicking it does nothing; close
  the chat hologram and it can be picked again.
* **NEW CHAT** opens a chat hologram on a new conversation. It joins the list, faded, with
  your first question.

Several chat holograms can stand at once, up to eight holograms in all, and each waits for its
own answer. A chat hologram has two panels side by side:

* **Conversation**, headed with the conversation's title, shows each line headed with your
  name or `Agent`. While an answer is on its way the last line says `Thinking…`. **RENAME**
  retitles the conversation, in a small hologram that opens beside it, and **DELETE** removes
  it and closes its hologram — after asking, in the same kind of hologram, because it takes
  every message in that conversation with it and cannot be undone. **MODEL** opens **Chat
  Model** for that conversation alone: switch on **Own chat model** or **Own embedding model**
  and pick a provider and a model, as on the Models page, then **SAVE**. A switch left off
  follows the Models page, so changing the global model there changes it here too; **USE
  GLOBAL** turns both off. A new conversation can be set before its first question.
* **User Prompt** is where you type. **SEND** asks the question in that conversation, and is
  greyed while an answer is on its way in it.

If the agent cannot answer — no key, a model the provider does not serve, a rate limit — the
reason appears as the agent's line in that conversation, in the provider's own words, until
the next question. An answer still on its way when you close a chat hologram is still stored,
and shows when you open the conversation again.

**KEYS** opens the Keys hologram in place of the menu: an **Add Key** form beside the list of
**Stored Keys**. Paste your key into the first box, where it shows as stars, and pick the
platform it opens from the arrow beside the box under it (`openrouter` unless you say
otherwise). The list holds the platforms the mod knows and the ones you already hold keys for,
and the box still takes anything you type, so a platform this release has never heard of needs
no update to use. **ADD** stores the key and empties the box. Stored keys are only ever shown
masked; pick one and press **REMOVE** to delete it. Where a platform is called is set on the
Models hologram, under **Providers**. The line at the bottom says whether stored keys are
encrypted.

**MODEL** opens the Models hologram, the **global model**: what every task without a model of
its own is answered and embedded with. It has a **Chat provider** and a **Chat model**, then an
**Embedding provider** and an **Embedding model**, each picked from the arrow's suggestions or
typed. The model lists are a convenience, not a limit, because OpenRouter adds models faster
than this mod ships. Nothing is stored until **SAVE**; **USE DEFAULTS** puts the defaults back
in the boxes.

The provider is the API the request is sent to, and nothing else: pick `deepseek` and the
question goes to `api.deepseek.com` with your deepseek key. The line under each provider says
where it is called, in amber when no key is stored for it yet (the question then fails asking
for one, rather than going through OpenRouter), and in red when no address is known for it.
Picking a provider the mod suggests models for moves the chat model onto one it serves; a
provider it suggests nothing for, such as one you added, keeps your model id. The **Embedding**
switch turns retrieval on and off — green for on, red for off.

The **Providers** list beside the form holds the providers you added. **ADD** opens a form for
a name, an address (`https://`, or a loopback address for one you run yourself) and, if you
like, its API key, which is stored like one added on the Keys hologram. A name like a built-in
provider's, such as `deepseek`, replaces that provider's address. Pick a row and **REMOVE** to
drop it; a model that used it moves back to OpenRouter. Both take effect at once.

**PERSONA** opens the editor for `AGENTS.md` as a hologram: type into its box, **SAVE** writes
the file, and **RELOAD** reads it again, dropping what you typed. Closing it without saving
leaves the file as it was.

**WALLETS** opens the Wallets hologram: three lists side by side, your accounts, the chosen
account's wallets and the chosen wallet's chain addresses. **COPY** puts an address on the
clipboard in full. See [`wallet-binding.md`](wallet-binding.md).

If local storage cannot be opened, the Chat Sessions hologram still opens and says so in its
list, and **NEW CHAT** tries again. KEYS, MODEL and WALLETS say why on the action
bar instead of opening; PERSONA works either way, because `AGENTS.md` is a file. Nothing
simply fails to appear.

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
* [`../environments/setup.md`](../environments/setup.md) — building and running it.
