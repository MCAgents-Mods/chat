package io.github.mcagents.clientfabric.common.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Encrypting a stored token, and every way reading one back can go. */
class TokenCipherTest {

    /** Obviously fake, and never anything that looks like a real key. */
    private static final String TOKEN = "sk-or-v1-first";

    private final TokenCipher cipher = TokenCipher.fromKeyMaterial("a passphrase for the test");

    @Test
    void readsBackWhatItWrote() {
        assertEquals(TOKEN, cipher.decrypt(cipher.encrypt(TOKEN)));
    }

    @Test
    void producesADifferentCiphertextEveryTimeForTheSameToken() {
        // The property the whole scheme exists for. A deterministic transform — a hash, a
        // salted hash, an unauthenticated cipher with a fixed nonce — would produce one
        // string for one token, and a stolen database would then answer "do these two rows
        // hold the same key?" and "is this guess the key?" without holding the key at all.
        String first = cipher.encrypt(TOKEN);
        String second = cipher.encrypt(TOKEN);

        assertNotEquals(first, second);
        assertEquals(TOKEN, cipher.decrypt(first));
        assertEquals(TOKEN, cipher.decrypt(second));
    }

    @Test
    void writesNothingThatContainsTheToken() {
        String stored = cipher.encrypt(TOKEN);

        assertFalse(stored.contains(TOKEN));
        assertTrue(TokenCipher.isEncrypted(stored));
    }

    @Test
    void storesAndReadsPlainTextWhenNoKeyIsConfigured() {
        // The fallback: a machine with no environment variable keeps a working mod, and what
        // it stores is exactly what it stored before encryption existed.
        TokenCipher disabled = TokenCipher.disabled();

        assertFalse(disabled.enabled());
        assertEquals(TOKEN, disabled.encrypt(TOKEN));
        assertEquals(TOKEN, disabled.decrypt(TOKEN));
    }

    @Test
    void treatsABlankVariableAsUnset() {
        assertFalse(TokenCipher.fromKeyMaterial(null).enabled());
        assertFalse(TokenCipher.fromKeyMaterial("").enabled());
        assertFalse(TokenCipher.fromKeyMaterial("   ").enabled());
    }

    @Test
    void readsATokenStoredBeforeEncryptionWasTurnedOn() {
        // Mixed rows are normal: turning encryption on must not make yesterday's database
        // unreadable, so anything without the marker is passed through as the key it is.
        assertEquals(TOKEN, cipher.decrypt(TOKEN));
    }

    @Test
    void refusesToOpenATokenEncryptedWithADifferentKey() {
        String stored = cipher.encrypt(TOKEN);
        TokenCipher other = TokenCipher.fromKeyMaterial("a different passphrase");

        // GCM's tag check is what makes this a refusal rather than plausible rubbish being
        // handed to a provider as though it were a key.
        assertThrows(TokenCipherException.class, () -> other.decrypt(stored));
    }

    @Test
    void refusesToOpenAnEncryptedTokenWithNoKeyAtAll() {
        String stored = cipher.encrypt(TOKEN);

        assertThrows(TokenCipherException.class, () -> TokenCipher.disabled().decrypt(stored));
    }

    @Test
    void refusesADamagedRowRatherThanGuessing() {
        assertThrows(TokenCipherException.class, () -> cipher.decrypt("mcagents:v1:not-base64!"));
        assertThrows(TokenCipherException.class, () -> cipher.decrypt("mcagents:v1:c2hvcnQ"));
    }

    @Test
    void neverNamesTheTokenInAFailure() {
        String stored = cipher.encrypt(TOKEN);
        TokenCipher other = TokenCipher.fromKeyMaterial("a different passphrase");

        TokenCipherException failure =
                assertThrows(TokenCipherException.class, () -> other.decrypt(stored));

        assertFalse(failure.getMessage().contains(TOKEN));
        assertFalse(failure.getMessage().contains(stored));
        assertTrue(failure.getMessage().contains(TokenCipher.KEY_VARIABLE));
    }

    @Test
    void takesKeyMaterialOfAnyLength() {
        // Hashed to 256 bits whatever it is, so a player is never told their passphrase is
        // the wrong shape — and the derivation cannot be reinterpreted by a later release.
        for (String material : new String[]{"x", "a rather long passphrase with spaces in it",
                "3q2+7wAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="}) {
            TokenCipher any = TokenCipher.fromKeyMaterial(material);
            assertTrue(any.enabled());
            assertEquals(TOKEN, any.decrypt(any.encrypt(TOKEN)));
        }
    }

    @Test
    void doesNotLeakTheKeyThroughToString() {
        assertDoesNotThrow(cipher::toString);
        assertFalse(cipher.toString().contains("passphrase"));
    }

    @Test
    void isTheSameCipherForTheSameMaterial() {
        // Restarting the game has to open what the last run wrote.
        TokenCipher restarted = TokenCipher.fromKeyMaterial("a passphrase for the test");

        assertEquals(TOKEN, restarted.decrypt(cipher.encrypt(TOKEN)));
    }

    @Test
    void ignoresSurroundingWhitespaceInTheMaterial() {
        // A variable set from a file or a launcher often arrives with a trailing newline;
        // that must not be a different key from the one without it.
        TokenCipher padded = TokenCipher.fromKeyMaterial("  a passphrase for the test\n");

        assertEquals(TOKEN, padded.decrypt(cipher.encrypt(TOKEN)));
    }
}
