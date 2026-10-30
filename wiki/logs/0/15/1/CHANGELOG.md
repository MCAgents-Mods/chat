# 0.15.1

Released: 2026-10-30

A clean-up release with nothing a player can see: two classes that did two jobs each are split
so that each file has one responsibility. Chat still registers no command, so no permission
changes; its only privileged actions, cloning and running an MCP server, already wait for the
player's approval.

## Upgrading

- **Players: replace the chat jar.** The `v0.15.1` release carries
  `MCAgentsChatFabric-0.15.1+26.3.jar`, `MCAgentsChatNeoforge-0.15.1+26.3.jar` and
  `MCAgentsChatForge-0.15.1+26.3.jar`. Core 0.14.0 or newer is still all chat needs, and no
  file, setting or database changes.
- **Nothing to change in a build that depends on chat.** No public type moved or changed.

## Changed

- **`LocalWalletBridge`** keeps the wallet handshake — its handlers, the method, `Host` and
  token guard, and its page — and the request and reply plumbing moved to the package-private
  `LoopbackHttp`. Replies, headers and the 8 KiB body limit are unchanged.
- **`WatchingMcpHost`** keeps the MCP sessions and what a reload does, and the watch service,
  its `MCAgents-mcp-watch` thread and the two-second settle window moved to the
  package-private `McpConfigWatcher`. A server cloned while the game runs is still picked up
  the same way.
