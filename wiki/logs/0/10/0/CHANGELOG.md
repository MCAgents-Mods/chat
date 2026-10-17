# 0.10.0

Released: 2026-10-17

Several conversations can be open at once, each in its own chat hologram. CHAT on the MCAgents
menu now opens a Chat Sessions list, and a conversation picked from it opens beside the list.

## Upgrading

- **Install core 0.11.0 or newer** beside chat, for the same loader. Chat 0.10.0 needs core's
  multiple holograms and its pick list panel.
- **Nothing to move for a player.** Conversations, keys, the model, the persona and wallets
  stay in `{game}/MCAgents/data.db` as before.

## Added

- **Chat Sessions.** CHAT opens a list of every conversation, newest first, in place of the
  menu. Its title bar carries a **NEW CHAT** button on the right.
- **One chat hologram per conversation.** Picking a conversation opens its chat hologram
  beside the list, which stays open; NEW CHAT opens one on an empty conversation, which joins
  the list with its first question. Several can be open and waiting for answers at once.
- **Open conversations are faded.** While a conversation's chat hologram stands, its row in
  the list is grey and ignores clicks. Closing the chat hologram with its X brings the row
  back.

## Changed

- A chat hologram has two panels: the conversation, titled with its name and with RENAME and
  DELETE under it, and the prompt box. The session list left the chat hologram for Chat
  Sessions.
- DELETE, after its confirmation, closes that conversation's chat hologram.
- `ChatController` in `common.ui` is split into `ChatSessions`, the shared list and store, and
  `ChatConversation`, one conversation's messages and waiting question.
- Built against MCAgents core 0.11.0.
