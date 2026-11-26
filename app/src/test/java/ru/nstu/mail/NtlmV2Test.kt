package ru.nstu.mail

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.nstu.mail.data.Md4
import ru.nstu.mail.data.NtlmAuthenticator
import ru.nstu.mail.data.NtlmV2
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

class NtlmV2Test {

    @Test
    fun `md4 matches RFC 1320 test vectors`() {
        assertEquals("31d6cfe0d16ae931b73c59d7e0c089c0", Md4.digest("".toByteArray()).toHex())
        assertEquals("bde52cb31de33e46245e05fbdbd6fb24", Md4.digest("a".toByteArray()).toHex())
        assertEquals("a448017aaf21d8525fc10ae87aa6729d", Md4.digest("abc".toByteArray()).toHex())
        assertEquals(
            "d9130a8164549fe818874806e1c7014b",
            Md4.digest("message digest".toByteArray()).toHex()
        )
        assertEquals(
            "d79e1c308aa5bbcdeea8ed63df412da9",
            Md4.digest("abcdefghijklmnopqrstuvwxyz".toByteArray()).toHex()
        )
    }

    @Test
    fun `ntlmv2 matches MS-NLMP 4_2_4 vector`() {
        val responseKeyNt = NtlmV2.ntowfV2(
            password = "Password",
            user = "User",
            domain = "Domain"
        )
        assertEquals("0c868a403bfd7a93a3001ef22ef02e3f", responseKeyNt.toHex())

        val serverChallenge = "0123456789abcdef".hexToBytes()
        val clientChallenge = "aaaaaaaaaaaaaaaa".hexToBytes()
        val targetInfo =
            "02000c0044006f006d00610069006e0001000c0053006500720076006500720000000000".hexToBytes()

        val type2 = NtlmV2.Type2Challenge(
            serverChallenge = serverChallenge,
            flags = 0x00808205,
            targetInfo = targetInfo,
            serverTimestampLe = null
        )

        val type3 = NtlmV2.createType3Message(
            type2 = type2,
            user = "User",
            password = "Password",
            domain = "Domain",
            workstation = "COMPUTER",
            clientChallenge = clientChallenge,
            timestampLe = ByteArray(8)
        )

        val buf = ByteBuffer.wrap(type3).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(3, buf.getInt(8))

        val lmLen = buf.getShort(12).toInt() and 0xFFFF
        val lmOff = buf.getInt(16)
        val lmBytes = type3.copyOfRange(lmOff, lmOff + lmLen)
        assertEquals("86c35097ac9cec102554764a57cccc19aaaaaaaaaaaaaaaa", lmBytes.toHex())

        val ntLen = buf.getShort(20).toInt() and 0xFFFF
        val ntOff = buf.getInt(24)
        val ntProof = type3.copyOfRange(ntOff, ntOff + 16)
        assertEquals("68cd0ab851e51c96aabc927bebef6a1c", ntProof.toHex())
        assertTrue(ntLen > 16)
    }

    @Test
    fun `ntlm authenticator performs 3-step handshake and stops on bad credentials`() {
        val server = MockWebServer()
        server.start()
        try {
            val type2Bytes = buildFakeType2("1122334455667788".hexToBytes())
            val type2B64 = Base64.getEncoder().encodeToString(type2Bytes)

            server.enqueue(MockResponse().setResponseCode(401).addHeader("WWW-Authenticate", "NTLM"))
            server.enqueue(
                MockResponse().setResponseCode(401).addHeader("WWW-Authenticate", "NTLM $type2B64")
            )
            server.enqueue(MockResponse().setResponseCode(200).setBody("OK"))

            val client = OkHttpClient.Builder()
                .authenticator(NtlmAuthenticator("user@stud.nstu.ru", "secret"))
                .build()

            val resp = client.newCall(Request.Builder().url(server.url("/EWS/Exchange.asmx")).build())
                .execute()
            assertEquals(200, resp.code)
            assertEquals("OK", resp.body?.string())

            val r1 = server.takeRequest()
            val r2 = server.takeRequest()
            val r3 = server.takeRequest()

            assertEquals(null, r1.getHeader("Authorization"))

            val t1 = Base64.getDecoder().decode(r2.getHeader("Authorization")!!.removePrefix("NTLM "))
            assertArrayEquals("NTLMSSP\u0000".toByteArray(), t1.copyOfRange(0, 8))
            assertEquals(1, ByteBuffer.wrap(t1).order(ByteOrder.LITTLE_ENDIAN).getInt(8))

            val t3 = Base64.getDecoder().decode(r3.getHeader("Authorization")!!.removePrefix("NTLM "))
            assertEquals(3, ByteBuffer.wrap(t3).order(ByteOrder.LITTLE_ENDIAN).getInt(8))

            // Verify no infinite loop if server rejects Type 3 with 401
            server.enqueue(MockResponse().setResponseCode(401).addHeader("WWW-Authenticate", "NTLM"))
            server.enqueue(
                MockResponse().setResponseCode(401).addHeader("WWW-Authenticate", "NTLM $type2B64")
            )
            server.enqueue(MockResponse().setResponseCode(401).addHeader("WWW-Authenticate", "NTLM"))
            val failResp = client.newCall(
                Request.Builder().url(server.url("/EWS/Exchange.asmx")).build()
            ).execute()
            assertEquals(401, failResp.code)
        } finally {
            server.shutdown()
        }
    }

    private fun buildFakeType2(challenge: ByteArray): ByteArray {
        return ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("NTLMSSP\u0000".toByteArray())
            putInt(2)
            putShort(0).putShort(0).putInt(0)
            putInt(0x00088205)
            put(challenge)
            putLong(0)
            putShort(0).putShort(0).putInt(48)
        }.array()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun String.hexToBytes(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
