package ru.nstu.mail.data

import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Pure-Kotlin MS-NLMP (NTLMv2) message generator and OkHttp Authenticator.
 */
object NtlmV2 {

    private val SIGNATURE = byteArrayOf(
        'N'.code.toByte(), 'T'.code.toByte(), 'L'.code.toByte(), 'M'.code.toByte(),
        'S'.code.toByte(), 'S'.code.toByte(), 'P'.code.toByte(), 0
    )

    private const val NTLMSSP_NEGOTIATE_UNICODE = 0x00000001
    private const val NTLMSSP_REQUEST_TARGET = 0x00000004
    private const val NTLMSSP_NEGOTIATE_NTLM = 0x00000200
    private const val NTLMSSP_NEGOTIATE_ALWAYS_SIGN = 0x00008000
    private const val NTLMSSP_NEGOTIATE_EXTENDED_SESSIONSECURITY = 0x00080000
    private const val NTLMSSP_NEGOTIATE_TARGET_INFO = 0x00800000
    private const val NTLMSSP_NEGOTIATE_128 = 0x20000000
    private const val NTLMSSP_NEGOTIATE_56 = 0x80000000.toInt()

    private const val TYPE1_FLAGS =
        NTLMSSP_NEGOTIATE_UNICODE or
            NTLMSSP_REQUEST_TARGET or
            NTLMSSP_NEGOTIATE_NTLM or
            NTLMSSP_NEGOTIATE_ALWAYS_SIGN or
            NTLMSSP_NEGOTIATE_EXTENDED_SESSIONSECURITY or
            NTLMSSP_NEGOTIATE_128 or
            NTLMSSP_NEGOTIATE_56

    private const val FILETIME_EPOCH_DIFF_100NS = 116444736000000000L
    private const val AV_EOL = 0x0000
    private const val AV_TIMESTAMP = 0x0007

    private val random = SecureRandom()

    data class Type2Challenge(
        val serverChallenge: ByteArray,
        val flags: Int,
        val targetInfo: ByteArray,
        val serverTimestampLe: ByteArray?
    )

    fun createType1Message(): ByteArray {
        return ByteBuffer.allocate(32)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put(SIGNATURE)
            .putInt(1)
            .putInt(TYPE1_FLAGS)
            .putShort(0).putShort(0).putInt(0) // DomainNameFields
            .putShort(0).putShort(0).putInt(0) // WorkstationFields
            .array()
    }

    fun parseType2Message(bytes: ByteArray): Type2Challenge {
        require(bytes.size >= 32) { "NTLM Type 2 message too short: ${bytes.size}" }
        for (i in SIGNATURE.indices) {
            require(bytes[i] == SIGNATURE[i]) { "Invalid NTLMSSP signature" }
        }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val msgType = buf.getInt(8)
        require(msgType == 2) { "Expected NTLM Type 2 message, got $msgType" }

        val flags = buf.getInt(20)
        val challenge = bytes.copyOfRange(24, 32)

        var targetInfo = ByteArray(0)
        if ((flags and NTLMSSP_NEGOTIATE_TARGET_INFO) != 0 && bytes.size >= 48) {
            val tiLen = buf.getShort(40).toInt() and 0xFFFF
            val tiOffset = buf.getInt(44)
            if (tiLen > 0 && tiOffset >= 48 && tiOffset + tiLen <= bytes.size) {
                targetInfo = bytes.copyOfRange(tiOffset, tiOffset + tiLen)
            }
        }

        val timestamp = extractServerTimestamp(targetInfo)
        return Type2Challenge(
            serverChallenge = challenge,
            flags = flags,
            targetInfo = targetInfo,
            serverTimestampLe = timestamp
        )
    }

    fun ntowfV2(password: String, user: String, domain: String): ByteArray {
        val ntHash = Md4.digest(password.toByteArray(Charsets.UTF_16LE))
        val identity = (user.uppercase(Locale.ROOT) + domain).toByteArray(Charsets.UTF_16LE)
        return hmacMd5(ntHash, identity)
    }

