---
name: memory-task-per-chat-models
status: in-progress
description: Each conversation sets its own chat model and embedding model, each with its provider, falling back to core 0.13.0's global model when unset; released as 0.13.0.
---

# Per-chat models

## The request

On 2026-10-17 the owner asked, as the fourth of four tasks: "Make each chat can set it own
model for ai chat and ai embedded. Plan then work immediately." The third task made the Models
page core's global model, "if each task not set their specific model then fallback to these
model". The owner asked for the four to be done in order and had already asked for every branch
to be opened as a pull request and rebase-merged in order. The plan is `plans/per-chat-models.md`
in the project's shared files.

## Decisions

- **Stored on the conversation.** Four nullable columns on `chat_sessions`: `chat_provider`,
  `chat_model`, `embedding_provider`, `embedding_model`, added by the guarded `ALTER TABLE` list.
  Null means "use the global model". Deleting a conversation deletes its choice with it, which
  settings rows keyed by session id would not.
- **One fallback rule.** A conversation's choice is `chat-api`'s `SessionModels`, two
  `ModelChoice`s that may each be null, resolved per question with `ModelChoice.orElse(own,
  global)`, the rule core's `Models.chat(own)` and `Models.embedding(own)` apply. A conversation
  left on Global follows the Models page at once.
- **Retrieval in another model.** The library's vectors are in the model recorded as
  `knowledge.embedded.model`, or the global embedding model when none is recorded. A conversation
  whose embedding model differs embeds the packs with its own model, through the embedding
  cache, rather than comparing vectors from two spaces. The embedding switch stays global.
- **The picker is a hologram**, like every page since 0.11.0: MODEL under the conversation opens
  a Chat Model form in the Models page's style. A new conversation can be set before its first
  question; the choice is stored with it.
- **A minor release, 0.13.0**, on core 0.13.0. Core and the template do not change.

## Tasks

| # | Title | Branch | PR |
|---|---|---|---|
| 1 | Task record | `chore/per-chat-models-plan` | |
| 2 | Per-chat models in storage and answers | `feat/session-models` | |
| 3 | Chat Model hologram | `feat/chat-model-hologram` | |
| 4 | Release 0.13.0 | `release/0.13.0` | |

### Task 1 — chore/per-chat-models-plan

This record, and its row in the memory index.
