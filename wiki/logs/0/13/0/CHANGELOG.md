# 0.13.0

Released: 2026-10-17

Each conversation can have its own chat model and embedding model. One you leave alone keeps
following the global model on the Models page.

## Upgrading

- **Players: replace the chat jar.** The `v0.13.0` release carries
  `MCAgentsChatForge-0.13.0+26.3.jar`, `MCAgentsChatFabric-0.13.0+26.3.jar` and
  `MCAgentsChatNeoforge-0.13.0+26.3.jar`. Core stays at 0.13.0.
- **Your conversations are kept.** On the first start, four empty columns are added to
  `chat_sessions` in `data.db`; every existing conversation follows the global model until you
  give it one of its own.

## Added

- **MODEL under each conversation**, between RENAME and DELETE, opens a **Chat Model** page for
  that conversation alone. Switch on **Own chat model** or **Own embedding model**, pick a
  provider and a model as on the Models page, and press **SAVE**. A switch left off shows the
  global choice, greyed, and follows the Models page when it changes; **USE GLOBAL** turns both
  off. SAVE says so in amber when no key is stored for the provider you picked. DELETE closes the
  page with its conversation.
- **A new conversation can be set before its first question.** Its choice is kept and stored
  with the conversation when you send that question, which is then answered with it.
- **Retrieval in a conversation's own embedding model.** When a conversation embeds with a
  model other than the one your Knowledge Packs were embedded with, the packs are embedded
  again in its model for that question, through the six-hour embedding cache, so retrieval
  keeps working instead of going quiet.
- `chat-api`: `SessionModels`, and `ChatRepository.findSessionModels` and `saveSessionModels`.
  Saving a conversation's models does not move it in the Chat Sessions list.
