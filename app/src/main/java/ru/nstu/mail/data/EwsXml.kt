package ru.nstu.mail.data

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import java.util.Base64
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * SOAP 1.1 XML request builders and DOM response parsers for Microsoft Exchange EWS (2016).
 */
object EwsXml {

    private const val SOAP_ENV = "http://schemas.xmlsoap.org/soap/envelope/"
    private const val EWS_TYPES = "http://schemas.microsoft.com/exchange/services/2006/types"
    private const val EWS_MESSAGES = "http://schemas.microsoft.com/exchange/services/2006/messages"

    fun escapeXml(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            when (ch) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&apos;")
                '\t', '\n', '\r' -> sb.append(ch)
                else -> if (ch.code >= 0x20) sb.append(ch)
            }
        }
        return sb.toString()
    }

    fun buildGetFoldersRequest(folders: List<MailFolderType>): String {
        val ids = folders.joinToString("\n        ") {
            """<t:DistinguishedFolderId Id="${escapeXml(it.ewsId)}"/>"""
        }
        return wrapSoap(
            """
            <m:GetFolder>
              <m:FolderShape>
                <t:BaseShape>Default</t:BaseShape>
              </m:FolderShape>
              <m:FolderIds>
                $ids
              </m:FolderIds>
            </m:GetFolder>
            """.trimIndent()
        )
    }

    fun buildFindItemsRequest(
        folder: MailFolderType,
        offset: Int,
        pageSize: Int,
        query: String?
    ): String {
        val queryBlock = if (!query.isNullOrBlank()) {
            "<m:QueryString>${escapeXml(query.trim())}</m:QueryString>"
        } else {
            ""
        }
        return wrapSoap(
            """
            <m:FindItem Traversal="Shallow">
              <m:ItemShape>
                <t:BaseShape>IdOnly</t:BaseShape>
                <t:AdditionalProperties>
                  <t:FieldURI FieldURI="item:Subject"/>
                  <t:FieldURI FieldURI="item:DateTimeReceived"/>
                  <t:FieldURI FieldURI="item:HasAttachments"/>
                  <t:FieldURI FieldURI="item:Preview"/>
                  <t:FieldURI FieldURI="message:From"/>
                  <t:FieldURI FieldURI="message:IsRead"/>
                </t:AdditionalProperties>
              </m:ItemShape>
              <m:IndexedPageItemView MaxEntriesReturned="$pageSize" Offset="$offset" BasePoint="Beginning"/>
              <m:SortOrder>
                <t:FieldOrder Order="Descending">
                  <t:FieldURI FieldURI="item:DateTimeReceived"/>
                </t:FieldOrder>
              </m:SortOrder>
              <m:ParentFolderIds>
                <t:DistinguishedFolderId Id="${escapeXml(folder.ewsId)}"/>
              </m:ParentFolderIds>
              $queryBlock
            </m:FindItem>
            """.trimIndent()
        )
    }

    fun buildGetItemRequest(itemId: String): String {
        return wrapSoap(
            """
            <m:GetItem>
              <m:ItemShape>
                <t:BaseShape>IdOnly</t:BaseShape>
                <t:IncludeMimeContent>false</t:IncludeMimeContent>
                <t:BodyType>Best</t:BodyType>
                <t:AdditionalProperties>
                  <t:FieldURI FieldURI="item:Subject"/>
                  <t:FieldURI FieldURI="item:DateTimeReceived"/>
                  <t:FieldURI FieldURI="item:Body"/>
                  <t:FieldURI FieldURI="item:HasAttachments"/>
                  <t:FieldURI FieldURI="item:Attachments"/>
                  <t:FieldURI FieldURI="message:From"/>
                  <t:FieldURI FieldURI="message:ToRecipients"/>
                  <t:FieldURI FieldURI="message:CcRecipients"/>
                  <t:FieldURI FieldURI="message:IsRead"/>
                </t:AdditionalProperties>
              </m:ItemShape>
              <m:ItemIds>
                <t:ItemId Id="${escapeXml(itemId)}"/>
              </m:ItemIds>
            </m:GetItem>
            """.trimIndent()
        )
    }

    fun buildGetAttachmentRequest(attachmentId: String): String {
        return wrapSoap(
            """
            <m:GetAttachment>
              <m:AttachmentIds>
                <t:AttachmentId Id="${escapeXml(attachmentId)}"/>
              </m:AttachmentIds>
            </m:GetAttachment>
            """.trimIndent()
        )
    }

    fun buildSendEmailRequest(
        to: List<String>,
        cc: List<String>,
        subject: String,
        body: String
    ): String {
        val toXml = to.joinToString("") {
            "<t:Mailbox><t:EmailAddress>${escapeXml(it)}</t:EmailAddress></t:Mailbox>"
        }
        val ccBlock = if (cc.isNotEmpty()) {
            val ccXml = cc.joinToString("") {
                "<t:Mailbox><t:EmailAddress>${escapeXml(it)}</t:EmailAddress></t:Mailbox>"
            }
            "<t:CcRecipients>$ccXml</t:CcRecipients>"
        } else {
            ""
        }
        return wrapSoap(
            """
            <m:CreateItem MessageDisposition="SendAndSaveCopy">
              <m:SavedItemFolderId>
                <t:DistinguishedFolderId Id="sentitems"/>
              </m:SavedItemFolderId>
              <m:Items>
                <t:Message>
                  <t:Subject>${escapeXml(subject)}</t:Subject>
                  <t:Body BodyType="Text">${escapeXml(body)}</t:Body>
                  <t:ToRecipients>$toXml</t:ToRecipients>
                  $ccBlock
                </t:Message>
              </m:Items>
            </m:CreateItem>
            """.trimIndent()
        )
    }

    fun buildSetReadRequest(itemId: String, changeKey: String, isRead: Boolean): String {
        val ckAttr = if (changeKey.isNotBlank()) """ ChangeKey="${escapeXml(changeKey)}"""" else ""
        return wrapSoap(
            """
            <m:UpdateItem ConflictResolution="AlwaysOverwrite" MessageDisposition="SaveOnly">
              <m:ItemChanges>
                <t:ItemChange>
                  <t:ItemId Id="${escapeXml(itemId)}"$ckAttr/>
                  <t:Updates>
                    <t:SetItemField>
                      <t:FieldURI FieldURI="message:IsRead"/>
                      <t:Message>
                        <t:IsRead>$isRead</t:IsRead>
                      </t:Message>
                    </t:SetItemField>
                  </t:Updates>
                </t:ItemChange>
              </m:ItemChanges>
            </m:UpdateItem>
            """.trimIndent()
        )
    }

    fun buildDeleteItemRequest(itemId: String, changeKey: String, hardDelete: Boolean): String {
        val deleteType = if (hardDelete) "HardDelete" else "MoveToDeletedItems"
        val ckAttr = if (changeKey.isNotBlank()) """ ChangeKey="${escapeXml(changeKey)}"""" else ""
        return wrapSoap(
            """
            <m:DeleteItem DeleteType="$deleteType">
              <m:ItemIds>
                <t:ItemId Id="${escapeXml(itemId)}"$ckAttr/>
              </m:ItemIds>
            </m:DeleteItem>
            """.trimIndent()
        )
    }

    fun parseFoldersResponse(xml: String, requested: List<MailFolderType>): List<FolderInfo> {
        val doc = parseDocument(xml)
        checkSoapErrors(doc)

        val folderNodes = doc.getElementsByTagNameNS(EWS_TYPES, "Folder")
        val result = mutableListOf<FolderInfo>()
        for (i in 0 until folderNodes.length) {
            val el = folderNodes.item(i) as? Element ?: continue
            val type = requested.getOrNull(i) ?: continue
            val name = el.firstChildText(EWS_TYPES, "DisplayName").ifBlank { type.labelRu }
            val total = el.firstChildText(EWS_TYPES, "TotalCount").toIntOrNull() ?: 0
            val unread = el.firstChildText(EWS_TYPES, "UnreadCount").toIntOrNull() ?: 0
            result += FolderInfo(
                type = type,
                displayName = name,
                totalCount = total,
                unreadCount = unread
            )
        }
        return result
    }

    fun parseFindItemsResponse(xml: String): Pair<List<EmailSummary>, Boolean> {
        val doc = parseDocument(xml)
        checkSoapErrors(doc)

        val rootFolder = doc.getElementsByTagNameNS(EWS_MESSAGES, "RootFolder").item(0) as? Element
        val includesLast = rootFolder?.getAttribute("IncludesLastItemInRange")
            ?.equals("true", ignoreCase = true) ?: true

        val items = mutableListOf<EmailSummary>()
        val messages = doc.getElementsByTagNameNS(EWS_TYPES, "Message")
        for (i in 0 until messages.length) {
            val msg = messages.item(i) as? Element ?: continue
            val itemIdEl = msg.firstChildElement(EWS_TYPES, "ItemId") ?: continue
            val id = itemIdEl.getAttribute("Id")
            val changeKey = itemIdEl.getAttribute("ChangeKey")
            val subject = msg.firstChildText(EWS_TYPES, "Subject").ifBlank { "(Без темы)" }
            val receivedAt = msg.firstChildText(EWS_TYPES, "DateTimeReceived")
            val isRead = msg.firstChildText(EWS_TYPES, "IsRead").equals("true", ignoreCase = true)
            val hasAtt = msg.firstChildText(EWS_TYPES, "HasAttachments")
                .equals("true", ignoreCase = true)
            val preview = msg.firstChildText(EWS_TYPES, "Preview")

            val fromMailbox = msg.firstChildElement(EWS_TYPES, "From")
                ?.firstChildElement(EWS_TYPES, "Mailbox")
            val senderName = fromMailbox?.firstChildText(EWS_TYPES, "Name").orEmpty()
            val senderEmail = fromMailbox?.firstChildText(EWS_TYPES, "EmailAddress").orEmpty()

            items += EmailSummary(
                id = id,
                changeKey = changeKey,
                subject = subject,
                senderName = senderName.ifBlank { senderEmail.ifBlank { "Неизвестный" } },
                senderEmail = senderEmail,
                receivedAt = receivedAt,
                isRead = isRead,
                hasAttachments = hasAtt,
                preview = preview
            )
        }
        return items to includesLast
    }

    fun parseGetItemResponse(xml: String): EmailDetail {
        val doc = parseDocument(xml)
        checkSoapErrors(doc)

        val msg = doc.getElementsByTagNameNS(EWS_TYPES, "Message").item(0) as? Element
            ?: throw EwsException("Сообщение не найдено в ответе сервера")

        val itemIdEl = msg.firstChildElement(EWS_TYPES, "ItemId")
            ?: throw EwsException("Отсутствует ItemId")
        val id = itemIdEl.getAttribute("Id")
        val changeKey = itemIdEl.getAttribute("ChangeKey")
        val subject = msg.firstChildText(EWS_TYPES, "Subject").ifBlank { "(Без темы)" }
        val receivedAt = msg.firstChildText(EWS_TYPES, "DateTimeReceived")
        val isRead = msg.firstChildText(EWS_TYPES, "IsRead").equals("true", ignoreCase = true)

        val fromMailbox = msg.firstChildElement(EWS_TYPES, "From")
            ?.firstChildElement(EWS_TYPES, "Mailbox")
        val fromName = fromMailbox?.firstChildText(EWS_TYPES, "Name").orEmpty()
        val fromEmail = fromMailbox?.firstChildText(EWS_TYPES, "EmailAddress").orEmpty()

        val to = parseRecipients(msg.firstChildElement(EWS_TYPES, "ToRecipients"))
        val cc = parseRecipients(msg.firstChildElement(EWS_TYPES, "CcRecipients"))

        val bodyEl = msg.firstChildElement(EWS_TYPES, "Body")
        val bodyType = bodyEl?.getAttribute("BodyType").orEmpty()
        val bodyText = bodyEl?.textContent.orEmpty()
        val isHtml = bodyType.equals("HTML", ignoreCase = true)

        val attachments = mutableListOf<AttachmentInfo>()
        val attContainer = msg.firstChildElement(EWS_TYPES, "Attachments")
        if (attContainer != null) {
            val fileAtts = attContainer.getElementsByTagNameNS(EWS_TYPES, "FileAttachment")
            for (i in 0 until fileAtts.length) {
                val fa = fileAtts.item(i) as? Element ?: continue
                val attIdEl = fa.firstChildElement(EWS_TYPES, "AttachmentId") ?: continue
                val attId = attIdEl.getAttribute("Id")
                val name = fa.firstChildText(EWS_TYPES, "Name").ifBlank { "attachment" }
                val ctype = fa.firstChildText(EWS_TYPES, "ContentType")
                    .ifBlank { "application/octet-stream" }
                val size = fa.firstChildText(EWS_TYPES, "Size").toLongOrNull() ?: 0L
                val isInline = fa.firstChildText(EWS_TYPES, "IsInline")
                    .equals("true", ignoreCase = true)
                attachments += AttachmentInfo(
                    id = attId,
                    name = name,
                    contentType = ctype,
                    size = size,
                    isInline = isInline
                )
            }
        }

        return EmailDetail(
            id = id,
            changeKey = changeKey,
            subject = subject,
            from = fromName.ifBlank { fromEmail },
            fromEmail = fromEmail,
            to = to,
            cc = cc,
            receivedAt = receivedAt,
            body = bodyText,
            isHtml = isHtml,
            isRead = isRead,
            attachments = attachments
        )
    }

    fun parseGetAttachmentResponse(xml: String): DownloadedAttachment {
        val doc = parseDocument(xml)
        checkSoapErrors(doc)

        val fa = doc.getElementsByTagNameNS(EWS_TYPES, "FileAttachment").item(0) as? Element
            ?: throw EwsException("Вложение не найдено")
        val name = fa.firstChildText(EWS_TYPES, "Name").ifBlank { "attachment" }
        val ctype = fa.firstChildText(EWS_TYPES, "ContentType")
            .ifBlank { "application/octet-stream" }
        val contentB64 = fa.firstChildText(EWS_TYPES, "Content")
            .replace("\r", "")
            .replace("\n", "")
            .trim()
        val bytes = Base64.getDecoder().decode(contentB64)
        return DownloadedAttachment(name = name, contentType = ctype, bytes = bytes)
    }

    fun parseSimpleSuccessResponse(xml: String) {
        val doc = parseDocument(xml)
        checkSoapErrors(doc)
    }

    private fun parseRecipients(container: Element?): List<String> {
        if (container == null) return emptyList()
        val list = mutableListOf<String>()
        val mailboxes = container.getElementsByTagNameNS(EWS_TYPES, "Mailbox")
        for (i in 0 until mailboxes.length) {
            val mb = mailboxes.item(i) as? Element ?: continue
            val email = mb.firstChildText(EWS_TYPES, "EmailAddress")
            val name = mb.firstChildText(EWS_TYPES, "Name")
            val formatted = when {
                name.isNotBlank() && email.isNotBlank() && !name.equals(email, ignoreCase = true) ->
                    "$name <$email>"
                email.isNotBlank() -> email
                else -> name
            }
            if (formatted.isNotBlank()) list += formatted
        }
        return list
    }

    private fun checkSoapErrors(doc: Document) {
        val faults = doc.getElementsByTagNameNS(SOAP_ENV, "Fault")
        if (faults.length > 0) {
            val fault = faults.item(0) as? Element
            val faultString = fault?.getElementsByTagName("faultstring")?.item(0)?.textContent
                ?: "SOAP Fault"
            throw EwsException(faultString)
        }

        val respMessages = doc.getElementsByTagNameNS(EWS_MESSAGES, "*")
        for (i in 0 until respMessages.length) {
            val el = respMessages.item(i) as? Element ?: continue
            if (el.getAttribute("ResponseClass").equals("Error", ignoreCase = true)) {
                val code = el.firstChildText(EWS_MESSAGES, "ResponseCode")
                val msg = el.firstChildText(EWS_MESSAGES, "MessageText")
                val text = listOf(code, msg).filter { it.isNotBlank() }.joinToString(": ")
                throw EwsException(text.ifBlank { "Ошибка EWS сервера" })
            }
        }
    }

    private fun parseDocument(xml: String): Document {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            runCatching { isExpandEntityReferences = false }
            runCatching { isXIncludeAware = false }
            runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }
        val builder = factory.newDocumentBuilder().apply {
            setEntityResolver { _, _ -> InputSource(StringReader("")) }
        }
        return builder.parse(InputSource(StringReader(xml)))
    }

    private fun Element.firstChildElement(ns: String, localName: String): Element? {
        val nodes = getElementsByTagNameNS(ns, localName)
        return if (nodes.length > 0) nodes.item(0) as? Element else null
    }

    private fun Element.firstChildText(ns: String, localName: String): String {
        return firstChildElement(ns, localName)?.textContent?.trim().orEmpty()
    }

    private fun wrapSoap(bodyContent: String): String {
        return """<?xml version="1.0" encoding="utf-8"?>
<soap:Envelope xmlns:soap="$SOAP_ENV"
               xmlns:t="$EWS_TYPES"
               xmlns:m="$EWS_MESSAGES">
  <soap:Header>
    <t:RequestServerVersion Version="Exchange2016"/>
  </soap:Header>
  <soap:Body>
    $bodyContent
  </soap:Body>
</soap:Envelope>"""
    }
}
