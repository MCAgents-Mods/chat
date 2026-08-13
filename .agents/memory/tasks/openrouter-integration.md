---
name: memory-tasks-openrouter-integration
description: Record of building the OpenRouter client, prompt caching, model selection, and the cache layer — decisions, and what remains unverified.
---

# Task — OpenRouter, caching, and model selection

**Status:** merged · **Branches:** `feat/openrouter`, `feat/prompt-cache`,
`feat/model-selection`, `feat/cache`, `build/gradle` (dependency scoping)

## Decisions worth not relitigating

* **Prompt order is the caching strategy.** Providers match a prefix, so the persona goes
  first, then settled history, then the new question. **Retrieved Knowledge Pack content
  must travel with the question, never in the system message** — putting it first would
  invalidate the whole conversation on every turn. `PromptCachingTest` asserts turn N's
  prefix is a byte-exact prefix of turn N+1's, because nothing else would catch a
  regression here: the mod would keep answering perfectly and silently cost full price.
* **Two cache breakpoints, never more.** Providers cap them and each costs a cache write.
* **Embeddings are cached locally, completions are not.** An embedding is a pure function;
  a completion asked twice should be allowed to differ.
* **Model ids are never validated locally.** OpenRouter's catalog moves faster than this
  mod ships, so the suggested lists are suggestions and the provider's own rejection is the
  error the player sees.
* **A malformed 200 is not retried.** That request already succeeded and was already
  billed; retrying charges the player again for the same broken answer. This was found by
  writing the test, not by writing the code.

## Dependency scoping, measured

`implementation` in Loom bundles nothing — only `include` does. Verified: `compileOnly`
leaves the compile classpath intact and empties `runtimeClasspath`, which would break
`runClient` while producing an identical shipped jar. Gson is `compileOnly` because
Minecraft provides it. Fabric has **no** equivalent of Spigot's runtime Maven `libraries:`.

## Still unverified

No request has ever reached OpenRouter from this codebase under test — that needs a real
key and real money. Key rotation, backoff, and `cached_tokens` are exercised only against a
stub transport.
