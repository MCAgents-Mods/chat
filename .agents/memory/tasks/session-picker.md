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
