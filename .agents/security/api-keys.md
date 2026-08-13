---
name: api-key-handling
description: How OpenRouter keys are stored, displayed, logged, and rotated — and the places they must never appear.
---

# API Key Handling

The player supplies their own OpenRouter key. It pays for their own usage, it is stored on
their own machine, and it is the single most damaging thing this mod could leak.

## Where a key lives

* In `data.db`, in the `api_keys` table, on the player's machine. Nowhere else.
* **Never in the central service.** `MCAgents/server-fabric-expressjs` has no endpoint that
  accepts, forwards, or stores a key, and adding one is a product decision, not an
  implementation detail.
* **Never in a config file the player might share.** No `.env`, no `config.json`, no
  in-game screenshot of a plain-text field.
* **Never in this repository.** Not in a test fixture, not in a comment, not in an example.
  A test uses an obviously fake value such as `sk-or-v1-first`.

Keys are stored as the player entered them, not encrypted. That is a deliberate choice: a
key encrypted with a key that ships in the same jar is not protected, only harder to read.
The protection is that the file never leaves the machine. Do not add an encryption scheme
that implies otherwise.

## Where a key may appear

| Place | Allowed |
|---|---|
| The `Authorization` header of a request to OpenRouter | Yes — this is the only place the full value leaves the mod |
| A `PreparedStatement` parameter | Yes |
| The interface, as `ApiKey#masked()` | Yes — `sk-o…mnop`, never the whole value |
| A log line | Only masked, and only when there is a reason |
| An exception message | No |
| A crash report or a stack trace | No |
| A bug report attachment | No — this is why `data.db` is never attached to an issue |

`ApiKey#toString()` masks, so a key cannot leak through string interpolation into a log.
**Do not remove that override**, and do not add a getter that a logging call would reach
for by accident.

## Rotation

The mod stores several keys and uses the oldest active one.

* When OpenRouter rejects a key — expired, revoked, out of credit — **deactivate it and
  move to the next**. `ApiKeyRepository#deactivate` sets `is_active = 0`.
* **Deactivate, do not delete.** A key that vanishes silently looks like a bug to the
  player; one shown as inactive explains itself. Deleting is the player's choice, through
  `remove`.
* Re-adding a deactivated key reactivates the existing row rather than failing or
  duplicating it, because "paste it again" is what a player does after topping up.
* Rotation is invisible: a rejected key must not surface as a failed conversation if
  another key would have worked.

## Reviewing a change

Before a change that touches keys lands, check all four:

1. Does any new log, exception, or message carry a key value?
2. Does any new request send a key anywhere other than OpenRouter?
3. Does a rejected key still deactivate rather than delete?
4. Does any new test, fixture, or example contain something that looks like a real key?

## Related

* [`../database/schema.md`](../database/schema.md) — the `api_keys` table.
* [`../api/openrouter.md`](../api/openrouter.md) — how the key is used on a request.
