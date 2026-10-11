# Wallet Binding

MCAgentsClient can record the crypto wallets a player owns, and prove the EVM ones actually
belong to them. It is entirely client-side: no Minecraft server is involved, no server
operator has to install anything, and nothing about a player's wallets leaves their machine.

## What is stored, and what is not

**Public addresses only.** There is no field anywhere in the mod for a private key or a
recovery phrase, no code path that would accept one, and nothing that could do anything with
one. An address is a destination — publishing it is what addresses are for.

An input that *looks* like a secret — a 64-character hex string, or a run of twelve or more
lowercase words — is refused outright with an explanation, because a player pasting one has
been asked for the wrong thing, either by a scam or by their own confusion.

## The three levels

A player's holdings nest, so the mod's do too.

```
Account            "Steve" — the signed-in profile by default, any label the player likes
 └── Wallet        "Main", "Trading", "Server alt"
      ├── Ethereum   0x4b470…6BB75   ✓ proved by signature
      ├── Base       0x4b470…6BB75   ✓
      ├── Bitcoin    bc1qm8t…jqygj     recorded, not proved
      ├── Solana     9iA8dBe…jJuVF     recorded, not proved
      └── Tron       TQu217P…ME7Rn     recorded, not proved
```

* An **account** holds many wallets. It has no table of its own: naming one in a wallet
  creates it, and deleting the last wallet under it makes it stop existing, so empty
  accounts never accumulate. Sharing a computer and keeping profiles apart keeps wallets
  apart.
* A **wallet** holds one address per chain. Binding an address for a chain that already has
  one *replaces* it — that is how a typo is corrected, and a second row for the same chain
  would be a contradiction rather than a history.
* A **chain address** belongs to exactly one chain and remembers whether it was proved or
  typed in.

Every EVM chain in the list shares one address, exactly as MetaMask shows it.

## Proved against recorded

The mod draws this distinction everywhere, with a tick beside anything proved.

| | How it gets there | What it means |
|---|---|---|
| **Proved** | MetaMask signed a message the mod chose, and the mod recovered the signing address from that signature | The player demonstrably controls the key for this address |
| **Recorded** | Typed or pasted into the mod | The mod has the player's word for it, and a shape check |

Only EVM addresses can be proved, because signing is the only thing a browser extension
offers a web page and MetaMask only offers it for EVM accounts. Bitcoin, Solana, and Tron
addresses are typed in — and refusing them instead would mean the mod could not hold most of
what a player's wallet actually contains.

## How proving works

MetaMask is a browser extension: `window.ethereum` exists on a web page and nowhere else.
There is no Java API, no local socket, nothing a desktop program can talk to. So proving an
address means a web page, and the mod has two ways to reach one.

**From the game**, for the length of one handshake the mod becomes a web server. **From the
web console**, `/wallets/` is already a page in the player's browser, so it talks to MetaMask
itself and there is no second server at all. The checking is the same code in both.

1. The player clicks **Connect MetaMask**.
2. From the game, the mod opens a listener on `127.0.0.1`, on a port the operating system
   picks, and opens the player's browser at it. The address is shown on screen too, because
   the default browser is often not the one MetaMask is installed in.
3. The page asks MetaMask which accounts it may see, and then asks the mod for **one message
   per address** — each naming the account, naming the address it proves, carrying a nonce
   good exactly once, and stating plainly that signing authorises nothing.
4. MetaMask signs each. **A `personal_sign` never reaches a chain, moves no funds, costs no
   gas, and grants no approval.**
5. The page posts each address and signature back. The mod **ignores the claimed address**
   and derives the signer from the signature itself. If the two do not match, that one is
   refused; the others are unaffected.
6. **The listener stops when the page says it has run through the list** — and on cancel, on
   timeout after five minutes, and whenever the screen closes.

### Every address in one trip

A connection binds **every** address the browser shares, not the one it happens to have
selected. It used to bind the first and stop, which made a player with five accounts take the
same journey five times.

Each proved address becomes **a wallet of its own**. It has to: a wallet holds one address
per chain, so filing five Ethereum addresses into one wallet would mean each overwriting the
one before it and four signatures being thrown away.

A message naming its address is what makes this safe. One shared message would let a single
signature be posted back beside any *other* address the caller went on to name — a hole that
only opens once a connection carries more than one of them. The nonce is spent when it is
redeemed, so a signature captured in flight is not worth a second wallet. A nonce is also
good only for the account and address it was issued for, and only for ten minutes.

**What was proved is kept.** A connection that ends early — the tab closed, a signature
declined on the third of five — binds the ones already signed rather than discarding them.
They were proved; throwing them away would only mean asking for them again.

