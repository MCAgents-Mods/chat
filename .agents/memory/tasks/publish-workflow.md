---
name: memory-task-publish-workflow
status: in-progress
description: Adding the GitHub Actions publish workflow to chat — resolving published core from GitHub Packages, publishing all six modules with a v{version} tag, and the 0.2.0 release.
---

# Publish workflow

## 2026-10-12

**Goal.** The owner asked for every MCAgents repository to publish itself: a workflow on
`master` that publishes the jars to GitHub Packages and tags the release, so a mod built on
chat — or on core, or on agents — resolves a real artifact instead of a local checkout. The
owner supplied the workflow; this repository's copy names chat's own modules.

**Objective.** `.github/workflows/publish.yml` reads `project-version`, stops when
`v{version}` already exists on the remote, and otherwise builds, publishes
`mcagents-chat-{api, common, website, shared, fabric, neoforge}` and tags the commit. The
build that workflow runs succeeds on a runner with no core checkout beside it. Chat is
released as `0.2.0`, and that release is the first one the workflow publishes.

**Detail.**

* Chat compiles against core `0.5.0`. With no `../core` checkout, Gradle looked for it on
  Maven Central only, where core is not published, so a runner could not build chat. Core is
  published to `https://maven.pkg.github.com/MCAgents-Mods/core` by core's own workflow, and
  a public package there can be read by any repository's workflow token. Chat's merges wait
  until core's run has published `0.5.0`.
* The workflow publishes whatever version has no tag. Chat has no tags at all, and the
  artifact renames and the NeoForge jar landed after `0.1.0` without a release, so merging
  the workflow at `0.1.0` would publish today's `master` under a version whose changelog
  describes something else.
* Chat's agent and tool logic is not touched here; the agents repository may take it over
  later.

## Decisions

The owner was away and asked for each question to be settled by taking the recommended
option, so these were decided that way and posted in the project thread.

* **Version `0.2.0`.** A minor bump: the unreleased work since `0.1.0` renamed every artifact
  and added a NeoForge jar. `0.1.1` would understate it, and `release/0.1.0` is already taken.
* **`v0.1.0` marks the commit that released `0.1.0`.** That is accurate history, and it makes
  the run started by the workflow's own merge find its tag and publish nothing. `0.1.0` itself
  is never published.
* **CI gets core from GitHub Packages**, not from a checkout of core's `master`. The build is
  then checked against the exact core version chat declares, which is what a consumer of
  chat's packages will get.

## The task list

| # | Title | Scope | Branch | Files / areas | PR |
|---|---|---|---|---|---|
| 1 | The record | This file and its index row | `chore/publish-workflow-plan` | `.agents/memory/` | |
| 2 | Core from GitHub Packages | Resolve published core when no checkout is beside chat | `build/core-packages` | `build.gradle`, `settings.gradle`, repository map, setup page | |
| 3 | Publish workflow | The owner's workflow, naming chat's modules | `ci/publish-workflow` | `.github/workflows/publish.yml`, repository map, setup page | |
| 4 | The release | `0.2.0`, changelog, closing this record | `release/0.2.0` | `gradle.properties`, `wiki/logs/`, version mentions, this file | |

*(each task appends below as it lands)*
