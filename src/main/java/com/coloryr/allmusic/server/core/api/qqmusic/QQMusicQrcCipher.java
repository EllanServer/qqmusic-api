package com.coloryr.allmusic.server.core.api.qqmusic;

import java.nio.charset.StandardCharsets;

/** QQ Music's QRC-compatible Triple-DES variant. */
final class QQMusicQrcCipher {
    private static final int ENCRYPT = 1;
    private static final int DECRYPT = 0;
    private static final byte[] KEY = "!@#)(*$%123ZXC!@!@#)(NHL"
            .getBytes(StandardCharsets.US_ASCII);

    // These are the DES substitution boxes used by the QRC implementation.
    // Two historical table values differ from standardized DES and therefore
    // this cipher cannot be replaced with javax.crypto.DESede.
    private static final int[][] SBOX = {
            {
                    14, 4, 13, 1, 2, 15, 11, 8, 3, 10, 6, 12, 5, 9, 0, 7,
                    0, 15, 7, 4, 14, 2, 13, 1, 10, 6, 12, 11, 9, 5, 3, 8,
                    4, 1, 14, 8, 13, 6, 2, 11, 15, 12, 9, 7, 3, 10, 5, 0,
                    15, 12, 8, 2, 4, 9, 1, 7, 5, 11, 3, 14, 10, 0, 6, 13
            },
            {
                    15, 1, 8, 14, 6, 11, 3, 4, 9, 7, 2, 13, 12, 0, 5, 10,
                    3, 13, 4, 7, 15, 2, 8, 15, 12, 0, 1, 10, 6, 9, 11, 5,
                    0, 14, 7, 11, 10, 4, 13, 1, 5, 8, 12, 6, 9, 3, 2, 15,
                    13, 8, 10, 1, 3, 15, 4, 2, 11, 6, 7, 12, 0, 5, 14, 9
            },
            {
                    10, 0, 9, 14, 6, 3, 15, 5, 1, 13, 12, 7, 11, 4, 2, 8,
                    13, 7, 0, 9, 3, 4, 6, 10, 2, 8, 5, 14, 12, 11, 15, 1,
                    13, 6, 4, 9, 8, 15, 3, 0, 11, 1, 2, 12, 5, 10, 14, 7,
                    1, 10, 13, 0, 6, 9, 8, 7, 4, 15, 14, 3, 11, 5, 2, 12
            },
            {
                    7, 13, 14, 3, 0, 6, 9, 10, 1, 2, 8, 5, 11, 12, 4, 15,
                    13, 8, 11, 5, 6, 15, 0, 3, 4, 7, 2, 12, 1, 10, 14, 9,
                    10, 6, 9, 0, 12, 11, 7, 13, 15, 1, 3, 14, 5, 2, 8, 4,
                    3, 15, 0, 6, 10, 10, 13, 8, 9, 4, 5, 11, 12, 7, 2, 14
            },
            {
                    2, 12, 4, 1, 7, 10, 11, 6, 8, 5, 3, 15, 13, 0, 14, 9,
                    14, 11, 2, 12, 4, 7, 13, 1, 5, 0, 15, 10, 3, 9, 8, 6,
                    4, 2, 1, 11, 10, 13, 7, 8, 15, 9, 12, 5, 6, 3, 0, 14,
                    11, 8, 12, 7, 1, 14, 2, 13, 6, 15, 0, 9, 10, 4, 5, 3
            },
            {
                    12, 1, 10, 15, 9, 2, 6, 8, 0, 13, 3, 4, 14, 7, 5, 11,
                    10, 15, 4, 2, 7, 12, 9, 5, 6, 1, 13, 14, 0, 11, 3, 8,
                    9, 14, 15, 5, 2, 8, 12, 3, 7, 0, 4, 10, 1, 13, 11, 6,
                    4, 3, 2, 12, 9, 5, 15, 10, 11, 14, 1, 7, 6, 0, 8, 13
            },
            {
                    4, 11, 2, 14, 15, 0, 8, 13, 3, 12, 9, 7, 5, 10, 6, 1,
                    13, 0, 11, 7, 4, 9, 1, 10, 14, 3, 5, 12, 2, 15, 8, 6,
                    1, 4, 11, 13, 12, 3, 7, 14, 10, 15, 6, 8, 0, 5, 9, 2,
                    6, 11, 13, 8, 1, 4, 10, 7, 9, 5, 0, 15, 14, 2, 3, 12
            },
            {
                    13, 2, 8, 4, 6, 15, 11, 1, 10, 9, 3, 14, 5, 0, 12, 7,
                    1, 15, 13, 8, 10, 3, 7, 4, 12, 5, 6, 11, 0, 14, 9, 2,
                    7, 11, 4, 1, 9, 12, 14, 2, 0, 6, 10, 13, 15, 3, 5, 8,
                    2, 1, 14, 7, 4, 10, 8, 13, 15, 12, 9, 0, 3, 5, 6, 11
            }
    };

