---
name: memory-tasks-gui-play-testing
description: What running the mod in a real client found that the test suite structurally could not, and the one mistake made twice.
---

# Task — GUI defects found by play-testing

**Status:** merged · **Branches:** `fix/chat-screen-unreachable`, `fix/gui-usability`,
`feat/session-management`, `fix/chat-feedback`

## The mistake made twice

**An error that only reaches the log is, from the player's side, identical to no error at
all.**

1. `ChatScreenLauncher` refused to open the screen when storage was not ready, logged a
   warning, and returned. A dead key, indistinguishable from a mod that never loaded.
2. `AgentChatScreen.send()` refreshed the interface only on the success path and logged
   failures. A conversation that simply stopped, with no way to tell a missing key from an
   unserved model id — and a saved chat that never appeared in the sidebar.

The rule that came out of it: **a screen opens, then explains itself.** Never gate an
interface on a subsystem, and never let a failure reach only the log.

## A wrong diagnosis, recorded because it cost work

"Pressing G does nothing" was diagnosed as a storage failure. It was not: storage was fine,
the mod worked, and the player did not know the key was `G`. A fix was built on an
untestable theory before asking for a log or a screenshot. Ask first.

## Defects only a running client revealed

* Model settings drew the embedding label **inside** its own field — labels computed
  positions from `height / 2` while fields used a running offset.
* `ApiKeyScreen` rebuilt itself endlessly: `init()` → `loadKeys()` → `rebuildWidgets()` →
  `init()`. Widgets were destroyed several times a second, so **nothing could hold focus**
  and the entry box could not be clicked into.
* No way to rename or delete a conversation, though both repository methods existed.
* A new conversation did not appear until something else refreshed the list.

## Why the test suite missed all of it

Every one is a **layout, lifecycle, or error-presentation** problem. All 91 tests live in
`common/`, which has no layout, no lifecycle, and no player to show an error to. **Screens
are only verified by running them**, and this repository has no way to do that in CI. Treat
any GUI change as unverified until someone has clicked it.

## Discoverability

Deliberate outcome: rename moved from a double-click to a pencil button, and the
`/mcagents` command was removed in favour of putting everything in the interface. A control
that says what it does beats a gesture that must be discovered — the same lesson as the `G`
key, which is **still** unaddressed for new players.
