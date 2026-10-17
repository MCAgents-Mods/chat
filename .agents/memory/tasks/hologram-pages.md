---
name: memory-task-hologram-pages
status: done
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
| 1 | Task record | `chore/hologram-pages-plan` | #65 |
| 2 | Dialogs as holograms | `feat/hologram-dialogs` | #66 |
| 3 | Keys, Models and Persona holograms | `feat/settings-holograms` | #67 |
| 4 | Wallets hologram | `feat/wallet-holograms` | #68 |
| 5 | Release 0.11.0 | `release/0.11.0` | #69 |

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

### Task 3 — feat/settings-holograms

KEYS, MODEL and PERSONA open hologram pages in the menu's place, all built on
`HologramPage`, which counts the storage requests a page waits on and drops an answer that
lands after the page closed. `KeysHologram` is a wide Add Key form (the key as stars, the
platform with its suggestions, the address greyed for OpenRouter, a note line, the encryption
line; ADD and SET) beside a Stored Keys list (masked, platform, rejected) with REMOVE.
`ModelsHologram` is a wide form with the provider, route line, chat and embedding fields, the
Embedding switch and SAVE / USE DEFAULTS; nothing is stored before SAVE. `PersonaHologram` is a
wide form with the file's path, a status line and the text area; SAVE writes and RELOAD reads
again. `ApiKeyScreen`, `ModelSettingsScreen`, `PersonaScreen`, `ModelSettingsRows` and its test
are deleted; `EditableDropdown` and `WaitingIndicator` stay until task 4, because the wallet
screens still use them. Text added for the new labels and notes; the Embedding on/off pair
became one switch label. README, overview, knowledge retrieval, setup and the repository map
name the pages. `.agents/security/api-keys.md` still says "key screen"; that is an instruction
file, so the wording is left to owen as a finding.

### Task 4 — feat/wallet-holograms

WALLETS opens `WalletsHologram` in the menu's place: three `SelectList`s, Accounts (ADD,
CONNECT, NEW WALLET; the player's own account always first and chosen on open), the chosen
account's Wallets (RENAME, DELETE) and the chosen wallet's Addresses (ADD, COPY, REMOVE).
Writes still go through `AccountWallets`. A button that needs a choice made says so on the
action bar, and so do copied and recorded notices. `WalletDialogs` holds the two larger
questions as form holograms in the dialog place: add an address (chain with suggestions, the
address, the format notice as it is typed, ADD enabled only when the address passes) and
connect MetaMask (the warnings, the state line, the link, OPEN / COPY LINK / CANCEL); the
bridge stops in the hologram's `onClose`, so every way of closing it stops the listener. The
whole `client.gui` package is deleted, with `StackedRows`, `AccountColumns` and their tests.
Text for the list titles, short buttons and notices added; the screen-only text removed.
`wallet-binding.md`, overview, architecture and the repository map describe the hologram.

### Task 5 — release/0.11.0

`project-version` 0.11.0, `wiki/logs/0/11/0/CHANGELOG.md` and its row in the logs index, and
the version in the state file, the repository rule, the repository map and the setup guide.
Built green against core 0.12.0; every page and dialog was tried on Fabric, NeoForge and Forge
26.3. The `PR` column is filled.
