package ru.nstu.mail.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Exchange Web Services (EWS) client using HTTP/1.1 + NTLMv2 over HTTPS.
 */
class EwsClient(private val credentials: Credentials) {

    private val baseUrl: String = normalizeHttpsUrl(credentials.serverUrl)
    private val endpointUrl: String = "$baseUrl/EWS/Exchange.asmx"

    @Volatile
    private var activeClient: OkHttpClient = buildHttpClient(parseInitialIdentity(credentials.email))

    private val authLock = Any()

    @Volatile
    private var fallbackTried = false

    @Volatile
    private var cachedRootFolderId: String? = null

    @Volatile
    private var cachedSystemIdMap: Map<String, SystemFolderKind> = emptyMap()

    // --- Folders ---

    fun getCachedSystemFolderIds(): Map<String, SystemFolderKind> = cachedSystemIdMap

    suspend fun getFolders(includeSubtreeCounts: Boolean = true): List<MailFolder> = withContext(Dispatchers.IO) {
        if (cachedSystemIdMap.isEmpty()) {
            val reqIds = listOf("msgfolderroot") + SystemFolderKind.entries.map { it.ewsId }
            val distXml = postSoapWithAuthFallback(EwsXml.buildGetDistinguishedFoldersRequest(reqIds))
            val idMap = EwsXml.parseDistinguishedFolderIds(distXml, reqIds)
            cachedRootFolderId = idMap["msgfolderroot"]
            val sysMap = mutableMapOf<String, SystemFolderKind>()
            for (kind in SystemFolderKind.entries) {
                idMap[kind.ewsId]?.let { realId -> sysMap[realId] = kind }
            }
            cachedSystemIdMap = sysMap
        }

        val findXml = postSoapWithAuthFallback(EwsXml.buildFindFoldersRequest())
        val folders = EwsXml.parseFindFoldersResponse(findXml, cachedRootFolderId, cachedSystemIdMap)
        if (!includeSubtreeCounts) return@withContext folders
        val byId = folders.associateBy { it.id }
        val parentIds = folders.mapNotNull { it.parentId.takeIf { pid -> pid in byId } }.toSet()
        if (parentIds.isEmpty()) return@withContext folders

        val subtreeFolderIds = folders
            .filter { it.id in parentIds || it.parentId in byId }
            .map { it.id }
        val mergedKeys = mutableMapOf<String, Pair<List<FolderMessageKey>, Boolean>>()
        val fetched = runCatching {
            for (chunk in subtreeFolderIds.chunked(20)) {
                val keysXml = postSoapWithAuthFallback(EwsXml.buildFindFolderMessageKeysRequest(chunk))
                mergedKeys += EwsXml.parseFindFolderMessageKeysResponse(keysXml, chunk)
            }
            true
        }.getOrDefault(false)
        if (!fetched) return@withContext folders
        applyUniqueSubtreeCounts(folders, mergedKeys)
    }

    suspend fun createFolder(displayName: String, parentFolderId: String? = null) =
        withContext(Dispatchers.IO) {
            val xml = postSoapWithAuthFallback(
                EwsXml.buildCreateFolderRequest(displayName, parentFolderId)
            )
            EwsXml.parseSimpleSuccessResponse(xml)
        }

    suspend fun renameFolder(folderId: String, changeKey: String, newName: String) =
        withContext(Dispatchers.IO) {
            val xml = postSoapWithAuthFallback(
                EwsXml.buildRenameFolderRequest(folderId, changeKey, newName)
            )
            EwsXml.parseSimpleSuccessResponse(xml)
        }

    suspend fun deleteFolder(folderId: String, changeKey: String) =
        withContext(Dispatchers.IO) {
            try {
                val xml = postSoapWithAuthFallback(
                    EwsXml.buildDeleteFolderRequest(folderId, changeKey, hardDelete = false)
                )
                EwsXml.parseSimpleSuccessResponse(xml)
            } catch (e: EwsException) {
                if (e.isAuthError) throw e
                // Fall back to HardDelete if a same-named folder is already in Deleted Items or ChangeKey is stale
                val xml = postSoapWithAuthFallback(
                    EwsXml.buildDeleteFolderRequest(folderId, "", hardDelete = true)
                )
                EwsXml.parseSimpleSuccessResponse(xml)
            }
        }

    suspend fun emptyFolder(folderId: String) =
        withContext(Dispatchers.IO) {
            val xml = postSoapWithAuthFallback(EwsXml.buildEmptyFolderRequest(folderId))
            EwsXml.parseSimpleSuccessResponse(xml)
        }