    private static final int[] KEY_SHIFTS = {
            1, 1, 2, 2, 2, 2, 2, 2, 1, 2, 2, 2, 2, 2, 2, 1
    };
    private static final int[] KEY_PERMUTATION_C = {
            56, 48, 40, 32, 24, 16, 8, 0, 57, 49, 41, 33, 25, 17,
            9, 1, 58, 50, 42, 34, 26, 18, 10, 2, 59, 51, 43, 35
    };
    private static final int[] KEY_PERMUTATION_D = {
            62, 54, 46, 38, 30, 22, 14, 6, 61, 53, 45, 37, 29, 21,
            13, 5, 60, 52, 44, 36, 28, 20, 12, 4, 27, 19, 11, 3
    };
    private static final int[] KEY_COMPRESSION = {
            13, 16, 10, 23, 0, 4, 2, 27, 14, 5, 20, 9, 22, 18, 11, 3,
            25, 7, 15, 6, 26, 19, 12, 1, 40, 51, 30, 36, 46, 54, 29, 39,
            50, 44, 32, 47, 43, 48, 38, 55, 33, 52, 45, 41, 49, 35, 28, 31
    };
    private static final int[] INITIAL_LEFT = {
            57, 49, 41, 33, 25, 17, 9, 1, 59, 51, 43, 35, 27, 19, 11, 3,
            61, 53, 45, 37, 29, 21, 13, 5, 63, 55, 47, 39, 31, 23, 15, 7
    };
    private static final int[] INITIAL_RIGHT = {
            56, 48, 40, 32, 24, 16, 8, 0, 58, 50, 42, 34, 26, 18, 10, 2,
            60, 52, 44, 36, 28, 20, 12, 4, 62, 54, 46, 38, 30, 22, 14, 6
    };
    private static final int[] ROUND_PERMUTATION = {
            15, 6, 19, 20, 28, 11, 27, 16, 0, 14, 22, 25, 4, 17, 30, 9,
            1, 7, 23, 13, 31, 26, 2, 8, 18, 12, 29, 5, 21, 10, 3, 24
    };
    private static final int[][][] DECRYPT_SCHEDULE = setupTripleDes(KEY, DECRYPT);

    private QQMusicQrcCipher() {
    }

    static byte[] decrypt(byte[] encrypted) {
        if (encrypted == null || (encrypted.length & 7) != 0) {
            throw new IllegalArgumentException("QRC ciphertext must contain complete 8-byte blocks");
        }
        byte[] output = new byte[encrypted.length];
        byte[] block = new byte[8];
        for (int offset = 0; offset < encrypted.length; offset += 8) {
            System.arraycopy(encrypted, offset, block, 0, 8);
            for (int stage = 0; stage < DECRYPT_SCHEDULE.length; stage++) {
                block = cryptBlock(block, DECRYPT_SCHEDULE[stage]);
            }
            System.arraycopy(block, 0, output, offset, 8);
        }
        return output;
    }

    private static int[][][] setupTripleDes(byte[] key, int mode) {
        int[][][] result = new int[3][][];
        if (mode == ENCRYPT) {
            result[0] = keySchedule(key, 0, ENCRYPT);
            result[1] = keySchedule(key, 8, DECRYPT);
            result[2] = keySchedule(key, 16, ENCRYPT);
        } else {
            result[0] = keySchedule(key, 16, DECRYPT);
            result[1] = keySchedule(key, 8, ENCRYPT);
            result[2] = keySchedule(key, 0, DECRYPT);
        }
        return result;
    }

    private static int[][] keySchedule(byte[] key, int offset, int mode) {
        int[][] schedule = new int[16][6];
        int c = 0;
        int d = 0;
        for (int index = 0; index < 28; index++) {
            c |= byteBit(key, offset, KEY_PERMUTATION_C[index], 31 - index);
            d |= byteBit(key, offset, KEY_PERMUTATION_D[index], 31 - index);
        }

        for (int round = 0; round < 16; round++) {
            int shift = KEY_SHIFTS[round];
            c = ((c << shift) | (c >>> (28 - shift))) & 0xfffffff0;
            d = ((d << shift) | (d >>> (28 - shift))) & 0xfffffff0;
            int target = mode == DECRYPT ? 15 - round : round;
            for (int bit = 0; bit < 24; bit++) {
                schedule[target][bit / 8] |= intBit(
                        c, KEY_COMPRESSION[bit], 7 - bit % 8);
            }
            for (int bit = 24; bit < 48; bit++) {
                // QRC compatibility: the original implementation subtracts
                // 27 here, not the standardized DES offset of 28.
                schedule[target][bit / 8] |= intBit(
                        d, KEY_COMPRESSION[bit] - 27, 7 - bit % 8);
            }
        }
        return schedule;
    }

