---
name: memory-task-embedding-switch
description: Knowledge retrieval and the embedding on/off switch — what was missing, the decisions not to relitigate, and what is proven versus only compiled.
---

# Retrieval and the Embedding Switch

## The finding that shaped the task

The user asked for a switch whose "off" position meant "do not use knowledge". **Retrieval
did not exist.** `ModelClient.embed` was implemented, cached, and called by nothing;
`OpenRouterAgent.respond` read the preferences and the conversation and sent them straight
to the provider. A downloaded pack was browsable and never reached a prompt.

So the switch would have toggled between *no knowledge* and *no knowledge*. Retrieval was
built as part of the same request, because that is the only way the requested behaviour is
real. `README.md`, `AGENTS.md`, and `wiki/information/overview.md` had all described
retrieval as working; `overview.md` also carried "Retrieval is not wired up yet" in a later
section, so the repository contradicted itself on the point.

**Rule this suggests:** a feature described in three documents and implemented in none is
not obvious from any single file. Check the call graph, not the prose.

## Decisions not to relitigate

**One setting, three surfaces.** `model.embedding.enabled` in `settings`, carried on
`ModelPreferences` beside the two model ids. The in-game screen, `/model/`, and the chat
header all read and write that one row, so they cannot disagree.

**`ModelPreferences.of` has no two-argument overload, deliberately.** An overload that
filled in the switch would read like a convenience and behave like a reset — every caller
saving a model selection would quietly turn retrieval back on. The comment saying so is in
the record; do not add the overload back.

**Absent ≠ blank, in the API and in storage.**

* absent — this request is not about that setting; leave it
* blank — the player cleared the box, which is how they ask for the default

Collapsing them broke a real case: the chat toggle sends only `embeddingEnabled`, and
reading the two missing model ids as blanks reset both models to the defaults every time
anyone flipped the switch. Caught in the browser, not by the suite.

**Off costs nothing.** No embedding call, no read of the pack table. A filter applied after
the work would still bill the player for a feature they switched off.

**Retrieval never fails a question.** Embedding failure, unreadable vector, nothing above
the floor — all produce an empty list and an ordinary answer. The player must always be able
to chat. Do not "improve" this into an error path.

**Turning it off does not clear the embedding model id.** Off is not "forget my choice".

**Packs go after the last cache breakpoint and before the question.** `PromptAssembler`
already said retrieved content had to go there; now it does. Anywhere earlier re-bills the
whole conversation every turn. `PromptCachingTest` fails if someone folds it into the
persona — verified by sabotage, three tests bite.

**Different vector lengths mean different vector spaces.** Refused rather than scored, and
logged at INFO, because the symptom a player sees is "the agent stopped knowing things" and
nothing else would explain it.

**Oversized packs are skipped, not truncated.** A rule cut before its exception is worse
than no rule.

**Deferred on `/model/`, immediate on `/chat/`.** The model page has a Save button and every
other control on it waits for Save; the chat header has none. Not an inconsistency to fix.

**Colour is the second signal, never the only one.** The label reads "Embedding on" or
"Embedding off" in words, `aria-pressed` repeats it, and the fill is green or red. The user
asked for the text "Embedding"; the state was added to the words because the design
system's accessibility checklist is binding through AGENTS.md.

## What is proven, and what is not

**Proven by test** (227 tests, all green): the stored flag survives a restart, an unreadable
flag falls back, a save that omits the switch leaves it alone, a save that omits the model
ids leaves them alone, a cleared box still means "default". Retrieval: off makes no embedding
call at all, the closest pack ranks first, orthogonal packs are dropped, the cap and the
character budget hold, an oversized pack is skipped without blocking the next, mismatched and
broken vectors are ignored, a failed embedding call still yields an answer, and the chosen
embedding model is the one used.

**Proven in a browser**: green `#1f7a54` on, red `#d6336c` off, white text on both;
`/model/` writes nothing until Save; the chat toggle persists at once and leaves a
non-default model pair untouched; the chat page reads the stored state on load; chatting
works with the switch off; no console errors.

**Not proven**: nothing has been run inside Minecraft. `ModelSettingsScreen` is
compile-verified only — `platforms/fabric` has no test source set. The layout shift that
moved the save row down to make space for the toggle has not been looked at on a real
screen.

**Not proven end-to-end**: every pack the live service publishes carries `embeddedData` of
`"[]"`, so retrieval currently selects nothing from real content no matter how the switch is
set. The client side is correct and tested against vectors; the catalog has no vectors to
match. That is a server-content gap, not a client one.

## Branches

`feat/embedding-preference` → `feat/knowledge-retrieval` → `feat/embedding-toggle-screen` →
`feat/embedding-toggle-console` → `docs/embedding-switch`, stacked in that order.
