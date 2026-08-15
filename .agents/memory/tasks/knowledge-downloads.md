---
name: memory-task-knowledge-downloads
description: Per-pack download, where each update check happens, and why embedding is the client's job — decisions not to relitigate, and what is proven versus only compiled.
---

# Downloads, and Embedding on the Client

## The question that started it

*"Why does the knowledge detail page have no download button?"* Because `/knowledge/` listed
the local `contents` table, so the only page you could open was about a pack you already had.
There was nothing left to download from it. Downloading was all-or-nothing through Refresh.

The fix is not a button — it is that the console had no way to show a pack you do not own.

## Decisions not to relitigate

**The catalog is a separate section, not rows merged into the library list.** The library is
a window over a local table and pages with an offset; the catalog comes from the network and
vanishes when the service is asleep. Merging them means paging a union of two sources with
different costs and different failure modes, and the whole page would stop working whenever
the service did. `/api/knowledge` and `/api/catalog` stay separate for the same reason.

**An out-of-date pack is counted but not listed as available.** It is already in the library
list, where the player will look for it. Listing it in both places is one pack twice under
two headings.

**The detail route falls back to the service.** That is what makes a pack you do not own have
a page at all, and a page about exactly one pack is the only sensible home for a download
button.

**404 has its own exception type.** `ContentNotPublishedException` means the service answered
and there is no such pack; `CentralServiceException` means it could not tell us. Reporting
"no such pack" for a host that is merely asleep would have a player stop looking for
something that is there.

**Download does not check the version first.** The player pressed the button for that pack. A
button that decided there was nothing to do and did nothing looks broken.

**Startup fetches and checks `mcagents-info` and nothing else.** Tested — one request, by id,
no catalog listing. A game that stalls on somebody else's host while it loads is a game that
stalls, and a player who never opens the console never asked for a library to be kept
current. Everything else is checked when `/chat/` opens.

**Checking and downloading are separate calls.** `checkForUpdates()` is one request that
fetches nothing, which is what lets the chat page say "4 updates available" without spending
a download to find out.

## The architectural one: embedding belongs on the client

Packs are published as **text with no vector**, including the base pack. A vector is only
comparable inside the space of the model that produced it, so a vector shipped by the service
would silently force every player onto whichever model the service happened to use, and would
be wrong for everyone who chose another.

So the service ships text, and `EmbeddingBackfill` embeds what the player stores with the
player's own embedding model. This is what closes the `embeddedData: "[]"` gap recorded in
`central-service.md` — it was never a content bug, it was a missing client feature.

**A downloaded pack is inert until embedded.** That is why there is a button rather than a
hope, and why the chat page reports the count on load.

**"Has the model changed" is read from a recorded fact, not inferred from the vectors.** The
first implementation took the width most stored vectors shared and re-embedded the odd ones
out. A test showed that is exactly backwards after a model change: the stale majority looks
authoritative and the correctly embedded minority looks wrong, so each press pushed the
library *further* from the space it needed. The model is now written to
`knowledge.embedded.model` when a run finishes.

**That model is recorded only after a run with no failures.** Recording it after a partial run
would claim the whole library had moved and hide the stragglers from the next press — the one
thing that would fix them.

**Backfill writes the vector and nothing else.** The version in particular: change it and the
next update check decides the pack changed and downloads it again.

**Backfill runs with the embedding switch off.** The switch governs whether retrieval
happens, not whether a library may be prepared for it.

## What is proven, and what is not

**Proven by test** (265 client, 47 server, all green): single-pack download into an empty
store, download of an already-current pack, the check that fetches nothing, catalog summaries
carrying titles, the available list excluding stored packs, the outdated count, the detail
fallback and its `stored` flag, 404 versus unreachable, the startup budget of one request,
and eleven backfill cases including the model-change re-embed and the partial-run rule.

**Proven in a browser**: the available section lists what is published and missing; a pack's
page shows its body from the service with a Download button and no embedding panel; pressing
it stores the pack and the page becomes the stored view; the pack leaves the available list;
`/chat/` opens saying "4 updates available"; Update knowledge reports "Downloaded 4 packs."
while the notice becomes "4 packs need embedding"; Check embeddings fails cleanly with no
real key and re-enables its button. No console errors.

**Not proven**: nothing has been run inside Minecraft — `platforms/fabric` still has no test
source set. And no pack has been embedded against a real provider: the harness has fake keys,
so the failure path is exercised and the success path is not. A real key would settle it in
one press.

## A trap this walked into

Adding the service fallback to the detail route silently put the whole of
`LocalWebConsoleTest` on the network, where it passed or failed depending on whether a host
that idles happened to be awake. It now takes a stubbed `CentralService`. **When a route
gains a network fallback, check what its tests were relying on not being there.**

## Branches

`feat/real-catalog` (server) · `feat/pack-download` → `feat/knowledge-catalog-page` →
`feat/embedding-backfill` → `feat/chat-update-check` → `docs/knowledge-downloads` (client).
