---
name: memory-task-wallet-accounts
description: Proving every address in one trip, the account boundary and where it is enforced, the wallets page and screen, and the row-height guess a browser caught.
---

# Every Wallet, In The Right Account

## What was wrong

The MetaMask handshake took `accounts[0]` and stopped on the first signature that verified.
A player with five accounts in one browser made the same trip five times. The wallet screen
showed one account at a time behind a dropdown, so two identities could never be compared.
The console had no wallet page and no wallet routes; a test asserted `/api/wallets` was a
404.

Nothing anywhere checked that a wallet belonged to the account acting on it. The write was
keyed by wallet id alone, and a wallet id does not carry an account.

## One trip, every address

The bridge collects rather than stopping. The page loops the accounts MetaMask shares, and
the bridge settles when the page says it has reached the end — only the page knows how many
there are.

**One challenge per address.** Each message names the address it proves and carries a nonce
spent on redemption. A single shared message would let one signature be posted back beside
any *other* address the caller went on to name — a hole that exists only once a trip carries
more than one address. Do not "simplify" this back to one message per handshake.

**What was proved is kept.** Cancel, timeout, and close settle with whatever was already
signed, and fail only when nothing was. Those signatures were given; discarding them would
mean asking for them again. The exceptional path is reserved for "nothing at all".

**One wallet per address.** A wallet holds one address *per chain*, so filing five Ethereum
addresses into one wallet means four of them overwriting each other. `AccountWallets.bindAll`
reuses a wallet in that account that already holds the address, then fills an empty wallet if
there is one — that is the wallet the player just made by pressing Add, and leaving it behind
would look like the button had failed — and otherwise creates one named after the address.

## The account boundary

`WalletRepository.findWalletInAccount` puts the account in the `WHERE` clause, so a wallet
from another account is never loaded and then rejected. Every mutating path — the screen, the
page, the routes — goes through `AccountWallets`, which resolves through that method first.

**It is enforced in the mod, not in the interface.** A boundary kept by a page is a boundary
anyone with a `fetch()` can step over. The browser probe posts a cross-account rename
directly and expects 404.

Matching is scoped the same way: `bindAll` only ever looks at wallets in the target account,
so the same MetaMask account bound under two identities stays two separate records rather
than one being moved.

## Two front ends, one implementation

`WalletChallenges` issues and checks; both callers use it. The in-game path needs
`LocalWalletBridge` because a Minecraft screen has no `window.ethereum` and something must
hand the browser a page. **The console needs no bridge at all** — `/wallets/` is already a
page in the player's browser, so it calls `window.ethereum` and posts to
`/api/wallets/verify`. Do not "unify" these by routing the console through the bridge; it
would add a second server for no reason.

## The five-row defect

The page asks for five wallet rows per account and the first attempt put the height in CSS:
`--wallet-row: 4.25rem`. A browser showed **two**. A wallet row is one line with no address
and four with three, so any single height in the stylesheet is right for one account and
wrong for the next.

`wallets.js` measures the first five rows after render and sets `max-height`, re-measuring on
resize; the stylesheet keeps a fallback for the moment before that and for scripting off.
Verified at three widths: 5 of 8 visible at 1500 and 1100, and the panel reverts to page flow
below 900.

**The lesson is the general one: a layout requirement stated in rows cannot be met by a
constant in pixels when the rows are not a constant.**

Two more the same probe caught: `auto-fill` left the unused column tracks standing, so two
accounts on a wide screen were two narrow strips beside a third of blank page — `auto-fit`
collapses them. And stretched grid items padded a two-wallet account out to match an
eight-wallet one; `align-items: start`.

## The screen

Accounts are columns, five rows each, with `◀ ▶` when there are more accounts than fit.
Squeezing eight into eight slivers would be worse than paging.

`common/ui/AccountColumns` holds the arithmetic for the same reason `StackedRows` does:
`platforms/fabric/` has no test source set, so a sum that only a screen uses can only be
checked by looking at it. Tested properties: columns never overlap, they divide the width
exactly rather than leaving a ragged strip, there is always at least one, and the window
comes back inside the list when the list gets shorter — which is what deleting an account's
last wallet does, because an account stops existing when nothing names it.

## Testing note worth keeping

`TestSigner` is public and lives in the `common.wallet` test package. `Secp256k1`,
`Keccak256`, and `hashPersonalMessage` are package-private and should stay that way — the web
test needed real signatures, and the choice was to widen a signature scheme's internals or to
put a helper on their side of the boundary. The helper costs nothing.

## What is proven, and what is only compiled

Proven by tests: the multi-address handshake end to end over HTTP, nonce spending, partial
results kept, one-wallet-per-address filing, the account boundary in storage and over the
wire, and the column arithmetic. Proven by a browser: the page's five rows at three widths,
the in-page dialogs, and the cross-account 404.

**Only compiled: `WalletScreen` itself**, as ever — the Fabric module has no tests and the
screen has not been run in a client since this change.
