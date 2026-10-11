# The Web Console

MCAgentsClient can serve your own data to your own browser. Press `G` in game, then the
globe button beside the key button, and the mod opens the MCAgents web console on
`127.0.0.1` with your Knowledge Packs, your chats, your models, your wallets, and your API
keys.

The console itself belongs to **MCAgents core**, which every MCAgents mod runs on. There is
one console for all of them: each mod registers its pages, and core serves them under one
header, behind one token. MCAgentsClient's pages are under `/chat/`; another mod's pages
appear beside them in the header when it is installed.

It exists because a Minecraft screen is a good place to ask a question and a poor place to
read a thousand-word pack or scan a year of conversations. A browser has a scrollbar, a find
bar, real text selection, and a window as large as your monitor.

## What it is not

**Not a server anyone else can reach.** It binds to the loopback interface on a port the
operating system picks. Nothing outside your machine can connect to it, and nothing about it
is published or announced.

**Not the wallet bridge.** That is a listener which exists for one MetaMask handshake and
stops as soon as the browser has finished with it. The console is the opposite by design: it
runs from when you start it until you stop it, because a browser tab whose server vanished is
a page that stops working while you are reading it.

The console has no need of that bridge for itself. The bridge exists because a Minecraft
screen has no `window.ethereum` and something has to hand the browser a page; `/chat/wallets/`
*is* a page in the browser, so it asks MetaMask directly.

**Not a network dependency.** Everything except asking a new question is local. Closing the
console changes nothing about the game.

## The pages

### `/chat/knowledge/`

Every pack stored on this machine, twenty at a time in a panel that scrolls on its own —
so the search box and the category filters stay where you left them however large the
library grows. The search box filters as you type, across titles and pack text. The chips
filter by category and by free or premium. **Refresh** re-reads the library; nothing leaves
the machine.

On a narrow window the list goes back to flowing with the page, where a fixed box would show
three cards and put its own scrollbar inside the page's.

The list deliberately does not carry pack bodies. An embedding is thousands of numbers, and
reading twenty of them to render a list of titles would be slow for no benefit.

### `/chat/knowledge/<id>`

One pack in full: its identifier, version, category, availability, dates, its text, and its
embedding.

**The pack's content is read-only, on purpose.** There is no form and no endpoint behind
this page that could change one; packs are replaced whole.

It has one button, **Delete**, which removes this machine's copy and returns you to the list.
Nothing can bring the pack back, and embedding it again costs a request to your model
provider, so the button asks first.

### `/chat/chat/`

Your conversations, ten at a time, with a search box that matches titles *and* what was said
inside them. Open one and it behaves as the in-game screen does: type, send, get an answer.

**It writes.** A question asked here goes to the same database, through the same agent, with
the same key — so it costs the same credit and lands in the same conversation you will see
in game. It is not a preview.

**Both directions stay in step.** The mod keeps a counter that moves whenever any
conversation changes, from either side. The browser polls it; the game checks it every tick.
A message typed in Minecraft appears in the browser within a few seconds, and one typed in
the browser appears in the game the same way.

**Answered by** sits on the line above the box you type into: the provider that answers your
questions, picked from a list of the ones the mod knows an address for, the ones its suggested
models come from, and any you hold a key for. Choosing one changes which model is used, because
a model id names both halves — who serves it, and which model it is:

| What you had | What you pick | What you get |
|---|---|---|
| `deepseek/deepseek-v4-flash` | `deepseek` | the same model — picking the provider you are on is not a change |
| `deepseek/deepseek-v4-flash` | `openai` | `openai/gpt-4o-mini` — OpenAI does not serve the DeepSeek model, so OpenAI's own suggestion is used |
| `deepseek/deepseek-v4-flash` | `groq` | `groq/deepseek-v4-flash` — nothing is suggested for Groq, so your model id is carried over and Groq gets to say whether it serves it |

The note beside it names the model that resulted, and the badge says whether that provider is
reached **Direct** or **Via openrouter** — the same two routes `/chat/keys/` explains. **Other…**
takes a provider this release has never heard of. OpenRouter itself is not on the list: it is a
route rather than a provider of models, and `openrouter/gpt-4o-mini` is a model nobody serves.

