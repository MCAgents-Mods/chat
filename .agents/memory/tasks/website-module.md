---
name: memory-task-website-module
description: Why the web console became its own Gradle module, where the boundary is, what stayed behind in api/ and common/, and how the move was proven not to have broken the pages.
---

# The `website/` Module

## Why it moved

The console was `common.web` — five classes and a resource tree sitting beside SQLite
access, the OpenRouter client, retrieval, and the wallet cryptography. Four unrelated
purposes in one module, sharing nothing but a constraint: none of them needs Minecraft.

**"Does not need Minecraft" is not a purpose.** `website/` has one: everything a browser
ever sees, and nothing else.

The move cost nothing to untangle — **nothing in `common/` outside `web/` had ever
referenced it.** That is the thing worth remembering: it was always a separate thing, and
the package boundary had been doing the work a module boundary should have been doing.

## Where the arrows point

`api/` ← `common/` ← `website/` ← `platforms/fabric/`, one way.

`website/` sits **above** `common/`, not beside it. Serving a page means reading storage,
driving the agent, syncing knowledge, and filing wallets, so it depends on those
implementations. **Nothing in `common/` may depend back on it.** The day something in
`common/` wants to is the day that logic belongs in `common/` rather than in a page.

## Two decisions not to relitigate

**`WebConsole` and `WebConsoleException` stayed in `api/`.** They are contracts, `api/` is
the contracts module, and a contract that follows its implementation out is a rule that has
quietly stopped applying. `platforms/fabric` still types against the contract.

**`StubTransport` and `TestSigner` moved to `common/src/testFixtures/`.** A Gradle test
source set is private to its own project, so a double two modules need cannot be imported
across. The alternative was a copy in each, and a duplicated double drifts until the two
modules are testing against different fictions. `common`'s own tests still see the fixtures
automatically; `website` asks for them with `testImplementation testFixtures(...)`.

`TestSigner` still reaches package-private `Secp256k1`, `Keccak256`, and
`hashPersonalMessage` — same package name, different source set, flat classpath, so the
access holds. Do not widen those to `public` for a test.

## What the resources did *not* do

The resource path is unchanged: `mcagents/web/**`, now under `website/src/main/resources/`.
`WebAssets` reads by classpath path and the fabric jar merges every module's output, so
renaming the resource root would have been churn with a runtime 404 as its only possible
outcome.

`mcagents/wallet-bridge.html` **stayed in `common/`**. It belongs to `LocalWalletBridge`,
not to the console — the two servers are deliberately opposite in lifetime and always have
been.

## How the move was proven

A refactor that compiles is not a refactor that works, and the failure mode here is a
resource that silently stops being found.

* Test totals unchanged at **322**, now split 256 in `common` and 66 in `website` — the
  number is the check that no test was dropped in the move rather than relocated.
* The mod jar was unzipped: 5 website classes and all 35 `mcagents/web/` entries present,
  with `wallet-bridge.html` still arriving from `common`.
* All six pages driven in Chromium against the moved module — every linked stylesheet and
  script returning 200, no HTTP errors, no console errors, and `/wallets/` still showing 5
  of 8 rows.

## Note for the next module split

The harness scripts in the scratchpad hard-code a classpath of module output directories.
Adding a module means adding its `build/classes/java/main` **and** its
`build/resources/main` there, or the console starts and then serves a blank page. That cost
one confusing minute.
