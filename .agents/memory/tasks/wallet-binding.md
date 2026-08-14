---
name: memory-tasks-wallet-binding
description: Record of the MetaMask wallet binding work — the shape chosen, the decisions not to relitigate, what is proven and what is not.
---

# Task — MetaMask Wallet Binding

**Status:** complete, unverified in a real client · **Base:** the trunk this repository is
actually developed on

## Branches

One per task, stacked, each based on the one before it. All pushed; no pull requests were
opened, on the user's instruction.

| Branch | What it carries |
|---|---|
| `feat/wallet-contracts` | `api.wallet` records and enums, `WalletRepository` |
| `feat/wallet-storage` | The two tables, `SqliteWalletRepository`, 16 tests |
| `feat/wallet-bridge` | The loopback bridge, Keccak-256, secp256k1 recovery, address checks, 39 tests |
| `feat/wallet-screen` | The wallet screens and the bottom-left button |
| `docs/wallet-binding` | This record, the wiki page, the schema and map updates |
| `fix/wallet-bridge-shutdown` | Close the listener **before** completing the binding future |

## Goal

Bind MetaMask wallets client-side, with several accounts, several wallets per account, and
several chains per wallet — the shape MetaMask's own multichain address list has.

## Decisions, not to relitigate

| Decision | Why |
|---|---|
| A local web server, not WalletConnect | The user asked for it, and it needs no third-party SDK. `window.ethereum` exists only on a web page, so a page is the only way to reach the extension. |
| The listener stops on the first success | The user asked for it explicitly, and it is what keeps this from being "the mod runs a web server". |
| Keccak-256 and secp256k1 written out | A cryptography library would add megabytes to a jar shipped to every player for one hash and one curve operation. The JDK's SHA3-256 is a **different function** and would recover a wrong address silently. |
| Only EVM addresses can be proved | MetaMask offers a web page no way to sign with its Bitcoin, Solana, or Tron accounts. Refusing to record them would mean the mod could not hold most of a real wallet. |
| Accounts have no table | An account exists as long as a wallet names it. Nothing accumulates and nothing needs tidying. |
| The default account is the profile **name**, not the UUID | It is readable, and the account is a free-text label the player may change anyway. |
| The address is filed under the chain the browser was on | Every EVM chain shares the address; an unknown chain id falls back to Ethereum rather than failing a signature that verified. |
| `is_verified` on the address row | A recorded address and a proved one must never look alike, in the store or on screen. |

## Proven

* Keccak-256 against four published digests, including the empty input and a check that it
  differs from the JDK's SHA3-256.
* The address of private key 1 (`0x7E5F…95Bdf`) and all four EIP-55 checksum vectors from
  the EIP itself.
* Recovery round-trips across twenty keys, over a non-ASCII message, and for all three
  encodings of `v`.
* The bridge: binds on a proved signature, refuses a mismatched one, refuses a replayed
  one, refuses a missing token, refuses a foreign `Host`, times out, cancels — and
  **the port is closed once the binding is made**.
* Storage: cascade, one-address-per-chain replacement, account lifecycle, reopening.
* 146 tests in `common/`, all green. `./gradlew build` produces the mod jar with
  `mcagents/wallet-bridge.html` inside it.

## The mistake worth not repeating

The bridge originally did `binding.complete(verified)` and *then* started a thread to close
the listener. Both things happened, so every reading of the code said "it stops on success"
— but a caller waiting on the future could observe the handshake as finished while the
socket was still accepting connections. The test that connects to the port the instant the
future resolves passed on one machine and failed on another, which is exactly how this class
of bug presents.

The fix is ordering, not a delay: `stop()` shuts the listener first and settles the future
second, and it runs off the request thread because it also shuts down the executor that
serves requests. **Do not "simplify" that back into completing the future first.** A
`Thread.sleep` in the test would have hidden the defect instead of fixing it.

## Not proven, and worth knowing

* **No real MetaMask signature has ever been checked.** The tests sign with their own
  secp256k1 signer. The maths is pinned to published vectors, but the round trip through
  the real extension has not happened.
* **No screen here has been run in a real client.** Compile-verified against Minecraft
  26.2 only; the build environment has no display. This is the mistake recorded twice in
  [`gui-play-testing.md`](gui-play-testing.md) — do not treat a green build as a working
  screen.
* `SystemBrowser` is untested on Windows and macOS; the fallback is the copyable link on
  the connect screen.

## Reported, not created

`.agents/security/wallets.md` was proposed to the user as a discovery-protocol finding
rather than written. Instruction files are never created without the user selecting them.
