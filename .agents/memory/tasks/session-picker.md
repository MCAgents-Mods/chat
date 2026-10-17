---
name: memory-task-session-picker
status: in progress
description: A Chat Sessions hologram that lists every conversation with NEW CHAT on its nav bar, one chat hologram per conversation with several open at once and the open ones faded in the list, on core 0.11.0, released as a minor.
---

# Chat session picker

## 2026-10-17

**Goal.** On 2026-10-17 the owner wrote: "I want it can spawn multiple hologram at once. Also
I want it can spawn multiple chat hologram at once too. After click chat in GUI then make
another GUI to show all chat session available. And then have button to add button "New Chat"
at right as nav bar. And when click chat make it spawn hologram and just make chat session that
was spawn faded not disappear and can't click." The owner approved the plan
(`plans/multi-hologram.md` in the project's shared files) the same day, and had already asked
for every branch to be opened as a pull request and rebase-merged in order.

**What core does first.** Core 0.11.0 (its `multi-hologram` task record) lets up to 8
holograms stand at once, adds the `PickList` panel (a nav bar with buttons on its right, rows
that can be faded), and gives `Transcript` a live heading and buttons under it.

**What changes in chat.**

- CHAT on the G menu opens a **Chat Sessions** hologram in the menu's place: a pick list of
  every conversation, the most recently used first, with **NEW CHAT** on the right of its nav
  bar. It stays open while the player picks.
- Picking a conversation opens its own chat hologram, `mcagents.chat.{session id}`. While that
  hologram stands, the conversation's row is faded and cannot be picked; it stays in the list.
- A chat hologram has two panels: Conversation, headed with the conversation's title, with
  RENAME and DELETE under it, and User Prompt. The Sessions panel moves out into the picker.
- NEW CHAT opens a chat hologram on an empty conversation; the conversation is stored, and its
  row appears faded, with its first question.
- Each chat hologram waits for its own answer, so questions can wait in several at once.
- DELETE, after its confirm screen, deletes the conversation and closes its hologram.

**Decisions.**

- **`ChatController` splits in two:** `ChatSessions`, the shared list, store and agent, and
  `ChatConversation`, one conversation's messages, waiting question and failure, one per chat
  hologram. Both stay free of Minecraft and are tested on the real store.
- **A minor release, 0.10.0:** the chat hologram changes shape, and core 0.11.0 is the oldest
  the jars load with.

## Tasks

| # | Title | Branch | PR |
|---|---|---|---|
| 1 | Task record | `chore/session-picker-plan` | |
| 2 | One conversation per hologram | `refactor/chat-conversations` | |
| 3 | Session picker and chat holograms | `feat/session-picker` | |
| 4 | Release 0.10.0 | `release/0.10.0` | |

### Task 1 — chore/session-picker-plan

This record, and its row in the memory index.

### Task 2 — refactor/chat-conversations

`ChatController` is split in `common.ui`. `ChatSessions` binds the store and the agent, lists
the conversations (`sessions`, `find`, `version`), renames and deletes them, and hands out a
`ChatConversation` with `open(sessionId)`, `null` for a new one. A `ChatConversation` holds
one conversation's messages, the question waiting in it and the reason the last was not
answered, so two can wait for answers at once; a new one takes the id it is stored as with its
first question, and the list is read again. `ChatControllerTest` became
`ChatConversationTest` and `ChatSessionsTest`, with a test of two conversations waiting at
once. `ChatHologram` keeps its three panels on the new classes: picking a row opens that
conversation, NEW opens a new one. The architecture page, the repository map and the state
file name the two classes.

### Task 3 — feat/session-picker

Chat compiles against core 0.11.0, and the three manifests require it. `ChatSessionsHologram`
(`mcagents.chat.sessions`) is a core `PickList` titled Chat Sessions with NEW CHAT on its nav
bar; its rows are the conversations, each faded while `ChatHologram.isOpen` says its hologram
stands, and while storage is not open, one faded row saying why. CHAT on the G menu opens it,
and the menu runs CHAT through `Holograms.replacing`, so it opens where the menu stood.
`ChatHologram` opens one hologram per conversation: `mcagents.chat.{session id}` for a stored
one, `mcagents.chat.new-{n}` for a new one, which keeps that id once stored. Each holds its own
`ChatConversation`: a Conversation transcript headed with the conversation's title (New Chat
until stored) with RENAME and DELETE under it, and the User Prompt. DELETE, once confirmed,
deletes the conversation and closes its hologram. The Sessions panel, the NEW button and the
`hologram.mcagents.sessions` and `.new` keys are gone; `chat_sessions` and `new_chat` are new,
and the storage messages name NEW CHAT. The overview, README, architecture page, repository
map and state file describe the picker.