The choice is the same one `/chat/model/` and the in-game model screen show, in force from your next
question onward wherever you ask it. The game screen has the same row, on the settings screen
behind the gear rather than beside the prompt — see
[`overview.md`](overview.md) — so a player who never opens a browser chooses a provider too.

**AGENTS.md** opens your instructions in a modal with the file's text in it — the same file
the in-game editor writes and the same one every answer starts from. Save writes it and it
takes effect on the next question, in the browser and in the game alike; Cancel writes
nothing. The text is fetched when the button is pressed rather than when the page loads, so
a tab left open all evening cannot save a stale copy over something you typed in game since.
Enter is a new line here, not a save — the buttons, or Ctrl+Enter, commit.

### `/chat/model/`

The chat model and the embedding model, with the suggested ids and the defaults. The lists
are suggestions — type any id your provider serves. Changing the embedding model changes
which vector space new embeddings live in, so it really means re-embedding your library, and
the page says so.

**The Embedding switch** lives here, and here only in the browser. Green is on, red is off,
and the label says which in words — colour is never the only signal. Off means questions are
sent without Knowledge Packs and no embedding is charged for; chatting is otherwise
unchanged. See [`knowledge-retrieval.md`](knowledge-retrieval.md).

It applies on **Save**, like everything else on this page. It was in the chat header too, and
one setting with two homes is one setting that can be shown two ways — the chat copy even had
to behave differently, applying at once because that page has no Save button. The in-game
model screen keeps its own copy, for a player who never opens a browser.

**The chat page's provider selector writes the chat model this page shows.** That is one
setting with one home, reached two ways rather than copied: `/chat/chat/` sends the provider and the
mod decides the id, so what appears in the box above is what was stored, whichever page it was
chosen from.

### `/chat/wallets/`

Every account you keep wallets under, **one panel per account**, side by side. Each panel
shows five wallets and scrolls past that, so an account with forty of them cannot make its
own column forty rows tall and push every other account off the window. On a narrow window
the panels stack and go back to flowing with the page.

The five is measured rather than declared. A wallet row is one line with no address and four
with three, so a height written in the stylesheet showed five rows in one account and two in
the next.

**Connect MetaMask** proves every address the browser shares — each with its own message and
its own single-use nonce — and files each one as a wallet of its own, in that panel's account
and no other. A declined signature skips that address rather than abandoning the ones already
proved. See [`wallet-binding.md`](wallet-binding.md).

**A panel can only act on its own account.** Rename, delete, remove an address, bind one: all
of them name the account, and the mod checks that rather than trusting it. A request naming
one account and a wallet in another changes nothing.

Wallets live on this machine and nothing about them leaves it.

### `/chat/keys/`

Your API keys, ten at a time, with add and remove — and, above them, where each platform is
called.

**A key is never shown in full and never sent to the browser.** The page receives a
shortened form to recognise a key by — `sk-o…mnop` — and an opaque handle to act on it with.
The handle is a hash: it names a key without being one, cannot be used to spend your credit,
and the mod resolves it back to a key internally. Adding a key sends it *inward* over
loopback, the same direction it travels when you type it into the game. Nothing sends one
outward.

**What the page does show is the platform each key opens** — `openrouter`, `openai`, whichever
you chose when you added it. A provider's name is not a secret, and without it two masked keys
look identical.

**The platform is picked from a selector** beside the key box: the gateway, the platforms the
mod ships an address for, the ones its suggested models come from, and any you already hold a
key for. It is a list of suggestions and not a limit — **Other…** reveals a box that takes a
platform this release has never heard of, and stores it exactly as you typed it. Adding a
second key for a platform does not mean choosing it again: the selector keeps your last
answer.

**Where each platform is called** lists every platform you hold a key for, plus the ones the
mod knows an address for. Each row has the address in use and a badge saying which route it
is actually on:

| Badge | Meaning |
|---|---|
| **Direct** | An address and a key for this platform, so questions for its models go straight there |
| **No address** | You hold a key, but nothing knows where to send it — type an address |
| **Via OpenRouter** | No key for this platform, so its models go through the gateway |

An address must be `https`, or a loopback address for a gateway you run yourself; anything
else is refused rather than sent your key. Clearing a box goes back to the address the mod
ships for that platform.

