---
name: memory-decision-token-encryption-at-rest
description: Stored API tokens are encrypted with a key from the environment — the rule that decision set aside, who asked for it, and what it does and does not protect.
---

# Stored Tokens Are Encrypted

**Decided:** 2026-08-30, on an explicit instruction from the repository owner.

## The rule that was set aside

[`../../security/api-keys.md`](../../security/api-keys.md) said, in full:

> Keys are stored as the player entered them, not encrypted. That is a deliberate choice: a
> key encrypted with a key that ships in the same jar is not protected, only harder to read.
> The protection is that the file never leaves the machine. Do not add an encryption scheme
> that implies otherwise.

That paragraph has been replaced, and this file records why rather than letting the change
look like the rule was never there. The instruction was explicit and specific — encrypt
stored tokens, take the key from `MCAGENTS_CLIENTFABRIC_TOKEN_ENCRYPT_KEY`, and make the
same value encrypt differently every time — which is the one thing that outranks a local
rule.

## Why the old reasoning does not apply to what was built

The rule's objection was to a key that ships in the jar. **Nothing ships.** The encryption
key is read from the environment at startup and exists nowhere in this repository, in the
mod jar, or in `data.db`. A copy of the database taken without the environment it ran in
opens nothing, which is a different situation from the one the rule was written about.

## What it protects, and what it does not

**Protects:** a database file copied off the machine — a backup, a synced folder, a modpack
bundle, an attachment on a bug report. Nothing in the file says which platform key a row
holds, and two rows holding the same token do not look alike.

**Does not protect:** a machine an attacker is already running code on. The mod must be able
to send the key to a provider, so a process that can read the environment can read the keys.
Encryption at rest is not a claim about a compromised machine, and the console says exactly
which of the two states a player is in rather than implying the stronger one.

## The consequences worth remembering

* **No lookup by value in SQL.** A randomised nonce per write is what makes two writes of one
  token look unrelated, and it is also what makes `WHERE key_value = ?` useless. The
  repository reads and decrypts instead. A deterministic column to search on — a blind index,
  a salted hash — would have given back precisely what the encryption was for.
* **An unset variable is a working mod, not an error.** Tokens are then stored as they always
  were. Making encryption mandatory would have broken every existing player at once for a
  property most of them never asked for.
* **A wrong key hides rows; it never deletes them.** Putting the right value back is all it
  takes to get the keys back, which is why an unreadable row is skipped with a warning rather
  than pruned.
