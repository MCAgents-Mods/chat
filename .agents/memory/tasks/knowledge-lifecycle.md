---
name: memory-task-knowledge-lifecycle
description: A pack's life on one machine — arriving, being embedded, going stale, and being removed — and why the catalog panel never hides.
---

# The Life of a Pack on One Machine

## What the round started from

A screenshot of `/knowledge/` after pressing Refresh, with the question "why does it look
like this?". Reproducing it exactly — empty library, Refresh against the live service —
showed a clean layout, no console errors, and the panel sized to its content. **The
reproduction was what made the question answerable**, because it ruled out everything the
screenshot could have been about except the one thing it was.

The answer: the page had two panels, and Refresh removed the second. Refresh is the one
action guaranteed to empty "Available to download", so the reliable result of using the
button was the page collapsing from two panels to one under the reader's cursor.

**Rule:** a panel that says "nothing here" is information. A panel that vanishes is a page
rearranging itself, and the reader has to work out whether something broke. Empty states,
not `hidden`.

## The embedding lifecycle, settled

**An update drops the pack's embedding.** A new version is new text; the stored vector
describes text that is no longer there. It would keep matching questions about wording that
has gone and keep the pack out of results it now belongs in — while still showing as
*Embedded*. Wrong, and invisible.

**Re-downloading the same version keeps it.** The download button does not check the version
first, so it will re-fetch a pack the player already has. That text is byte-identical, so the
vector still describes it, and discarding it would charge them to compute the same numbers
twice.

Version is the protocol's word for "the text changed" — the service publishes a new version
rather than editing one in place — so comparing versions is comparing texts.

**A test fixture was hiding this entirely.** `fullPack()` returned `"embeddedData":"[0.1]"`,
so every update in the suite looked like it preserved an embedding, because the stub was
inventing one the real service never sends. The fixture now publishes `""`, like the service.
**When a test double is more generous than the real thing, it tests a system that does not
exist.**

## Being behind is shown, not counted

`/api/catalog` compared versions from the start but returned only a count, so the library
list could not mark the pack that was stale — the reader had to open each one to find it. It
names them now (`outdatedItems`: id, stored version, published version) and the card carries
the published version as a badge.

**A badge is only ever a version the service reported.** The map is empty before the catalog
is read and emptied again when it cannot be, so an unreachable service shows nothing rather
than a claim it can no longer support. That is the same rule the catalog panel follows.

**The catalog is read before the list is rendered**, because the badges depend on it; a later
catalog read that changes the answer re-renders. The two lists load independently and the
local one always wins the race, so without that the first paint would be unbadged.

Each list has its own Refresh. The catalog's is the one worth pressing after the service
wakes up, and pressing it does not re-read a local table that has not changed.

## Where each operation lives, and why

| Operation | Scope | Where |
|---|---|---|
| Startup fetch | `mcagents-info` only | the loader |
| Check for update | one pack | that pack's page |
| Update knowledge / Refresh | the library | chat page, knowledge page |
| Check embeddings | the library | chat page |
| Download / Delete | one pack | that pack's page |

The per-pack and whole-library checks are not substitutes. A reader looking at one pack did
not ask about every other one; a player about to ask a question does not want to check packs
one at a time.

**Deleting is local and says so.** It unpublishes nothing, and the pack reappears under
"Available to download" — the honest outcome, since that is exactly what happened. It is
confirmed because it is not undoable from there: downloading again means embedding again,
and paying for it again.

## One setting, one home

The embedding switch was on `/model/` **and** in the chat header. One setting with two homes
is one setting that can be shown two ways, and the two copies had already diverged in
behaviour — the chat one applied immediately because that page has no Save button. It now
lives on `/model/` in the browser, plus the in-game screen for a player who never opens one.

`wireEmbeddingToggle` went with it. A shared helper for a control that no longer exists is a
comment about a page nobody will find.

## A process note

The delete/update work and the toggle removal were committed together by accident and then
split back apart before pushing. Worth the two minutes: the branches merge in order, and a
commit carrying two unrelated concerns is one that cannot be reverted without taking the
other with it.

## What is proven

**By test** (280 client, 47 server): the version rule in both directions and through both
download paths, delete including the 404 for a pack that is not there, and the per-pack check
costing exactly one request.

**In a browser**: both panels survive Refresh with the second reading "Everything published
is already downloaded."; the stored-actions panel appears only for a stored pack; Check for
update reports the published version; Delete returns the page to a download offer and the
pack to the available list; the chat header has no toggle and `/model/` still does.

**Not proven**: nothing has run inside Minecraft, and no pack has been embedded against a
real provider.

## Branches

`fix/available-section-persists` → `feat/update-clears-embedding` → `feat/knowledge-delete` →
`refactor/chat-toggle-removal` → `docs/knowledge-panels`.
