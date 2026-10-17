# 0.12.0

Released: 2026-10-17

The provider you pick on the Models page is the API that is called, you can add as many
providers of your own as you like, and the Models page is now the global model: what every task
without a model of its own is answered and embedded with.

## Upgrading

- **Players: replace the chat jar and the core jar.** The `v0.12.0` release carries
  `MCAgentsChatForge-0.12.0+26.3.jar`, `MCAgentsChatFabric-0.12.0+26.3.jar` and
  `MCAgentsChatNeoforge-0.12.0+26.3.jar`. This release needs core 0.13.0, which keeps the global
  model.
- **Your models and addresses move once.** On the first start, the chat and embedding models
  stored in `data.db` and any address set on the Keys page move into core's
  `MCAgents/agents/models.properties`. A model keeps the route it had: its own platform when
  that platform had an address and a key, otherwise OpenRouter. An address becomes a custom
  provider of the same name. The embedding switch stays where it was.

## Added

- **An Embedding provider** beside the embedding model, OpenRouter by default, so a chat
  provider that serves no embeddings no longer takes the embedding with it.
- **Custom providers.** A Providers list beside the Models form: ADD opens a form for a name,
  an address (`https://`, or loopback) and an optional API key, stored under the name; REMOVE
  drops one, and a model that used it moves back to OpenRouter. A provider named like a
  built-in one, such as `deepseek`, replaces that one's address. Every one is offered in both
  provider lists and on the Keys page.

## Changed

- **The provider is called, always.** Picking `deepseek` sends the question to
  `api.deepseek.com` with a deepseek key. With no key stored the question fails asking for
  one, instead of going through OpenRouter without a word. The line under each provider says
  where it is called: green with a key, amber without one, red when no address is known.
- **Picking a provider** moves the chat model onto one it serves only when the mod suggests
  models for it; a provider it suggests nothing for keeps your model id. Typing a model no
  longer changes the provider.
- **The models live in core's global model**, so another MCAgents mod falls back to the same
  choice. SAVE refuses a provider with no known address.

## Removed

- **The Keys page's address box and SET button.** An address is set as a custom provider.
- `StoredProviderRoutes`; requests are routed by core's `Models.routes()`. The embedding tips
  under the switch, to make room for the provider rows.
