# The Web Console

MCAgentsClient can serve your own data to your own browser. Press `G` in game, then the
globe button beside the key button, and the mod opens a small web server on `127.0.0.1`
with three pages: your Knowledge Packs, your chats, and your API keys.

It exists because a Minecraft screen is a good place to ask a question and a poor place to
read a thousand-word pack or scan a year of conversations. A browser has a scrollbar, a find
bar, real text selection, and a window as large as your monitor.

## What it is not

**Not a server anyone else can reach.** It binds to the loopback interface on a port the
operating system picks. Nothing outside your machine can connect to it, and nothing about it
is published or announced.

**Not the wallet bridge.** That is a listener which exists for one MetaMask handshake and
stops the instant a signature verifies. The console is the opposite by design: it runs from
when you start it until you stop it, because a browser tab whose server vanished is a page
that stops working while you are reading it.

**Not a network dependency.** Everything except asking a new question is local. Closing the
console changes nothing about the game.

## The pages

### `/knowledge/`

Every pack downloaded to this machine, twenty at a time in a panel that scrolls on its own —
so the search box and the category filters stay where you left them however large the
library grows. The search box filters as you type, across titles and pack text. The chips
filter by category and by free or premium.

The list deliberately does not carry pack bodies. An embedding is thousands of numbers, and
reading twenty of them to render a list of titles would be slow for no benefit.

### `/knowledge/<id>`

One pack in full: its identifier, version, category, availability, dates, its text, and its
embedding.

**Read-only, on purpose.** There is no form and no endpoint behind this page that could
change a pack. Packs are published by version and replaced whole, so editing a stored copy
would only put you out of step with the next update.

### `/chat/`

Your conversations, ten at a time, with a search box that matches titles *and* what was said
inside them. Open one and it behaves as the in-game screen does: type, send, get an answer.

**It writes.** A question asked here goes to the same database, through the same agent, with
the same key — so it costs the same credit and lands in the same conversation you will see
in game. It is not a preview.

**Both directions stay in step.** The mod keeps a counter that moves whenever any
conversation changes, from either side. The browser polls it; the game checks it every tick.
A message typed in Minecraft appears in the browser within a few seconds, and one typed in
the browser appears in the game the same way.

### `/keys/`

Your OpenRouter keys, ten at a time, with add and remove.

**A key is never shown in full and never sent to the browser.** The page receives a
shortened form to recognise a key by — `sk-o…mnop` — and an opaque handle to act on it with.
The handle is a hash: it names a key without being one, cannot be used to spend your credit,
and the mod resolves it back to a key internally. Adding a key sends it *inward* over
loopback, the same direction it travels when you type it into the game. Nothing sends one
outward.

## What keeps it safe

| Measure | What it stops |
|---|---|
| Loopback bind, operating-system-assigned port | Anything off the machine reaching it |
| A 256-bit token minted per run, required on every request | Another program on the machine driving it; another web page cannot read it |
| `Host` must be a loopback name | DNS rebinding — a hostile site pointing its own domain at `127.0.0.1` |
| No CORS headers | Another origin reading a reply even if it could send a request |
| Bounded request bodies | A local socket being used to exhaust the game's memory |
| Keys as masks and handles only | Your OpenRouter key reaching a page, a fetch, or the browser's memory |

The token is in the address, which means it is in your browser history. That is acceptable
for something only reachable from your own machine, but it is why the address is worth
treating as private and why a fresh one is minted every time the console starts.

Stylesheets and scripts are served without the token, because a `<link>` in a page cannot
carry one. They are files from the mod jar, identical for every player, and carry nothing of
yours. Every page and every data route stays behind the token.

## Using it

* The console keeps running when you close the screen — that is the point of it. Stop it
  from the same screen when you are done, or it stops when the game exits.
* The address is shown as well as opened, because the browser that opens by default is often
  not the one you want to read in. Copy it and paste it wherever you like.
* Starting it twice is harmless: you get the same address back rather than a second server.

## Where it lives in the code

| Layer | What it holds |
|---|---|
| `api/` | `WebConsole`, `WebConsoleException`, `ChangeFeed`, `Page`, `PageRequest`, `KnowledgeSummary` |
| `common/` | `LocalWebConsole`, `WebApi`, `WebJson`, `WebAssets`, `ApiKeyHandles`, `RevisionedChatRepository`, and the pages under `resources/mcagents/web/` |
| `platforms/fabric/` | `WebConsoleScreen`, `WebConsoleHolder`, and the button on `AgentChatScreen` |

The pages are built from the Silver Glass design system — see
`.agents/design/web-design-system.md`. Everything they use ships inside the jar; nothing is
fetched from anywhere, so the console looks the same with no internet at all.

## Related

* [`architecture.md`](architecture.md) — the module split these layers follow.
* [`wallet-binding.md`](wallet-binding.md) — the other thing the mod serves in a browser,
  and why its server works the opposite way.
