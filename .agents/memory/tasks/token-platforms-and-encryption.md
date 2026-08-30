---
name: memory-task-token-platforms-and-encryption
description: Platform-aware, encrypted token storage and direct platform routing — the plan, the rule that was set aside, the decisions not to relitigate, and what is proven.
---

# Tokens, Platforms, and Direct Routes

## 2026-08-30

**Goal.** Apply the shared `{platform}/{model}` naming convention to every stored model
identifier, let the mod call a platform's own API directly instead of only through
OpenRouter, record which platform each stored token belongs to, and encrypt tokens at rest
with a key taken from the environment.

**Objective.** A player can store a key for a named platform and have questions for that
platform's models go straight to it; a player who configures nothing keeps exactly the mod
they had; and a copy of `data.db` taken off a machine with an encryption key set discloses
no token, with the same token encrypting differently every time it is written.

**Detail.** The platform is one added column, never a rebuilt table. Every path keeps a
fallback: an unqualified model id, a platform with no address, a platform with no key, and a
machine with no encryption key all behave as they did before. The encryption key comes from
`MCAGENTS_CLIENTFABRIC_TOKEN_ENCRYPT_KEY` and from nowhere else.

## The task list

| # | Title | Scope | Repository | Branch | Files / areas | PR |
|---|---|---|---|---|---|---|
| 1 | The record | This file, written before the work is committed | `MCAgents/client-fabric` | `chore/token-platforms-and-encryption-plan` | `.agents/memory/`, `.agents/index/memory-index.md` | |
| 2 | Model naming | `{platform}/{model}` as a type, applied before every write | `MCAgents/client-fabric` | `feat/model-naming` | `api.model` | |
| 3 | The token cipher | AES-GCM, a random nonce per write, key from the environment | `MCAgents/client-fabric` | `feat/token-cipher` | `common.security` | |
| 4 | Platform column and encryption at rest | The added column, the migration, the storage boundary | `MCAgents/client-fabric` | `feat/token-storage` | `api.model`, `api.storage`, `common.storage.sqlite` | |
| 5 | Direct routes | Resolving a route, and what changes on the wire | `MCAgents/client-fabric` | `feat/direct-routes` | `api.agent`, `common.agent` | |
| 6 | The console | Choosing a platform, configuring an address, disclosing encryption | `MCAgents/client-fabric` | `feat/console-platforms` | `website/` | |
| 7 | The game screen | Choosing a platform where a key is pasted | `MCAgents/client-fabric` | `feat/key-screen-platform` | `platforms/fabric/` | |
| 8 | The release | Instructions, both wikis, the changelog, indexes | `MCAgents/client-fabric` | `docs/token-platform-release` | `.agents/`, `wiki/`, `README.md` | |

Ordered by dependency, and stacked that way: task 1 branches from `master`, task `k` from
task `k-1`. Tasks 4 and 5 both need task 2's `ModelName`; task 4 needs task 3's cipher; tasks
6 and 7 need task 4's column; task 8 describes all of them. Nothing here is independent of
what precedes it, which is why nothing here is parallel.

**The `PR` column is filled by task 8**, not by task 1 — pull request numbers do not exist
until every branch is pushed, and back-filling them onto branch 1 afterwards would rebase the
whole stack.

## Decisions not to relitigate

*(each task appends below as it lands)*

### Task 2 — model naming

**`ModelName` is a type, not a convention people remember.** `{platform}/{model}` lowercase
now has a parser, a builder that refuses a doubled prefix, and one `normalise` that every
write goes through. The rule came from the shared set's `model-naming-convention`, read
through the connector rather than copied here.

**An unqualified id is kept, not completed.** `openrouter/gpt-4o-mini` is not a longer
spelling of `gpt-4o-mini`; it is a model nobody serves. So `parse` returns empty for a bare
id, `normalise` only trims and lowercases it, and such a request goes through the gateway.
The alternative — invent the platform — would have made the mod reject or misroute an id the
player typed, which `.agents/api/openrouter.md` forbids.

**Lowercasing happens at the write and nowhere else.** `ModelPreferences.of` is the last
point before storage and already normalised blanks and retirements, so it took the case rule
too. `ModelCatalog.resolve` normalises before it looks a retirement up, which is why a
retired id pasted back in a different case is still retired.

