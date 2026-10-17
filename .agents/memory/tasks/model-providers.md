---
name: memory-task-model-providers
status: done
description: The Models page calls the provider the player picks at that provider's own address, takes several custom providers, and edits core 0.13.0's global model that every task falls back to; released as 0.12.0.
---

# Model providers and the global model

## The request

On 2026-10-17 the owner asked, as the third of four tasks: "In model GUI page. If provider set
as what it is then use that provider url. Also make it can add custom too, multiple as that.
This model page will be global model if each task not set their specific model then fallback
to these model. Plan then work immediately." The attached picture showed provider `deepseek`
with the note "Called through OpenRouter": a platform with no key stored went through the
gateway without saying so. The owner had already asked for every branch to be opened as a pull
request and rebase-merged in order.

Core's part (routing by a chosen provider, `ModelChoice`, `ModelProvider`, the global model
store `Models`) is core's task record `model-providers.md`, released as core 0.13.0. This
record is chat's part.

## Decisions

- **The provider is its own setting.** The Models page has a chat provider and an embedding
  provider, each beside its model; OpenRouter is one of the choices. A request goes to the
  provider picked and nowhere else; with no key stored for it, it fails saying which key.
- **The Models page edits core's global model**, `{game_dir}/MCAgents/agents/models.properties`,
  which every task with no model of its own uses. Only the embedding switch stays in chat's
  database: it is chat's retrieval switch.
- **Moving in once.** When core's store was never saved, chat's old `model.chat` and
  `model.embedding` settings and its `api.base-url.{platform}` addresses move into it, each
  model keeping the route it had (direct when a key and an address existed, else OpenRouter).
- **Custom providers** are a list beside the Models form: ADD asks for a name, an address
  (https, or http on loopback) and, optionally, a key stored under that name; REMOVE drops
  one. One named like a built-in platform replaces that platform's address.
- **The Keys page loses its address row**: addresses are set as custom providers now.
- **A minor release, 0.12.0.**

## Tasks

| # | Title | Branch | PR |
|---|---|---|---|
| 1 | Task record | `chore/model-providers-plan` | #70 |
| 2 | Global models from core | `refactor/global-models` | #71 |
| 3 | Models page providers | `feat/custom-providers` | #72 |
| 4 | Release 0.12.0 | `release/0.12.0` | #73 |

### Task 1 — chore/model-providers-plan

This record, and its row in the memory index.

### Task 2 — refactor/global-models

Chat reads and writes its models through core 0.13.0's global model, pinned in the catalog.

- `ModelPreferences` holds two `ModelChoice`s (provider and model) and the embedding switch;
  `of(String, String, boolean)` still builds one through OpenRouter.
- `SqliteSettingsRepository` keeps only `model.embedding.enabled` in SQLite and the choices in
  the `GlobalModelStore` it is opened with, keeping the custom providers on save.
  `SqliteDatabase.open(file)` and `open(file, cipher)` use an in-memory store, so tests stay
  apart from the game's file; `ChatRuntime` passes `Models.store()`.
- `LegacyModelSettings.migrate` runs once, while the store was never saved: old `model.chat`,
  `model.embedding` and `api.base-url.*` rows become choices and custom providers, a model
  keeping the direct route only when its platform had both an address and an active key.
- Requests carry their provider (`AgentRequest.of(choice, …)`, `embed(choice, …)`);
  `StoredProviderRoutes` is gone, replaced by core's `Models.routes()`.
- `.agents/api/openrouter.md` and the repository map describe the chosen-provider route.

### Task 3 — feat/custom-providers

The Models hologram edits the whole global model.

- Form: chat provider, its note, chat model, embedding provider, its note, embedding model,
  switch, status; SAVE refuses a provider with no known address. The embedding tips are gone
  to make room. Picking a provider moves the chat model only when the catalog suggests models
  for it (`ModelCatalog.chatModelOn`); a custom provider keeps the id, so a host is sent the
  whole name.
- `common.ui.ProviderNotes` answers each note from the global model and the keyed providers:
  called (green), called with no key (amber, the request fails asking for one), no address (red).
- A Providers list beside the form: ADD opens the `mcagents.models.add` dialog (name, address,
  optional key stored under the name), REMOVE calls `GlobalModels.withoutProvider`. Both save
  at once.
- The Keys page lost its address row and SET button, and offers the custom providers as
  platforms. Lang keys `keys.address.*`, `models.provider`, `models.route.direct/gateway` and
  `models.embedding.tip.*` were removed.
- README, `wiki/information/overview.md` and `architecture.md` describe the pages.

### Task 4 — release/0.12.0

`project-version` 0.12.0, `wiki/logs/0/12/0/CHANGELOG.md`, its row in the logs index, core
0.13.0 in the setup page and the map, and the repository state.

Tried in 26.3 dev clients before the release: Fabric (a custom provider on loopback added,
picked and saved; a question reached a stub there with its key and the bare model id), NeoForge
(the choice survived a restart; `deepseek` showed it is called at api.deepseek.com with no key
yet, and a question failed naming deepseek) and Forge (REMOVE dropped the provider; the notes and
the status line fit after the notes were cut to one line each). Shots are in the project's
`reports/model-providers/`.
