package ru.nstu.mail.data

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pure-Kotlin RFC 1320 MD4 message-digest implementation (required for NTLMv2 NT hash).
 */
object Md4 {

    fun digest(input: ByteArray): ByteArray {
        val bitLen = input.size.toLong() * 8L
        val mod = (input.size + 8) % 64
        val padLen = if (mod == 0) 64 else 64 - mod
        val padded = ByteArray(input.size + padLen + 8)
        System.arraycopy(input, 0, padded, 0, input.size)
        padded[input.size] = 0x80.toByte()
        ByteBuffer.wrap(padded, padded.size - 8, 8)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putLong(bitLen)

        var a = 0x67452301
        var b = 0xefcdab89.toInt()
        var c = 0x98badcfe.toInt()
        var d = 0x10325476

        val x = IntArray(16)
        val buf = ByteBuffer.wrap(padded).order(ByteOrder.LITTLE_ENDIAN)
        while (buf.hasRemaining()) {
            for (i in 0 until 16) {
                x[i] = buf.int
            }
            val aa = a
            val bb = b
            val cc = c
            val dd = d

            // Round 1
            a = ff(a, b, c, d, x[0], 3); d = ff(d, a, b, c, x[1], 7)
            c = ff(c, d, a, b, x[2], 11); b = ff(b, c, d, a, x[3], 19)
            a = ff(a, b, c, d, x[4], 3); d = ff(d, a, b, c, x[5], 7)
            c = ff(c, d, a, b, x[6], 11); b = ff(b, c, d, a, x[7], 19)
            a = ff(a, b, c, d, x[8], 3); d = ff(d, a, b, c, x[9], 7)
            c = ff(c, d, a, b, x[10], 11); b = ff(b, c, d, a, x[11], 19)
            a = ff(a, b, c, d, x[12], 3); d = ff(d, a, b, c, x[13], 7)
            c = ff(c, d, a, b, x[14], 11); b = ff(b, c, d, a, x[15], 19)

            // Round 2
            a = gg(a, b, c, d, x[0], 3); d = gg(d, a, b, c, x[4], 5)
            c = gg(c, d, a, b, x[8], 9); b = gg(b, c, d, a, x[12], 13)
            a = gg(a, b, c, d, x[1], 3); d = gg(d, a, b, c, x[5], 5)
            c = gg(c, d, a, b, x[9], 9); b = gg(b, c, d, a, x[13], 13)
            a = gg(a, b, c, d, x[2], 3); d = gg(d, a, b, c, x[6], 5)
            c = gg(c, d, a, b, x[10], 9); b = gg(b, c, d, a, x[14], 13)
            a = gg(a, b, c, d, x[3], 3); d = gg(d, a, b, c, x[7], 5)
            c = gg(c, d, a, b, x[11], 9); b = gg(b, c, d, a, x[15], 13)

            // Round 3
            a = hh(a, b, c, d, x[0], 3); d = hh(d, a, b, c, x[8], 9)
            c = hh(c, d, a, b, x[4], 11); b = hh(b, c, d, a, x[12], 15)
            a = hh(a, b, c, d, x[2], 3); d = hh(d, a, b, c, x[10], 9)
            c = hh(c, d, a, b, x[6], 11); b = hh(b, c, d, a, x[14], 15)
            a = hh(a, b, c, d, x[1], 3); d = hh(d, a, b, c, x[9], 9)
            c = hh(c, d, a, b, x[5], 11); b = hh(b, c, d, a, x[13], 15)
            a = hh(a, b, c, d, x[3], 3); d = hh(d, a, b, c, x[11], 9)
            c = hh(c, d, a, b, x[7], 11); b = hh(b, c, d, a, x[15], 15)

            a += aa
            b += bb
            c += cc
            d += dd
        }

        return ByteBuffer.allocate(16)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(a)
            .putInt(b)
            .putInt(c)
            .putInt(d)
            .array()
    }

    private fun ff(a: Int, b: Int, c: Int, d: Int, x: Int, s: Int): Int {
        val f = (b and c) or (b.inv() and d)
        return Integer.rotateLeft(a + f + x, s)
    }

    private fun gg(a: Int, b: Int, c: Int, d: Int, x: Int, s: Int): Int {
        val g = (b and c) or (b and d) or (c and d)
        return Integer.rotateLeft(a + g + x + 0x5a827999, s)
    }

    private fun hh(a: Int, b: Int, c: Int, d: Int, x: Int, s: Int): Int {
        val h = b xor c xor d
        return Integer.rotateLeft(a + h + x + 0x6ed9eba1, s)
    }
}
