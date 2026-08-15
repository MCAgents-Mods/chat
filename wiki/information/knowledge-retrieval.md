# Retrieval, and the Embedding Switch

The mod can put relevant Knowledge Packs in front of your question before it asks the
model. This page is what that means, what it costs, and how to turn it off.

## Getting packs onto your machine

Packs live on the knowledge service and do nothing until they are on your disk. There are
three ways to get them there, and they exist for different moments.

| | What it fetches | Where |
|---|---|---|
| **Automatic, at startup** | `mcagents-info` only | Every time the game loads |
| **Download** | One pack | That pack's page, `/knowledge/<id>` |
| **Update knowledge** / **Refresh** | Everything missing or out of date | The chat page, and `/knowledge/` |
| **Check for update** | Tells you about one pack; downloads nothing | That pack's page |

**Only one pack is fetched without being asked for**, and only at startup: the pack
describing the mod, so the agent can answer "what are you". A game that stalled on somebody
else's host while it loaded would be a game that stalled, so nothing else is checked there.

**Everything else is checked when the chat page opens.** It tells you how many updates are
waiting — that check is one request and downloads nothing. Pressing **Update knowledge** is
what actually fetches them.

To browse what exists without taking all of it, open `/knowledge/`. Below your own library
is **Available to download**: packs the service publishes that you do not have. Open one and
its page has a **Download** button. That page works for a pack you do not own — the console
shows it from the service — which is the only reason a download button can live there.

## Embedding what you downloaded

**A pack you just downloaded cannot be retrieved against yet.** It arrives as text with no
vector, and it stays inert until it has one.

That is on purpose. A vector is only comparable inside the space of the model that produced
it, so a vector shipped by the service would silently force every player onto whichever
model the service happened to use. Instead the service ships text and *your* client embeds
it with *your* embedding model.

**Check embeddings**, in the chat page header, is what does it. It embeds every stored pack
that has no usable vector, one at a time, with your embedding model. The header also tells
you how many are waiting — counting is free, so the page says so on load rather than leaving
you to wonder why the agent knows nothing about a pack you can plainly see.

It also re-embeds the whole library when you change your embedding model, because that is
exactly what changing it means. The mod records which model embedded the library, so it
knows; it does not guess from the vectors.

**Updating a pack drops its embedding.** A new version is new text, and the old vector
describes text that is no longer there — it would keep matching questions about wording that
has gone and keep the pack out of results it now belongs in, while still showing as
*Embedded*. So the vector goes and the pack asks to be embedded again. Re-downloading a pack
at the version you already have keeps its embedding, because that text is identical.

The button asks your model provider, not the knowledge service, so it works while the
service is asleep — and it works with the Embedding switch off, because the switch governs
whether retrieval happens, not whether you may prepare for it.

## What happens when you ask something

1. Your question is sent to your **embedding model**, which returns a vector — a list of
   numbers standing for what the question is about.
2. Every downloaded pack that carries an embedding is compared against it. The comparison
   is cosine similarity: how nearly the two point the same way.
3. The closest few packs are attached to the request, just ahead of your question.
4. The model answers with them in view.

Step 1 is the only part that leaves your machine before the answer itself. Steps 2 and 3
are arithmetic over the packs already on your disk.

**Only the newest question is matched, not the conversation.** A thread that started about
redstone and wandered onto villagers should retrieve villager packs, and scoring the whole
history would keep it anchored to what it used to be about.

## The switch

There is one control, in two places: the **Embedding** button on the in-game model screen,
and the same button on `/model/` and in the chat header of the web console. Green means on,
red means off, and the label says which in words.

**Off means off.** No embedding call is made, the pack table is not read, and nothing is
attached. It is not a filter applied after the fact — the work simply does not happen, and
you are not billed for it.

**Chat is unaffected.** Questions, history, personas, model choice, every screen: all
identical. The only difference is that answers are drawn from the model's own knowledge and
your conversation rather than from your packs.

It is one stored setting, so the game and the browser can never disagree. Flip it in either
and the other shows it.

### Why you might turn it off

* **Cost.** Retrieval is one extra provider call per question.
* **Your packs are not relevant to what you are asking.** Attaching a server's rules to a
  question about Java syntax spends tokens to make the answer slightly worse.
* **Your library is embedded with a different model** and you have not re-embedded it yet
  — see below.
* **You want the model's own answer**, unshaped by anything you downloaded.

## What is *not* switched off

Downloading packs, browsing them, reading them, and searching them all keep working. The
switch governs whether packs reach a **prompt**, not whether they exist.

## Limits, and why they are there

| Limit | Value | Why |
|---|---|---|
| Packs per question | 3 | Every attached pack is tokens paid for on that turn, and a model given ten loosely related documents answers worse than one given two relevant ones. |
| Characters per question | 6,000 | So one very large pack cannot crowd out the conversation it was meant to support. |
| Similarity floor | 0.2 | A guard against nonsense. Without it, the best of a library of unrelated packs is still the best, and a question about redstone gets answered with whatever scored highest. |

A pack too large for the remaining budget is **skipped, not truncated**. Half a pack can
contradict its own second half, and a rule cut before its exception is worse than no rule.

## When retrieval goes quiet

**The usual cause is a changed embedding model.** Packs embedded by one model and questions
embedded by another are not points in the same space; the numbers would multiply happily and
mean nothing. The mod detects this — vectors of different lengths cannot be compared — skips
those packs, and says so in the log. The fix is to re-embed the library, not to change the
switch.

**A pack published without an embedding never takes part** until you embed it. The list
marks these *No embedding*; **Check embeddings** on the chat page fixes them.

**Retrieval failing never stops an answer.** If the embedding call fails — no key, rate
limited, provider down, a model id that does not exist — you get an answer without the extra
context rather than an error about it. Retrieval improves an answer; it is not a
precondition for one.

## What it costs you in tokens

Attached packs sit *after* the cached part of the prompt, immediately before your question.
That placement is deliberate: providers reuse a prompt by matching a prefix, and putting
content that changes every turn near the front would re-bill the whole conversation behind
it on every single turn. Turning retrieval on or off does not move that boundary, so
flipping the switch never costs you an uncached turn.

## Where it lives in the code

| Layer | What it holds |
|---|---|
| `api/` | `ModelPreferences` — the two model ids and the switch, loaded together |
| `common/` | `KnowledgeRetriever` (choosing), `Vectors` (parsing and cosine), `PromptAssembler` (placement), `EmbeddingBackfill` (giving packs a vector), `KnowledgeSync` (downloading) |
| `platforms/fabric/` | the toggle on `ModelSettingsScreen` |

## Related

* [`web-console.md`](web-console.md) — the browser view, including `/model/`.
* [`overview.md`](overview.md) — what the mod is, in full.
