package io.github.mcagents.clientfabric.common.security;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;

/**
 * Encrypts the tokens that go into the local database, and reads them back.
 *
 * <h2>Why the same token never looks the same twice</h2>
 *
 * <p>Every {@link #encrypt(String)} draws a fresh 96-bit nonce from {@link SecureRandom} and
 * stores it in front of the ciphertext. Encrypting one value twice therefore produces two
 * different strings, and neither can be compared with the other to discover they hold the same
 * secret. That is the point, and it is what a hash — of any kind, salted per-database or not —
 * cannot give: a deterministic transform of a token is a fingerprint of it, so a stolen
 * database still says "these two players use the same key", and a guessed value can be
 * confirmed by transforming it and looking for a match.
 *
 * <p>The cost is that no lookup can be done in SQL. The repository resolves a token by reading
 * the column and decrypting, which is affordable because a player holds a handful of keys, and
 * it is the price of the property above rather than an oversight.
 *
 * <h2>Where the key comes from</h2>
 *
 * <p>The environment variable {@value #KEY_VARIABLE}, read once at startup. Whatever it holds is
 * hashed to a 256-bit AES key with SHA-256, so any length works — a passphrase, a
 * {@code openssl rand -base64 32}, a secret from a launcher's environment — and the derivation
 * can never change under a database that was written with it.
 *
 * <p><strong>An unset variable is not an error.</strong> The mod runs, stores tokens as it
 * always did, and reads back everything it has ever written; {@link #encrypt(String)} returns
 * its input unchanged. This is the fallback that keeps a mod usable on a machine whose owner
 * has never heard of an environment variable, and it is honest about what it gives: a token in
 * the clear on a machine that never sends the file anywhere.
 *
 * <h2>Mixed rows are normal</h2>
 *
 * <p>{@link #decrypt(String)} passes anything without the {@value #PREFIX} marker straight
 * through, so a database written before encryption existed — or written while the variable was
 * unset — is readable without a migration having to have run first. A row that <em>is</em>
 * marked and cannot be opened raises {@link TokenCipherException} rather than being guessed at
 * or dropped.
 */
public final class TokenCipher {

    /** The environment variable holding the key material. */
    public static final String KEY_VARIABLE = "MCAGENTS_CLIENTFABRIC_TOKEN_ENCRYPT_KEY";

    /**
     * What marks a stored value as encrypted, and by which scheme.
     *
     * <p>Versioned because the day AES-GCM is replaced, rows written by both schemes have to be
     * readable at once — a stored token cannot be re-encrypted by a version that cannot read
     * it.
     */
    static final String PREFIX = "mcagents:v1:";

    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random;

    private TokenCipher(SecretKeySpec key) {
        this.key = key;
        this.random = key == null ? null : new SecureRandom();
    }

    /**
     * The cipher this machine is configured for: encrypting when {@value #KEY_VARIABLE} is set,
     * passing through when it is not.
     */
    public static TokenCipher fromEnvironment() {
        return fromKeyMaterial(System.getenv(KEY_VARIABLE));
    }

    /**
     * The cipher for {@code keyMaterial}, or the pass-through cipher when it is blank.
     *
     * <p>The seam every test uses, so no test has to set an environment variable to prove what
     * encryption does.
     */
    public static TokenCipher fromKeyMaterial(String keyMaterial) {
        if (keyMaterial == null || keyMaterial.isBlank()) {
            return disabled();
        }
        return new TokenCipher(new SecretKeySpec(derive(keyMaterial), ALGORITHM));
    }

    /** The pass-through cipher: stores what it is given, reads back what is there. */
    public static TokenCipher disabled() {
        return new TokenCipher(null);
    }

    /** Whether this cipher encrypts, or hands values through unchanged. */
    public boolean enabled() {
        return key != null;
    }

    /** Whether {@code stored} was written by {@link #encrypt(String)}. */
    public static boolean isEncrypted(String stored) {
        return stored != null && stored.startsWith(PREFIX);
    }

    /**
     * The form of {@code token} to write to the database.
     *
     * <p>Called twice with one token this returns two different strings, both of which
     * {@link #decrypt(String)} opens to the same value.
     *
     * @throws TokenCipherException if the JDK cannot perform AES-GCM, which would otherwise
     *                              silently store a token in the clear
     */
    public String encrypt(String token) {
        Objects.requireNonNull(token, "token");
        if (!enabled()) {
            return token;
        }
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] sealed = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));

            byte[] envelope = ByteBuffer.allocate(nonce.length + sealed.length)
                    .put(nonce)
                    .put(sealed)
                    .array();
            return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(envelope);
        } catch (GeneralSecurityException e) {
            // Deliberately fatal to the write. Falling back to plain text here would store a
            // token the player believes is encrypted.
            throw new TokenCipherException("Encrypting a stored token failed", e);
        }
    }

    /**
     * The token a stored value holds.
     *
     * <p>A value with no marker is returned unchanged: it was written before encryption, or
     * while no key was configured, and it is still the player's key.
     *
     * @throws TokenCipherException if the value is marked as encrypted and cannot be opened —
     *                              no key is configured, the key changed, or the row is damaged
     */
    public String decrypt(String stored) {
        Objects.requireNonNull(stored, "stored");
        if (!isEncrypted(stored)) {
            return stored;
        }
        if (!enabled()) {
            throw new TokenCipherException("A stored token is encrypted and no "
                    + KEY_VARIABLE + " is set to open it");
        }
        try {
            byte[] envelope = Base64.getUrlDecoder()
                    .decode(stored.substring(PREFIX.length()));
            if (envelope.length <= NONCE_BYTES) {
                throw new TokenCipherException("A stored token is too short to be readable");
            }
            byte[] nonce = java.util.Arrays.copyOfRange(envelope, 0, NONCE_BYTES);
            byte[] sealed = java.util.Arrays.copyOfRange(envelope, NONCE_BYTES, envelope.length);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            return new String(cipher.doFinal(sealed), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            // Includes the wrong-key case: GCM's tag check fails rather than returning
            // plausible rubbish, which is why a wrong key is detected instead of stored.
            throw new TokenCipherException("A stored token could not be read back with the "
                    + KEY_VARIABLE + " this machine is configured with", e);
        }
    }

    /**
     * A 256-bit key from whatever the player put in the variable.
     *
     * <p>SHA-256 rather than a detection of base64 or hex: one derivation, no ambiguity about
     * which one a given value took, and therefore no way for a later release to reinterpret an
     * existing value and lock a player out of their own tokens.
     */
    private static byte[] derive(String keyMaterial) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(keyMaterial.trim().getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new TokenCipherException("SHA-256 should be available on this JDK", e);
        }
    }

    @Override
    public String toString() {
        // Never the key, nor anything derived from it.
        return "TokenCipher[" + (enabled() ? "AES-GCM" : "pass-through") + "]";
    }
}
