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
| `api_keys` | The player's API keys, and which platform each one opens | the stored form of the key |
| `content_categories` | Groupings for the content browser | the id the caller supplies |
| `contents` | Stored Knowledge Packs, text and embedding | the pack's own id |
| `chat_sessions` | One row per conversation | a UUID string |
| `chat_messages` | One row per turn | autoincrement |
| `settings` | Player preferences, including the selected models | the setting key |
| `player_wallets` | The wallets a player has bound, under an account | a UUID string |
| `wallet_platform_addresses` | One public address per chain per wallet | a UUID string |

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
* **A pack is identified by `id` plus `version`.** A new version replaces the stored one
  whole, so storing an update is an overwrite in place. Never accumulate rows per version.
* **Timestamps are text in UTC**, written by `CURRENT_TIMESTAMP`. SQLite has no date type.
  An unparseable value is read as the epoch rather than failing the query — a corrupt
  timestamp must not make a player's history unreadable.
* **`CURRENT_TIMESTAMP` resolves to the second**, so two rows written in the same breath
  carry identical timestamps. Any ordering that must be stable breaks the tie on `rowid`;
  `player_wallets` does. A sort on timestamps alone returns rows in whatever order SQLite
  felt like, and it will differ between runs.
* **A wallet holds one address per chain**, enforced by `UNIQUE(wallet_id, platform)`.
  Binding an address for a chain that already has one is an `ON CONFLICT … DO UPDATE`, so
  correcting a typo replaces the row rather than adding a second one that contradicts it.
* **`wallet_platform_addresses` cascades from `player_wallets`**, and like `chat_messages`
  that only works because the connection enables foreign key enforcement.
* **An account is not a table.** `player_wallets.account_id` is the only place an account
  exists; it comes into being when a wallet names it and stops existing when the last wallet
  under it is deleted. There is deliberately nothing to clean up.
* **A chain is stored by name, not by ordinal**, and a name this release does not recognise
  reads back as `OTHER`. A row written by a newer version of the mod must never make an
  older one unable to read the player's wallets.
* **Wallet tables hold public addresses only.** No private key and no recovery phrase is
  ever stored, accepted, or logged — the same rule as
  [`../security/api-keys.md`](../security/api-keys.md), and for a stronger reason.
* **`api_keys.key_value` holds the stored form of a key, not the key.** With
  `MCAGENTS_CLIENTFABRIC_TOKEN_ENCRYPT_KEY` set it is an AES-GCM envelope under a fresh nonce,
  so writing one key twice produces two different rows; with it unset it is the key itself.
  `SqliteApiKeyRepository` is the only place either direction happens, and everything above it
  sees the key.
* **No lookup by key value happens in SQL.** A randomised ciphertext has nothing to match on,
  so the repository reads the column and decrypts. **Never add a deterministic column to
  search on** — a blind index is a fingerprint of the key and gives back exactly what the
  encryption prevents. A player holds a handful of keys; the scan is a handful of AES
  operations against a local file.
* **A row that cannot be decrypted is skipped on read and never deleted.** Its place in a
  page's `COUNT(*)` is kept too, so one unreadable row does not shift every later window.
* **`api_keys.platform` decides which requests may use a key**, and never falls back to
  another platform's. It defaults to `openrouter`, which is what every key was before the
  column existed.
* **The oldest active key wins, and the tie breaks on `rowid`.** `created_at` resolves to the
  second and `key_value` may be ciphertext, whose order means nothing — so the tie-break is
  insertion order, as it is for `player_wallets`.

## Migrations

* Every statement in `SqliteSchema` is `CREATE TABLE IF NOT EXISTS` or
  `CREATE INDEX IF NOT EXISTS`, and the whole list is applied on **every** start. Applying
  it to an existing database is a no-op; applying it to a fresh one creates everything.
* **A new table or index is added by appending to that list.** Nothing else is needed, and
  nothing else is permitted.
* **A new column** is added with `ALTER TABLE … ADD COLUMN`, guarded by a check of
  `PRAGMA table_info`, appended after the create statements. It must be nullable or carry a
  default, because existing rows will not have it. `SqliteSchema` keeps these in their own
  append-only list — `IF NOT EXISTS` does not exist for a column, so the guard is a read of
  `pragma_table_info(?)`, which is the table-valued form and therefore takes a bound parameter
  like every other value this layer sends.
* **A migration that rewrites values**, rather than shape, runs after the schema and before
  anything reads — `SqliteApiKeyRepository.encryptStoredKeys` is the only one, and it is a
  no-op on every start after the first. It runs in a transaction, so a failure leaves the
  database exactly as it was.
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
