# External MCP Servers

MCAgentsClient can use tools that are not part of the mod. An **MCP server** is a small
program — someone else's, usually — that publishes a set of tools, resources, and prompts
over a standard protocol. The mod runs those servers, asks what they offer, and puts the
result in front of the agent alongside everything it can already do.

It is entirely optional. A copy of the mod with no MCP servers is the normal case, and
nothing about the game or the chat hologram changes when there are none.

## Where they live

```
{minecraft_dir}/MCAgents/mcps/{owner}/{repository}/
```

One directory per repository, grouped by the owner it came from — the same shape GitHub
uses, so a checkout is identifiable at a glance:

```
MCAgents/mcps/
  LXAgents-MCP/
    shared-instruction/
  acme/
    weather-mcp/
  mcp_servers.json          <- optional
```

This is player data, like `data.db` and `AGENTS.md`. It is never part of the mod and never
committed anywhere.

## Adding one

Ask the agent to add a repository, and it clones it here. Only `https://` URLs naming
exactly `{owner}/{repository}` are accepted — an `ssh` URL would need credentials the mod
does not have, and a `file://` URL would let a prompt reach anywhere on your disk. The owner
and repository name come from the URL rather than from anything the model chose, so a clone
cannot land outside the `mcps` directory, and the agent is told which host it came from.

Cloning uses the `git` already on your machine. If you do not have git, the mod says so
rather than failing obscurely; bundling a git implementation would have made the mod
permanently larger for a feature most players never use.

A repository that is already there is updated rather than re-cloned, so asking twice is
harmless. One already cloned from a different host is left alone and reported, because two
hosts can serve an `owner/repository` of the same name.

## Approving it

**A cloned server does not run until you approve it.** The agent can be asked to clone a
repository by text you did not write — a Knowledge Pack, or another tool's output — and a
repository's `mcp.json` can name any command. So the mod tells you what the server would run
and waits:

> **Cloned acme/weather-mcp from github.com. Waiting for your approval. It would run: node
> src/index.js (in acme/weather-mcp). To allow it, add "approved": "3f9a1c0b7d2e5a46" to
> "weather-mcp" in mcp_servers.json.**

Read the command, then add that line to the server's entry in `mcp_servers.json`:

```json
{
  "mcpServers": {
    "weather-mcp": { "approved": "3f9a1c0b7d2e5a46" }
  }
}
```

The mod notices the change and starts the server; there is no restart. The approval is for
that exact command, its arguments, its directory and its environment. If an update changes
any of them, the server stops and waits for you again with a new value to approve.

A server you wire yourself in `mcp_servers.json`, with a `command` or a `url` of your own,
needs no approval: you wrote what it runs.

## Being picked up

**No restart.** The mod watches the `mcps` directory. A repository cloned while you are
playing is listed within a few seconds of the clone finishing, and once approved its tools
become available in the next question you ask.

The wait is deliberate. A clone writes hundreds of files, and connecting to a repository
that is still being written produces a failure that looks like a broken server. The mod
waits for the directory to go quiet first.

## How the mod knows what to run

Two things are read, in this order.

**1. The repository itself.** A repository carrying an `mcp.json` at its root is stating
how it wants to be run:

```json
{
  "command": "node",
  "args": ["src/index.js"]
}
```

Without one, the mod reads `package.json` and uses the `bin` entry, or failing that
`main`. It goes no further than that: a repository that does not say how to start itself is
listed as a problem rather than guessed at, because a wrong guess produces a server that
starts and then fails in a way nobody can diagnose.

**2. `mcp_servers.json`, if you have one.** It sits at `MCAgents/mcps/mcp_servers.json` and
does two jobs — correcting what was inferred, and adding servers that are not on your disk
at all:

```json
{
  "mcpServers": {
    "weather-mcp": { "command": "python3", "args": ["server.py"] },
    "noisy-mcp":   { "enabled": false },
    "hosted":      { "type": "http", "url": "https://example.com/mcp" }
  }
}
```

An entry naming a repository you have cloned edits that server. An entry naming something
else adds one. An entry with only `"enabled": false` switches a server off without
forgetting how it was wired, so turning it back on needs no retyping.

A repository's own `mcp.json` may name a hosted server only over `https://`, or over plain
`http://` to `localhost`: tool calls carry your conversation, and a repository does not get
to send them anywhere in the clear.

For a hosted server, **the URL must include the MCP path** — usually `/mcp`. Without it the
request lands on a route that speaks no MCP, and servers commonly report that as a sign-in
failure rather than a wrong address. If a server insists it needs authentication, check the
path first.

## What the agent sees

Every connected server's tools are merged into one list, each renamed to
`{server}__{tool}`. Two servers can therefore both offer `search` without either shadowing
the other. Resources keep their own URIs, which are already unique to the server that
published them.

The tools are offered through MCAgents core's agent, grouped by server: for each question the agent
first picks the servers it needs, then their tools, and only then is shown those tools in
full. A player with many servers pays for the ones a question needs, not for all of them.

## Asking the agent to use them

You do not call these tools; the agent does. Ask a question, and if answering it needs one
of the tools a server offers, the agent calls it, reads the result, and answers you. All you
see is the answer. It gets one round of tool calls per question unless you allow more under
**SETTINGS** on the MCAgents menu, which **G** opens.

Adding a server is itself one of these tools. Ask the agent to add a repository and it
clones it and tells you what it would run and how to approve it, as in
[Approving it](#approving-it). Asked again once approved, it says how many tools the server
brought:

> **Updated acme/weather-mcp from github.com. It is connected and offers 3 tools.**

or, when a repository arrives but will not run:

> **Cloned acme/notes from github.com, but it is not offering any tools — it may not be an
> MCP server, or it may not say how to start itself.**

An approved server's tools can be used from your very next question.

## When a tool call goes wrong

It does not break your question. A tool that does not exist, arguments the agent got wrong,
or a server that has stopped since the agent was told about it all come back to the agent
as a message it can read — so it corrects itself and tries again, or tells you plainly what
it could not do.

The agent will not loop forever either. If it keeps needing tools without getting anywhere,
it stops and gives you what it has.

## When a server does not work

It is reported, and nothing else breaks. A server that will not start, answers nothing, or
is missing its entry point becomes a line saying which server and why — the other servers
stay connected, their tools stay available, and the mod carries on. An optional thing
that is unavailable is a message, never a failure.

Servers are stopped when the game exits. One that ignores that is killed rather than
allowed to outlive the game that started it.

## A note on trust

An MCP server is a program you have chosen to run, with your user account's permissions.
The mod isolates *where it is cloned* and *what it is called*, and runs nothing until you
approve it, but not what it does once running. Approve only repositories you would be
willing to run by hand. A server is not handed the passphrase your API keys are encrypted
with, even though the game holds it, and remember that a
server's environment in `mcp_servers.json` is where its credentials would sit — that file
is as sensitive as an API key.
