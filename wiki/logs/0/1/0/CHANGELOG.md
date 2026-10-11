# 0.1.0

Released: 2026-10-11

Chat now runs on MCAgents core. Its model client and its web console are core's, shared
with every other MCAgents mod, and a cloned MCP server no longer runs until the player
approves it.

## Upgrading

Every player has something to change.

* **Install MCAgents core 0.5.0 or newer** (`mcagents_core`) beside this mod. Chat no
  longer carries its own model client or web server, and will not load without core.
* **Approve your cloned MCP servers.** A server wired by its repository's `mcp.json` or
  `package.json` stays stopped until `mcp_servers.json` approves it. Its status, and the
  agent's reply when it clones one, give the exact line to add:
  `"approved": "<fingerprint>"` in that server's entry. A server you wired yourself with a
  `command` or `url` needs nothing.
* **Web console addresses moved.** Chat's pages are at `/chat/…` on core's console, and its
  API at `/api/chat/…`. A bookmark to the old `/knowledge/` address no longer resolves.
* **A stored key's platform address cannot move to another host from the console.** Remove
  the key, change the address, and add the key again.

## Added

* **`ChatWebModule`**, chat's pages registered with core's `CoreWebRegistry`: Knowledge,
  Chat, Models, Wallets and API keys, using core's page shell, stylesheets and `site.js`.
* **`ChatModelClients`**, which builds core's `OpenRouterClient` with chat's key source,
  provider routes and attribution.
* **MCP server approval** (`McpApproval`), keyed on a fingerprint of the server's command,
  arguments, directory and environment, or its URL. A changed wiring needs approving again.
  A new `McpServerStatus.State.AWAITING_APPROVAL` says what the server would run.
* **`RepositoryCoordinate.hostOf` and `canonicalCloneUrl`**, so the clone outcome names
  the host and git is given a URL rebuilt from the coordinate.

## Changed

* The model client, its transport, the request types and model naming are core's
  `io.github.mcagentsmods.core` types, compiled against and never bundled.
* `WebConsoleHolder` registers chat's module with core's shared console instead of running
  a server of its own.
* Clone URLs must name exactly `{owner}/{repository}`, with no credentials, port, query or
  fragment. An existing checkout from a different host is not pulled.
* A repository's `mcp.json` may name a hosted server over https, or plain http to loopback
  only.
* Wallet challenges are bound to their account and address and expire after ten minutes.
  `WalletChallenges.redeem` takes the account id.
* The wallet bridge's `Host` check requires the listener's own port.

## Fixed

* MCP servers and git no longer inherit `MCAGENTS_CHAT_TOKEN_ENCRYPT_KEY`; git no longer
  waits on a credentials prompt.
* A server started while the MCP host was closing could outlive it, and a watch service
  that failed to register leaked.
* HTTP MCP requests now have a deadline; git and stdio server output are bounded; a
  timed-out git is stopped, and a server's descendant processes are stopped with it.
* Manifests, `package.json` files and the catalog over 1 MB are no longer read.
* The embedding cache no longer keeps whole Knowledge Pack text in its keys.

## Removed

* Chat's own copies of the model client, HTTP transport, model types, web server, page
  shell, shared stylesheets and `site.js`, all of which are core's.