    private static byte[] cryptBlock(byte[] input, int[][] schedule) {
        int left = permuteInput(input, INITIAL_LEFT);
        int right = permuteInput(input, INITIAL_RIGHT);
        for (int round = 0; round < 15; round++) {
            int previousRight = right;
            right = feistel(right, schedule[round]) ^ left;
            left = previousRight;
        }
        left = feistel(right, schedule[15]) ^ left;
        return inversePermutation(left, right);
    }

    private static int permuteInput(byte[] input, int[] permutation) {
        int result = 0;
        for (int index = 0; index < permutation.length; index++) {
            result |= byteBit(input, 0, permutation[index], 31 - index);
        }
        return result;
    }

    private static int feistel(int state, int[] key) {
        int t1 = ((state & 1) << 31)
                | ((state & 0xf8000000) >>> 1)
                | ((state & 0x1f800000) >>> 3)
                | ((state & 0x01f80000) >>> 5)
                | ((state & 0x001f8000) >>> 7);
        int t2 = ((state & 0x0001f800) << 15)
                | ((state & 0x00001f80) << 13)
                | ((state & 0x000001f8) << 11)
                | ((state & 0x0000001f) << 9)
                | ((state & 0x80000000) >>> 23);

        int k0 = ((t1 >>> 24) & 0xff) ^ key[0];
        int k1 = ((t1 >>> 16) & 0xff) ^ key[1];
        int k2 = ((t1 >>> 8) & 0xff) ^ key[2];
        int k3 = ((t2 >>> 24) & 0xff) ^ key[3];
        int k4 = ((t2 >>> 16) & 0xff) ^ key[4];
        int k5 = ((t2 >>> 8) & 0xff) ^ key[5];

        int substituted = (SBOX[0][sboxIndex(k0 >>> 2)] << 28)
                | (SBOX[1][sboxIndex(((k0 & 0x03) << 4) | (k1 >>> 4))] << 24)
                | (SBOX[2][sboxIndex(((k1 & 0x0f) << 2) | (k2 >>> 6))] << 20)
                | (SBOX[3][sboxIndex(k2 & 0x3f)] << 16)
                | (SBOX[4][sboxIndex(k3 >>> 2)] << 12)
                | (SBOX[5][sboxIndex(((k3 & 0x03) << 4) | (k4 >>> 4))] << 8)
                | (SBOX[6][sboxIndex(((k4 & 0x0f) << 2) | (k5 >>> 6))] << 4)
                | SBOX[7][sboxIndex(k5 & 0x3f)];

        int result = 0;
        for (int index = 0; index < ROUND_PERMUTATION.length; index++) {
            result |= intLeftBit(substituted, ROUND_PERMUTATION[index], index);
        }
        return result;
    }

    private static byte[] inversePermutation(int left, int right) {
        byte[] output = new byte[8];
        output[3] = inverseByte(left, right, 7);
        output[2] = inverseByte(left, right, 6);
        output[1] = inverseByte(left, right, 5);
        output[0] = inverseByte(left, right, 4);
        output[7] = inverseByte(left, right, 3);
        output[6] = inverseByte(left, right, 2);
        output[5] = inverseByte(left, right, 1);
        output[4] = inverseByte(left, right, 0);
        return output;
    }

    private static byte inverseByte(int left, int right, int bit) {
        int result = 0;
        for (int group = 0; group < 4; group++) {
            result |= intBit(right, bit + group * 8, 7 - group * 2);
            result |= intBit(left, bit + group * 8, 6 - group * 2);
        }
        return (byte) result;
    }

    private static int byteBit(byte[] value, int offset, int bit, int target) {
        int index = offset + (bit / 32) * 4 + 3 - (bit % 32) / 8;
        return (((value[index] & 0xff) >>> (7 - bit % 8)) & 1) << target;
    }

    private static int intBit(int value, int bit, int target) {
        return ((value >>> (31 - bit)) & 1) << target;
    }

    private static int intLeftBit(int value, int bit, int target) {
        return ((value << bit) & 0x80000000) >>> target;
    }

    private static int sboxIndex(int value) {
        return (value & 32) | ((value & 31) >>> 1) | ((value & 1) << 4);
    }
}
