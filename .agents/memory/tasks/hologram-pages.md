---
name: memory-task-hologram-pages
status: in progress
description: Every agents menu page chat adds (Keys, Models, Persona, Wallets) and every dialog they or the chat hologram open, rebuilt as holograms from core 0.12.0's form panel; the flat screens deleted; released as a minor.
---

# Chat pages as holograms

## The request

On 2026-10-17 the owner asked: "Make every button show as GUI hologram likes chat. use same
theme. Plan then work immediately." The attached picture was the flat Models screen. The
message is the go-ahead, and the owner had already asked for every branch to be opened as a
pull request and rebase-merged in order. A later request settles the Models page's fields
(providers, custom providers, the global model); this task only moves the page as it is.

Core's part, the form panel and typing into its boxes, is core's `hologram-forms` record,
released as core 0.12.0.

## Decisions

- **Every page and dialog becomes a hologram:** KEYS, MODEL, PERSONA and WALLETS on the menu,
  the chat hologram's RENAME and DELETE, and the wallet page's account, rename, delete, add
  address and MetaMask dialogs. Nothing chat opens is a flat screen any more.
- **Pages open in the menu's place; dialogs open beside the page that opened it,** which stays,
  and close themselves on their action or X.
- **Keys: two panels.** A wide Add Key form (key, secret; platform with suggestions; the
  platform's address with SET; the address note; the encryption line) and a Stored Keys list
  (masked key, platform, rejected) with REMOVE.
- **Models: one form,** the screen's rows as they are: provider with suggestions and its route
  line, chat and embedding models with suggestions, the embedding toggle, SAVE and DEFAULTS.
- **Persona: one wide form:** the file's path, a status line, the text area, SAVE and RELOAD.
  Only SAVE writes.
- **Wallets: three lists:** Accounts (ADD, CONNECT, NEW WALLET), the chosen account's Wallets
  (RENAME, DELETE) and the chosen wallet's Addresses (ADD, COPY, REMOVE). Short notices go to
  the action bar.
- **The flat screens go,** with `EditableDropdown`, the wallet panels and the layout helpers
  only they used (`ModelSettingsRows`, `StackedRows`, `AccountColumns`).
- **A minor release, 0.11.0,** on core 0.12.0.

## Tasks

| # | Title | Branch | PR |
|---|---|---|---|
| 1 | Task record | `chore/hologram-pages-plan` | |
| 2 | Dialogs as holograms | `feat/hologram-dialogs` | |
| 3 | Keys, Models and Persona holograms | `feat/settings-holograms` | |
| 4 | Wallets hologram | `feat/wallet-holograms` | |
| 5 | Release 0.11.0 | `release/0.11.0` | |

### Task 1 — chore/hologram-pages-plan

This record, and its row in the memory index.

### Task 2 — feat/hologram-dialogs

Chat compiles against core 0.12.0. `HologramDialogs` builds the two questions every page asks,
as normal-width form holograms with id `mcagents.dialog`, so one stands at a time: `name`
(the subject, a Name field, the answer button and CANCEL) and `confirm` (the message, the
answer button and CANCEL). Both close before running the answer, and X answers no. The chat
hologram's RENAME and DELETE use them instead of `RenameScreen` and vanilla's `ConfirmScreen`.
`RenameScreen` stays until task 4, because the wallet screen still opens it. New text: the
Name label. `wiki/information/overview.md` says where the two questions open.
