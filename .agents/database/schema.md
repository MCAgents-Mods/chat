---
name: database-schema
description: The local SQLite schema — what each table is for, the invariants that hold across them, and how a change to it ships.
---

# Local Database Schema

The store is SQLite at `{minecraft_dir}/MCAgents/data.db`. It is the player's data, on the
player's machine, and it is the only place chat history, API keys, and downloaded Knowledge
Packs exist.

Implementation lives in `common/src/main/java/.../storage/sqlite/`. The statements
themselves are in `SqliteSchema`, which is the single source of truth — this file explains
*why*, and `SqliteSchema` is *what*.

## The rule that outranks the rest

**Never resolve a schema problem by deleting or recreating the database.** That file is
every conversation the player has ever had and every pack they have paid for. A change
ships as an additive migration or it does not ship.

Concretely, this rules out: dropping a table to change a column, `DELETE FROM` as part of
an upgrade path, renaming a file out of the way, and any instruction to a player that
begins "delete your data.db".

## The tables

| Table | Holds | Key |
|---|---|---|
| `api_keys` | The player's OpenRouter keys | the key value itself |
| `content_categories` | Groupings for the content browser | id assigned by the central service |
| `contents` | Downloaded Knowledge Packs, text and embedding | id assigned by the central service |
| `chat_sessions` | One row per conversation | a UUID string |
| `chat_messages` | One row per turn | autoincrement |
| `settings` | Player preferences, including the selected models | the setting key |

## Invariants

These are not incidental; code depends on each one.

* **A chat message is one turn by one speaker**, with `role` holding `user`, `assistant`,
  or `system`. It is not a question-and-answer pair. That shape is what lets a stored
  conversation be sent to the model API without being reassembled — see
  [`../api/openrouter.md`](../api/openrouter.md).
* **`message_index` starts at 1** and is assigned by reading `MAX(message_index) + 1` for
  the session inside the same transaction as the insert.
* **Appending a message touches its session's `updated_at`.** The session list sorts on
  that column, so skipping it leaves the conversation the player is in buried.
* **`chat_messages` cascades from `chat_sessions`,** and that only works because the
  connection enables foreign key enforcement — SQLite defaults it off. Deleting a session
  must never leave orphaned messages.
* **`contents.category_id` is a foreign key**, so a category is stored before its packs.
  The repository checks first and reports the missing category by id rather than letting a
  raw constraint violation reach the player.
* **A pack is identified by `id` plus `version`.** The central service publishes a new
  version rather than editing an existing one, so an update is an overwrite in place and an
  update *check* is a version-string comparison. Never accumulate rows per version.
* **Timestamps are text in UTC**, written by `CURRENT_TIMESTAMP`. SQLite has no date type.
  An unparseable value is read as the epoch rather than failing the query — a corrupt
  timestamp must not make a player's history unreadable.

## Migrations

* Every statement in `SqliteSchema` is `CREATE TABLE IF NOT EXISTS` or
  `CREATE INDEX IF NOT EXISTS`, and the whole list is applied on **every** start. Applying
  it to an existing database is a no-op; applying it to a fresh one creates everything.
* **A new table or index is added by appending to that list.** Nothing else is needed, and
  nothing else is permitted.
* **A new column** is added with `ALTER TABLE … ADD COLUMN`, guarded by a check of
  `PRAGMA table_info`, appended after the create statements. It must be nullable or carry a
  default, because existing rows will not have it.
* **A column that must change type or disappear** is the expensive case: create the new
  table beside the old one, copy the rows across, and switch. Propose it before writing it.
* Never reorder the existing statements. Their order is the migration history.

## Writing queries

* Every statement is a `PreparedStatement` with bound parameters. String-concatenating a
  value into SQL is forbidden, including in a `LIKE` clause.
* **Search text is escaped before it reaches `LIKE`** — `%`, `_`, and `\` are literals to a
  player typing `100%`, and `SqlValues.containsPattern` is the only way to build the
  pattern.
* Multi-statement work runs in a transaction through `SqlWorker#submitInTransaction`, so a
  failure leaves nothing half-written.
* Every read returns immutable collections. The interface holds onto what it is given.

## What never goes in a log or an error message

An API key value, in any form. `DataAccessException` messages describe what was attempted,
never what it was attempted with — see [`../security/api-keys.md`](../security/api-keys.md).