If MetaMask is only sharing some of your accounts, **Choose accounts…** on the mod's own page
asks it for the rest.

### An address lands in one account, and only one

The account is named in the message the player signs, so binding somewhere else would store
something other than what was agreed to.

Every write goes through one place that checks it. A request naming one account and a wallet
in another is answered as though there were no such wallet — which, for that caller, there is
not. That is enforced in the mod rather than in the interface: a boundary kept by a page is a
boundary anyone with a `fetch()` can step over. Matching an address against what is already
stored is scoped the same way, so the same MetaMask account bound under two identities stays
two separate records.

### What keeps that listener safe

| Measure | What it stops |
|---|---|
| Bound to `127.0.0.1` on an operating-system-assigned port | Anything outside the machine reaching it at all |
| A 256-bit one-time token, required on every request, only ever in the URL | Another program on the machine driving the handshake; another web page cannot read it |
| `Host` header must be a loopback name | DNS rebinding — a hostile site pointing a domain of its own at `127.0.0.1` |
| No CORS headers, and a JSON content type on the callback | A hostile page reading the mod's replies, or posting to it without a preflight it will not get |
| Stops when the page is finished, and on cancel, timeout, and screen close | The listener outliving the screen that opened it |
| One challenge per address, its nonce spent on redemption | A signature standing for an address it was not issued for, or counting twice |

The handshake is a loopback conversation. It never touches the network, and the mod's
offline-first rule is untouched: every wallet already bound stays readable, listable, and
copyable with no internet at all.

## Using it

In the game, press `G` for the chat screen, then **Wallets** in the bottom-left corner. In a
browser, open the console and go to **Wallets**. Both show the same thing the same way.

**Every account is a column.** Each shows five wallets and scrolls past that, so an account
with forty wallets cannot push the others off the screen. The in-game screen pages between
columns with `◀ ▶` when there are more accounts than fit; the browser wraps them onto a
second row. It used to be one account at a time behind a dropdown, which meant a player with
two identities could never see both.

* **Add account** asks for a name. There is nothing else to create — an account exists as
  soon as a wallet names it — so this makes its first wallet, and the account arrives with
  it.
* **Add wallet** makes an empty wallet in that column's account. Rename and delete are on
  each row; deleting asks first, because it takes every address in it.
* **Copy** puts the whole address on the clipboard. The list shows the shortened form —
  `0x4b470…6BB75` — only because forty characters do not fit beside a chain name.
* **Add address** records one by hand, with the shape checked against the chain as it is
  typed. Pick **Other** for a chain this release has never heard of; nothing is checked
  there.
* **Connect MetaMask** proves every EVM address the browser shares, into that column's
  account. No wallet has to exist first: what comes back decides how many there are.

Every question either interface asks — rename, delete, remove an address — is drawn by the
mod or by the page itself, never a browser popup.

Deleting a wallet from the mod removes the mod's record of it. It does nothing to the wallet
itself, which lives in MetaMask and knows nothing about any of this.

## Where it lives in the code

| Layer | What it holds |
|---|---|
| `api/` | `ChainPlatform`, `ChainAddress`, `Wallet`, `WalletBinding`, `WalletBridge`, `WalletRepository` |
| `common/` | `SqliteWalletRepository`; `WalletChallenges` (issuing and checking), `AccountWallets` (where results may land), `LocalWalletBridge` and its page; `EvmSignatures`, `Keccak256`, `Secp256k1`, `AddressFormats`, `SystemBrowser`; `AccountColumns` for the screen's layout; the `/wallets/` page and its routes |
| `platforms/fabric/` | `WalletScreen`, `WalletListPanel`, `ChainAddressPanel`, `AddAddressScreen`, `WalletConnectScreen`, `PlayerAccounts` |

`WalletChallenges` and `AccountWallets` sit in `common/` because both interfaces use them.
Two implementations of one handshake would be two chances to get it wrong, and a rule about
which account a wallet may go in is not a rule worth having twice.

`AccountColumns` holds the screen's arithmetic — how many columns fit, how the width divides,
which accounts are on screen — because `platforms/fabric/` has no test source set. A sum can
be wrong without Minecraft being involved, and there it can be tested.

Keccak-256 and secp256k1 recovery are written out rather than taken from a library. The JDK
ships SHA3-256, which is **a different function** — Ethereum standardised on Keccak before
NIST changed the padding byte — and would silently recover the wrong address. The
alternative, a general-purpose cryptography library, would add megabytes to a jar shipped to
every player for one hash and one curve operation.

## Related

* [`architecture.md`](architecture.md) — the module split these layers follow.
* [`web-console.md`](web-console.md) — the browser view, including `/wallets/`.
* [`overview.md`](overview.md) — what the mod is.
