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

    // --- Folders ---

    fun buildGetDistinguishedFoldersRequest(distinguishedIds: List<String>): String {
        val ids = distinguishedIds.joinToString("\n        ") {
            """<t:DistinguishedFolderId Id="${escapeXml(it)}"/>"""
        }
        return wrapSoap(
            """
            <m:GetFolder>
              <m:FolderShape>
                <t:BaseShape>IdOnly</t:BaseShape>
              </m:FolderShape>
              <m:FolderIds>
                $ids
              </m:FolderIds>
            </m:GetFolder>
            """.trimIndent()
        )
    }

    fun parseDistinguishedFolderIds(xml: String, requestedIds: List<String>): Map<String, String> {
        val doc = parseDocument(xml)
        val faults = doc.getElementsByTagNameNS(SOAP_ENV, "Fault")
        if (faults.length > 0) checkSoapErrors(doc)

        val responses = doc.getElementsByTagNameNS(EWS_MESSAGES, "GetFolderResponseMessage")
        val map = mutableMapOf<String, String>()
        for (i in 0 until responses.length) {
            val msgEl = responses.item(i) as? Element ?: continue
            val key = requestedIds.getOrNull(i) ?: continue
            if (!msgEl.getAttribute("ResponseClass").equals("Success", ignoreCase = true)) continue
            val folderEl = msgEl.firstChildElement(EWS_TYPES, "Folder") ?: continue
            val folderId = folderEl.firstChildElement(EWS_TYPES, "FolderId")?.getAttribute("Id")
            if (!folderId.isNullOrBlank()) {
                map[key] = folderId
            }
        }
        return map
    }

    fun buildFindFoldersRequest(): String {
        return wrapSoap(
            """
            <m:FindFolder Traversal="Deep">
              <m:FolderShape>
                <t:BaseShape>Default</t:BaseShape>
                <t:AdditionalProperties>
                  <t:FieldURI FieldURI="folder:ParentFolderId"/>
                  <t:FieldURI FieldURI="folder:FolderClass"/>
                  <t:FieldURI FieldURI="folder:ChildFolderCount"/>
                </t:AdditionalProperties>
              </m:FolderShape>
              <m:ParentFolderIds>
                <t:DistinguishedFolderId Id="msgfolderroot"/>
              </m:ParentFolderIds>
            </m:FindFolder>
            """.trimIndent()
        )
    }

    fun parseFindFoldersResponse(
        xml: String,
        rootFolderId: String?,
        systemIdMap: Map<String, SystemFolderKind>
    ): List<MailFolder> {
        val doc = parseDocument(xml)
        checkSoapErrors(doc)

        val folderNodes = doc.getElementsByTagNameNS(EWS_TYPES, "Folder")
        val rawList = mutableListOf<MailFolder>()
        for (i in 0 until folderNodes.length) {
            val el = folderNodes.item(i) as? Element ?: continue
            val fidEl = el.firstChildElement(EWS_TYPES, "FolderId") ?: continue
            val id = fidEl.getAttribute("Id")
            if (id.isBlank()) continue
            val changeKey = fidEl.getAttribute("ChangeKey")
            val parentId = el.firstChildElement(EWS_TYPES, "ParentFolderId")
                ?.getAttribute("Id").orEmpty()
            val folderClass = el.firstChildText(EWS_TYPES, "FolderClass")
            val kind = systemIdMap[id]

            val isMailFolder = kind != null ||
                folderClass.equals("IPF.Note", ignoreCase = true) ||
                folderClass.startsWith("IPF.Note.", ignoreCase = true)
            if (!isMailFolder) continue

            val displayName = el.firstChildText(EWS_TYPES, "DisplayName")
                .ifBlank { kind?.labelRu ?: "Папка" }
            val total = el.firstChildText(EWS_TYPES, "TotalCount").toIntOrNull() ?: 0
            val unread = el.firstChildText(EWS_TYPES, "UnreadCount").toIntOrNull() ?: 0
            val childCount = el.firstChildText(EWS_TYPES, "ChildFolderCount").toIntOrNull() ?: 0

            rawList += MailFolder(
                id = id,
                changeKey = changeKey,
                parentId = parentId,
                displayName = displayName,
                totalCount = total,
                unreadCount = unread,
                childFolderCount = childCount,
                depth = 0,
                systemKind = kind
            )
        }

        val byId = rawList.associateBy { it.id }
        val topLevel = mutableListOf<MailFolder>()
        val childrenByParent = mutableMapOf<String, MutableList<MailFolder>>()

        for (folder in rawList) {
            val hasParentInTree = folder.parentId.isNotBlank() &&
                folder.parentId != rootFolderId &&
                byId.containsKey(folder.parentId)
            if (hasParentInTree) {
                childrenByParent.getOrPut(folder.parentId) { mutableListOf() }.add(folder)
            } else {
                topLevel += folder
            }
        }

        topLevel.sortWith(
            compareBy<MailFolder>(
                { it.systemKind?.sortOrder ?: Int.MAX_VALUE },
                { it.displayName.lowercase() }
            )
        )

        val ordered = mutableListOf<MailFolder>()
        val visited = mutableSetOf<String>()

        fun appendTree(node: MailFolder, depth: Int) {
            if (!visited.add(node.id)) return
            ordered += node.copy(depth = depth)
            val children = childrenByParent[node.id]
                ?.sortedWith(
                    compareBy<MailFolder>(
                        { it.systemKind?.sortOrder ?: Int.MAX_VALUE },
                        { it.displayName.lowercase() }
                    )
                )
                .orEmpty()
            for (child in children) {
                appendTree(child, depth + 1)
            }
        }

        for (root in topLevel) {
            appendTree(root, 0)
        }
        return ordered
    }

    fun buildCreateFolderRequest(displayName: String, parentFolderId: String?): String {
        val parentXml = if (parentFolderId.isNullOrBlank()) {
            """<t:DistinguishedFolderId Id="msgfolderroot"/>"""
        } else {
            """<t:FolderId Id="${escapeXml(parentFolderId)}"/>"""
        }
        return wrapSoap(
            """
            <m:CreateFolder>
              <m:ParentFolderId>
                $parentXml
              </m:ParentFolderId>
              <m:Folders>
                <t:Folder>
                  <t:FolderClass>IPF.Note</t:FolderClass>
                  <t:DisplayName>${escapeXml(displayName.trim())}</t:DisplayName>
                </t:Folder>
              </m:Folders>
            </m:CreateFolder>
            """.trimIndent()
        )
    }

    fun buildRenameFolderRequest(folderId: String, changeKey: String, newName: String): String {
        val ckAttr = if (changeKey.isNotBlank()) """ ChangeKey="${escapeXml(changeKey)}"""" else ""
        return wrapSoap(
            """
            <m:UpdateFolder>
              <m:FolderChanges>
                <t:FolderChange>
                  <t:FolderId Id="${escapeXml(folderId)}"$ckAttr/>
                  <t:Updates>
                    <t:SetFolderField>
                      <t:FieldURI FieldURI="folder:DisplayName"/>
                      <t:Folder>
                        <t:DisplayName>${escapeXml(newName.trim())}</t:DisplayName>
                      </t:Folder>
                    </t:SetFolderField>
                  </t:Updates>
                </t:FolderChange>
              </m:FolderChanges>
            </m:UpdateFolder>
            """.trimIndent()
        )
    }

    fun buildDeleteFolderRequest(folderId: String, changeKey: String): String {
        val ckAttr = if (changeKey.isNotBlank()) """ ChangeKey="${escapeXml(changeKey)}"""" else ""
        return wrapSoap(
            """
            <m:DeleteFolder DeleteType="MoveToDeletedItems">
              <m:FolderIds>
                <t:FolderId Id="${escapeXml(folderId)}"$ckAttr/>
              </m:FolderIds>
            </m:DeleteFolder>
            """.trimIndent()
        )
    }

    fun buildEmptyFolderRequest(folderId: String): String {
        return wrapSoap(
            """
            <m:EmptyFolder DeleteType="HardDelete" DeleteSubFolders="false">
              <m:FolderIds>
                <t:FolderId Id="${escapeXml(folderId)}"/>
              </m:FolderIds>
            </m:EmptyFolder>
            """.trimIndent()
        )
    }

    fun buildMarkAllReadRequest(folderId: String): String {
        return wrapSoap(
            """
            <m:MarkAllItemsAsRead>
              <m:ReadFlag>true</m:ReadFlag>
              <m:SuppressReadReceipts>true</m:SuppressReadReceipts>
              <m:FolderIds>
                <t:FolderId Id="${escapeXml(folderId)}"/>
              </m:FolderIds>
            </m:MarkAllItemsAsRead>
            """.trimIndent()
        )
    }

    // --- Messages ---

    fun buildFindItemsRequest(
        folderIds: List<String>,
        offset: Int,
        pageSize: Int,
        query: String? = null,
        filter: MailFilter = MailFilter.ALL
    ): String {
        val parentIds = folderIds.joinToString("\n        ") {
            """<t:FolderId Id="${escapeXml(it)}"/>"""
        }

        val trimmedQuery = query?.trim().orEmpty()
        val kqlFilterToken = when (filter) {
            MailFilter.ALL -> ""
            MailFilter.UNREAD -> "isread:false"
            MailFilter.FLAGGED -> "isflagged:true"
            MailFilter.ATTACHMENTS -> "hasattachments:true"
        }

        val restrictionBlock: String
        val queryStringBlock: String
        if (trimmedQuery.isNotEmpty()) {
            val combinedKql = listOf(trimmedQuery, kqlFilterToken)
                .filter { it.isNotBlank() }
                .joinToString(" ")
            restrictionBlock = ""
            queryStringBlock = "<m:QueryString>${escapeXml(combinedKql)}</m:QueryString>"
        } else {
            queryStringBlock = ""
            restrictionBlock = when (filter) {
                MailFilter.ALL -> ""
                MailFilter.UNREAD -> """
                    <m:Restriction>
                      <t:IsEqualTo>
                        <t:FieldURI FieldURI="message:IsRead"/>
                        <t:FieldURIOrConstant><t:Constant Value="false"/></t:FieldURIOrConstant>
                      </t:IsEqualTo>
                    </m:Restriction>
                """.trimIndent()
                MailFilter.FLAGGED -> """
                    <m:Restriction>
                      <t:IsEqualTo>
                        <t:ExtendedFieldURI PropertyTag="0x1090" PropertyType="Integer"/>
                        <t:FieldURIOrConstant><t:Constant Value="2"/></t:FieldURIOrConstant>
                      </t:IsEqualTo>
                    </m:Restriction>
                """.trimIndent()
                MailFilter.ATTACHMENTS -> """
                    <m:Restriction>
                      <t:IsEqualTo>
                        <t:FieldURI FieldURI="item:HasAttachments"/>
                        <t:FieldURIOrConstant><t:Constant Value="true"/></t:FieldURIOrConstant>
                      </t:IsEqualTo>
                    </m:Restriction>
                """.trimIndent()
            }
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
                  <t:FieldURI FieldURI="item:Importance"/>
                  <t:FieldURI FieldURI="item:Flag"/>
                  <t:FieldURI FieldURI="message:From"/>
                  <t:FieldURI FieldURI="message:IsRead"/>
                </t:AdditionalProperties>
              </m:ItemShape>
              <m:IndexedPageItemView MaxEntriesReturned="$pageSize" Offset="$offset" BasePoint="Beginning"/>
              $restrictionBlock
              <m:SortOrder>
                <t:FieldOrder Order="Descending">
                  <t:FieldURI FieldURI="item:DateTimeReceived"/>
                </t:FieldOrder>
              </m:SortOrder>
              <m:ParentFolderIds>
                $parentIds
              </m:ParentFolderIds>
              $queryStringBlock
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
                  <t:FieldURI FieldURI="item:Importance"/>
                  <t:FieldURI FieldURI="item:Flag"/>
                  <t:FieldURI FieldURI="item:IsDraft"/>
                  <t:FieldURI FieldURI="message:From"/>
                  <t:FieldURI FieldURI="message:ToRecipients"/>
                  <t:FieldURI FieldURI="message:CcRecipients"/>
                  <t:FieldURI FieldURI="message:BccRecipients"/>
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

    fun buildCreateMessageRequest(
        to: List<String>,
        cc: List<String>,
        bcc: List<String>,
        subject: String,
        body: String,
        importance: EmailImportance,
        sendImmediately: Boolean
    ): String {
        val disposition = if (sendImmediately) "SendAndSaveCopy" else "SaveOnly"
        val targetFolder = if (sendImmediately) "sentitems" else "drafts"
        val toBlock = recipientsBlock("ToRecipients", to)
        val ccBlock = recipientsBlock("CcRecipients", cc)
        val bccBlock = recipientsBlock("BccRecipients", bcc)

        return wrapSoap(
            """
            <m:CreateItem MessageDisposition="$disposition">
              <m:SavedItemFolderId>
                <t:DistinguishedFolderId Id="$targetFolder"/>
              </m:SavedItemFolderId>
              <m:Items>
                <t:Message>
                  <t:Subject>${escapeXml(subject)}</t:Subject>
                  <t:Importance>${escapeXml(importance.ewsValue)}</t:Importance>
                  <t:Body BodyType="Text">${escapeXml(body)}</t:Body>
                  $toBlock
                  $ccBlock
                  $bccBlock
                </t:Message>
              </m:Items>
            </m:CreateItem>
            """.trimIndent()
        )
    }

    fun buildUpdateDraftRequest(
        itemId: String,
        changeKey: String,
        to: List<String>,
        cc: List<String>,
        bcc: List<String>,
        subject: String,
        body: String,
        importance: EmailImportance
    ): String {
        val ckAttr = if (changeKey.isNotBlank()) """ ChangeKey="${escapeXml(changeKey)}"""" else ""
        val toUpdate = recipientUpdateField("message:ToRecipients", "ToRecipients", to)
        val ccUpdate = recipientUpdateField("message:CcRecipients", "CcRecipients", cc)
        val bccUpdate = recipientUpdateField("message:BccRecipients", "BccRecipients", bcc)

        return wrapSoap(
            """
            <m:UpdateItem ConflictResolution="AlwaysOverwrite" MessageDisposition="SaveOnly">
              <m:ItemChanges>
                <t:ItemChange>
                  <t:ItemId Id="${escapeXml(itemId)}"$ckAttr/>
                  <t:Updates>
                    <t:SetItemField>
                      <t:FieldURI FieldURI="item:Subject"/>
                      <t:Message><t:Subject>${escapeXml(subject)}</t:Subject></t:Message>
                    </t:SetItemField>
                    <t:SetItemField>
                      <t:FieldURI FieldURI="item:Importance"/>
                      <t:Message><t:Importance>${escapeXml(importance.ewsValue)}</t:Importance></t:Message>
                    </t:SetItemField>
                    <t:SetItemField>
                      <t:FieldURI FieldURI="item:Body"/>
                      <t:Message><t:Body BodyType="Text">${escapeXml(body)}</t:Body></t:Message>
                    </t:SetItemField>
                    $toUpdate
                    $ccUpdate
                    $bccUpdate
                  </t:Updates>
                </t:ItemChange>
              </m:ItemChanges>
            </m:UpdateItem>
            """.trimIndent()
        )
    }

    fun buildCreateAttachmentRequest(
        parentItemId: String,
        parentChangeKey: String,
        attachments: List<OutgoingAttachment>
    ): String {
        val ckAttr = if (parentChangeKey.isNotBlank()) {
            """ ChangeKey="${escapeXml(parentChangeKey)}""""
        } else {
            ""
        }
        val attXml = attachments.joinToString("\n        ") { att ->
            val b64 = Base64.getEncoder().encodeToString(att.bytes)
            """
            <t:FileAttachment>
              <t:Name>${escapeXml(att.name)}</t:Name>
              <t:ContentType>${escapeXml(att.contentType)}</t:ContentType>
              <t:Content>$b64</t:Content>
            </t:FileAttachment>
            """.trimIndent()
        }
        return wrapSoap(
            """
            <m:CreateAttachment>
              <m:ParentItemId Id="${escapeXml(parentItemId)}"$ckAttr/>
              <m:Attachments>
                $attXml
              </m:Attachments>
            </m:CreateAttachment>
            """.trimIndent()
        )
    }

    fun buildSendItemRequest(itemId: String, changeKey: String): String {
        val ckAttr = if (changeKey.isNotBlank()) """ ChangeKey="${escapeXml(changeKey)}"""" else ""
        return wrapSoap(
            """
            <m:SendItem SaveItemToFolder="true">
              <m:ItemIds>
                <t:ItemId Id="${escapeXml(itemId)}"$ckAttr/>
              </m:ItemIds>
              <m:SavedItemFolderId>
                <t:DistinguishedFolderId Id="sentitems"/>
              </m:SavedItemFolderId>
            </m:SendItem>
            """.trimIndent()
        )
    }

    fun parseItemIdResponse(xml: String): Pair<String, String> {
        val doc = parseDocument(xml)
        checkSoapErrors(doc)
        val el = doc.getElementsByTagNameNS(EWS_TYPES, "ItemId").item(0) as? Element
            ?: throw EwsException("Не получен ItemId от сервера")
        return el.getAttribute("Id") to el.getAttribute("ChangeKey")
    }

    fun parseAttachmentRootChangeKey(xml: String): String {
        val doc = parseDocument(xml)
        checkSoapErrors(doc)
        val nodes = doc.getElementsByTagNameNS(EWS_TYPES, "AttachmentId")
        for (i in nodes.length - 1 downTo 0) {
            val el = nodes.item(i) as? Element ?: continue
            val ck = el.getAttribute("RootItemChangeKey")
            if (ck.isNotBlank()) return ck
        }
        return ""
    }

    fun buildSetReadRequest(items: List<Pair<String, String>>, isRead: Boolean): String {
        val changes = items.joinToString("\n        ") { (id, ck) ->
            val ckAttr = if (ck.isNotBlank()) """ ChangeKey="${escapeXml(ck)}"""" else ""
            """
            <t:ItemChange>
              <t:ItemId Id="${escapeXml(id)}"$ckAttr/>
              <t:Updates>
                <t:SetItemField>
                  <t:FieldURI FieldURI="message:IsRead"/>
                  <t:Message><t:IsRead>$isRead</t:IsRead></t:Message>
                </t:SetItemField>
              </t:Updates>
            </t:ItemChange>
            """.trimIndent()
        }
        return wrapSoap(
            """
            <m:UpdateItem ConflictResolution="AlwaysOverwrite" MessageDisposition="SaveOnly">
              <m:ItemChanges>
                $changes
              </m:ItemChanges>
            </m:UpdateItem>
            """.trimIndent()
        )
    }

    fun buildSetFlagRequest(items: List<Pair<String, String>>, flagged: Boolean): String {
        val flagStatus = if (flagged) "Flagged" else "NotFlagged"
        val changes = items.joinToString("\n        ") { (id, ck) ->
            val ckAttr = if (ck.isNotBlank()) """ ChangeKey="${escapeXml(ck)}"""" else ""
            """
            <t:ItemChange>
              <t:ItemId Id="${escapeXml(id)}"$ckAttr/>
              <t:Updates>
                <t:SetItemField>
                  <t:FieldURI FieldURI="item:Flag"/>
                  <t:Message>
                    <t:Flag><t:FlagStatus>$flagStatus</t:FlagStatus></t:Flag>
                  </t:Message>
                </t:SetItemField>
              </t:Updates>
            </t:ItemChange>
            """.trimIndent()
        }
        return wrapSoap(
            """
            <m:UpdateItem ConflictResolution="AlwaysOverwrite" MessageDisposition="SaveOnly">
              <m:ItemChanges>
                $changes
              </m:ItemChanges>
            </m:UpdateItem>
            """.trimIndent()
        )
    }

    fun buildDeleteItemsRequest(items: List<Pair<String, String>>, hardDelete: Boolean): String {
        val deleteType = if (hardDelete) "HardDelete" else "MoveToDeletedItems"
        val ids = items.joinToString("\n        ") { (id, ck) ->
            val ckAttr = if (ck.isNotBlank()) """ ChangeKey="${escapeXml(ck)}"""" else ""
            """<t:ItemId Id="${escapeXml(id)}"$ckAttr/>"""
        }
        return wrapSoap(
            """
            <m:DeleteItem DeleteType="$deleteType">
              <m:ItemIds>
                $ids
              </m:ItemIds>
            </m:DeleteItem>
            """.trimIndent()
        )
    }

    fun buildMoveItemsRequest(items: List<Pair<String, String>>, targetFolderId: String): String {
        val ids = items.joinToString("\n        ") { (id, ck) ->
            val ckAttr = if (ck.isNotBlank()) """ ChangeKey="${escapeXml(ck)}"""" else ""
            """<t:ItemId Id="${escapeXml(id)}"$ckAttr/>"""
        }
        return wrapSoap(
            """
            <m:MoveItem>
              <m:ToFolderId>
                <t:FolderId Id="${escapeXml(targetFolderId)}"/>
              </m:ToFolderId>
              <m:ItemIds>
                $ids
              </m:ItemIds>
            </m:MoveItem>
            """.trimIndent()
        )
    }

    // --- GAL / Contacts / Calendar ---

    fun buildResolveNamesRequest(query: String): String {
        return wrapSoap(
            """
            <m:ResolveNames ReturnFullContactData="true" SearchScope="ActiveDirectoryContacts">
              <m:UnresolvedEntry>${escapeXml(query.trim())}</m:UnresolvedEntry>
            </m:ResolveNames>
            """.trimIndent()
        )
    }

    fun parseResolveNamesResponse(xml: String): List<ContactEntry> {
        return parsePersonProfilesFromResolveNames(xml).map { p ->
            ContactEntry(
                name = p.displayName.ifBlank { p.email },
                email = p.email,
                isFromDirectory = true
            )
        }.distinctBy { it.email.lowercase() }
    }

    fun parsePersonProfilesFromResolveNames(xml: String): List<PersonProfile> {
        val doc = parseDocument(xml)
        val faults = doc.getElementsByTagNameNS(SOAP_ENV, "Fault")
        if (faults.length > 0) checkSoapErrors(doc)

        val respMsg = doc.getElementsByTagNameNS(EWS_MESSAGES, "ResolveNamesResponseMessage")
            .item(0) as? Element
        if (respMsg != null && respMsg.getAttribute("ResponseClass").equals("Error", true)) {
            val code = respMsg.firstChildText(EWS_MESSAGES, "ResponseCode")
            if (code == "ErrorNameResolutionNoResults") return emptyList()
            checkSoapErrors(doc)
        }

        val resolutions = doc.getElementsByTagNameNS(EWS_TYPES, "Resolution")
        val list = mutableListOf<PersonProfile>()
        for (i in 0 until resolutions.length) {
            val res = resolutions.item(i) as? Element ?: continue
            val mb = res.firstChildElement(EWS_TYPES, "Mailbox") ?: continue
            val email = mb.firstChildText(EWS_TYPES, "EmailAddress")
            if (email.isBlank()) continue

            val contact = res.firstChildElement(EWS_TYPES, "Contact")
            val contactDisplayName = contact?.firstChildText(EWS_TYPES, "DisplayName").orEmpty()
            val mbName = mb.firstChildText(EWS_TYPES, "Name")
            val displayName = contactDisplayName.ifBlank { mbName.ifBlank { email } }

            val aliases = mutableListOf<String>()
            val emailContainer = contact?.firstChildElement(EWS_TYPES, "EmailAddresses")
            if (emailContainer != null) {
                val entries = emailContainer.getElementsByTagNameNS(EWS_TYPES, "Entry")
                for (j in 0 until entries.length) {
                    val raw = (entries.item(j) as? Element)?.textContent?.trim().orEmpty()
                    val clean = raw.removePrefix("SMTP:").removePrefix("smtp:").trim()
                    if (clean.contains('@') && !clean.equals(email, ignoreCase = true) &&
                        aliases.none { it.equals(clean, ignoreCase = true) }
                    ) {
                        aliases += clean
                    }
                }
            }

            val phones = mutableListOf<Pair<String, String>>()
            val phoneContainer = contact?.firstChildElement(EWS_TYPES, "PhoneNumbers")
            if (phoneContainer != null) {
                val labels = mapOf(
                    "BusinessPhone" to "Рабочий",
                    "MobilePhone" to "Мобильный",
                    "HomePhone" to "Домашний",
                    "AssistantPhone" to "Ассистент"
                )
                val entries = phoneContainer.getElementsByTagNameNS(EWS_TYPES, "Entry")
                for (j in 0 until entries.length) {
                    val pe = entries.item(j) as? Element ?: continue
                    val num = pe.textContent?.trim().orEmpty()
                    if (num.isNotBlank()) {
                        val key = pe.getAttribute("Key")
                        phones += (labels[key] ?: key.ifBlank { "Телефон" }) to num
                    }
                }
            }

            list += PersonProfile(
                displayName = displayName,
                email = email,
                aliases = aliases,
                jobTitle = contact?.firstChildText(EWS_TYPES, "JobTitle").orEmpty(),
                department = contact?.firstChildText(EWS_TYPES, "Department").orEmpty(),
                company = contact?.firstChildText(EWS_TYPES, "CompanyName").orEmpty(),
                officeLocation = contact?.firstChildText(EWS_TYPES, "OfficeLocation").orEmpty(),
                phones = phones
            )
        }
        return list
    }

    fun buildFindContactsRequest(): String {
        return wrapSoap(
            """
            <m:FindItem Traversal="Shallow">
              <m:ItemShape>
                <t:BaseShape>IdOnly</t:BaseShape>
                <t:AdditionalProperties>
                  <t:FieldURI FieldURI="contacts:DisplayName"/>
                  <t:FieldURI FieldURI="contacts:EmailAddresses"/>
                </t:AdditionalProperties>
              </m:ItemShape>
              <m:IndexedPageItemView MaxEntriesReturned="200" Offset="0" BasePoint="Beginning"/>
              <m:ParentFolderIds>
                <t:DistinguishedFolderId Id="contacts"/>
              </m:ParentFolderIds>
            </m:FindItem>
            """.trimIndent()
        )
    }

    fun parseFindContactsResponse(xml: String): List<ContactEntry> {
        val doc = parseDocument(xml)
        checkSoapErrors(doc)

        val contacts = doc.getElementsByTagNameNS(EWS_TYPES, "Contact")
        val list = mutableListOf<ContactEntry>()
        for (i in 0 until contacts.length) {
            val c = contacts.item(i) as? Element ?: continue
            val idEl = c.firstChildElement(EWS_TYPES, "ItemId") ?: continue
            val id = idEl.getAttribute("Id")
            val ck = idEl.getAttribute("ChangeKey")
            val name = c.firstChildText(EWS_TYPES, "DisplayName")
            val entries = c.getElementsByTagNameNS(EWS_TYPES, "Entry")
            val email = (0 until entries.length)
                .mapNotNull { (entries.item(it) as? Element)?.textContent?.trim() }
                .firstOrNull { it.contains('@') }
                .orEmpty()
            if (email.isNotBlank() || name.isNotBlank()) {
                list += ContactEntry(
                    id = id,
                    changeKey = ck,
                    name = name.ifBlank { email },
                    email = email,
                    isFromDirectory = false
                )
            }
        }
        return list.sortedBy { it.name.lowercase() }
    }

    fun buildCreateContactRequest(name: String, email: String): String {
        return wrapSoap(
            """
            <m:CreateItem>
              <m:SavedItemFolderId>
                <t:DistinguishedFolderId Id="contacts"/>
              </m:SavedItemFolderId>
              <m:Items>
                <t:Contact>
                  <t:DisplayName>${escapeXml(name.trim())}</t:DisplayName>
                  <t:GivenName>${escapeXml(name.trim())}</t:GivenName>
                  <t:EmailAddresses>
                    <t:Entry Key="EmailAddress1">${escapeXml(email.trim())}</t:Entry>
                  </t:EmailAddresses>
                </t:Contact>
              </m:Items>
            </m:CreateItem>
            """.trimIndent()
        )
    }

    fun buildFindCalendarEventsRequest(startIso: String, endIso: String): String {
        return wrapSoap(
            """
            <m:FindItem Traversal="Shallow">
              <m:ItemShape>
                <t:BaseShape>IdOnly</t:BaseShape>
                <t:AdditionalProperties>
                  <t:FieldURI FieldURI="item:Subject"/>
                  <t:FieldURI FieldURI="calendar:Start"/>
                  <t:FieldURI FieldURI="calendar:End"/>
                  <t:FieldURI FieldURI="calendar:Location"/>
                  <t:FieldURI FieldURI="calendar:IsAllDayEvent"/>
                </t:AdditionalProperties>
              </m:ItemShape>
              <m:CalendarView MaxEntriesReturned="100" StartDate="${escapeXml(startIso)}" EndDate="${escapeXml(endIso)}"/>
              <m:ParentFolderIds>
                <t:DistinguishedFolderId Id="calendar"/>
              </m:ParentFolderIds>
            </m:FindItem>
            """.trimIndent()
        )
    }

    fun parseCalendarEventsResponse(xml: String): List<CalendarEvent> {
        val doc = parseDocument(xml)
        checkSoapErrors(doc)

        val items = doc.getElementsByTagNameNS(EWS_TYPES, "CalendarItem")
        val list = mutableListOf<CalendarEvent>()
        for (i in 0 until items.length) {
            val ev = items.item(i) as? Element ?: continue
            val idEl = ev.firstChildElement(EWS_TYPES, "ItemId") ?: continue
            list += CalendarEvent(
                id = idEl.getAttribute("Id"),
                changeKey = idEl.getAttribute("ChangeKey"),
                subject = ev.firstChildText(EWS_TYPES, "Subject").ifBlank { "(Без названия)" },
                startIso = ev.firstChildText(EWS_TYPES, "Start"),
                endIso = ev.firstChildText(EWS_TYPES, "End"),
                location = ev.firstChildText(EWS_TYPES, "Location"),
                isAllDay = ev.firstChildText(EWS_TYPES, "IsAllDayEvent").equals("true", true)
            )
        }
        return list.sortedBy { it.startIso }
    }

    fun buildCreateCalendarEventRequest(
        subject: String,
        startIso: String,
        endIso: String,
        location: String,
        body: String
    ): String {
        val locBlock = if (location.isNotBlank()) {
            "<t:Location>${escapeXml(location.trim())}</t:Location>"
        } else {
            ""
        }
        return wrapSoap(
            """
            <m:CreateItem SendMeetingInvitations="SendToNone">
              <m:SavedItemFolderId>
                <t:DistinguishedFolderId Id="calendar"/>
              </m:SavedItemFolderId>
              <m:Items>
                <t:CalendarItem>
                  <t:Subject>${escapeXml(subject.trim())}</t:Subject>
                  <t:Body BodyType="Text">${escapeXml(body)}</t:Body>
                  <t:Start>${escapeXml(startIso)}</t:Start>
                  <t:End>${escapeXml(endIso)}</t:End>
                  $locBlock
                </t:CalendarItem>
              </m:Items>
            </m:CreateItem>
            """.trimIndent()
        )
    }

    // --- Response Parsers (Messages / Attachments) ---

    fun parseFindItemsResponse(xml: String): Pair<List<EmailSummary>, Boolean> {
        val doc = parseDocument(xml)
        checkSoapErrors(doc)

        val rootFolders = doc.getElementsByTagNameNS(EWS_MESSAGES, "RootFolder")
        var includesLast = true
        for (i in 0 until rootFolders.length) {
            val rf = rootFolders.item(i) as? Element ?: continue
            if (rf.getAttribute("IncludesLastItemInRange").equals("false", ignoreCase = true)) {
                includesLast = false
            }
        }

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
            val importance = EmailImportance.fromEws(msg.firstChildText(EWS_TYPES, "Importance"))
            val flagStatus = msg.firstChildElement(EWS_TYPES, "Flag")
                ?.firstChildText(EWS_TYPES, "FlagStatus").orEmpty()
            val isFlagged = flagStatus.equals("Flagged", ignoreCase = true)

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
                preview = preview,
                isFlagged = isFlagged,
                importance = importance
            )
        }
        val sorted = if (rootFolders.length > 1) {
            items.sortedByDescending { it.receivedAt }
        } else {
            items
        }
        return sorted to includesLast
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
        val isDraft = msg.firstChildText(EWS_TYPES, "IsDraft").equals("true", ignoreCase = true)
        val importance = EmailImportance.fromEws(msg.firstChildText(EWS_TYPES, "Importance"))
        val flagStatus = msg.firstChildElement(EWS_TYPES, "Flag")
            ?.firstChildText(EWS_TYPES, "FlagStatus").orEmpty()
        val isFlagged = flagStatus.equals("Flagged", ignoreCase = true)

        val fromMailbox = msg.firstChildElement(EWS_TYPES, "From")
            ?.firstChildElement(EWS_TYPES, "Mailbox")
        val fromName = fromMailbox?.firstChildText(EWS_TYPES, "Name").orEmpty()
        val fromEmail = fromMailbox?.firstChildText(EWS_TYPES, "EmailAddress").orEmpty()

        val to = parseRecipients(msg.firstChildElement(EWS_TYPES, "ToRecipients"))
        val cc = parseRecipients(msg.firstChildElement(EWS_TYPES, "CcRecipients"))
        val bcc = parseRecipients(msg.firstChildElement(EWS_TYPES, "BccRecipients"))

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
                val contentId = fa.firstChildText(EWS_TYPES, "ContentId")
                attachments += AttachmentInfo(
                    id = attId,
                    name = name,
                    contentType = ctype,
                    size = size,
                    isInline = isInline,
                    contentId = contentId
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
            bcc = bcc,
            receivedAt = receivedAt,
            body = bodyText,
            isHtml = isHtml,
            isRead = isRead,
            isFlagged = isFlagged,
            importance = importance,
            isDraft = isDraft,
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

    private fun recipientsBlock(tagName: String, addresses: List<String>): String {
        if (addresses.isEmpty()) return ""
        val mb = addresses.joinToString("") {
            "<t:Mailbox><t:EmailAddress>${escapeXml(it)}</t:EmailAddress></t:Mailbox>"
        }
        return "<t:$tagName>$mb</t:$tagName>"
    }

    private fun recipientUpdateField(fieldUri: String, tagName: String, addresses: List<String>): String {
        return if (addresses.isEmpty()) {
            """
            <t:DeleteItemField>
              <t:FieldURI FieldURI="$fieldUri"/>
            </t:DeleteItemField>
            """.trimIndent()
        } else {
            val block = recipientsBlock(tagName, addresses)
            """
            <t:SetItemField>
              <t:FieldURI FieldURI="$fieldUri"/>
              <t:Message>$block</t:Message>
            </t:SetItemField>
            """.trimIndent()
        }
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