**Once a key is stored for a platform, its address can only change within the same host**, or
go back to the address the mod ships. Moving it to another host is refused, because the next
question would carry your key there, and the page that cannot show your key should not be
able to send it somewhere new. To move a platform to another host, remove its key, change
the address, and add the key again. Type the address before adding the key for a platform
the mod does not know.

**A note above says whether your keys are encrypted on this machine.** They are when
`MCAGENTS_CHAT_TOKEN_ENCRYPT_KEY` is set in the environment the game runs in, and
stored as you pasted them when it is not. The page says which rather than implying the
stronger one.

## Embedding

**Check embeddings** on the chat page asks your model provider to embed the packs that have no
usable vector. It asks first and tells you how many packs it is about to send: each one is a
separate request charged to your API key. Once it has run, the line beside it says how many
packs still need embedding.

Every question the console asks — rename, delete, embed — is a dialog drawn by the
page itself, never a browser popup. Browsers let you silence their own dialogs after the
second one, and a "delete this?" that has been silenced is a deletion.

## What keeps it safe

Every measure here is core's, and covers every mod's pages alike.

| Measure | What it stops |
|---|---|
| Loopback bind, operating-system-assigned port | Anything off the machine reaching it |
| A 256-bit token minted per run, required on every request | Another program on the machine driving it; another web page cannot read it |
| `Host` must be a loopback name | DNS rebinding — a hostile site pointing its own domain at `127.0.0.1` |
| No CORS headers | Another origin reading a reply even if it could send a request |
| Bounded request bodies (256 KiB) and a bounded request queue | A local socket being used to exhaust the game's memory |
| A content security policy that runs only the console's own scripts, styles and requests | A pack's text, or anything else a page shows, running as script; a page reaching off the machine |
| Keys as masks and handles only | Your OpenRouter key reaching a page, a fetch, or the browser's memory |

The token is in the address, which means it is in your browser history. That is acceptable
for something only reachable from your own machine, but it is why the address is worth
treating as private and why a fresh one is minted every time the console starts.

Stylesheets and scripts are served without the token, because a `<link>` in a page cannot
carry one. They are files from the mod jars, identical for every player, and carry nothing of
yours. Every page and every data route stays behind the token.

## Using it

* The console keeps running when you close the screen — that is the point of it. Stop it
  from the same screen when you are done, or it stops when the game exits. Stopping it stops
  every mod's pages, since there is one console.
* The address is shown as well as opened, because the browser that opens by default is often
  not the one you want to read in. Copy it and paste it wherever you like.
* Starting it twice is harmless: you get the same address back rather than a second server.

## Where it lives in the code

| Layer | What it holds |
|---|---|
| core | The server, the token, the `Host` check, the content security policy, the body limit, the header, footer and stylesheets every mod's pages share — `CoreWebRegistry`, `WebModule`, `WebConsole` |
| `api/` | `ChangeFeed`, `Page`, `PageRequest`, `KnowledgeSummary`, `PersonaStore` |
| `common/` | `RevisionedChatRepository`, `WalletChallenges`, `AccountWallets`, `FilePersonaStore` — what the console reads, drives, and writes through |
| `website/` | `ChatWebModule` — chat's pages, registered with core — `WebApi`, `WebJson`, `ApiKeyHandles`, and the pages, scripts and styles under `resources/mcagents/web/` |
| `platforms/fabric/` | `WebConsoleScreen`, `WebConsoleHolder` — which registers chat's pages the first time the console is asked for — and the button on `AgentChatScreen` |

**The console is a module of its own**, `website/`, holding everything a browser ever sees
and nothing else. It sits above `common/`: serving a page means reading storage, driving the
agent, syncing knowledge and filing wallets, so it depends on those — and nothing in
`common/` depends back on it. `platforms/fabric` merges it into the mod jar with the rest,
so the split changes what the source looks like and not what ships. See
[`architecture.md`](architecture.md).

The pages are built from the Silver Glass design system — see
`.agents/design/web-design-system.md`. Everything they use ships inside the jar; nothing is
fetched from anywhere, so the console looks the same with no internet at all.

## Related

* [`architecture.md`](architecture.md) — the module split these layers follow.
* [`knowledge-retrieval.md`](knowledge-retrieval.md) — what the Embedding switch governs.
* [`wallet-binding.md`](wallet-binding.md) — what `/chat/wallets/` manages, and why the in-game
  handshake runs its own short-lived server while this page does not need one.