**`ModelPlatform` is separate from `ModelName`** because it answers a different question:
not what a model is called, but where its platform answers and whether a key may be sent
there. It carries the gateway constant, the addresses this release ships, the setting key
that overrides one, and the https-or-loopback test.

**Anthropic and Google are deliberately absent from the shipped addresses.** Their own APIs
are not the OpenAI shape this client speaks — different path, different auth header,
different body — so listing them would have produced requests they reject. They work through
the gateway, which is where they stay until something speaks their format.

### Task 3 — the token cipher

**AES-256-GCM with a fresh 96-bit nonce per write.** Encrypting one token twice gives two
unrelated strings, both of which open. That property is the whole reason a cipher was used
rather than a hash: a deterministic transform of a token is a fingerprint of it, so a stolen
database would still answer "do these two rows hold the same key?" and "is this guess the
key?" without holding a key.

**The key material is hashed with SHA-256, always.** No detection of hex or base64. One
derivation means no later release can reinterpret an existing variable and lock a player out
of their own tokens, and any length of passphrase works.

**A blank or absent variable is the pass-through cipher, not an error.** It stores what it is
given and reads back what is there, which is the mod that existed before this. Making
encryption mandatory would have broken every current player for a property they never asked
for.

**The stored form is marked `mcagents:v1:`**, so mixed rows are readable and a future scheme
can be told apart from this one — a token cannot be re-encrypted by a version that cannot
read it.

**Nothing in a failure message is a token.** The message names the variable and what was
attempted, which is the same rule every message out of the storage layer follows.

### Task 4 — the platform column and encryption at rest

**One added column, guarded by the pragma.** `SqliteSchema` gained a second list for columns
added to tables that already exist, because `IF NOT EXISTS` does not exist for a column.
`platform VARCHAR(32) NOT NULL DEFAULT 'openrouter'` is both the migration and the fallback:
every row a player already had is an OpenRouter key, which is what every key was.

**A key is only ever offered for its own platform.** `findNextActive()` is now shorthand for
`findNextActive("openrouter")`, and no platform borrows another's key. A gateway key is not
valid at OpenAI's own door, and sending it there would disclose it to somewhere it does not
belong — which is worse than the request failing.

**Re-adding a key under a different platform moves it.** Pasting it again is how a player
corrects a key they filed in the wrong place, and a second row holding the same secret is the
worse outcome.

**Every lookup by value is a scan and a decrypt.** A randomised ciphertext has nothing SQL
can match on. A blind index would have restored `WHERE`, and would have been a deterministic
fingerprint of the token — the exact thing the encryption exists to prevent. A player holds a
handful of keys, so the scan costs a handful of AES operations against a local file.

**The ordering tie now breaks on `rowid`, not on `key_value`.** `CURRENT_TIMESTAMP` resolves
to the second, and the column it used to break on holds ciphertext, whose order is arbitrary
— so "the oldest active key" would have stopped meaning the oldest.

**Turning encryption on encrypts what the player already had.** The migration runs at open,
after the schema and before anything reads a key, in a transaction, and is a no-op on every
start after the first. Protecting only the next key pasted would have been a promise the
database does not keep.

**A row that cannot be opened is skipped, warned about once, and left in place.** Putting the
right variable back is all it takes to get the keys back. A page's total still counts it, so
one unreadable row does not shift every later window.

### Task 5 — direct platform routes

**Three questions, and the gateway if any answers no.** Does the model id name a platform, is
there an address for it, and is there a key for it? `StoredProviderRoutes` asks all three per
request, against the same store the keys live in, so a key added while the game is running
takes effect on the next question.

**The route decides the model id on the wire.** Through the gateway the whole stored name
travels, because that is how OpenRouter addresses a model; direct, the bare id travels,
because the platform has never heard of the prefix. Both come out of one stored name, which
is the entire point of the naming convention.

**Two fields are dropped on a direct request.** `usage.include` and the `cache_control`
content part are OpenRouter's, not OpenAI's, and a platform's own API rejects the *whole
request* over an unrecognised argument rather than ignoring it. Nothing is lost: the
providers reachable directly cache implicitly, on prefix stability, which the message order
already arranges. The attribution headers go the same way.

