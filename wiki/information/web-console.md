# The Web Console

MCAgentsClient can serve your own data to your own browser. Press `G` in game, then the
globe button beside the key button, and the mod opens a small web server on `127.0.0.1`
with your Knowledge Packs, your chats, your models, your wallets, and your API keys.

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
screen has no `window.ethereum` and something has to hand the browser a page; `/wallets/`
*is* a page in the browser, so it asks MetaMask directly.

**Not a network dependency.** Everything except asking a new question is local. Closing the
console changes nothing about the game.

## The pages

### `/knowledge/`

Every pack downloaded to this machine, twenty at a time in a panel that scrolls on its own —
so the search box and the category filters stay where you left them however large the
library grows. The search box filters as you type, across titles and pack text. The chips
filter by category and by free or premium.

**Refresh** re-reads your library *and* checks it against the service. A pack the service
has moved past is badged with the version it now publishes — `v1.0.0 published` beside your
`v0.1.0` — so being behind is visible on the pack rather than buried in a count. The button
says how many are behind, or that everything is current. It downloads nothing; **Update
knowledge** on the chat page is what applies them.

A badge only ever repeats a version the service actually reported. Before the catalog has
been read, and again if it stops answering, there are no badges at all — an unreachable
service produces silence rather than a stale claim.

Below it, **Available to download** lists what the service publishes that you do not have,
in a scrolling panel of its own, with **its own Refresh**. That is the half of this page
which depends on the service, so it is the half worth retrying once the banner clears — and
retrying it does not re-read the library, which has not changed.

Both lists are boxed for the same reason: a library or a catalog of any size would otherwise
push the search box, the filters, and the headings that tell the two lists apart off the top
of the window.

**The second panel stays on the page even when it is empty**, saying so — everything
downloaded, nothing published yet, or the service not answering. It used to hide itself, so
pressing Refresh, the one action guaranteed to empty it, collapsed the page from two panels
to one while you were looking at it.

The two are deliberately separate sections rather than rows mixed together. The library is a
window over a local table and pages with an offset, while the catalog comes from the network
and disappears when the service is asleep. Merging them would mean paging a union of two
sources with different costs and different failure modes, and the page would stop working
whenever the service did.

On a narrow window both go back to flowing with the page, where a fixed box would show three
cards and put its own scrollbar inside the page's.

The list deliberately does not carry pack bodies. An embedding is thousands of numbers, and
reading twenty of them to render a list of titles would be slow for no benefit.

### `/knowledge/<id>`

One pack in full: its identifier, version, category, availability, dates, its text, and its
embedding.

**The pack's content is read-only, on purpose.** There is no form and no endpoint behind
this page that could change one. Packs are published by version and replaced whole, so
editing a stored copy would only put you out of step with the next update.

**This page opens for a pack you have not downloaded**, shown from the service, and that is
where the **Download** button lives — it is the only page that is about exactly one pack. A
downloaded pack shows its embedding instead; one you do not have has none, because nothing
has embedded it yet.

A pack you *do* have also gets two buttons of its own:

* **Check for update** asks about this pack alone. The chat page checks the whole library;
  neither replaces the other, because a reader looking at one pack did not ask about every
  other one.
* **Delete** removes this machine's copy. It does not unpublish anything — the pack goes
  straight back into *Available to download*, which is exactly what has happened. Downloading
  it again means embedding it again, so the button asks first.

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

### `/model/`

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

### `/wallets/`

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

Nothing here needs the central service, so none of it is disabled when the service is asleep.
Wallets live on this machine and the service has never heard of them.

### `/keys/`

Your OpenRouter keys, ten at a time, with add and remove.

**A key is never shown in full and never sent to the browser.** The page receives a
shortened form to recognise a key by — `sk-o…mnop` — and an opaque handle to act on it with.
The handle is a hash: it names a key without being one, cannot be used to spend your credit,
and the mod resolves it back to a key internally. Adding a key sends it *inward* over
loopback, the same direction it travels when you type it into the game. Nothing sends one
outward.

## When the service is asleep

The central service is hosted somewhere that idles, so the first request after a quiet
period is often a refused connection. The console treats that as ordinary: a banner appears,
it checks again once a minute for ten minutes, and then stops — a page that retried forever
would poll a host that is not coming back until you closed the tab. **Reconnect** restarts
that budget, and the banner disappears entirely once the service answers.

Nothing on any page depends on it. The lists, the conversations, and the keys all work while
it is unreachable; only the refresh and update buttons are disabled, because they are the
only things that need it.

**Refresh** on the knowledge page downloads nothing. It re-reads your library, checks it
against the catalog, and badges whatever is behind.

**Update knowledge** on the chat page brings the packs you already have up to their published
versions, and takes nothing new. A new pack is downloaded from its own page, one at a time,
where you can see what you are taking. It asks before it runs, because updating a pack drops
its embedding — so the real cost of pressing it includes embedding the result. The service
rate-limits these reads, so pressing repeatedly earns a short wait rather than a faster
answer.

The chat page also **checks** when it opens, and says how many updates are waiting. That
check is one request and downloads nothing — pressing the button is still the only thing
that spends bandwidth. Startup checks one pack and one pack only, `mcagents-info`; see
[`knowledge-retrieval.md`](knowledge-retrieval.md).

**Check embeddings** beside it is not a service operation at all. It asks your model
provider to embed the packs that have no usable vector, so it keeps working while the
service is asleep and is never disabled with the buttons that need it. It asks first and
tells you how many packs it is about to send: each one is a separate request charged to your
API key.

Every question the console asks — rename, delete, update, embed — is a dialog drawn by the
page itself, never a browser popup. Browsers let you silence their own dialogs after the
second one, and a "delete this?" that has been silenced is a deletion.

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
| `common/` | `LocalWebConsole`, `WebApi`, `WebJson`, `WebAssets`, `ApiKeyHandles`, `RevisionedChatRepository`, `WalletChallenges`, `AccountWallets`, and the pages under `resources/mcagents/web/` |
| `platforms/fabric/` | `WebConsoleScreen`, `WebConsoleHolder`, and the button on `AgentChatScreen` |

The pages are built from the Silver Glass design system — see
`.agents/design/web-design-system.md`. Everything they use ships inside the jar; nothing is
fetched from anywhere, so the console looks the same with no internet at all.

## Related

* [`architecture.md`](architecture.md) — the module split these layers follow.
* [`knowledge-retrieval.md`](knowledge-retrieval.md) — what the Embedding switch governs.
* [`wallet-binding.md`](wallet-binding.md) — what `/wallets/` manages, and why the in-game
  handshake runs its own short-lived server while this page does not need one.
