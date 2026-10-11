---
name: api-key-handling
description: How API keys are stored, encrypted, displayed, logged, and rotated — which platform each one may be sent to, and the places they must never appear.
---

# API Key Handling

The player supplies their own keys. They pay for the player's own usage, they are stored on
the player's own machine, and they are the single most damaging thing this mod could leak.

## Where a key lives

* In `data.db`, in the `api_keys` table, on the player's machine. Nowhere else.
* **Never uploaded to a service run for this mod.** There is none, and adding one that
  accepts, forwards, or stores a key is a product decision, not an implementation detail.
* **Never in a config file the player might share.** No `.env`, no `config.json`, no
  in-game screenshot of a plain-text field.
* **Never in this repository.** Not in a test fixture, not in a comment, not in an example.
  A test uses an obviously fake value such as `sk-or-v1-first`.

## Keys are encrypted at rest, when the machine has a key to encrypt with

`MCAGENTS_CHAT_TOKEN_ENCRYPT_KEY` in the environment is the only place the encryption
key comes from. **Nothing ships.** It is not in this repository, not in the mod jar, and not
in `data.db`, which is what makes this worth doing at all: a key encrypted with a key that
travels beside it is not protected, only harder to read.

* **Set:** `TokenCipher` encrypts with AES-256-GCM under a fresh nonce per write, so the same
  key stored twice produces two unrelated strings and nothing in the file says the two rows
  hold one secret. The `platform` column stays readable — a provider's name is not a
  credential, and the mod has to be able to sort keys by it without opening any of them.
* **Unset:** keys are stored as the player entered them, exactly as they always were. This is
  the fallback, and it is not an error — a mod that refused to run without an environment
  variable would be a worse mod for almost every player.

**Never make it deterministic.** A hash, a salted hash, or a fixed nonce would restore
`WHERE key_value = ?` and would hand back the property the encryption exists for: a stolen
database that can answer "are these the same key?" and "is this guess the key?". Lookups scan
and decrypt instead — see [`../database/schema.md`](../database/schema.md).

**What this protects is a file that left the machine** — a backup, a synced folder, a modpack
bundle, an attachment on a bug report. It is not a defence against a machine an attacker is
already running code on: the mod has to send the key to a provider, so anything that can read
the environment can read the keys. Say which of the two a player has; never imply the
stronger one. The console's key page does exactly that, and so should anything else that
mentions it.

The history of this rule — it previously forbade encryption outright — is in
[`../memory/decisions/token-encryption-at-rest.md`](../memory/decisions/token-encryption-at-rest.md).

## A key belongs to one platform

`api_keys.platform` records which API a key opens: `openrouter` for a gateway key, `openai`
for a key that speaks to OpenAI's own API. It is the `{platform}` half of a model name — see
`{shared}/rules/model-naming-convention.md`.

* **A key is only ever offered for its own platform.** Never fall back to another platform's
  key. A gateway key is not valid at a platform's own door; sending it there discloses it to
  somewhere it does not belong, which is worse than the request failing.
* **The fallback is `openrouter`**, for a key added without a platform and for every row
  written before the column existed.
* **A platform is not validated against a list**, exactly as a model id is not.

## Where a key may appear

| Place | Allowed |
|---|---|
| The `Authorization` header of a request to **its own platform** | Yes — this is the only place the full value leaves the mod |
| The interface, as the platform it belongs to | Yes — a provider's name is not a secret, and without it two masked keys are indistinguishable |
| A `PreparedStatement` parameter | Yes |
| The interface, as `ApiKey#masked()` | Yes — `sk-o…mnop`, never the whole value |
| A log line | Only masked, and only when there is a reason |
| An exception message | No |
| A crash report or a stack trace | No |
| A bug report attachment | No — this is why `data.db` is never attached to an issue |

`ApiKey#toString()` masks, so a key cannot leak through string interpolation into a log.
**Do not remove that override**, and do not add a getter that a logging call would reach
for by accident. The same applies to `TokenCipher`: its `toString` names the scheme and
nothing derived from the key, and no message it throws carries a token, encrypted or not.

## Rotation

The mod stores several keys and uses the oldest active one **for the platform being called**.

* When a provider rejects a key — expired, revoked, out of credit — **deactivate it and
  move to the next**. `ApiKeyRepository#deactivate` sets `is_active = 0`.
* **Deactivate, do not delete.** A key that vanishes silently looks like a bug to the
  player; one shown as inactive explains itself. Deleting is the player's choice, through
  `remove`.
* Re-adding a deactivated key reactivates the existing row rather than failing or
  duplicating it, because "paste it again" is what a player does after topping up.
* Rotation is invisible: a rejected key must not surface as a failed conversation if
  another key would have worked.

## A key that cannot be decrypted is kept

A row encrypted under a key this machine no longer has is **skipped on read, with one warning
naming how many**, and left exactly where it is. Never prune it, never guess at it: putting
the right variable back is all it takes to get the player's keys back, and a mod that tidied
them away would have destroyed the only copy.

## Reviewing a change

Before a change that touches keys lands, check all six:

1. Does any new log, exception, or message carry a key value?
2. Does any new request send a key to a platform other than the one it was stored for?
3. Does a rejected key still deactivate rather than delete?
4. Does any new test, fixture, or example contain something that looks like a real key?
5. Does anything new write a token to the database without going through `TokenCipher`, or
   introduce a deterministic transform of one?
6. Does any new message claim keys are encrypted without checking whether this machine
   actually has a key to encrypt with?

## Related

* [`../database/schema.md`](../database/schema.md) — the `api_keys` table.
* [`../api/openrouter.md`](../api/openrouter.md) — how the key is used on a request.