**`OpenRouterClient` kept its name.** It speaks the wire format OpenRouter defines, which is
OpenAI's, and that is exactly why one client covers both routes. A rename would have touched
four documents and every test for no behavioural gain; the class comment says which format it
speaks and why that is not a contradiction.

**A configured address that is neither https nor loopback is ignored, not used.** The value
being configured is where an API key gets sent. Loopback is allowed so a player can point at
a gateway they run themselves.

### Task 6 — the console

**The key page became the page about reaching a platform.** A key box, a platform box beside
it, and a panel giving each platform's address and saying which of the two routes it is
actually on — "Direct", "No address", or "Via OpenRouter". A URL with no key is as useless as
a key with no URL, so the badge says which half is missing.

**The platform is shown; the key still never is.** A platform is a provider's name, not a
secret, and without it two masked keys are indistinguishable. The handle mechanism is
untouched: no value reaches the browser, and a browser run asserts that again.

**The page says whether keys are encrypted on this machine.** A player who has not set the
variable is storing keys in the clear, and the page that takes their key is the right place
to say so rather than implying the stronger state.

**An address is refused, not warned about, when it is neither https nor loopback**, and a
blank one clears the override back to the address the mod ships.

**The platform box is a datalist, not a select.** The mod does not keep a whitelist of
providers any more than it keeps one of models, so the box suggests and still accepts
anything typed.

### Task 7 — the game screen

**A second box, and the list of stored keys shows the platform.** The box defaults to
`openrouter` and is kept after a save, because a player adding two keys for one platform
should not type it twice. The key box is still cleared the moment it is stored.

**The screen's two methods now place rows from the same two constants.** `init` and the
render pass each used to compute the list's top independently, which is exactly the
arithmetic that produced colliding text before —
[`screen-layout.md`](screen-layout.md).

**An address is configured in the console, not in game.** Typing a URL into a Minecraft text
box is a worse experience than the console's, and the console is one keypress away. The
platform, which is one short word, is worth having in both.

### Task 8 — the release

**What was updated, and why each was not optional.** `.agents/security/api-keys.md` — the
rule this work replaced, rewritten rather than quietly contradicted.
`.agents/database/schema.md` — the added column, the ciphertext in `key_value`, the ban on a
deterministic search column, and the value-rewriting migration.
`.agents/api/openrouter.md` — the two routes and what a direct request must not carry.
`.agents/wiki/context/repository-map.md`, `wiki/information/architecture.md`,
`wiki/information/overview.md`, `wiki/information/web-console.md`,
`wiki/environments/setup.md` (how to set the variable, and what happens if it is lost),
`README.md`, and the changelog.

**`AGENTS.md` was missing a mirrored row.** The shared trigger table has had
`model-naming-convention` since before this work; the local table did not. Added, because
mirroring is mandated rather than optional — a missing row is a rule that never fires here.

## What is proven, and what is not

**Proven, by 512 tests** (71 new): the same token encrypting differently on two writes and
both opening; a database file read with its own JDBC connection holding nothing legible; keys
already in the clear encrypted at the next open, and not re-encrypted after that; a wrong key
hiding rows without deleting them, and the right one bringing them back; a key found by value
though the column holds ciphertext; one platform's key never offered to another; a direct
route's address, bare model id, dropped extensions, per-platform key and rotation; and every
fallback path landing on the gateway.

**Proven in a browser**, against a live console on a real database: the platform badges, the
route badges, adding a key turning its platform direct, an address refused then accepted then
cleared, the encryption disclosure, and no horizontal scroll at four widths. The database
that run wrote held three rows, all ciphertext, with their platforms intact.

**Not proven.** **No request has ever reached a platform's real API** — every direct-route
assertion is against a stub transport, and the six shipped addresses have never been called.
No real player's database has been migrated. Nothing has run inside Minecraft: the key
screen's platform box is compile-verified only, as is everything else in
`platforms/fabric/` — see [`gui-play-testing.md`](gui-play-testing.md), this repository's own
record of a green Java suite meaning nothing about the mod loading.

**Deliberately absent.** No way to set a platform's address from the game screen; the console
does it, and typing a URL into a Minecraft text box is worse in every way. No rotation of the
encryption key — changing it means the old rows stay unreadable until the old value comes
back, and a re-encrypt-under-a-new-key path is worth building only if somebody needs it. No
per-platform model catalogue: the suggested lists are still one list.
