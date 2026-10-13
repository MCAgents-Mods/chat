# 0.4.1

Released: 2026-10-13

Each version's mod jars are attached to its GitHub release. No code, jar or API changes.

## Upgrading

- **Nothing to change in a build or a game.** The jars are the same as 0.4.0's apart from
  the version, and they still need MCAgents core 0.6.0 and MCAgents agents 0.2.0 or newer.
- **Players: download chat from the repository's Releases page.** The `v0.4.1` release
  carries `MCAgentsChatFabric-0.4.1.jar` and `MCAgentsChatNeoforge-0.4.1.jar`; install the
  one for your loader beside core's and agents'. Chat has no Forge jar.

## Added

- `.github/workflows/release.yml`, the Release workflow. After each Publish run on `master`,
  when the `v{version}` tag has no GitHub release yet, it builds the Fabric and NeoForge jars
  from the tag, creates the release with them attached and this changelog as its notes, and
  then deletes the copy of the jars the run kept in Actions. It can also be run by hand, with
  a `tag` input for an earlier tag that has no release.

## Changed

- The setup page, the repository map and the repository state describe the Release workflow
  beside Publish.
