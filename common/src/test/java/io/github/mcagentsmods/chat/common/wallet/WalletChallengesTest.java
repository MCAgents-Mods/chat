package io.github.mcagentsmods.chat.common.wallet;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A challenge proves one address, into one account, once, for a while.
 *
 * <p>The signature check itself is {@link EvmSignaturesTest}'s; these are about what a valid
 * signature is allowed to be used for.
 */
class WalletChallengesTest {

    private static final TestSigner PLAYER = TestSigner.PLAYER;
    private static final TestSigner SECOND = TestSigner.SECOND;

    /** A clock the test moves by hand. */
    private static final class MovableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    void aValidSignatureForTheIssuedAccountAndAddressBinds() {
        WalletChallenges challenges = new WalletChallenges();
        var challenge = challenges.issue("Personal", PLAYER.address());

        assertTrue(challenges.redeem(challenge.nonce(), "Personal", PLAYER.address(),
                PLAYER.sign(challenge.message()), "0x1").isPresent());
    }

    @Test
    void aSignatureCannotBeFiledUnderAnotherAccount() {
        WalletChallenges challenges = new WalletChallenges();
        var challenge = challenges.issue("Personal", PLAYER.address());

        assertTrue(challenges.redeem(challenge.nonce(), "Someone else", PLAYER.address(),
                PLAYER.sign(challenge.message()), "0x1").isEmpty(),
                "the binding lands where the signed message said, or nowhere");
    }

    @Test
    void aNonceIssuedForOneAddressCannotProveAnother() {
        WalletChallenges challenges = new WalletChallenges();
        var challenge = challenges.issue("Personal", PLAYER.address());

        assertTrue(challenges.redeem(challenge.nonce(), "Personal", SECOND.address(),
                SECOND.sign(challenge.message()), "0x1").isEmpty());
    }

    @Test
    void aChallengeExpires() {
        MovableClock clock = new MovableClock();
        WalletChallenges challenges = new WalletChallenges(clock);
        var challenge = challenges.issue("Personal", PLAYER.address());

        clock.now = clock.now.plus(WalletChallenges.LIFETIME).plus(Duration.ofSeconds(1));

        assertTrue(challenges.redeem(challenge.nonce(), "Personal", PLAYER.address(),
                PLAYER.sign(challenge.message()), "0x1").isEmpty());
    }
}
