---
name: memory-task-repository-move
status: open
description: Moving the repository to MCAgents-Mods/chat — the new namespace, the MIT license, what was deliberately left alone, and the plan.
---

# The Repository Move

## 2026-10-11

**Goal.** The repository now lives at `https://github.com/MCAgents-Mods/chat`. Everything
that names the old home — the package, the properties file, the license, the links — should
name the new one.

**Objective.** The base package is `io.github.mcagentsmods.chat`; `gradle.properties` carries
`git-org=MCAgents-Mods` and `git-repo=chat`; `LICENSE` is the MIT license, copyright 2026
MCAgents; the README no longer names the author; every document that stated the old
coordinates states the new ones; and the build and tests still pass.

**Detail.** Supplied by the repository owner: the repository URL, the namespace, the license
text (verbatim), and the paragraph to delete from the README. No version bump — the owner
bumps after this work lands.

## The task list

| # | Title | Scope | Repository | Branch | Files / areas | PR |
|---|---|---|---|---|---|---|
| 1 | The record | This file, written before the work is committed | `MCAgents-Mods/chat` | `chore/repository-move-plan` | `.agents/memory/`, `.agents/index/memory-index.md` | |
| 2 | Namespace and coordinates | Package rename, Gradle group and names, mod entrypoint, properties | `MCAgents-Mods/chat` | `refactor/package-namespace` | every Java source set, `build.gradle`, `settings.gradle`, `platforms/fabric/`, `gradle.properties` | |
| 3 | License and repository metadata | MIT `LICENSE`, mod metadata, README | `MCAgents-Mods/chat` | `docs/mit-license` | `LICENSE`, `fabric.mod.json`, `README.md` | |
| 4 | Docs sync | Instructions, both wikis, and state that named the old coordinates | `MCAgents-Mods/chat` | `docs/repository-move` | `AGENTS.md`, `.agents/`, `wiki/environments/` | |
| 5 | The release | Changelog entry, the `PR` column, and the closing entry | `MCAgents-Mods/chat` | `release/0.0.0` | `wiki/logs/`, this file | |

## Decisions not to relitigate

**Group `io.github.mcagentsmods`, base package `io.github.mcagentsmods.chat`.** The old pair
was `io.github.mcagents` and `io.github.mcagents.clientfabric`: the group is the parent of the
base package. The new pair keeps that relationship. The four sub-packages (`api`, `common`,
`website`, `fabric`) keep their names.

**What is not renamed.** The mod id `mcagents`, the `MCAgents/` game-data directory, the
Gradle module names `mcagents-fabric-*`, and the central service URL
`mcagents.mcrealms.space` all name things players or the service already know, not the
repository. Renaming them would move data or break a contract for no benefit.

**`MCAGENTS_CLIENTFABRIC_TOKEN_ENCRYPT_KEY` keeps its name.** It contains the old repository
name, but it is a name players set in their environment: renaming it silently stops
decrypting any key stored under the old one. Whether to rename it is the owner's decision, not
a side effect of a repository move.

**Historical records keep the old coordinates.** Task records under `.agents/memory/tasks/`
and changelog entries about past work say `MCAgents/client-fabric` because that was true when
they were written. Rewriting them would make the record disagree with the pull requests it
cites.

**Release branch `release/0.0.0`, no bump.** The owner bumps the version after this work, so
the release task logs into the existing `0.0.0` changelog and changes no version carrier.

**No session trailers in commits.** This repository's commit convention forbids a link to a
tool session, and a model identifier does not belong in a commit either; commits here carry
neither.

*(each task appends below as it lands)*

### Task 2 — refactor/package-namespace

**Landed.** Every source set moved from `io/github/mcagents/clientfabric` to
`io/github/mcagentsmods/chat` with `git mv`, so history follows the files, and every
`package`, `import` and fully qualified reference was rewritten. The Gradle group is
`io.github.mcagentsmods`, the root project is `chat`, the jar is `mcagents-chat`, and
`fabric.mod.json` names the new entrypoint class. `gradle.properties` carries
`git-org=MCAgents-Mods` and `git-repo=chat`; `project-version` is untouched.

**Proven.** `./gradlew build` passes for all four modules, tests included. The built jar holds
206 entries under `io/github/mcagentsmods/chat/`, none under the old path, and the entrypoint
class named in `fabric.mod.json` is in it.

**Not proven.** That the mod loads in a running client. The entrypoint string and the class
agree, which is what a rename can break, but nothing here launched Minecraft.

**One trap.** `CachingModelClient.java` contains a byte that makes grep treat it as binary, so
`grep -I` skipped it and the first rewrite missed it. The compile caught it. Searches for
leftover coordinates here need `grep -a`.

**For the next task.** The docs still state the old coordinates; task 3 and task 4 own those.

### Task 3 — docs/mit-license

**Landed.** `LICENSE` is the MIT text supplied by the owner, byte for byte, replacing the
commercial agreement. `fabric.mod.json` declares `"license": "MIT"` and points `sources` and
`issues` at `MCAgents-Mods/chat`. The README lost the author paragraph the owner asked to
delete, its "Where the project lives" section names the one repository instead of the old
organization on two hosts, and its License section is a short MIT summary that defers to
`LICENSE`.

**Decision.** The GitLab link was dropped with the old organization section, since the
repository now lives only on GitHub. The central service homepage in `fabric.mod.json` is
unchanged — it is the service, not the repository.

**Left for task 4.** The commercial license is still described in
`.agents/wiki/context/repository-map.md`, which owns that line.

### Task 4 — docs/repository-move

**Landed.** The documents that state the repository's coordinates now state the new ones:
`AGENTS.md` (the repository name in its opening line), the repository map (the license line,
the `gradle.properties` block, the group and base package rows), the state file's stack
line, and the setup page's `gradle.properties` block.

**Left as it was, on purpose.** Task records under `.agents/memory/tasks/` keep
`MCAgents/client-fabric` — it was true when they were written and the pull requests they cite
say the same. The `{minecraft_dir}/MCAgents/` paths are player data, not the repository. The
references to `MCAgents/server-fabric-expressjs` name a different repository, which this move
did not touch; whether it moved too is the owner's to say. `MCAGENTS_CLIENTFABRIC_TOKEN_ENCRYPT_KEY`
keeps its name, per the decision above.

**Verified.** A search of `AGENTS.md`, `.agents/`, `wiki/`, the README and the build files
for `client-fabric`, `clientfabric` and `commercial`, outside the historical records and the
changelog, finds nothing.
