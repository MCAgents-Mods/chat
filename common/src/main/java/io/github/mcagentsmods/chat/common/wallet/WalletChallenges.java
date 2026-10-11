package io.github.mcagentsmods.chat.common.wallet;

import io.github.mcagentsmods.chat.api.wallet.ChainPlatform;
import io.github.mcagentsmods.chat.api.wallet.WalletBinding;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The messages a player is asked to sign, and the signatures that come back.
 *
 * <h2>One challenge per address</h2>
 *
 * <p>A browser holds several accounts and the mod verifies every one of them in a single
 * trip, so a single shared message would not do: one signature over it would prove control
 * of one address and could then be posted back alongside any <em>other</em> address the
 * caller cared to name. So every address gets a message of its own, naming that address and
 * carrying a nonce good exactly once. A nonce is consumed when it is redeemed, which is what
 * stops the same signature counting twice.
 *
 * <p>The wording is written to be read. Someone is about to approve this in a wallet, and a
 * prompt they cannot understand is one they should not approve — so it says which account it
 * binds, which address it proves, and states plainly that it authorises nothing.
 *
 * <h2>Why this is not inside the bridge</h2>
 *
 * <p>Two callers need it. The in-game path serves its own page over {@link LocalWalletBridge}
 * because a Minecraft screen has no {@code window.ethereum}; the web console's wallets page
 * is already a page in the player's browser and talks to MetaMask itself. Both must issue
 * and check challenges the same way, and two implementations of the same handshake would be
 * two chances to get it wrong.
 *
 * <p>Thread-safe: the bridge redeems on its own executor and the console on a worker.
 */
public final class WalletChallenges {

    /**
     * How many unredeemed challenges are remembered at once.
     *
     * <p>Bounded because issuing is unauthenticated in the sense that anything holding the
     * token may ask repeatedly, and an unbounded map behind that is a way to grow the game's
     * heap. Well past what a browser with a long account list needs; the oldest is dropped,
     * which at worst makes the player sign one address again.
     */
    private static final int MAX_OUTSTANDING = 64;

    /**
     * How long a challenge may wait for its signature. Long enough to read the prompt and
     * approve it; short enough that a nonce left in a page's history is not good forever.
     */
    static final Duration LIFETIME = Duration.ofMinutes(10);

    private static final int NONCE_BYTES = 16;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Clock clock;

    private final Map<String, Issued> outstanding = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Issued> eldest) {
            return size() > MAX_OUTSTANDING;
        }
    };

    /** What a nonce was issued for: the redeem must name the same account and address. */
    private record Issued(String accountId, String address, String message, Instant issuedAt) {
    }

    public WalletChallenges() {
        this(Clock.systemUTC());
    }

    /** @param clock what "now" is, so expiry can be tested without waiting for it */
    WalletChallenges(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** A message to sign, and the nonce that identifies it when the signature comes back. */
    public record Challenge(String nonce, String message) {

        public Challenge {
            Objects.requireNonNull(nonce, "nonce");
            Objects.requireNonNull(message, "message");
        }
    }

    /**
     * Issues a challenge binding one address into one account.
     *
     * @param accountId the account the wallet will land under, named so the player can see
     *                  what they are agreeing to
     * @param address   the address about to be proved, as the browser reports it
     */
    public Challenge issue(String accountId, String address) {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(address, "address");
        if (address.isBlank()) {
            throw new IllegalArgumentException("A challenge has to name the address it proves");
        }

        String nonce = randomHex(NONCE_BYTES);
        Instant now = clock.instant();
        String message = messageFor(accountId, address.trim(), nonce, now);
        synchronized (outstanding) {
            outstanding.put(nonce, new Issued(accountId, address.trim(), message, now));
        }
        return new Challenge(nonce, message);
    }

    /**
     * Checks a signature against the challenge it claims to answer.
     *
     * <p>The address the caller states is never taken at its word — it is compared against
     * the address recovered from the signature, and a mismatch is a failure rather than a
     * warning. It must also be the address the challenge was issued for, and
     * {@code accountId} the account it named: the binding lands where the signed message
     * said it would, or nowhere. The nonce is consumed whether or not the signature holds,
     * so a wrong guess cannot be retried against the same message, and one older than
     * {@link #LIFETIME} is refused.
     *
     * @return the proved address, or empty if the nonce is unknown, spent or expired, names
     *         another account or address, or the signature does not belong to the address it
     *         arrived with
     */
    public Optional<WalletBinding> redeem(
            String nonce, String accountId, String address, String signature, String chainId) {
        if (nonce == null || accountId == null || address == null || signature == null) {
            return Optional.empty();
        }
        Issued issued;
        synchronized (outstanding) {
            issued = outstanding.remove(nonce);
        }
        if (issued == null
                || !issued.accountId().equals(accountId.trim())
                || !issued.address().equalsIgnoreCase(address.trim())
                || issued.issuedAt().plus(LIFETIME).isBefore(clock.instant())
                || !EvmSignatures.proves(issued.message(), signature, address)) {
            return Optional.empty();
        }

        long chain = parseChainId(chainId);
        ChainPlatform platform = ChainPlatform.fromEvmChainId(chain).orElse(ChainPlatform.ETHEREUM);
        return Optional.of(new WalletBinding(
                EvmSignatures.toChecksumAddress(address), platform, chain, clock.instant()));
    }

    /** How many challenges have been issued and not yet redeemed. */
    public int outstanding() {
        synchronized (outstanding) {
            return outstanding.size();
        }
    }

    /**
     * Reads the chain the browser reported.
     *
     * <p>MetaMask reports it as hex ({@code 0x1}); some pages send a plain number. Anything
     * unreadable becomes {@code 0}, which falls back to Ethereum — every EVM chain holds the
     * same address, so an unknown chain id is a naming problem rather than a reason to refuse
     * a signature that verified.
     */
    public static long parseChainId(String value) {
        String text = value == null ? "" : value.trim();
        try {
            if (text.startsWith("0x") || text.startsWith("0X")) {
                return Long.parseLong(text.substring(2), 16);
            }
            return text.isEmpty() ? 0 : Long.parseLong(text);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String messageFor(String accountId, String address, String nonce, Instant issuedAt) {
        return String.join("\n",
                "MCAgents wallet binding",
                "",
                "Account: " + accountId,
                "Address: " + address,
                "Nonce: " + nonce,
                "Issued: " + issuedAt,
                "",
                "Signing proves you control this address.",
                "It authorises nothing, moves no funds, and never reaches a chain.");
    }

    /** {@code bytes} of {@link SecureRandom} output as hex; also the bridge's token. */
    static String randomHex(int bytes) {
        byte[] value = new byte[bytes];
        RANDOM.nextBytes(value);
        return HexFormat.of().formatHex(value);
    }
}
