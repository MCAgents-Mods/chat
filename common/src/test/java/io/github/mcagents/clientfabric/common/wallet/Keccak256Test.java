package io.github.mcagents.clientfabric.common.wallet;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Keccak-256 against published digests.
 *
 * <p>These vectors are the only thing standing between the mod and a hash that looks
 * plausible and verifies nothing — every address the mod recovers depends on this function
 * being exactly right.
 */
class Keccak256Test {

    @Test
    void hashesTheEmptyInput() {
        assertEquals("c5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470",
                hex(Keccak256.digest(new byte[0])));
    }

    @Test
    void hashesShortInputs() {
        assertEquals("4e03657aea45a94fc7d47ba826c8d667c0d1e6e33a64a036ec44f58fa12d6c45",
                hex(Keccak256.digest("abc".getBytes(StandardCharsets.UTF_8))));
        assertEquals("1c8aff950685c2ed4bc3174f3472287b56d9517b9c948127319a09a7a36deac8",
                hex(Keccak256.digest("hello".getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    void isNotSha3() {
        // The difference is one padding byte, and taking SHA3-256 from the JDK instead
        // would produce a digest that is the right length, the right shape, and wrong.
        byte[] input = "The quick brown fox jumps over the lazy dog".getBytes(StandardCharsets.UTF_8);

        assertNotEquals(hex(sha3(input)), hex(Keccak256.digest(input)));
        assertEquals("4d741b6f1eb29cb2a9b9911c82f56fa8d73b04959d3d9d222895df6c0b28aa15",
                hex(Keccak256.digest(input)));
    }

    @Test
    void handlesInputsAroundTheBlockBoundary() {
        // 136 bytes is the rate: one byte under, exactly on it, and one over each take a
        // different path through the padding, and the one-under case is where the padding
        // byte and the terminator land on the same byte.
        assertEquals(32, Keccak256.digest(repeat(135)).length);
        assertEquals(32, Keccak256.digest(repeat(136)).length);
        assertEquals(32, Keccak256.digest(repeat(137)).length);

        assertNotEquals(hex(Keccak256.digest(repeat(135))), hex(Keccak256.digest(repeat(136))));
        assertNotEquals(hex(Keccak256.digest(repeat(136))), hex(Keccak256.digest(repeat(137))));
    }

    private static byte[] repeat(int length) {
        byte[] input = new byte[length];
        for (int i = 0; i < length; i++) {
            input[i] = (byte) ('a' + i % 26);
        }
        return input;
    }

    private static byte[] sha3(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA3-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA3-256 should be available on this JDK", e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            out.append(Character.forDigit((value >> 4) & 0x0F, 16));
            out.append(Character.forDigit(value & 0x0F, 16));
        }
        return out.toString();
    }
}
