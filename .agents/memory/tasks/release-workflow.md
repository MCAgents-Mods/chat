---
name: memory-task-release-workflow
status: in-progress
description: Adding the Release workflow, which attaches chat's Fabric and NeoForge mod jars to the GitHub release of each v{version} tag, and the 0.4.1 release.
---

# Release workflow

## 2026-10-13

**Goal.** The owner asked, for every MCAgents repository: "Create github workflows ci/cd name
"release.yml". Make it build platforms jar and upload to release with it tag. Also jar in
action must be deleted too. It must have three jar? forge, fabric, neoforge." Players then
download each version's mod jars from the repository's Releases page. core, agents and the
template get the same workflow in their own pull requests; this repository's copy builds
chat's loaders.

**Objective.**
* After every Publish run on `master`, a Release run makes the GitHub release for
  `v{version}` with chat's mod jars attached, then deletes the run's copy of the jars.
* Chat is released as 0.4.1, and that release gets its jars from the workflow.

**Detail.**
* `publish.yml` does not change.
* The jars are built from the tagged commit with the token Publish uses, because the build
  reads core and agents from GitHub Packages.

## Decisions

The owner was away and asked for every question to be settled by taking the recommended
option.

* **Two jars, not three.** Chat has a Fabric and a NeoForge module and no Forge module, so
  its releases carry `MCAgentsChatFabric-{version}.jar` and
  `MCAgentsChatNeoforge-{version}.jar`. A Forge port of chat would be a feature, not a
  workflow, and the Minecraft 26.3 plan keeps chat without one.
* **After Publish, not on a tag push.** Publish pushes the tag with the workflow's own token,
  and such a push starts no other workflow, so Release runs when a Publish run on `master`
  finishes (`workflow_run`), and by hand.
* **The version on `master`, whenever its tag has no release.** An ordinary merge finds the
  release already there and stops; a failed release is made by the next merge. The first run
  gives the newest existing tag its release too. Older tags are released by hand with the
  `tag` input.
* **The jars between the two jobs are deleted.** They travel as the artifact `mod-jars`,
  which the release job deletes once the release holds them, and which expires after a day
  if a run fails first.
* **The notes are the version's changelog,** with its relative links pointed at the tagged
  files.
* **Release 0.4.1.** A patch: the workflow changes no jar. It waits for chat 0.4.0 so the
  hologram work's branches are left alone. The alternative was no version change.

## Tasks

| # | Title | Branch | PR |
|---|---|---|---|
| 1 | Task record | `chore/release-workflow-plan` | |
| 2 | Release workflow | `ci/release` | |
| 3 | Release 0.4.1 | `release/0.4.1` | |

### Task 1 — chore/release-workflow-plan

This record, and its row in the memory index.