    fun createType3Message(
        type2: Type2Challenge,
        user: String,
        password: String,
        domain: String = "",
        workstation: String = "ANDROID",
        clientChallenge: ByteArray = ByteArray(8).also { random.nextBytes(it) },
        timestampLe: ByteArray? = null
    ): ByteArray {
        require(clientChallenge.size == 8) { "Client challenge must be 8 bytes" }

        val responseKeyNT = ntowfV2(password, user, domain)
        val tsBytes = timestampLe ?: type2.serverTimestampLe ?: currentWindowsFileTimeLe()
        require(tsBytes.size == 8) { "Timestamp must be 8 bytes" }

        val targetInfo = if (type2.targetInfo.isNotEmpty()) {
            type2.targetInfo
        } else {
            // Minimal AvPair list with MsvAvEOL
            ByteArray(4)
        }

        val blob = ByteArrayOutputStream().apply {
            write(byteArrayOf(0x01, 0x01, 0x00, 0x00)) // RespType=1, HiRespType=1, Reserved=0
            write(byteArrayOf(0x00, 0x00, 0x00, 0x00)) // Reserved
            write(tsBytes)
            write(clientChallenge)
            write(byteArrayOf(0x00, 0x00, 0x00, 0x00)) // Reserved
            write(targetInfo)
            write(byteArrayOf(0x00, 0x00, 0x00, 0x00)) // Reserved
        }.toByteArray()

        val ntProofStr = hmacMd5(responseKeyNT, type2.serverChallenge + blob)
        val ntResponse = ntProofStr + blob

        // MS-NLMP 3.3.2: if server provided MsvAvTimestamp, LMv2 response can be 24 zero bytes
        val lmResponse = if (type2.serverTimestampLe != null) {
            ByteArray(24)
        } else {
            hmacMd5(responseKeyNT, type2.serverChallenge + clientChallenge) + clientChallenge
        }

        val domainBytes = domain.toByteArray(Charsets.UTF_16LE)
        val userBytes = user.toByteArray(Charsets.UTF_16LE)
        val workstationBytes = workstation.toByteArray(Charsets.UTF_16LE)

        val headerSize = 64
        val lmOffset = headerSize
        val ntOffset = lmOffset + lmResponse.size
        val domainOffset = ntOffset + ntResponse.size
        val userOffset = domainOffset + domainBytes.size
        val wsOffset = userOffset + userBytes.size
        val totalSize = wsOffset + workstationBytes.size

        val flags = NTLMSSP_NEGOTIATE_UNICODE or
            NTLMSSP_NEGOTIATE_NTLM or
            NTLMSSP_NEGOTIATE_ALWAYS_SIGN or
            NTLMSSP_NEGOTIATE_EXTENDED_SESSIONSECURITY or
            NTLMSSP_NEGOTIATE_128 or
            NTLMSSP_NEGOTIATE_56

        return ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(SIGNATURE)
            putInt(3)
            putSecurityBuffer(lmResponse.size, lmOffset)
            putSecurityBuffer(ntResponse.size, ntOffset)
            putSecurityBuffer(domainBytes.size, domainOffset)
            putSecurityBuffer(userBytes.size, userOffset)
            putSecurityBuffer(workstationBytes.size, wsOffset)
            putSecurityBuffer(0, totalSize) // EncryptedRandomSessionKeyFields
            putInt(flags)
            put(lmResponse)
            put(ntResponse)
            put(domainBytes)
            put(userBytes)
            put(workstationBytes)
        }.array()
    }

    private fun ByteBuffer.putSecurityBuffer(length: Int, offset: Int) {
        putShort(length.toShort())
        putShort(length.toShort())
        putInt(offset)
    }

    private fun extractServerTimestamp(targetInfo: ByteArray): ByteArray? {
        if (targetInfo.size < 4) return null
        val buf = ByteBuffer.wrap(targetInfo).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 0
        while (pos + 4 <= targetInfo.size) {
            val avId = buf.getShort(pos).toInt() and 0xFFFF
            val avLen = buf.getShort(pos + 2).toInt() and 0xFFFF
            pos += 4
            if (avId == AV_EOL) break
            if (pos + avLen > targetInfo.size) break
            if (avId == AV_TIMESTAMP && avLen == 8) {
                return targetInfo.copyOfRange(pos, pos + 8)
            }
            pos += avLen
        }
        return null
    }

    private fun currentWindowsFileTimeLe(): ByteArray {
        val fileTime = System.currentTimeMillis() * 10_000L + FILETIME_EPOCH_DIFF_100NS
        return ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(fileTime).array()
    }

    internal fun hmacMd5(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacMD5")
        mac.init(SecretKeySpec(key, "HmacMD5"))
        return mac.doFinal(data)
    }
}

class NtlmAuthenticator(
    private val username: String,
    private val password: String,
    private val domain: String = ""
) : Authenticator {

    override fun authenticate(route: Route?, response: Response): Request? {
        val challenges = response.headers("WWW-Authenticate")
        val ntlmChallenge = challenges.firstOrNull {
            it.equals("NTLM", ignoreCase = true) ||
                it.startsWith("NTLM ", ignoreCase = true)
        } ?: return null

        val prevAuth = response.request.header("Authorization")

        // Server responded with Type 2 challenge: "NTLM <base64>"
        if (ntlmChallenge.length > 5 && ntlmChallenge.startsWith("NTLM ", ignoreCase = true)) {
            val tokenB64 = ntlmChallenge.substring(5).trim()
            val type2Bytes = runCatching { Base64.getDecoder().decode(tokenB64) }.getOrNull()
                ?: return null
            val type2 = runCatching { NtlmV2.parseType2Message(type2Bytes) }.getOrNull()
                ?: return null
            val type3 = NtlmV2.createType3Message(
                type2 = type2,
                user = username,
                password = password,
                domain = domain
            )
            val headerVal = "NTLM ${Base64.getEncoder().encodeToString(type3)}"
            if (prevAuth == headerVal) return null
            return response.request.newBuilder()
                .header("Authorization", headerVal)
                .build()
        }

        // Initial 401 advertising "NTLM" without challenge payload: send Type 1 once
        if (prevAuth != null && prevAuth.startsWith("NTLM ", ignoreCase = true)) {
            return null
        }

        val type1B64 = Base64.getEncoder().encodeToString(NtlmV2.createType1Message())
        return response.request.newBuilder()
            .header("Authorization", "NTLM $type1B64")
            .build()
    }
}