    suspend fun markAllRead(folderId: String) =
        markAllRead(listOf(folderId))

    suspend fun markAllRead(folderIds: List<String>) =
        withContext(Dispatchers.IO) {
            val cleanIds = folderIds.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            if (cleanIds.isEmpty()) return@withContext
            for (chunk in cleanIds.chunked(20)) {
                val xml = postSoapWithAuthFallback(EwsXml.buildMarkAllReadRequest(chunk))
                EwsXml.parseSimpleSuccessResponse(xml)
            }
        }

    // --- Messages ---

    suspend fun listMessages(
        folderIds: List<String>,
        offset: Int = 0,
        pageSize: Int = 25,
        query: String? = null,
        filter: MailFilter = MailFilter.ALL
    ): Pair<List<EmailSummary>, Boolean> = withContext(Dispatchers.IO) {
        if (folderIds.isEmpty()) return@withContext emptyList<EmailSummary>() to true
        val xml = postSoapWithAuthFallback(
            EwsXml.buildFindItemsRequest(folderIds, offset, pageSize, query, filter)
        )
        EwsXml.parseFindItemsResponse(xml)
    }

    suspend fun listInboxUnread(pageSize: Int = 15): List<EmailSummary> = withContext(Dispatchers.IO) {
        val xml = postSoapWithAuthFallback(EwsXml.buildFindInboxUnreadRequest(pageSize))
        EwsXml.parseFindItemsResponse(xml).first
    }

