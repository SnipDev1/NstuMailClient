package ru.nstu.mail.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Exchange Web Services (EWS) client using HTTP/1.1 + NTLMv2 over HTTPS.
 */
class EwsClient(private val credentials: Credentials) {

    private val baseUrl: String = normalizeHttpsUrl(credentials.serverUrl)
    private val endpointUrl: String = "$baseUrl/EWS/Exchange.asmx"

    @Volatile
    private var activeClient: OkHttpClient = buildHttpClient(parseInitialIdentity(credentials.email))

    private var fallbackTried = false

    suspend fun getFolders(
        folders: List<MailFolderType> = MailFolderType.entries
    ): List<FolderInfo> = withContext(Dispatchers.IO) {
        val xml = postSoapWithAuthFallback(EwsXml.buildGetFoldersRequest(folders))
        EwsXml.parseFoldersResponse(xml, folders)
    }

    suspend fun listMessages(
        folder: MailFolderType,
        offset: Int = 0,
        pageSize: Int = 25,
        query: String? = null
    ): Pair<List<EmailSummary>, Boolean> = withContext(Dispatchers.IO) {
        val xml = postSoapWithAuthFallback(
            EwsXml.buildFindItemsRequest(folder, offset, pageSize, query)
        )
        EwsXml.parseFindItemsResponse(xml)
    }

    suspend fun getMessage(itemId: String): EmailDetail = withContext(Dispatchers.IO) {
        val xml = postSoapWithAuthFallback(EwsXml.buildGetItemRequest(itemId))
        EwsXml.parseGetItemResponse(xml)
    }

    suspend fun downloadAttachment(attachmentId: String): DownloadedAttachment =
        withContext(Dispatchers.IO) {
            val xml = postSoapWithAuthFallback(EwsXml.buildGetAttachmentRequest(attachmentId))
            EwsXml.parseGetAttachmentResponse(xml)
        }

    suspend fun sendMessage(
        to: List<String>,
        cc: List<String>,
        subject: String,
        body: String
    ) = withContext(Dispatchers.IO) {
        val xml = postSoapWithAuthFallback(EwsXml.buildSendEmailRequest(to, cc, subject, body))
        EwsXml.parseSimpleSuccessResponse(xml)
    }

    suspend fun setRead(itemId: String, changeKey: String, isRead: Boolean) =
        withContext(Dispatchers.IO) {
            val xml = postSoapWithAuthFallback(
                EwsXml.buildSetReadRequest(itemId, changeKey, isRead)
            )
            EwsXml.parseSimpleSuccessResponse(xml)
        }

    suspend fun deleteMessage(itemId: String, changeKey: String, hardDelete: Boolean) =
        withContext(Dispatchers.IO) {
            val xml = postSoapWithAuthFallback(
                EwsXml.buildDeleteItemRequest(itemId, changeKey, hardDelete)
            )
            EwsXml.parseSimpleSuccessResponse(xml)
        }

    private fun postSoapWithAuthFallback(soapBody: String): String {
        return try {
            executeSoap(activeClient, soapBody)
        } catch (e: EwsException) {
            if (e.isAuthError && !fallbackTried && credentials.email.contains('@') &&
                !credentials.email.contains('\\')
            ) {
                fallbackTried = true
                val local = credentials.email.substringBefore('@')
                val domain = credentials.email.substringAfter('@')
                val altClient = buildHttpClient(local to domain)
                val result = executeSoap(altClient, soapBody)
                activeClient = altClient
                result
            } else {
                throw e
            }
        }
    }

    private fun executeSoap(client: OkHttpClient, soapBody: String): String {
        val request = Request.Builder()
            .url(endpointUrl)
            .post(soapBody.toRequestBody(XML_MEDIA_TYPE))
            .header("Accept", "text/xml")
            .build()

        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw EwsException(
                "Не удалось подключиться к серверу: ${e.localizedMessage ?: e.javaClass.simpleName}",
                cause = e
            )
        }

        response.use { resp ->
            val bodyStr = resp.body?.string().orEmpty()
            return when (resp.code) {
                200 -> bodyStr
                401 -> throw EwsException(
                    "Ошибка авторизации (401). Проверьте логин/пароль или войдите через OWA.",
                    isAuthError = true
                )
                403, 404 -> throw EwsException(
                    "EWS недоступен (HTTP ${resp.code}). Попробуйте вход через OWA.",
                    isAuthError = true
                )
                500 -> {
                    if (bodyStr.contains("Fault", ignoreCase = true)) bodyStr
                    else throw EwsException("Ошибка сервера (HTTP 500)")
                }
                else -> throw EwsException("HTTP ошибка ${resp.code}")
            }
        }
    }

    private fun buildHttpClient(identity: Pair<String, String>): OkHttpClient {
        val (user, domain) = identity
        return OkHttpClient.Builder()
            // NTLM binds to a single TCP connection and breaks over HTTP/2 on IIS 10
            .protocols(listOf(Protocol.HTTP_1_1))
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(25, TimeUnit.SECONDS)
            .authenticator(
                NtlmAuthenticator(
                    username = user,
                    password = credentials.password,
                    domain = domain
                )
            )
            .build()
    }

    private fun parseInitialIdentity(email: String): Pair<String, String> {
        val trimmed = email.trim()
        return if ('\\' in trimmed) {
            trimmed.substringAfter('\\') to trimmed.substringBefore('\\')
        } else {
            trimmed to ""
        }
    }

    companion object {
        private val XML_MEDIA_TYPE = "text/xml; charset=utf-8".toMediaType()

        fun normalizeHttpsUrl(raw: String): String {
            val trimmed = raw.trim().trimEnd('/')
            require(trimmed.isNotEmpty()) { "Укажите адрес сервера" }
            val candidate = if ("://" in trimmed) trimmed else "https://$trimmed"
            require(candidate.startsWith("https://", ignoreCase = true)) {
                "Разрешено только защищённое подключение HTTPS"
            }
            val parsed = candidate.toHttpUrlOrNull()
                ?: throw IllegalArgumentException("Некорректный адрес сервера")
            require(parsed.scheme == "https" && parsed.host.isNotBlank()) {
                "Разрешено только защищённое подключение HTTPS"
            }
            require(parsed.username.isEmpty() && parsed.password.isEmpty()) {
                "Учётные данные в URL запрещены"
            }
            return if (parsed.port != 443) {
                "https://${parsed.host}:${parsed.port}"
            } else {
                "https://${parsed.host}"
            }
        }
    }
}
