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

### Task 2 — feat/session-models

A conversation's models are stored and answered with.

- `chat-api`: `SessionModels(ModelChoice chat, ModelChoice embedding)`, either null for the
  global model, with `applyTo(ModelPreferences)` resolving by `ModelChoice.orElse`.
  `ChatRepository` gains `findSessionModels` (`GLOBAL` for a conversation with none, or none at
  all) and `saveSessionModels` (false when the conversation does not exist).
- `SqliteSchema` adds `chat_provider`, `chat_model`, `embedding_provider` and `embedding_model`
  to `chat_sessions` through the guarded column list; `SqliteChatRepository` reads a blank model
  as no choice and moves a retired id off, and saving leaves `updated_at` alone.
  `RevisionedChatRepository` moves its revision when a save changed a row.
- `OpenRouterAgent` reads the global preferences, the conversation's models and the library's
  model (`knowledge.embedded.model`, else the global embedding model) per question.
  `KnowledgeRetriever.retrieve(preferences, question, libraryModel)` embeds the packs with the
  question's model when the two differ, leaving out a pack whose call fails.
- Tests: `SqliteSessionModelsTest` (8, including a database from before the columns),
  `PerChatModelsTest` (4), three more in `KnowledgeRetrieverTest`; `:chat-common:test` passes,
  413 tests. The schema page, the architecture and retrieval pages and the state file describe it.

### Task 3 — feat/chat-model-hologram

MODEL under a conversation, between RENAME and DELETE, opens its Chat Model hologram,
`mcagents.chatmodel.{the chat hologram's id after mcagents.chat.}`.

- `ChatModelHologram` (a `HologramPage`) is a wide form in the Models page's style: "For
  {title}", an **Own chat model** switch over the chat provider (with the route note) and model,
  the same for embedding, a status line, **SAVE** and **USE GLOBAL**. A field whose switch is
  off shows the global choice, greyed; turning it on starts from the global choice. SAVE refuses
  a provider with no address, as the Models page does, and a blank model. DELETE closes the
  conversation's model page with its chat hologram.
- `ChatConversation` holds the conversation's `SessionModels`, read with its messages;
  `chooseModels` stores them at once, or, for a new conversation, keeps them and stores them
  with the conversation before its first question is appended, so the agent answers that
  question with them. Three tests in `ChatConversationTest`; 416 in `common/`.
- The overview, README, repository map and state file describe MODEL; nine
  `gui.mcagents.chat_model.*` keys and `hologram.mcagents.model` are new.
