package io.github.mcagentsmods.chat.common.wallet;

import java.math.BigInteger;

/**
 * The secp256k1 curve, and the one operation the mod needs from it: recovering the public
 * key that produced a signature.
 *
 * <h2>Recovery, not verification</h2>
 *
 * <p>Ordinary signature verification asks "did this key sign this?" and needs the key.
 * Ethereum signatures carry one extra bit — the recovery id, packed into {@code v} — that
 * lets the <em>signer's</em> key be reconstructed from the signature alone. That is what
 * makes wallet binding work: the browser sends a signature, the mod derives the address
 * that must have produced it, and that address is either the one the player claimed or the
 * binding fails. Nothing has to be trusted in between.
 *
 * <h2>Why this is written out</h2>
 *
 * <p>Everything below is arithmetic on {@link BigInteger} and needs no library. Adding one
 * would mean bundling megabytes of general-purpose cryptography into a jar that ships to
 * every player, for one curve and one operation. See {@link Keccak256} for the same
 * reasoning applied to the hash.
 *
 * <p>This code only ever handles public values — a signature, a message hash, a public key.
 * <strong>No private key or recovery phrase passes through the mod at any point</strong>,
 * so there is nothing here whose leakage would cost the player anything.
 */
final class Secp256k1 {

    /** The field prime, {@code 2^256 - 2^32 - 977}. */
    static final BigInteger P = new BigInteger(
            "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F", 16);

    /** The order of the generator. */
    static final BigInteger N = new BigInteger(
            "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16);

    /** The curve is {@code y² = x³ + 7}; there is no {@code a} term. */
    private static final BigInteger B = BigInteger.valueOf(7);

    private static final BigInteger TWO = BigInteger.TWO;
    private static final BigInteger THREE = BigInteger.valueOf(3);

    /** The generator point. */
    static final Point G = new Point(
            new BigInteger("79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798", 16),
            new BigInteger("483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8", 16));

    /** A point on the curve. The point at infinity is represented by {@code null}. */
    record Point(BigInteger x, BigInteger y) {
    }

    private Secp256k1() {
        throw new AssertionError("No instances.");
    }

    /**
     * Recovers the public key that signed {@code messageHash}.
     *
     * @param recoveryId the two bits packed into a signature's {@code v}, 0 to 3
     * @return the signer's public key, or {@code null} if no key on the curve fits — which
     *         is a malformed or tampered signature, not an error worth an exception
     */
    static Point recoverPublicKey(byte[] messageHash, BigInteger r, BigInteger s, int recoveryId) {
        if (recoveryId < 0 || recoveryId > 3) {
            return null;
        }
        if (r.signum() <= 0 || r.compareTo(N) >= 0 || s.signum() <= 0 || s.compareTo(N) >= 0) {
            return null;
        }

        // The signature carries r, which is an x coordinate reduced modulo n. The high bit
        // of the recovery id says whether the original x had wrapped around.
        BigInteger x = r.add(BigInteger.valueOf(recoveryId / 2).multiply(N));
        if (x.compareTo(P) >= 0) {
            return null;
        }

        Point point = decompress(x, (recoveryId & 1) == 1);
        if (point == null) {
            return null;
        }
        // A point off the subgroup would let a forged signature recover to a key of the
        // attacker's choosing, so this check is not decoration.
        if (multiply(point, N) != null) {
            return null;
        }

        BigInteger e = new BigInteger(1, messageHash);
        BigInteger rInverse = r.modInverse(N);
        BigInteger u1 = e.negate().mod(N).multiply(rInverse).mod(N);
        BigInteger u2 = s.multiply(rInverse).mod(N);

        return add(multiply(G, u1), multiply(point, u2));
    }

    /** The 64-byte uncompressed public key, {@code X || Y}, each left-padded to 32 bytes. */
    static byte[] encode(Point point) {
        byte[] encoded = new byte[64];
        writeFixed(point.x(), encoded, 0);
        writeFixed(point.y(), encoded, 32);
        return encoded;
    }

    /**
     * Rebuilds a point from its x coordinate.
     *
     * <p>{@code p ≡ 3 (mod 4)}, so the square root is a single modular exponentiation
     * rather than a search. A root that does not square back to the original means the x
     * coordinate is not on the curve at all.
     */
    private static Point decompress(BigInteger x, boolean yIsOdd) {
        BigInteger alpha = x.modPow(THREE, P).add(B).mod(P);
        BigInteger beta = alpha.modPow(P.add(BigInteger.ONE).shiftRight(2), P);
        if (!beta.modPow(TWO, P).equals(alpha)) {
            return null;
        }
        BigInteger y = beta.testBit(0) == yIsOdd ? beta : P.subtract(beta);
        return new Point(x, y);
    }

    /**
     * Multiplies a point by a scalar, double-and-add.
     *
     * <p>The scalar is <strong>not</strong> reduced modulo {@code n} here, because one
     * caller multiplies by {@code n} itself to check a point's order and reducing would
     * turn that check into a tautology.
     *
     * <p>Package-private rather than private so the tests can sign as well as recover:
     * proving that recovery inverts a signature needs something that produces one.
     */
    static Point multiply(Point base, BigInteger scalar) {
        Point result = null;
        Point addend = base;
        for (int bit = 0; bit < scalar.bitLength(); bit++) {
            if (scalar.testBit(bit)) {
                result = add(result, addend);
            }
            addend = twice(addend);
        }
        return result;
    }

    private static Point add(Point a, Point b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        if (a.x().equals(b.x())) {
            // Same x: either the same point, or a point and its negation, which sum to
            // infinity.
            return a.y().add(b.y()).mod(P).signum() == 0 ? null : twice(a);
        }
        BigInteger slope = b.y().subtract(a.y())
                .multiply(b.x().subtract(a.x()).modInverse(P)).mod(P);
        BigInteger x = slope.multiply(slope).subtract(a.x()).subtract(b.x()).mod(P);
        BigInteger y = slope.multiply(a.x().subtract(x)).subtract(a.y()).mod(P);
        return new Point(x, y);
    }

    private static Point twice(Point point) {
        if (point == null || point.y().signum() == 0) {
            return null;
        }
        BigInteger slope = point.x().multiply(point.x()).multiply(THREE)
                .multiply(point.y().multiply(TWO).modInverse(P)).mod(P);
        BigInteger x = slope.multiply(slope).subtract(point.x().multiply(TWO)).mod(P);
        BigInteger y = slope.multiply(point.x().subtract(x)).subtract(point.y()).mod(P);
        return new Point(x, y);
    }

    /**
     * Writes a coordinate into a fixed 32-byte slot.
     *
     * <p>{@link BigInteger#toByteArray()} is variable length — it drops leading zero bytes
     * and adds a sign byte when the top bit is set. Hashing that directly would produce a
     * different address for one key in every few hundred.
     */
    private static void writeFixed(BigInteger value, byte[] target, int offset) {
        byte[] bytes = value.toByteArray();
        int length = Math.min(bytes.length, 32);
        int from = bytes.length - length;
        System.arraycopy(bytes, from, target, offset + 32 - length, length);
    }
}