    suspend fun listUnreadInFolders(
        folderIds: List<String>,
        pageSizePerFolder: Int = 15
    ): List<EmailSummary> = withContext(Dispatchers.IO) {
        val uniqueIds = folderIds.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (uniqueIds.isEmpty()) return@withContext emptyList()
        val collected = mutableListOf<EmailSummary>()
        for (chunk in uniqueIds.chunked(20)) {
            val xml = postSoapWithAuthFallback(
                EwsXml.buildFindItemsRequest(
                    folderIds = chunk,
                    offset = 0,
                    pageSize = pageSizePerFolder,
                    query = null,
                    filter = MailFilter.UNREAD
                )
            )
            collected += EwsXml.parseFindItemsByFolderResponse(xml, chunk)
        }
        collected
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

    suspend fun saveOrSendDraft(
        draftId: String?,
        draftChangeKey: String?,
        to: List<String>,
        cc: List<String>,
        bcc: List<String>,
        subject: String,
        body: String,
        importance: EmailImportance,
        newAttachments: List<OutgoingAttachment>,
        send: Boolean
    ): Pair<String, String> = withContext(Dispatchers.IO) {
        if (send && draftId == null && newAttachments.isEmpty()) {
            val xml = postSoapWithAuthFallback(
                EwsXml.buildCreateMessageRequest(
                    to = to,
                    cc = cc,
                    bcc = bcc,
                    subject = subject,
                    body = body,
                    importance = importance,
                    sendImmediately = true
                )
            )
            EwsXml.parseSimpleSuccessResponse(xml)
            return@withContext "" to ""
        }

        var (itemId, changeKey) = if (draftId == null) {
            val createXml = postSoapWithAuthFallback(
                EwsXml.buildCreateMessageRequest(
                    to = to,
                    cc = cc,
                    bcc = bcc,
                    subject = subject,
                    body = body,
                    importance = importance,
                    sendImmediately = false
                )
            )
            EwsXml.parseItemIdResponse(createXml)
        } else {
            val updateXml = postSoapWithAuthFallback(
                EwsXml.buildUpdateDraftRequest(
                    itemId = draftId,
                    changeKey = draftChangeKey.orEmpty(),
                    to = to,
                    cc = cc,
                    bcc = bcc,
                    subject = subject,
                    body = body,
                    importance = importance
                )
            )
            EwsXml.parseItemIdResponse(updateXml)
        }

        if (newAttachments.isNotEmpty()) {
            val attXml = postSoapWithAuthFallback(
                EwsXml.buildCreateAttachmentRequest(itemId, changeKey, newAttachments)
            )
            val newCk = EwsXml.parseAttachmentRootChangeKey(attXml)
            if (newCk.isNotBlank()) changeKey = newCk
        }

        if (send) {
            val sendXml = postSoapWithAuthFallback(EwsXml.buildSendItemRequest(itemId, changeKey))
            EwsXml.parseSimpleSuccessResponse(sendXml)
            return@withContext "" to ""
        }

        itemId to changeKey
    }

    suspend fun setRead(items: List<Pair<String, String>>, isRead: Boolean) =
        withContext(Dispatchers.IO) {
            if (items.isEmpty()) return@withContext
            withStaleChangeKeyRetry(items) { curItems ->
                val xml = postSoapWithAuthFallback(EwsXml.buildSetReadRequest(curItems, isRead))
                EwsXml.parseSimpleSuccessResponse(xml)
            }
        }

    suspend fun setFlagged(items: List<Pair<String, String>>, flagged: Boolean) =
        withContext(Dispatchers.IO) {
            if (items.isEmpty()) return@withContext
            withStaleChangeKeyRetry(items) { curItems ->
                val xml = postSoapWithAuthFallback(EwsXml.buildSetFlagRequest(curItems, flagged))
                EwsXml.parseSimpleSuccessResponse(xml)
            }
        }

    suspend fun moveMessages(items: List<Pair<String, String>>, targetFolderId: String) {
        moveMessagesReturningIds(items, targetFolderId)
    }

    suspend fun moveMessagesReturningIds(
        items: List<Pair<String, String>>,
        targetFolderId: String
    ): List<String> = withContext(Dispatchers.IO) {
        if (items.isEmpty()) return@withContext emptyList()
        withStaleChangeKeyRetry(items) { curItems ->
            val xml = postSoapWithAuthFallback(EwsXml.buildMoveItemsRequest(curItems, targetFolderId))
            EwsXml.parseSimpleSuccessResponse(xml)
            EwsXml.parseMovedItemIds(xml)
        }
    }

    suspend fun copyMessages(items: List<Pair<String, String>>, targetFolderId: String) =
        withContext(Dispatchers.IO) {
            if (items.isEmpty()) return@withContext
            withStaleChangeKeyRetry(items) { curItems ->
                val xml = postSoapWithAuthFallback(EwsXml.buildCopyItemsRequest(curItems, targetFolderId))
                EwsXml.parseSimpleSuccessResponse(xml)
            }
        }

    suspend fun deleteMessages(items: List<Pair<String, String>>, hardDelete: Boolean) =
        withContext(Dispatchers.IO) {
            if (items.isEmpty()) return@withContext
            withStaleChangeKeyRetry(items) { curItems ->
                val xml = postSoapWithAuthFallback(EwsXml.buildDeleteItemsRequest(curItems, hardDelete))
                EwsXml.parseSimpleSuccessResponse(xml)
            }
        }

    private inline fun <T> withStaleChangeKeyRetry(
        items: List<Pair<String, String>>,
        action: (List<Pair<String, String>>) -> T
    ): T {
        return try {
            action(items)
        } catch (e: EwsException) {
            if (e.isAuthError || items.none { it.second.isNotBlank() }) throw e
            action(items.map { it.first to "" })
        }
    }

    // --- GAL / Contacts / Calendar ---

    suspend fun resolveNames(query: String): List<ContactEntry> = withContext(Dispatchers.IO) {
        if (query.trim().length < 2) return@withContext emptyList()
        val xml = postSoapWithAuthFallback(EwsXml.buildResolveNamesRequest(query))
        EwsXml.parseResolveNamesResponse(xml)
    }

    suspend fun getRecipientCache(maxEntries: Int = 100): List<ContactEntry> = withContext(Dispatchers.IO) {
        val xml = postSoapWithAuthFallback(EwsXml.buildFindRecipientCacheRequest(maxEntries))
        EwsXml.parseFindPeopleResponse(xml)
    }

    suspend fun resolvePersonProfile(email: String, name: String = ""): PersonProfile? =
        withContext(Dispatchers.IO) {
            val cleanEmail = email.trim()
            val cleanName = name.trim()

            suspend fun queryProfiles(term: String): List<PersonProfile> {
                if (term.length < 2) return emptyList()
                val xml = postSoapWithAuthFallback(EwsXml.buildResolveNamesRequest(term))
                return EwsXml.parsePersonProfilesFromResolveNames(xml)
            }

            if (cleanEmail.isNotBlank()) {
                val byEmail = queryProfiles(cleanEmail)
                val exact = byEmail.firstOrNull { p ->
                    p.email.equals(cleanEmail, ignoreCase = true) ||
                        p.aliases.any { it.equals(cleanEmail, ignoreCase = true) }
                }
                if (exact != null) return@withContext exact
                if (byEmail.isNotEmpty()) return@withContext byEmail.first()
            }

            if (cleanName.isNotBlank() && !cleanName.equals(cleanEmail, ignoreCase = true)) {
                val byName = queryProfiles(cleanName)
                val exact = byName.firstOrNull { p ->
                    (cleanEmail.isNotBlank() && (p.email.equals(cleanEmail, ignoreCase = true) ||
                        p.aliases.any { it.equals(cleanEmail, ignoreCase = true) })) ||
                        p.displayName.equals(cleanName, ignoreCase = true)
                }
                if (exact != null) return@withContext exact
                if (byName.size == 1) return@withContext byName.first()
            }
            null
        }

    suspend fun getContacts(): List<ContactEntry> = withContext(Dispatchers.IO) {
        val xml = postSoapWithAuthFallback(EwsXml.buildFindContactsRequest())
        EwsXml.parseFindContactsResponse(xml)
    }

    suspend fun createContact(name: String, email: String) = withContext(Dispatchers.IO) {
        val xml = postSoapWithAuthFallback(EwsXml.buildCreateContactRequest(name, email))
        EwsXml.parseSimpleSuccessResponse(xml)
    }

    suspend fun getCalendarEvents(startIso: String, endIso: String): List<CalendarEvent> =
        withContext(Dispatchers.IO) {
            val xml = postSoapWithAuthFallback(
                EwsXml.buildFindCalendarEventsRequest(startIso, endIso)
            )
            EwsXml.parseCalendarEventsResponse(xml)
        }

    suspend fun createCalendarEvent(
        subject: String,
        startIso: String,
        endIso: String,
        location: String,
        body: String
    ) = withContext(Dispatchers.IO) {
        val xml = postSoapWithAuthFallback(
            EwsXml.buildCreateCalendarEventRequest(subject, startIso, endIso, location, body)
        )
        EwsXml.parseSimpleSuccessResponse(xml)
    }

    suspend fun getUserPhoto(email: String, size: String = "HR96x96"): ByteArray? =
        withContext(Dispatchers.IO) {
            val trimmed = email.trim()
            if ('@' !in trimmed) return@withContext null
            val url = buildUserPhotoUrl(baseUrl, trimmed, size)
            val current = activeClient
            try {
                executeGetBytes(current, url)
            } catch (e: EwsException) {
                if (e.isAuthError) {
                    val alt = getOrSwitchFallbackClient(current)
                    if (alt != null) executeGetBytes(alt, url) else null
                } else {
                    null
                }
            }
        }

    private fun executeGetBytes(client: OkHttpClient, url: HttpUrl): ByteArray? {
        val request = Request.Builder()
            .url(url)
            .get()
            .build()
        val response = runCatching { client.newCall(request).execute() }.getOrNull() ?: return null
        response.use { resp ->
            if (resp.code == 401) {
                throw EwsException("Unauthorized", isAuthError = true)
            }
            if (resp.code != 200) return null
            val body = resp.body ?: return null
            if (body.contentLength() > MAX_AVATAR_BYTES) return null
            val bytes = body.bytes()
            return if (bytes.isEmpty() || bytes.size > MAX_AVATAR_BYTES) null else bytes
        }
    }

    suspend fun createStreamingSubscription(
        folderIds: List<String> = emptyList(),
        subscribeAllFolders: Boolean = false
    ): String = withContext(Dispatchers.IO) {
        if (subscribeAllFolders) {
            val allFoldersAttempt = try {
                val xml = postSoapWithAuthFallback(
                    EwsXml.buildStreamingSubscribeRequest(subscribeAllFolders = true)
                )
                EwsXml.parseSubscribeResponse(xml)
            } catch (e: EwsException) {
                if (e.isAuthError) throw e
                null
            }
            if (!allFoldersAttempt.isNullOrBlank()) return@withContext allFoldersAttempt
        }
        val cleanIds = folderIds.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (cleanIds.isNotEmpty()) {
            val multiAttempt = try {
                val xml = postSoapWithAuthFallback(
                    EwsXml.buildStreamingSubscribeRequest(folderIds = cleanIds)
                )
                EwsXml.parseSubscribeResponse(xml)
            } catch (e: EwsException) {
                if (e.isAuthError) throw e
                null
            }
            if (!multiAttempt.isNullOrBlank()) return@withContext multiAttempt
        }
        val xml = postSoapWithAuthFallback(EwsXml.buildStreamingSubscribeRequest())
        EwsXml.parseSubscribeResponse(xml)
    }

    suspend fun unsubscribe(subscriptionId: String) = withContext(Dispatchers.IO) {
        if (subscriptionId.isBlank()) return@withContext
        runCatching { postSoapWithAuthFallback(EwsXml.buildUnsubscribeRequest(subscriptionId)) }
    }

    suspend fun streamInboxEvents(
        subscriptionId: String,
        timeoutMinutes: Int = 25,
        onChunk: suspend (EwsXml.StreamingChunkResult) -> Unit
    ) = withContext(Dispatchers.IO) {
        val clampedTimeout = timeoutMinutes.coerceIn(1, 30)
        val soapBody = EwsXml.buildGetStreamingEventsRequest(subscriptionId, clampedTimeout)
        val streamingClient = activeClient.newBuilder()
            .readTimeout((clampedTimeout + 2).toLong(), TimeUnit.MINUTES)
            .build()
        val request = Request.Builder()
            .url(endpointUrl)
            .post(soapBody.toRequestBody(XML_MEDIA_TYPE))
            .header("Accept", "text/xml")
            .build()
        val call = streamingClient.newCall(request)
        val cancelHandle = coroutineContext[Job]?.invokeOnCompletion { call.cancel() }
        try {
            val response = try {
                call.execute()
            } catch (e: IOException) {
                throw EwsException(
                    "Потоковое соединение прервано: ${e.localizedMessage ?: e.javaClass.simpleName}",
                    cause = e
                )
            }
            response.use { resp ->
                if (resp.code == 401) {
                    throw EwsException("Ошибка авторизации (401)", isAuthError = true)
                }
                if (resp.code == 403 || resp.code == 404) {
                    throw EwsException("EWS недоступен (HTTP ${resp.code})", isAuthError = false)
                }
                if (resp.code != 200) {
                    throw EwsException("HTTP ошибка ${resp.code}")
                }
                val body = resp.body ?: return@use
                val reader = BufferedReader(InputStreamReader(body.byteStream(), Charsets.UTF_8))
                val buffer = StringBuilder()
                val charBuf = CharArray(4096)
                val endRegex = Regex("</(?:[A-Za-z0-9_]+:)?Envelope>")
                while (true) {
                    val read = reader.read(charBuf)
                    if (read == -1) break
                    buffer.append(charBuf, 0, read)
                    if (buffer.length > MAX_STREAM_BUFFER_CHARS) {
                        throw EwsException("Потоковый ответ сервера превысил допустимый размер")
                    }
                    while (true) {
                        val match = endRegex.find(buffer) ?: break
                        val startIdx = buffer.indexOf('<').coerceAtLeast(0)
                        val endIdx = match.range.last + 1
                        val chunkXml = buffer.substring(startIdx, endIdx)
                        buffer.delete(0, endIdx)
                        val parsed = EwsXml.parseStreamingChunkEvents(chunkXml)
                        onChunk(parsed)
                    }
                }
            }
        } finally {
            cancelHandle?.dispose()
            call.cancel()
        }
    }

    // --- Transport ---

    private fun getOrSwitchFallbackClient(failedClient: OkHttpClient): OkHttpClient? {
        if (!credentials.email.contains('@') || credentials.email.contains('\\')) return null
        synchronized(authLock) {
            if (activeClient !== failedClient) return activeClient
            if (fallbackTried) return null
            fallbackTried = true
            val local = credentials.email.substringBefore('@')
            val domain = credentials.email.substringAfter('@')
            val altClient = buildHttpClient(local to domain)
            activeClient = altClient
            return altClient
        }
    }

    private fun postSoapWithAuthFallback(soapBody: String): String {
        val currentClient = activeClient
        return try {
            executeSoap(currentClient, soapBody)
        } catch (e: EwsException) {
            if (e.isAuthError) {
                val alt = getOrSwitchFallbackClient(currentClient)
                if (alt != null) executeSoap(alt, soapBody) else throw e
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
                    "Ошибка авторизации (401). Проверьте логин и пароль.",
                    isAuthError = true
                )
                403, 404 -> throw EwsException(
                    "EWS недоступен (HTTP ${resp.code}). Проверьте адрес сервера.",
                    isAuthError = false
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
        private const val MAX_AVATAR_BYTES = 2 * 1024 * 1024L
        private const val MAX_STREAM_BUFFER_CHARS = 2 * 1024 * 1024

        fun buildUserPhotoUrl(baseUrl: String, email: String, size: String = "HR96x96"): HttpUrl =
            baseUrl.toHttpUrl()
                .newBuilder()
                .addPathSegments("EWS/Exchange.asmx/s/GetUserPhoto")
                .addQueryParameter("email", email.trim())
                .addQueryParameter("size", size)
                .build()

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
