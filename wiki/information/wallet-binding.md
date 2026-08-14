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
There is no Java API, no local socket, nothing a desktop program can talk to. So for the
length of one handshake, the mod becomes a web server.

1. The player clicks **Connect MetaMask**.
2. The mod opens a listener on `127.0.0.1`, on a port the operating system picks, and opens
   the player's browser at it. The address is shown on screen too, because the default
   browser is often not the one MetaMask is installed in.
3. The page asks the mod for a one-off message naming the account, carrying a random nonce,
   and stating plainly that signing authorises nothing.
4. MetaMask signs it. **A `personal_sign` never reaches a chain, moves no funds, costs no
   gas, and grants no approval.**
5. The page posts the address and signature back. The mod **ignores the claimed address**
   and derives the signer from the signature itself. If the two do not match, the binding
   fails.
6. **The listener stops the moment the signature verifies** — and on cancel, on timeout
   after five minutes, and whenever the screen closes.

### What keeps that listener safe

| Measure | What it stops |
|---|---|
| Bound to `127.0.0.1` on an operating-system-assigned port | Anything outside the machine reaching it at all |
| A 256-bit one-time token, required on every request, only ever in the URL | Another program on the machine driving the handshake; another web page cannot read it |
| `Host` header must be a loopback name | DNS rebinding — a hostile site pointing a domain of its own at `127.0.0.1` |
| No CORS headers, and a JSON content type on the callback | A hostile page reading the mod's replies, or posting to it without a preflight it will not get |
| Stops on success, cancel, timeout, and screen close | The listener outliving the screen that opened it |

The handshake is a loopback conversation. It never touches the network, and the mod's
offline-first rule is untouched: every wallet already bound stays readable, listable, and
copyable with no internet at all.

## Using it

Press `G` for the chat screen, then **Wallets** in the bottom-left corner.

* The account dropdown lists every account that already owns a wallet. Type a name that is
  not on the list to start a new one.
* **Add wallet** makes an empty wallet. Hover a wallet for a pencil to rename it and a `×`
  to delete it; deleting asks first, because it takes every address in it.
* Click any address row to copy the whole address to the clipboard. The list shows the
  shortened form — `0x4b470…6BB75` — only because forty characters do not fit beside a
  chain name.
* **Add address** records one by hand, with the shape checked against the chain as it is
  typed. Pick **Other** for a chain this release has never heard of; nothing is checked
  there.
* **Connect MetaMask** proves an EVM address. If no wallet is selected, one is created
  rather than telling the player to press something else first.

Deleting a wallet from the mod removes the mod's record of it. It does nothing to the wallet
itself, which lives in MetaMask and knows nothing about any of this.

## Where it lives in the code

| Layer | What it holds |
|---|---|
| `api/` | `ChainPlatform`, `ChainAddress`, `Wallet`, `WalletBinding`, `WalletBridge`, `WalletRepository` |
| `common/` | `SqliteWalletRepository`; `LocalWalletBridge` and its page; `EvmSignatures`, `Keccak256`, `Secp256k1`, `AddressFormats`, `SystemBrowser` |
| `platforms/fabric/` | `WalletScreen`, `WalletListPanel`, `ChainAddressPanel`, `AddAddressScreen`, `WalletConnectScreen`, `PlayerAccounts` |

Keccak-256 and secp256k1 recovery are written out rather than taken from a library. The JDK
ships SHA3-256, which is **a different function** — Ethereum standardised on Keccak before
NIST changed the padding byte — and would silently recover the wrong address. The
alternative, a general-purpose cryptography library, would add megabytes to a jar shipped to
every player for one hash and one curve operation.

## Related

* [`architecture.md`](architecture.md) — the module split these layers follow.
* [`overview.md`](overview.md) — what the mod is.
