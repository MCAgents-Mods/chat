---
name: memory-task-console-dialogs
description: Why the console draws its own dialogs, what each sync button is now allowed to do, and the self-inflicted deletion that a browser caught.
---

# Dialogs, and What a Button Is Allowed to Cost

## The dialog

`window.confirm` and `window.prompt` are gone from every page this project serves, and the
rule is in the design system so they do not come back. Three reasons, in the order they
bite:

1. **The browser owns the wording and the button order.** "OK" is the same word for renaming
   a chat and for deleting one, so the control cannot say what it is about to do.
2. **They can be switched off.** Every major browser offers "prevent this page from creating
   more dialogs" after the second one; from then on `confirm()` returns false and `prompt()`
   returns null for the session. **A destructive action guarded by a suppressed confirm is an
   action that has silently lost its guard.** This is the reason that matters.
3. They cannot be styled, tested, or driven like the rest of the console.

Decisions worth keeping:

* **Resolves on cancel, never rejects.** Cancelling is an ordinary answer; a caller forced to
  catch it would treat "no" as a failure.
* **Focus lands on the text box, or on Cancel — never on the confirming button.** The dialog
  opens under wherever the reader was already clicking, and a stray second click must not
  land on the destructive choice.
* **Opaque, no `backdrop-filter`**, per the overlay rule already in the design system: inside
  a blurred parent a filtered child loses its background paint and the page shows through the
  thing meant to hide it.
* Backdrop dismissal only when the press *started* on the backdrop, so a drag that selected
  text does not close it.

## What each button costs, and what it is allowed to do

Both sync buttons downloaded the whole published catalog. Neither label said so, and the
cost was not only bandwidth: a new pack arrives with no vector, so a click that fetched
twenty packs also committed the player to twenty billable embedding calls for content they
never chose.

| Button | Now does | Asks first |
|---|---|---|
| Refresh (`/knowledge/`) | re-reads both lists; downloads nothing | no — it spends nothing |
| Update knowledge (`/chat/`) | updates packs already stored; takes nothing new | yes |
| Check embeddings (`/chat/`) | embeds packs with no usable vector | yes, naming the count |
| Download (pack page) | one pack, chosen deliberately | no — the choice *is* the click |

**A pack the service stops publishing is left alone.** Unpublishing is not deleting, and
removing somebody's pack because a catalog changed under them is not an update.

`KnowledgeSync.refresh()` was deleted rather than left unused. "Fetch everything published"
is now something the console deliberately does not offer, and a public method sitting beside
`updateStored()` is an invitation to wire it back up.

## The mistake, and what caught it

Replacing the `wireSyncButton` block in `site.js` by matching from its doc comment to the
next section marker **also deleted `paintToggle`**, which happened to live inside that range.
Nothing in Java noticed; the build was green. The page threw `paintToggle is not defined` on
load, the whole ready handler aborted, and `/knowledge/` rendered an empty catalog panel with
a Refresh button that did nothing.

Two things follow:

* **A text-range replacement is only as safe as what is inside the range.** Matching on
  "from this comment to that marker" assumes nothing else was ever put between them.
* **`node --check` on every served script is worth running before a browser is opened.** It
  would not have caught this one — the file parses fine — but the browser run did, in the
  first probe, because the page reported the error rather than merely misbehaving.

The symptom that led there was misleading in a useful way: the refresh button producing *no
note at all* meant its listener had never been attached, which pointed at the handler
aborting rather than at the button.

## What is proven

**By test** (281 client): update takes nothing new, leaves an unpublished pack alone, reads
every catalog page, survives one pack failing, and still sends no key.

**In a browser**: all four dialogs open the console's own with no native dialog firing at
any point; Escape cancels; Refresh reports "Reloaded." with the stored count unmoved; Update
knowledge confirms, cancels cleanly, and leaves six available packs untaken; Check embeddings
names the count; `/model/` has zero `datalist` elements and its chips still fill.

**Not proven**: nothing has run inside Minecraft, and no pack has been embedded against a
real provider.

## Branches

`feat/console-dialog` → `feat/refresh-and-update-scope` → `refactor/model-page-datalist` →
`docs/console-dialogs`.
