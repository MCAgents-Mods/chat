package io.github.mcagentsmods.chat.common.wallet;

/**
 * Keccak-256, the hash Ethereum uses everywhere.
 *
 * <h2>Why this is written out rather than taken from the JDK</h2>
 *
 * <p>The JDK ships SHA3-256, and it is <strong>not</strong> the same function. Ethereum
 * standardised on Keccak before NIST finalised SHA-3, and the finalised standard changed
 * the padding byte from {@code 0x01} to {@code 0x06}. Everything else is identical, and the
 * digests are completely different — so {@code MessageDigest.getInstance("SHA3-256")}
 * produces a hash that looks plausible, verifies nothing, and recovers the wrong address.
 *
 * <p>The alternative is a cryptography library, which for this one function would add
 * megabytes to a jar that is bundled into the mod and shipped to every player. This is
 * ninety lines. See {@code Secp256k1} for the same reasoning applied to the curve.
 */
final class Keccak256 {

    /** Bytes absorbed per permutation: 1600 bits of state minus 512 bits of capacity. */
    private static final int RATE = 136;

    private static final int ROUNDS = 24;

    private static final long[] ROUND_CONSTANTS = {
            0x0000000000000001L, 0x0000000000008082L, 0x800000000000808AL, 0x8000000080008000L,
            0x000000000000808BL, 0x0000000080000001L, 0x8000000080008081L, 0x8000000000008009L,
            0x000000000000008AL, 0x0000000000000088L, 0x0000000080008009L, 0x000000008000000AL,
            0x000000008000808BL, 0x800000000000008BL, 0x8000000000008089L, 0x8000000000008003L,
            0x8000000000008002L, 0x8000000000000080L, 0x000000000000800AL, 0x800000008000000AL,
            0x8000000080008081L, 0x8000000000008080L, 0x0000000080000001L, 0x8000000080008008L};

    /** Rotation offsets for rho, indexed by lane as {@code x + 5y}. */
    private static final int[] ROTATION = {
            0, 1, 62, 28, 27,
            36, 44, 6, 55, 20,
            3, 10, 43, 25, 39,
            41, 45, 15, 21, 8,
            18, 2, 61, 56, 14};

    private Keccak256() {
        throw new AssertionError("No instances.");
    }

    /** The 32-byte Keccak-256 digest of {@code input}. */
    static byte[] digest(byte[] input) {
        long[] state = new long[25];

        int offset = 0;
        while (input.length - offset >= RATE) {
            absorb(state, input, offset);
            permute(state);
            offset += RATE;
        }

        // The final block, padded as pad10*1: the original Keccak padding, 0x01 rather than
        // SHA-3's 0x06. This one byte is the whole difference between the two functions.
        byte[] last = new byte[RATE];
        int remaining = input.length - offset;
        System.arraycopy(input, offset, last, 0, remaining);
        last[remaining] |= 0x01;
        last[RATE - 1] |= (byte) 0x80;

        absorb(state, last, 0);
        permute(state);

        byte[] digest = new byte[32];
        for (int lane = 0; lane < 4; lane++) {
            long value = state[lane];
            for (int byteIndex = 0; byteIndex < 8; byteIndex++) {
                digest[lane * 8 + byteIndex] = (byte) (value >>> (8 * byteIndex));
            }
        }
        return digest;
    }

    /** Exclusive-ors one rate-sized block into the state, little-endian per lane. */
    private static void absorb(long[] state, byte[] block, int offset) {
        for (int lane = 0; lane < RATE / 8; lane++) {
            long value = 0;
            for (int byteIndex = 0; byteIndex < 8; byteIndex++) {
                value |= (block[offset + lane * 8 + byteIndex] & 0xFFL) << (8 * byteIndex);
            }
            state[lane] ^= value;
        }
    }

    /** Keccak-f[1600]: theta, rho, pi, chi, and iota, twenty-four times. */
    private static void permute(long[] state) {
        long[] column = new long[5];
        long[] delta = new long[5];
        long[] scratch = new long[25];

        for (int round = 0; round < ROUNDS; round++) {
            for (int x = 0; x < 5; x++) {
                column[x] = state[x] ^ state[x + 5] ^ state[x + 10] ^ state[x + 15] ^ state[x + 20];
            }
            for (int x = 0; x < 5; x++) {
                delta[x] = column[(x + 4) % 5] ^ Long.rotateLeft(column[(x + 1) % 5], 1);
            }
            for (int x = 0; x < 5; x++) {
                for (int lane = x; lane < 25; lane += 5) {
                    state[lane] ^= delta[x];
                }
            }

            for (int x = 0; x < 5; x++) {
                for (int y = 0; y < 5; y++) {
                    scratch[y + 5 * ((2 * x + 3 * y) % 5)] =
                            Long.rotateLeft(state[x + 5 * y], ROTATION[x + 5 * y]);
                }
            }

            for (int y = 0; y < 25; y += 5) {
                for (int x = 0; x < 5; x++) {
                    state[y + x] = scratch[y + x]
                            ^ (~scratch[y + (x + 1) % 5] & scratch[y + (x + 2) % 5]);
                }
            }

            state[0] ^= ROUND_CONSTANTS[round];
        }
    }
}
