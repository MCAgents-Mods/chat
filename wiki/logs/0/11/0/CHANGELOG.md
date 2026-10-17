# 0.11.0

Released: 2026-10-17

Every button chat puts on the G menu now opens a hologram, in the same theme as the chat
hologram, and so does every question those pages ask. No flat screen is left.

## Upgrading

- **Players: replace the chat jar and the core jar.** The `v0.11.0` release carries
  `MCAgentsChatForge-0.11.0+26.3.jar`, `MCAgentsChatFabric-0.11.0+26.3.jar` and
  `MCAgentsChatNeoforge-0.11.0+26.3.jar`. This release needs core 0.12.0, for its form panel.
- **Nothing you stored changes.** Keys, models, the persona file, wallets and conversations
  are read exactly as before.

## Changed

- **KEYS opens the Keys hologram:** an Add Key form (the key as stars, the platform with
  suggestions behind its arrow, the platform's address, greyed for OpenRouter, and whether keys
  are encrypted) beside the Stored Keys list, masked, with REMOVE. ADD empties the key box.
- **MODEL opens the Models hologram:** the provider, the line saying whether it is called
  directly or through OpenRouter, the chat and embedding models with their suggestions, and the
  Embedding switch. Nothing is stored before SAVE; USE DEFAULTS puts the defaults back.
- **PERSONA opens the persona editor as a hologram:** the path of `AGENTS.md`, a status line
  and the text. SAVE writes the file, RELOAD reads it again, and closing it without saving
  leaves the file as it was.
- **WALLETS opens the Wallets hologram:** three lists side by side, Accounts (ADD, CONNECT,
  NEW WALLET), the chosen account's Wallets (RENAME, DELETE) and the chosen wallet's Addresses
  (ADD, COPY, REMOVE). Short notices go to the action bar.
- **Every question is a small hologram** beside the page that asked: naming an account,
  renaming a conversation or a wallet, deleting one, adding an address (its format checked as it
  is typed) and connecting MetaMask. Closing the MetaMask hologram, by any path, stops the local
  listener.

## Removed

- The flat screens and the helpers only they used: `ApiKeyScreen`, `ModelSettingsScreen`,
  `PersonaScreen`, `WalletScreen`, `AddAddressScreen`, `WalletConnectScreen`, `RenameScreen`,
  the `client.gui` package, and `ModelSettingsRows`, `StackedRows` and `AccountColumns` in
  `chat-common`.
