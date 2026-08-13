---
name: openrouter-integration
description: How the mod talks to OpenRouter — request shape, model selection, key rotation on rejection, retries, timeouts, and what must never be sent.
---

# OpenRouter Integration

OpenRouter is the only model provider this mod calls, with the player's own key. Every call
is optional: a player with no key has a working mod that cannot produce new answers, not a
broken one.

## The seam

`AgentReply` in `platforms/fabric/.../client/gui` is what the chat screen depends on. The
model client implements it. Until one exists, `AgentReply.notConfigured()` explains itself
to the player instead of failing.

When the implementation lands it belongs in `common/`, behind a contract in `api/`, because
nothing about calling an HTTP API needs Minecraft. Move `AgentReply` to `api/` at that
point.

## Request shape

* Chat completions at `https://openrouter.ai/api/v1/chat/completions`; embeddings at
  `https://openrouter.ai/api/v1/embeddings`.
* The key travels in the `Authorization: Bearer …` header and nowhere else —
  [`../security/api-keys.md`](../security/api-keys.md).
* **The stored conversation is the request body.** `chat_messages` holds one row per turn
  with a `role` of `user`, `assistant`, or `system`, which is exactly the array the API
  expects. Mapping is the only step; if you find yourself parsing or reassembling, the
  storage shape has been broken — see [`../database/schema.md`](../database/schema.md).
* The system message is assembled at request time from the player's
  `{minecraft_dir}/MCAgents/AGENTS.md` and the retrieved Knowledge Pack content. It is
  stored with `role = 'system'` only when it should persist in the transcript.

## Model selection

* The player chooses a chat model and an embedding model. Both are stored in `settings` and
  read at request time — never hardcoded at a call site.
* **The suggested lists are suggestions, not a whitelist.** The interface offers them and
  also accepts anything the player types, because OpenRouter adds models faster than this
  mod ships. Never reject a model id the player entered; let the API reject it and report
  what the API said.
* A blank or missing selection falls back to the default in `ModelCatalog`. It never falls
  back to "no model".

## Failure handling

* **Every call returns `CompletableFuture`** and runs off the client thread. No exception.
* **A rejected key rotates.** 401 or 403 deactivates the key and retries once with the next
  active key. If none is left, the player is told they need a working key — not that the
  request failed.
* **429 and 5xx retry** with exponential backoff, at most three attempts, with jitter. 4xx
  other than 401/403/429 does not retry; the request was wrong and repeating it will not fix
  it.
* **Timeouts are mandatory** — connect and read both. A request without one can hang until
  the player closes the game.
* A failure surfaces in the interface as a message in the conversation, not as a crash and
  not as silence. The player must be able to tell "the model refused" from "nothing
  happened".

## What is never sent

* Anything the player did not ask to send. Context injection — inventory, the block being
  looked at, chat logs — is a feature the player controls, not a default.
* Another player's data.
* Any identifier for the central service's account system. OpenRouter is between the player
  and OpenRouter.

## Offline

A network failure here changes nothing about the rest of the mod. The screen opens, the
history is readable, downloaded packs are usable. Whatever calls OpenRouter must not be on
the path of any of those.
