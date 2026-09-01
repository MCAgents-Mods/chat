---
name: memory-task-game-provider-selector
status: in-progress
description: Bringing provider selection into the game GUI — the key screen's platform dropdown, the model screen's provider row, and why the offered list moved into api/.
---

# Provider Selection in the Game GUI

## 2026-09-01

**Goal.** The console gained two provider selectors and the game gained neither. A player who
never opens a browser still types a platform id from memory on the key screen, and has no way
at all to say which provider should answer — they have to know that a model id's first segment
is its platform and edit it by hand.

**Objective.** In game, the platform a key opens is picked from a list that still accepts
anything typed, and the provider that answers is picked on the model screen, with the model id
resolved by the same rule the console uses and a line saying whether that provider is reached
directly or through the gateway.

**Detail.** No new concept and no schema change: this is the console's own behaviour, reached
from the game. The list of platforms worth offering moves into `api/` so both interfaces show
one list rather than two that drift. Nothing becomes a whitelist —
[`../../api/openrouter.md`](../../api/openrouter.md) forbids rejecting a platform or a model
the player named, and `EditableDropdown` is already the control that suggests without
constraining. The in-game address box stays absent: a URL is still configured in the console,
which [`token-platforms-and-encryption.md`](token-platforms-and-encryption.md) settled and
this work does not reopen.

## The task list

| # | Title | Scope | Repository | Branch | Files / areas | PR |
|---|---|---|---|---|---|---|
| 1 | The record | This file, written before the work is committed | `MCAgents/client-fabric` | `chore/game-provider-selector-plan` | `.agents/memory/`, `.agents/index/memory-index.md` | — |
| 2 | One offered list | The platforms worth offering, computed once in `api/` | `MCAgents/client-fabric` | `feat/offered-platforms` | `api.model.ModelCatalog`, `website.WebApi` | — |
| 3 | The key screen | Picking the platform a key opens, in game | `MCAgents/client-fabric` | `feat/key-screen-provider` | `platforms/fabric` — `ApiKeyScreen` | — |
| 4 | The model screen | Picking the provider that answers, and saying how it is reached | `MCAgents/client-fabric` | `feat/model-screen-provider` | `platforms/fabric` — `ModelSettingsScreen`, `common.ui` | — |
| 5 | The release | Both wikis, the README, the changelog, the closing entry | `MCAgents/client-fabric` | `docs/game-provider-release` | `wiki/`, `README.md`, `.agents/` | — |

Ordered by dependency and stacked that way: task 1 branches from `master`, task `k` from task
`k-1`. Tasks 3 and 4 both need task 2's list; task 5 describes all of them.

**The pull request gate is open from the last chain**: the user asked for pull requests and an
in-order merge there, and asked for this work in the same breath. It is asked again at the end
rather than assumed — permission for one chain is not permission for the next.

## Decisions not to relitigate

*(each task appends below as it lands)*

### Task 2 — one offered list

**The list moved into `api/` before either screen was touched.** `ModelCatalog.offeredPlatforms`
takes the platforms the player is already on and returns the gateway, the platforms the
suggested models name, the ones this release ships an address for, and the player's own — in
that order, deduplicated. `WebApi.routes()` now calls it instead of building the same set
inline. Doing this first is what stops the game screen from growing a second list: two lists
computed in two places from the same three sources are equal exactly until one of them is
edited.

**`offeredModelPlatforms` is a second method rather than a flag.** It is the same list without
the gateway, for the one question the gateway is not an answer to: who serves a model.
`openrouter/gpt-4o-mini` is a model nobody serves, so the control that picks a model's platform
must not offer it — while the control that files a key under a platform must, since the gateway
is what most keys are for. A boolean argument would have made those two questions look like one
question with a setting.

### Task 3 — the key screen

**The platform box became an `EditableDropdown`, which already existed for exactly this.** It
is a field with a list attached: it suggests without constraining, which is the property the
console reaches with a selector plus an "Other…" row. The game needed no new control and no new
rule — the class was written for model ids, and "pick a known one or type one nobody here has
heard of" is the same question a platform asks.

**The list includes the platforms the player already holds keys for.** They are read for the
list of stored keys anyway, so `platformsInUse()` costs nothing, and it means a player who
filed a key under a platform this release never heard of does not retype that name — and
cannot mistype it into a second platform nobody serves.

**A picked row is read back in `mouseClicked` rather than trusted to notify.** The dropdown
writes into the field directly, and this screen keeps `pendingPlatform` across the rebuild that
storing a key triggers. Relying on the field's responder to fire on a programmatic `setValue`
would have made a kept value depend on a vanilla implementation detail; reading it where the
click is handled does not.
